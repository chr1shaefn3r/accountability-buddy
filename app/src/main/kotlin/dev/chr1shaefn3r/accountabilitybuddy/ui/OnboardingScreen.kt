package dev.chr1shaefn3r.accountabilitybuddy.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chr1shaefn3r.accountabilitybuddy.AccountabilityBuddyApp
import kotlinx.coroutines.launch

private const val STEP_WELCOME = 0
private const val STEP_NAME = 1
private const val STEP_NOTIFICATIONS = 2

/** The introduction: what the app does, the display name, and the notification permission. */
@Composable
fun OnboardingScreen(app: AccountabilityBuddyApp, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(STEP_WELCOME) }
    var name by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val askForNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }

    when (step) {
        STEP_WELCOME ->
            OnboardingStep(
                title = "Accountability-Buddy",
                text =
                    "Set daily time limits for your apps. When you reach a limit, a friend you trust is told.\n\n" +
                        "Nothing is blocked. Knowing that your buddy knows is what helps.\n\n" +
                        "You can also be the buddy for someone else.",
                action = "Get started",
                onAction = { step = STEP_NAME },
            )
        STEP_NAME ->
            OnboardingStep(
                title = "What should your buddies call you?",
                text = "Your name is shown only to the people you pair with. The server never sees it.",
                action = "Continue",
                actionEnabled = name.isNotBlank(),
                onAction = {
                    scope.launch {
                        app.buddies.changeDisplayName(name.trim())
                        step = STEP_NOTIFICATIONS
                    }
                },
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        else ->
            OnboardingStep(
                title = "Notifications",
                text = "Notifications tell you when you reach a limit, and when someone you support reaches theirs.",
                action = "Allow notifications",
                onAction = { askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                secondaryAction = "Not now",
                onSecondaryAction = onDone,
            )
    }
}

@Composable
private fun OnboardingStep(
    title: String,
    text: String,
    action: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
    secondaryAction: String? = null,
    onSecondaryAction: () -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            content()
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction, enabled = actionEnabled, modifier = Modifier.fillMaxWidth()) { Text(action) }
            secondaryAction?.let {
                TextButton(onClick = onSecondaryAction, modifier = Modifier.fillMaxWidth()) { Text(it) }
            }
        }
    }
}
