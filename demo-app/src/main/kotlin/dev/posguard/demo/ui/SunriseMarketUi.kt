package dev.posguard.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.posguard.core.ledger.TransactionRecord
import dev.posguard.core.ledger.TxnOutcome
import dev.posguard.core.ledger.TxnState
import dev.posguard.core.verdict.GuardAction
import dev.posguard.core.verdict.GuardVerdict
import dev.posguard.demo.CheckoutViewModel
import dev.posguard.demo.DemoThreats
import dev.posguard.demo.checkout.Catalog
import dev.posguard.demo.checkout.DemoScenario
import dev.posguard.demo.checkout.PaymentPhase
import dev.posguard.demo.checkout.Screen
import dev.posguard.demo.checkout.StartupState
import dev.posguard.demo.checkout.UiState
import dev.posguard.demo.checkout.formatCents
import dev.posguard.integrity.PaymentScreenProtection
import dev.posguard.mockengine.MockPaymentEngine
import dev.posguard.telemetry.TelemetryEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Sunrise = Color(0xFFF59E0B)
private val DemoRed = Color(0xFFB00020)

@Composable
fun SunriseMarketApp(vm: CheckoutViewModel) {
    val ui by vm.controller.ui.collectAsState()
    val history by vm.controller.history.collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(0) }
    var showDemoControls by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            DemoBanner()
            Header(onLongPress = { showDemoControls = true })
            StatusBanners(ui, onDismiss = { vm.controller.dismissBanner() }, onReconnect = { vm.run { reconnect() } })

            when (val screen = ui.screen) {
                is Screen.Flagged -> FlaggedPanel(screen, vm)
                is Screen.Payment -> PaymentScreen(screen, vm)
                Screen.Shop -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        TextButton(onClick = { tab = 0 }) { Text(if (tab == 0) "● Shop" else "Shop") }
                        TextButton(onClick = { tab = 1 }) { Text(if (tab == 1) "● History" else "History") }
                    }
                    if (tab == 0) ShopScreen(ui, vm) else HistoryScreen(history)
                }
            }
        }
    }

    if (showDemoControls) DemoControls(ui, vm, onClose = { showDemoControls = false })
}

/** Required on every screen (CLAUDE.md §9). */
@Composable
private fun DemoBanner() {
    Text(
        text = MockPaymentEngine.LABEL,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().background(DemoRed).padding(6.dp),
    )
}

/** The logo hides Demo controls: long-press it. */
@Composable
private fun Header(onLongPress: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Sunrise.copy(alpha = 0.15f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "☀",
            fontSize = 32.sp,
            modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Sunrise Market", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Fresh food stand · protected by posguard", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusBanners(ui: UiState, onDismiss: () -> Unit, onReconnect: () -> Unit) {
    when (val s = ui.startup) {
        StartupState.Recovering -> Note("Checking for unfinished payments…", Color(0xFFE0E7FF))
        is StartupState.Pending -> Card(
            Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("${s.unresolved} payment(s) waiting for connection", fontWeight = FontWeight.Bold)
                Text("New payments are paused until the result is confirmed, so nobody is charged twice.")
                Button(onClick = onReconnect, modifier = Modifier.padding(top = 6.dp)) { Text("Reconnect") }
            }
        }
        StartupState.Ready -> Unit
    }
    ui.banner?.let { text ->
        Card(
            Modifier.fillMaxWidth().padding(8.dp).clickable(onClick = onDismiss),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFDCFCE7)),
        ) {
            Text("$text\n(tap to dismiss)", Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().background(color).padding(10.dp))
}

// --- Shop -------------------------------------------------------------------------------

@Composable
private fun ShopScreen(ui: UiState, vm: CheckoutViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f)) {
            items(Catalog.products) { p ->
                Card(
                    Modifier.padding(4.dp).clickable { vm.controller.add(p.id) },
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(p.emoji, fontSize = 32.sp)
                        Text(p.name, textAlign = TextAlign.Center)
                        Text(formatCents(p.priceCents), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(vertical = 8.dp)) {
            if (ui.cart.isEmpty()) Text("Cart is empty. Tap an item to add it.")
            ui.cart.forEach { (id, qty) ->
                val p = Catalog.byId(id) ?: return@forEach
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.emoji} ${p.name} × $qty", Modifier.weight(1f))
                    TextButton(onClick = { vm.controller.remove(id) }) { Text("−") }
                    TextButton(onClick = { vm.controller.add(id) }) { Text("+") }
                }
            }
            Text("Total ${formatCents(ui.totalCents)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(
                onClick = { vm.run { takePayment() } },
                enabled = ui.canTakePayment,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(52.dp),
            ) {
                if (ui.busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White) else Text("Take payment")
            }
        }
    }
}

// --- Why blocked / flagged ---------------------------------------------------------------

@Composable
private fun FlaggedPanel(screen: Screen.Flagged, vm: CheckoutViewModel) {
    val blocked = screen.verdict is GuardVerdict.Block
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            if (blocked) "⛔ Payment blocked" else "⚠ Security notice",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (blocked) DemoRed else Color(0xFF92400E),
        )
        Text(
            if (blocked) "The payment screen did not open. Here is why:" else "You can continue, but please review:",
            Modifier.padding(vertical = 8.dp),
        )
        ReasonList(screen.verdict, vm)
        Spacer(Modifier.height(16.dp))
        if (screen.canContinue) {
            Button(onClick = { vm.controller.continueAfterWarning() }, modifier = Modifier.fillMaxWidth()) { Text("Continue anyway") }
        }
        OutlinedButton(onClick = { vm.controller.backToShop() }, modifier = Modifier.fillMaxWidth()) { Text("Back to shop") }
    }
}

@Composable
private fun ReasonList(verdict: GuardVerdict, vm: CheckoutViewModel) {
    verdict.reasons.forEach { reason ->
        val action = vm.actionFor(reason)
        val simulated = verdict.signals.any { it.reason == reason && (it as? dev.posguard.core.signal.SignalResult.Detected)?.detail == DemoThreats.DETAIL }
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "${actionLabel(action)} · ${reason.code}",
                    fontWeight = FontWeight.Bold,
                    color = when (action) {
                        GuardAction.BLOCK -> DemoRed
                        GuardAction.WARN -> Color(0xFF92400E)
                        GuardAction.ALLOW -> Color.Gray
                    },
                )
                Text(reason.description)
                if (simulated) Text("Simulated for this demo", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

private fun actionLabel(action: GuardAction) = when (action) {
    GuardAction.BLOCK -> "Blocked"
    GuardAction.WARN -> "Warning"
    GuardAction.ALLOW -> "Noted"
}

// --- Payment ----------------------------------------------------------------------------

@Composable
private fun PaymentScreen(screen: Screen.Payment, vm: CheckoutViewModel) {
    val activity = LocalActivity.current
    // Harden the payment window while it is shown (FLAG_SECURE, obscured-touch filter, hide overlays).
    DisposableEffect(activity) {
        activity?.let { PaymentScreenProtection.protect(it) }
        onDispose { activity?.let { PaymentScreenProtection.unprotect(it) } }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Amount due", style = MaterialTheme.typography.titleMedium)
        Text(formatCents(screen.amount.amountCents), fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Text("Secure payment screen · screenshots blocked", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(Modifier.height(20.dp))

        when (val phase = screen.phase) {
            PaymentPhase.ReadyToTap -> {
                Text("📶 Hold card near the phone", fontSize = 20.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.run { tapCard() } }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Tap card (simulated)")
                }
                OutlinedButton(onClick = { vm.controller.startEbt() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Pay with EBT (simulated)")
                }
                OutlinedButton(onClick = { vm.controller.backToShop() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            PaymentPhase.Processing -> {
                CircularProgressIndicator()
                Text("Processing… (simulated)", Modifier.padding(top = 12.dp))
            }
            is PaymentPhase.Approved -> {
                Result("✅ Approved", Color(0xFF15803D), "Reference ${phase.engineReference ?: "-"}\nProcessor charged ${phase.processorCharges}×")
                Button(onClick = { vm.controller.finishSale() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
            is PaymentPhase.Declined -> {
                Result("❌ Declined", DemoRed, "Decline code ${phase.declineCode ?: "-"}. The customer was not charged.")
                OutlinedButton(onClick = { vm.controller.backToShop() }, modifier = Modifier.fillMaxWidth()) { Text("Back to shop") }
            }
            is PaymentPhase.Unknown -> {
                Result(
                    "⚠ Connection lost after sending",
                    Color(0xFF92400E),
                    "The result is unknown. posguard will NOT retry blindly, to avoid a double charge. " +
                        "It will check with the payment engine using the same idempotency key.",
                )
                Button(onClick = { vm.run { reconnect() } }, modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
                OutlinedButton(onClick = { vm.controller.backToShop() }, modifier = Modifier.fillMaxWidth()) { Text("Back to shop (stays pending)") }
            }
            is PaymentPhase.SafeToResubmit -> {
                Result(
                    "ℹ Nothing was charged",
                    Color(0xFF1D4ED8),
                    "The payment engine confirmed it never processed this payment. It is safe to send it again with the same key.",
                )
                Button(onClick = { vm.run { resubmit() } }, modifier = Modifier.fillMaxWidth()) { Text("Resubmit (same key)") }
            }
            is PaymentPhase.EbtSplit -> EbtSplitPanel(phase, vm)
            is PaymentPhase.EbtProcessing -> {
                CircularProgressIndicator()
                EbtLog(phase.log)
                Text(
                    "The PIN is entered on the certified engine's secure PIN entry, never in this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                )
            }
            is PaymentPhase.EbtRemainder -> {
                Result(
                    if (phase.snap.isPartial) "◐ SNAP partially approved" else "✅ SNAP approved",
                    Color(0xFF15803D),
                    "SNAP paid ${formatCents(phase.snap.approved.amountCents)}. " +
                        "Rest due by card: ${formatCents(phase.remaining.amount.amountCents)}",
                )
                EbtLog(phase.log)
                Button(onClick = { vm.run { payRemainderByCard() } }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Charge ${formatCents(phase.remaining.amount.amountCents)} to card")
                }
            }
            is PaymentPhase.EbtDone -> {
                Result("✅ Sale complete", Color(0xFF15803D), "")
                EbtLog(phase.log)
                Text("Receipt (screen copy)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Text(
                    phase.receiptLines.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F4)).padding(10.dp),
                )
                Button(onClick = { vm.controller.finishSale() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Done") }
            }
            is PaymentPhase.EbtFailed -> {
                Result(phase.title, DemoRed, phase.body)
                EbtLog(phase.log)
                OutlinedButton(onClick = { vm.controller.backToShop() }, modifier = Modifier.fillMaxWidth()) { Text("Back to shop") }
            }
            is PaymentPhase.HeldForReview -> {
                Result(
                    "🔎 Approved · held for review",
                    Color(0xFF92400E),
                    "Relay-risk check (experimental) flagged this payment:\n" +
                        phase.reasons.joinToString("\n") { "• $it" } +
                        "\n\nCheck the customer before handing over goods. posguard never declines on this. " +
                        "Processor charged ${phase.processorCharges}×.",
                )
                Button(onClick = { vm.run { completeReviewed() } }, modifier = Modifier.fillMaxWidth()) { Text("Reviewed, complete sale") }
                OutlinedButton(onClick = { vm.controller.finishSale() }, modifier = Modifier.fillMaxWidth()) { Text("Review later") }
            }
        }

        if (screen.verdict.reasons.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Security checks noted:", style = MaterialTheme.typography.labelLarge)
            ReasonList(screen.verdict, vm)
        }
    }
}

@Composable
private fun EbtSplitPanel(phase: PaymentPhase.EbtSplit, vm: CheckoutViewModel) {
    Text("EBT split · ${phase.split.lines.size} lines", style = MaterialTheme.typography.titleMedium)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        phase.split.lines.forEach { line ->
            val p = Catalog.byId(line.line.itemId)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${p?.emoji ?: ""} ${p?.name ?: line.line.itemId} × ${line.line.quantity}", Modifier.weight(1f))
                Text(
                    if (line.snapEligible) "SNAP" else "Other",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(if (line.snapEligible) Color(0xFF15803D) else Color.Gray)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (!line.snapEligible) {
                Text(line.reason.explanation, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
    Text("SNAP-eligible ${formatCents(phase.split.snapEligibleCents)} · other ${formatCents(phase.split.otherCents)}", fontWeight = FontWeight.Bold)
    Text(
        "Example eligibility config for a fictional jurisdiction. Not legal guidance.",
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray,
    )
    Spacer(Modifier.height(8.dp))
    if (phase.split.snapEligibleCents > 0) {
        Button(onClick = { vm.run { payEbt() } }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Charge ${formatCents(phase.split.snapEligibleCents)} to SNAP")
        }
    } else {
        Text("Nothing in this basket is SNAP-eligible. Pay by card.")
    }
    OutlinedButton(onClick = { vm.controller.backToShop() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun EbtLog(log: List<String>) {
    if (log.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        log.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Result(title: String, color: Color, body: String) {
    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
    Text(body, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp))
}

// --- History ----------------------------------------------------------------------------

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
private fun HistoryScreen(history: List<TransactionRecord>) {
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No payments yet.") }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(history, key = { it.id.value }) { r ->
            Card(Modifier.fillMaxWidth().padding(4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${formatCents(r.amount.amountCents)} · ${r.tender}", fontWeight = FontWeight.Bold)
                        Text("${TIME.format(r.createdAt)} · ${r.id.value.take(8)}", style = MaterialTheme.typography.bodySmall)
                    }
                    StateChip(r.state, r.outcome)
                }
            }
        }
    }
}

@Composable
private fun StateChip(state: TxnState, outcome: TxnOutcome?) {
    val (label, color) = when (state) {
        TxnState.COMPLETED ->
            if (outcome == TxnOutcome.DECLINED) "DECLINED" to DemoRed else "APPROVED" to Color(0xFF15803D)
        TxnState.AUTHORIZED -> "HELD FOR REVIEW" to Color(0xFF92400E) // approved, awaiting relay-risk review
        TxnState.DECLINED -> "DECLINED" to DemoRed
        TxnState.VOIDED -> "VOIDED" to Color.Gray
        TxnState.UNKNOWN, TxnState.SUBMITTED, TxnState.CREATED -> "PENDING" to Color(0xFF92400E)
    }
    Text(label, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.background(color).padding(horizontal = 8.dp, vertical = 4.dp))
}

// --- Demo controls ----------------------------------------------------------------------

@Composable
private fun DemoControls(ui: UiState, vm: CheckoutViewModel, onClose: () -> Unit) {
    val events by vm.controller.telemetryEvents.collectAsState()
    val held by vm.controller.heldForReview.collectAsState()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                DemoBanner()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Demo controls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                    TextButton(onClick = onClose) { Text("✕ Close") }
                }
                Text("Arm a scenario, then take a payment.", style = MaterialTheme.typography.bodySmall)
                DemoScenario.entries.forEach { scenario ->
                    val armed = scenario in ui.armed
                    OutlinedButton(
                        onClick = { vm.run { arm(scenario) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (armed) "✔ ${scenario.label} (armed)" else scenario.label) }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.run { reconnect() } }, modifier = Modifier.fillMaxWidth()) { Text("Reconnect network") }
                Button(onClick = { vm.run { resetDemo() }; onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Reset demo") }
                OutlinedButton(onClick = { vm.controller.logScrubbedTestError() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Log an error containing a test card number")
                }

                if (held.isNotEmpty()) {
                    Text("Held for review", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    held.forEach { flagged ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${flagged.hits.joinToString { it.ruleId }} · ${flagged.txnId.value.take(8)}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.run { markReviewed(flagged.txnId) } }) { Text("Mark reviewed") }
                        }
                    }
                }

                Text("Telemetry events (scrubbed)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                if (events.isEmpty()) Text("No events yet.", style = MaterialTheme.typography.bodySmall)
                events.takeLast(15).reversed().forEach { EventRow(it) }

                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

@Composable
private fun EventRow(e: TelemetryEvent) {
    val summary = buildString {
        append(e.type.name)
        e.verdict?.let { append(" · $it") }
        if (e.reasonCodes.isNotEmpty()) append(" · ${e.reasonCodes.joinToString()}")
        e.txn?.let { append(" · ${it.from ?: "new"} → ${it.to}") }
        e.message?.let { append(" · \"$it\"") }
        if (e.attributes.isNotEmpty()) append(" · ${e.attributes}")
    }
    Text(summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
    HorizontalDivider()
}
