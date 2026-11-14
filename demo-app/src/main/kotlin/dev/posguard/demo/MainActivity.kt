package dev.posguard.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import dagger.hilt.android.AndroidEntryPoint
import dev.posguard.demo.ui.SunriseMarketApp

/** Single-activity Sunrise Market demo. **DEMO — NOT A REAL PAYMENT.** */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: CheckoutViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                SunriseMarketApp(vm)
            }
        }
    }
}
