package dev.chr1shaefn3r.accountabilitybuddy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chr1shaefn3r.accountabilitybuddy.AccountabilityBuddyApp
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingState
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PartnerRole
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.InstalledApp
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Limit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.UUID

private const val BUDDIES_WILL_BE_TOLD = "Your buddies will be told about this change."

private val appsSaver =
    listSaver<Set<AppId>, String>(save = { it.map(AppId::packageName) }, restore = { it.map(::AppId).toSet() })

/** Creates a new limit, or changes or deletes the one with [limitId]. */
@Composable
fun EditLimitScreen(
    app: AccountabilityBuddyApp,
    limitId: LimitId?,
    onBack: () -> Unit,
) {
    if (limitId == null) {
        LimitForm(app, existing = null, onBack = onBack)
        return
    }
    val loaded by produceState<Limit?>(null, limitId) { value = app.limitRepository.find(limitId) }
    loaded?.let { LimitForm(app, existing = it, onBack = onBack) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LimitForm(
    app: AccountabilityBuddyApp,
    existing: Limit?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pairings by app.pairings.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val hasBuddies = pairings.any { it.partnerRole == PartnerRole.BUDDY && it.state == PairingState.ACTIVE }

    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    val initialBudget = existing?.budget ?: Duration.ofHours(1)
    val time =
        rememberTimePickerState(
            initialHour = initialBudget.toHoursPart().coerceAtMost(MAX_HOUR),
            initialMinute = if (initialBudget.toHours() > MAX_HOUR) MAX_MINUTE else initialBudget.toMinutesPart(),
            is24Hour = true,
        )
    var apps by rememberSaveable(stateSaver = appsSaver) { mutableStateOf(existing?.apps.orEmpty()) }
    var pickingApps by rememberSaveable { mutableStateOf(false) }
    var confirmingWeakening by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    val budget = Duration.ofHours(time.hour.toLong()).plusMinutes(time.minute.toLong())
    val valid = name.isNotBlank() && budget >= Limit.MIN_BUDGET && apps.isNotEmpty()

    fun candidate() = Limit(existing?.id ?: LimitId(UUID.randomUUID().toString()), name.trim(), budget, apps)

    fun save() {
        scope.launch {
            app.limits.save(candidate())
            app.wakeUp()
            onBack()
        }
    }

    if (pickingApps) {
        AppPicker(
            app = app,
            selected = apps,
            onDone = {
                apps = it
                pickingApps = false
            },
        )
        return
    }

    Scaffold(
        topBar = {
            PageTopBar(if (existing == null) "New limit" else "Edit limit", onBack) {
                if (existing != null) {
                    IconButton(onClick = { confirmingDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete limit")
                    }
                }
            }
        },
        // Pinned to the bottom so that it stays in reach however long the list of apps gets.
        bottomBar = {
            Button(
                onClick = {
                    val weakened = existing?.weakenedBy(candidate()).orEmpty().isNotEmpty()
                    if (weakened && hasBuddies) confirmingWeakening = true else save()
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("Save") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(Limit.MAX_NAME_LENGTH) },
                label = { Text("Name") },
                placeholder = { Text("Social Media") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Text("Time per day (hours : minutes)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            TimeInput(state = time)
            if (budget < Limit.MIN_BUDGET) {
                Text("At least 1 minute", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Text("Apps sharing this time", style = MaterialTheme.typography.titleSmall)
            apps
                .map {
                    InstalledApp(
                        it,
                        remember(it) { app.phone.label(it) },
                    )
                }.sortedBy { it.label.lowercase() }
                .forEach {
                    ListItem(
                        headlineContent = { Text(it.label) },
                        leadingContent = { AppIcon(it.id) },
                        trailingContent = {
                            IconButton(onClick = { apps = apps - it.id }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove ${it.label}")
                            }
                        },
                    )
                }
            OutlinedButton(onClick = { pickingApps = true }, modifier = Modifier.fillMaxWidth()) { Text("Add apps") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmingWeakening) {
        ConfirmDialog(
            title = "Loosen this limit?",
            text = BUDDIES_WILL_BE_TOLD,
            confirm = "Save",
            onConfirm = {
                confirmingWeakening = false
                save()
            },
            onDismiss = { confirmingWeakening = false },
        )
    }
    if (confirmingDelete && existing != null) {
        ConfirmDialog(
            title = "Delete this limit?",
            text = if (hasBuddies) BUDDIES_WILL_BE_TOLD else "The limit is removed for good.",
            confirm = "Delete",
            onConfirm = {
                confirmingDelete = false
                scope.launch {
                    app.limits.delete(existing.id)
                    onBack()
                }
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

private const val MAX_HOUR = 23
private const val MAX_MINUTE = 59

/** A searchable list of the installed apps with a checkbox each. */
@Composable
private fun AppPicker(
    app: AccountabilityBuddyApp,
    selected: Set<AppId>,
    onDone: (Set<AppId>) -> Unit,
) {
    var chosen by rememberSaveable(stateSaver = appsSaver) { mutableStateOf(selected) }
    var search by rememberSaveable { mutableStateOf("") }
    val installed by produceState<List<InstalledApp>?>(null) {
        value = withContext(Dispatchers.IO) { app.phone.launchableApps() }
    }
    BackHandler { onDone(chosen) }
    Scaffold(
        topBar = {
            PageTopBar("Choose apps", onBack = { onDone(chosen) }) {
                TextButton(onClick = { onDone(chosen) }) { Text("Done") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyColumn {
                items(
                    installed.orEmpty().filter { it.label.contains(search.trim(), ignoreCase = true) },
                    key = { it.id.packageName },
                ) {
                    val checked = it.id in chosen
                    ListItem(
                        headlineContent = { Text(it.label) },
                        leadingContent = { AppIcon(it.id) },
                        trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        modifier =
                            Modifier.toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { on -> chosen = if (on) chosen + it.id else chosen - it.id },
                            ),
                    )
                }
            }
        }
    }
}
