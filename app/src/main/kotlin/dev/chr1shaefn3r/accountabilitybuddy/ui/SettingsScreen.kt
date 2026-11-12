package dev.chr1shaefn3r.accountabilitybuddy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.chr1shaefn3r.accountabilitybuddy.AccountabilityBuddyApp
import dev.chr1shaefn3r.accountabilitybuddy.BuildConfig
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.TrackingProblem
import kotlinx.coroutines.launch

/** Display name, the state of the permissions with a way to fix them, and a word about privacy. */
@Composable
fun SettingsScreen(
    app: AccountabilityBuddyApp,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var displayName by remember { mutableStateOf(app.preferences.displayName) }
    var renaming by remember { mutableStateOf(false) }
    val resumes = rememberResumeCount()

    Scaffold(topBar = { PageTopBar("Settings", onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("Display name") },
                supportingContent = { Text(displayName) },
                modifier = Modifier.clickable(onClickLabel = "Change") { renaming = true },
            )

            SectionTitle("Permissions")
            key(resumes) {
                val problems = app.phone.currentProblems()
                PermissionRow(
                    title = "Notifications",
                    reason = "To tell you about limits, yours and those of people you support",
                    granted = TrackingProblem.NOTIFICATIONS_BLOCKED !in problems,
                    onFix = { context.openNotificationSettings() },
                )
                PermissionRow(
                    title = "Usage access",
                    reason = "To measure how long apps are used. Only needed if you set limits.",
                    granted = TrackingProblem.USAGE_ACCESS_MISSING !in problems,
                    onFix = { context.openUsageAccessSettings() },
                )
                PermissionRow(
                    title = "No battery restrictions",
                    reason = "To notice a reached limit on time. Only needed if you set limits.",
                    granted = context.isExemptFromBatteryOptimization,
                    onFix = { context.requestBatteryExemption() },
                )
            }

            SectionTitle("About")
            ListItem(
                headlineContent = { Text("Privacy") },
                supportingContent = {
                    Text(
                        "Everything sent between you and the people you pair with is end-to-end encrypted. " +
                            "The server never sees names, apps, limits or usage. " +
                            "There are no analytics and no crash reports.",
                    )
                },
            )
            ListItem(headlineContent = { Text("Version") }, supportingContent = { Text(BuildConfig.VERSION_NAME) })
        }
    }

    if (renaming) {
        TextInputDialog(
            title = "Display name",
            text = "The people you are paired with will see the new name.",
            label = "Display name",
            initial = displayName,
            confirm = "Save",
            onConfirm = {
                renaming = false
                displayName = it
                scope.launch { app.buddies.changeDisplayName(it) }
            },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun PermissionRow(
    title: String,
    reason: String,
    granted: Boolean,
    onFix: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(reason) },
        trailingContent = {
            if (granted) Text("Allowed") else TextButton(onClick = onFix) { Text("Fix") }
        },
    )
}
