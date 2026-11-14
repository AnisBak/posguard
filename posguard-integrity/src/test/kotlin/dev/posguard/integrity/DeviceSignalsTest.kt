package dev.posguard.integrity

import androidx.test.core.app.ApplicationProvider
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.ReasonCode
import dev.posguard.integrity.internal.AccessibilityServiceSnapshot
import dev.posguard.integrity.internal.DebugProbe
import dev.posguard.integrity.internal.SystemProbe
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Properties
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class DeviceSignalsTest {

    // --- Accessibility -------------------------------------------------------------------

    private fun service(pkg: String, content: Boolean = true, gestures: Boolean = false, tool: Boolean? = null) =
        AccessibilityServiceSnapshot(pkg, content, gestures, tool)

    private fun a11y(vararg services: AccessibilityServiceSnapshot, allowlist: Set<String> = IntegrityConfig().accessibilityAllowlist) =
        AccessibilitySignal({ services.toList() }, allowlist)

    @Test
    fun `no enabled services is clear`() = runTest {
        assertEquals(SignalResult.Clear(AccessibilitySignal.ID), a11y().check())
    }

    @Test
    fun `allowlisted assistive tools are clear`() = runTest {
        val result = a11y(
            service("com.google.android.marvin.talkback", content = true, gestures = true),
            service("com.samsung.android.accessibility.talkback"),
        ).check()
        assertEquals(SignalResult.Clear(AccessibilitySignal.ID), result)
    }

    @Test
    fun `unknown service that reads content or performs gestures is risky`() = runTest {
        val result = a11y(
            service("com.example.remote", content = false, gestures = true),
            service("com.example.reader", content = true),
            service("com.google.android.marvin.talkback"),
        ).check()
        assertEquals(
            SignalResult.Detected(AccessibilitySignal.ID, ReasonCode.RISKY_ACCESSIBILITY_SERVICE, "count=2"),
            result,
        )
    }

    @Test
    fun `self declared accessibility tool is not trusted`() = runTest {
        val result = a11y(service("com.example.fake", tool = true)).check()
        assertTrue(result is SignalResult.Detected)
    }

    @Test
    fun `service without risky capabilities is ignored`() = runTest {
        val result = a11y(service("com.example.feedback", content = false, gestures = false)).check()
        assertEquals(SignalResult.Clear(AccessibilitySignal.ID), result)
    }

    @Test
    fun `custom allowlist is respected`() = runTest {
        val result = a11y(service("com.fleet.kiosk"), allowlist = setOf("com.fleet.kiosk")).check()
        assertEquals(SignalResult.Clear(AccessibilitySignal.ID), result)
    }

    @Test
    fun `source failure is unavailable`() = runTest {
        val signal = AccessibilitySignal({ error("binder died") }, emptySet())
        assertEquals(SignalResult.Unavailable(AccessibilitySignal.ID, "accessibility-manager-error"), signal.check())
    }

    @Test
    fun `real accessibility manager works on a clean device`() = runTest {
        val signal = AccessibilitySignal(ApplicationProvider.getApplicationContext())
        assertEquals(SignalResult.Clear(AccessibilitySignal.ID), signal.check())
    }

    // --- Debugger ------------------------------------------------------------------------

    private fun debug(connected: Boolean, debuggable: Boolean = false) = DebuggerSignal(object : DebugProbe {
        override fun isDebuggerConnected() = connected
        override fun isDebuggableBuild() = debuggable
    })

    @Test
    fun `debugger attached is detected with build detail`() = runTest {
        assertEquals(
            SignalResult.Detected(DebuggerSignal.ID, ReasonCode.DEBUGGER_ATTACHED, "debuggable-build"),
            debug(connected = true, debuggable = true).check(),
        )
    }

    @Test
    fun `debuggable build without debugger is clear`() = runTest {
        assertEquals(SignalResult.Clear(DebuggerSignal.ID), debug(connected = false, debuggable = true).check())
    }

    @Test
    fun `debug probe failure is unavailable`() = runTest {
        val signal = DebuggerSignal(object : DebugProbe {
            override fun isDebuggerConnected(): Boolean = error("x")
            override fun isDebuggableBuild() = false
        })
        assertTrue(signal.check() is SignalResult.Unavailable)
    }

    @Test
    fun `real debug probe runs`() = runTest {
        assertEquals(SignalResult.Clear(DebuggerSignal.ID), DebuggerSignal(ApplicationProvider.getApplicationContext<android.content.Context>()).check())
    }

    // --- Root / emulator -----------------------------------------------------------------

    private class FakeSystem(
        val files: Set<String> = emptySet(),
        override val tags: String = "release-keys",
        override val fingerprint: String = "acme/pos/pos:15/AP3A/123:user/release-keys",
        override val hardware: String = "qcom",
        override val product: String = "pos",
        override val model: String = "POS-1",
        override val manufacturer: String = "Acme",
    ) : SystemProbe {
        override fun fileExists(path: String) = path in files
    }

    private suspend fun root(system: SystemProbe) = RootEmulatorSignal(system).check()

    @Test
    fun `clean production device is clear`() = runTest {
        assertEquals(SignalResult.Clear(RootEmulatorSignal.ID), root(FakeSystem()))
    }

    @Test
    fun `each heuristic is reported`() = runTest {
        val cases = mapOf(
            FakeSystem(files = setOf("/system/xbin/su")) to "su-binary",
            FakeSystem(files = setOf("/data/adb/magisk")) to "magisk",
            FakeSystem(tags = "test-keys") to "test-keys",
            FakeSystem(fingerprint = "generic/sdk/generic:15") to "emulator",
            FakeSystem(hardware = "ranchu") to "emulator",
            FakeSystem(product = "sdk_gphone64_x86_64") to "emulator",
            FakeSystem(model = "Android SDK built for x86") to "emulator",
            FakeSystem(manufacturer = "Genymotion") to "emulator",
        )
        cases.forEach { (system, finding) ->
            assertEquals(
                finding,
                SignalResult.Detected(RootEmulatorSignal.ID, ReasonCode.ROOT_OR_EMULATOR_SUSPECTED, finding),
                root(system),
            )
        }
    }

    @Test
    fun `multiple findings are combined`() = runTest {
        val result = root(FakeSystem(files = setOf("/sbin/su"), tags = "test-keys")) as SignalResult.Detected
        assertEquals("su-binary,test-keys", result.detail)
    }

    @Test
    fun `system probe failure is unavailable`() = runTest {
        val broken = object : SystemProbe by FakeSystem() {
            override fun fileExists(path: String): Boolean = throw SecurityException("denied")
        }
        assertTrue(root(broken) is SignalResult.Unavailable)
    }

    // --- Config --------------------------------------------------------------------------

    @Test
    fun `config reads overrides from properties`() {
        val config = IntegrityConfig.fromProperties(
            Properties().apply {
                setProperty(IntegrityConfig.PROPERTY_ALLOWLIST_EXTRA, " com.fleet.kiosk , com.fleet.reader ")
                setProperty(IntegrityConfig.PROPERTY_TOUCH_WINDOW_SECONDS, "10")
            },
        )
        assertTrue(config.accessibilityAllowlist.containsAll(IntegrityConfig.DEFAULT_ACCESSIBILITY_ALLOWLIST))
        assertTrue("com.fleet.kiosk" in config.accessibilityAllowlist)
        assertEquals(10.seconds, config.obscuredTouchWindow)
    }

    @Test
    fun `config replace list and invalid values`() {
        val replaced = IntegrityConfig.fromProperties(
            Properties().apply { setProperty(IntegrityConfig.PROPERTY_ALLOWLIST, "a.b") },
        )
        assertEquals(setOf("a.b"), replaced.accessibilityAllowlist)
        assertThrows(IllegalArgumentException::class.java) {
            IntegrityConfig.fromProperties(Properties().apply { setProperty(IntegrityConfig.PROPERTY_TOUCH_WINDOW_SECONDS, "x") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            IntegrityConfig.fromProperties(Properties().apply { setProperty(IntegrityConfig.PROPERTY_TOUCH_WINDOW_SECONDS, "0") })
        }
    }
}
