package dev.chr1shaefn3r.accountabilitybuddy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chr1shaefn3r.accountabilitybuddy.AccountabilityBuddyApp
import dev.chr1shaefn3r.accountabilitybuddy.R
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Pairing
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingState
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PartnerRole
import dev.chr1shaefn3r.accountabilitybuddy.limits.application.LimitStatus
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Limit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.TrackingProblem
import kotlinx.coroutines.delay

private const val REFRESH_MILLIS = 10_000L
private const val MAX_ICONS = 8

private enum class SetupStep { NONE, USAGE_ACCESS, BATTERY }

/** The sender's main page: buddies, tracking problems and today's usage against each limit. */
@Composable
fun MyLimitsScreen(
    app: AccountabilityBuddyApp,
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    onEditLimit: (LimitId?) -> Unit,
    onMyBuddies: () -> Unit,
) {
    val context = LocalContext.current
    val limits by app.limitRepository.observeAll().collectAsStateWithLifecycle(initialValue = null)
    val pairings by app.pairings.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val resumes = rememberResumeCount()
    val lifecycle = LocalLifecycleOwner.current
    // The last known usage is at hand at once; it is brought up to date while the page is visible.
    val statuses by app.limits.statuses.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                app.limits.today()
                delay(REFRESH_MILLIS)
            }
        }
    }
    // The page never waits for usage to be measured: a limit is listed as soon as it is known.
    val cards =
        limits?.map { limit -> limit to statuses?.firstOrNull { it.limit == limit } }
            ?: statuses?.map { it.limit to it }.orEmpty()
    val problems =
        remember(resumes, limits) { if (limits.isNullOrEmpty()) emptySet() else app.phone.currentProblems() }

    // Before the first limit, the sender role needs usage access and, ideally, no battery restrictions.
    var setupStep by rememberSaveable { mutableStateOf(SetupStep.NONE) }
    val continueSetup = {
        setupStep =
            when {
                !app.phone.hasUsageAccess -> SetupStep.USAGE_ACCESS
                setupStep != SetupStep.BATTERY && limits.isNullOrEmpty() && !context.isExemptFromBatteryOptimization ->
                    SetupStep.BATTERY
                else -> {
                    onEditLimit(null)
                    SetupStep.NONE
                }
            }
    }
    LaunchedEffect(resumes) {
        val granted =
            (setupStep == SetupStep.USAGE_ACCESS && app.phone.hasUsageAccess) ||
                (setupStep == SetupStep.BATTERY && context.isExemptFromBatteryOptimization)
        if (granted) continueSetup()
    }

    Scaffold(
        topBar = topBar,
        bottomBar = bottomBar,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = continueSetup,
                // Screen readers get the label from the icon: the button hides its text from them.
                icon = { Icon(Icons.Default.Add, contentDescription = "Add limit") },
                text = { Text("Add limit") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BuddiesRow(pairings.filter { it.partnerRole == PartnerRole.BUDDY }, onMyBuddies) }
            items(problems.toList()) { ProblemBanner(it) }
            if (limits?.isEmpty() == true) {
                item {
                    Text(
                        "Set a daily time limit for one or more apps. " +
                            "When a limit is reached, your buddies are told. Nothing is blocked.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            items(cards, key = { it.first.id.value }) { (limit, status) ->
                LimitCard(limit, status, onClick = { onEditLimit(limit.id) })
            }
        }
    }

    when (setupStep) {
        SetupStep.USAGE_ACCESS ->
            ConfirmDialog(
                title = "Allow usage access",
                text =
                    "To measure how long apps are used, Accountability-Buddy needs usage access. " +
                        "On the next page, choose Accountability-Buddy and switch it on.",
                confirm = "Open settings",
                onConfirm = { context.openUsageAccessSettings() },
                onDismiss = { setupStep = SetupStep.NONE },
            )
        SetupStep.BATTERY ->
            ConfirmDialog(
                title = "Keep watching in the background",
                text =
                    "So that a reached limit is noticed on time, allow Accountability-Buddy to run " +
                        "without battery restrictions.",
                confirm = "Allow",
                onConfirm = { context.requestBatteryExemption() },
                dismiss = "Not now",
                onDismiss = continueSetup,
            )
        SetupStep.NONE -> Unit
    }
}

@Composable
private fun BuddiesRow(
    buddies: List<Pairing>,
    onClick: () -> Unit,
) {
    val waiting = buddies.firstOrNull { it.state == PairingState.AWAITING_CONFIRMATION }
    val headline =
        when (buddies.size) {
            0 -> "No buddy yet — add one"
            1 -> "1 buddy"
            else -> "${buddies.size} buddies"
        }
    val supporting =
        when {
            waiting != null -> "${waiting.displayName} wants to be your buddy"
            buddies.isEmpty() -> "A buddy is told when you reach a limit"
            else -> buddies.joinToString { it.displayName }
        }
    Card(onClick = onClick) {
        ListItem(
            headlineContent = { Text(headline) },
            supportingContent = { Text(supporting) },
            leadingContent = { Icon(painterResource(R.drawable.ic_supporting), contentDescription = null) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun ProblemBanner(problem: TrackingProblem) {
    val context = LocalContext.current
    val (text, fix) =
        when (problem) {
            TrackingProblem.USAGE_ACCESS_MISSING ->
                "Your limits are not being tracked: usage access is missing." to { context.openUsageAccessSettings() }
            TrackingProblem.NOTIFICATIONS_BLOCKED ->
                "Notifications are switched off for this app." to { context.openNotificationSettings() }
        }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Text(text, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            TextButton(onClick = fix) { Text("Fix") }
        }
    }
}

@Composable
private fun LimitCard(
    limit: Limit,
    status: LimitStatus?,
    onClick: () -> Unit,
) {
    val hit = status?.hit == true
    val fraction = status?.let { (it.used.seconds.toFloat() / limit.budget.seconds).coerceIn(0f, 1f) } ?: 0f
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(limit.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (hit) {
                    Text(
                        "Limit reached",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                limit.apps.take(MAX_ICONS).forEach { AppIcon(it, size = 28.dp) }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = if (hit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                status?.let { usageText(it.used, limit.budget) } ?: "Measuring today's usage…",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
