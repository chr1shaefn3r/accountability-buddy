package dev.chr1shaefn3r.accountabilitybuddy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.chr1shaefn3r.accountabilitybuddy.AccountabilityBuddyApp
import dev.chr1shaefn3r.accountabilitybuddy.buddies.application.PairingRequestResult
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.HistoryEntry
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.InvitationCode
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Pairing
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingId
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingState
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PartnerRole
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushUnavailableException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The buddy's main page: the people the user supports, with the latest news about each. */
@Composable
fun SupportingScreen(
    app: AccountabilityBuddyApp,
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    onSender: (PairingId) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val pairings by app.pairings.observeAll().collectAsStateWithLifecycle(initialValue = null)
    val senders = pairings?.filter { it.partnerRole == PartnerRole.SENDER }
    val latest by app.history.observeLatest().collectAsStateWithLifecycle(initialValue = emptyList())
    var scanned by remember { mutableStateOf<InvitationCode?>(null) }

    val say: (String) -> Unit = { scope.launch { snackbar.showSnackbar(it) } }
    val scan = {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning
            .getClient(context, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val code = barcode.rawValue?.let(InvitationCode::parse)
                if (code == null) say("This is not an Accountability-Buddy code.") else scanned = code
            }.addOnFailureListener { say("The scanner could not be started. Check your internet connection.") }
        Unit
    }

    Scaffold(
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = scan,
                // Screen readers get the label from the icon: the button hides its text from them.
                icon = { Icon(Icons.Default.Add, contentDescription = "Support someone") },
                text = { Text("Support someone") },
            )
        },
    ) { padding ->
        when {
            senders == null -> Unit
            senders.isEmpty() ->
                EmptyState(
                    "Be someone's buddy: scan the code they show you. " +
                        "You are told when they reach one of their limits.",
                    "Support someone",
                    scan,
                    Modifier.padding(padding),
                )
            else ->
                LazyColumn(
                    modifier = Modifier.padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(senders, key = { it.id.hex }) { sender ->
                        val status =
                            senderStatusText(
                                sender,
                                latest.firstOrNull { it.pairingId == sender.id },
                                LocalDate.now(app.clock),
                                app.clock.zone,
                            )
                        Card(onClick = { onSender(sender.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(sender.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(status, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
        }
    }

    scanned?.let { code ->
        TextInputDialog(
            title = "Support ${code.senderName}?",
            text =
                "You will be told when ${code.senderName} reaches a limit or loosens the setup. " +
                    "${code.senderName} will see you under this name:",
            label = "Your name",
            initial = app.preferences.displayName,
            confirm = "Send request",
            onConfirm = { name ->
                scanned = null
                scope.launch {
                    val message =
                        try {
                            when (app.buddies.requestPairing(code, name)) {
                                PairingRequestResult.REQUESTED ->
                                    "Request sent. Waiting for ${code.senderName} to confirm."
                                PairingRequestResult.EXPIRED -> "This code has expired. Ask for a new one."
                                PairingRequestResult.ALREADY_KNOWN -> "You have already scanned this code."
                                PairingRequestResult.OWN_INVITATION -> "This is your own code."
                            }
                        } catch (_: PushUnavailableException) {
                            "No connection to the push service. Try again later."
                        }
                    snackbar.showSnackbar(message)
                }
            },
            onDismiss = { scanned = null },
        )
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

/** Everything about one sender: notification settings, history, rename and stop supporting. */
@Composable
fun SenderDetailScreen(
    app: AccountabilityBuddyApp,
    pairingId: PairingId,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pairings by app.pairings.observeAll().collectAsStateWithLifecycle(initialValue = null)
    val sender = pairings?.firstOrNull { it.id == pairingId }
    val history by app.history.observe(pairingId).collectAsStateWithLifecycle(initialValue = emptyList())
    var renaming by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            PageTopBar(sender?.displayName.orEmpty(), onBack) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
            }
        },
    ) { padding ->
        if (sender == null) return@Scaffold
        val zone = app.clock.zone
        val today = LocalDate.now(app.clock)
        val byDay = history.groupBy { it.dayIn(zone) }.toSortedMap(reverseOrder())
        LazyColumn(Modifier.padding(padding)) {
            item { SenderSettings(app, sender) }
            item {
                Text(
                    if (history.isEmpty()) "Nothing reported yet." else "Last 30 days",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                )
            }
            byDay.forEach { (day, entries) ->
                stickyHeader(key = day.toString()) {
                    Surface(Modifier.fillMaxWidth()) {
                        Text(
                            day.asTextRelativeTo(today),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(entries) { entry ->
                    if (entry.message is BuddyMessage.DayClosed) {
                        ListItem(
                            headlineContent = { Text("Day summary") },
                            supportingContent = { Text(entry.message.asText()) },
                        )
                    } else {
                        ListItem(
                            headlineContent = { Text(entry.message.asText()) },
                            supportingContent = { Text(entry.at.atZone(zone).format(timeFormat)) },
                        )
                    }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                OutlinedButton(
                    onClick = { stopping = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
                ) {
                    Text(if (sender.state == PairingState.ENDED) "Remove" else "Stop supporting ${sender.displayName}")
                }
            }
        }
    }

    if (renaming && sender != null) {
        TextInputDialog(
            title = "Rename ${sender.partnerName}",
            text = "The name is changed on this phone only. Leave it empty to use the name they chose.",
            label = "Name",
            initial = sender.nickname.orEmpty(),
            confirm = "Save",
            allowEmpty = true,
            onConfirm = {
                renaming = false
                scope.launch { app.buddies.rename(pairingId, it) }
            },
            onDismiss = { renaming = false },
        )
    }
    if (stopping && sender != null) {
        ConfirmDialog(
            title = if (sender.state == PairingState.ENDED) "Remove ${sender.displayName}?" else "Stop supporting?",
            text =
                if (sender.state == PairingState.ENDED) {
                    "The ended pairing and its history are cleared away."
                } else {
                    "${sender.displayName} will be told. The history on this phone is deleted."
                },
            confirm = if (sender.state == PairingState.ENDED) "Remove" else "Stop",
            onConfirm = {
                stopping = false
                scope.launch {
                    app.buddies.end(pairingId)
                    onBack()
                }
            },
            onDismiss = { stopping = false },
        )
    }
}

/** A day summary belongs to the day it is about, everything else to the day it arrived. */
private fun HistoryEntry.dayIn(zone: java.time.ZoneId): LocalDate =
    (message as? BuddyMessage.DayClosed)?.day ?: at.atZone(zone).toLocalDate()

@Composable
private fun SenderSettings(
    app: AccountabilityBuddyApp,
    sender: Pairing,
) {
    val scope = rememberCoroutineScope()
    Column {
        Text(
            senderStatusLine(sender),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SwitchRow(
            title = "Instant notifications",
            text = "When a limit is reached or the setup is loosened",
            checked = sender.instantNotifications,
            onChange = { scope.launch { app.buddies.configure(sender.id, it, sender.daySummary) } },
        )
        SwitchRow(
            title = "Day summary",
            text = "At midnight, if a limit was reached that day",
            checked = sender.daySummary,
            onChange = { scope.launch { app.buddies.configure(sender.id, sender.instantNotifications, it) } },
        )
    }
}

private fun senderStatusLine(sender: Pairing): String =
    when (sender.state) {
        PairingState.ACTIVE -> "You are ${sender.displayName}'s buddy."
        PairingState.REQUESTED -> "Waiting for ${sender.displayName} to confirm."
        PairingState.AWAITING_CONFIRMATION -> "Waiting for your confirmation."
        PairingState.UNREACHABLE -> "${sender.displayName} cannot be reached at the moment."
        PairingState.ENDED -> "${sender.displayName} ended the pairing."
    }

@Composable
private fun SwitchRow(
    title: String,
    text: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}
