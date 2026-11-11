package dev.chr1shaefn3r.accountabilitybuddy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.chr1shaefn3r.accountabilitybuddy.ui.AppNavigation
import dev.chr1shaefn3r.accountabilitybuddy.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as AccountabilityBuddyApp
        app.wakeUp()
        setContent {
            AppTheme {
                AppNavigation(app)
            }
        }
    }
}
