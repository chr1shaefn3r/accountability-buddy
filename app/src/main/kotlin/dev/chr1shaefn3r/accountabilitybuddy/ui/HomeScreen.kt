package dev.chr1shaefn3r.accountabilitybuddy.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import dev.chr1shaefn3r.accountabilitybuddy.AccountabilityBuddyApp
import dev.chr1shaefn3r.accountabilitybuddy.R
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId

private enum class Destination(
    val title: String,
    val icon: Int,
) {
    MY_LIMITS("My limits", R.drawable.ic_limits),
    SUPPORTING("Supporting", R.drawable.ic_supporting),
}

/** The two main destinations, one per role, behind a bottom navigation bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    app: AccountabilityBuddyApp,
    onEditLimit: (LimitId?) -> Unit,
    onMyBuddies: () -> Unit,
    onSender: (PairingId) -> Unit,
    onSettings: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.MY_LIMITS) }
    val topBar: @Composable () -> Unit = {
        TopAppBar(
            title = { Text(destination.title) },
            actions = {
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            },
        )
    }
    val bottomBar: @Composable () -> Unit = {
        NavigationBar {
            Destination.entries.forEach {
                NavigationBarItem(
                    selected = destination == it,
                    onClick = { destination = it },
                    icon = { Icon(painterResource(it.icon), contentDescription = null) },
                    label = { Text(it.title) },
                )
            }
        }
    }
    when (destination) {
        Destination.MY_LIMITS -> MyLimitsScreen(app, topBar, bottomBar, onEditLimit, onMyBuddies)
        Destination.SUPPORTING -> SupportingScreen(app, topBar, bottomBar, onSender)
    }
}
