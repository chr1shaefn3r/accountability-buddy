package dev.chr1shaefn3r.accountabilitybuddy.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.chr1shaefn3r.accountabilitybuddy.AccountabilityBuddyApp
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.InvitationCode
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Pairing
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingState
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PartnerRole
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushUnavailableException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration

private const val WHAT_A_BUDDY_SEES =
    "A buddy is told when you reach a limit and when you loosen your setup, " +
        "and can get a summary at the end of a day on which you reached a limit.\n\n" +
        "A buddy never sees your list of limits or how much you have used so far."

/** The sender's buddies: confirm requests, rename, remove, and invite new ones. */
@Composable
fun MyBuddiesScreen(
    app: AccountabilityBuddyApp,
    onInvite: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pairings by app.pairings.observeAll().collectAsStateWithLifecycle(initialValue = null)
    val buddies = pairings?.filter { it.partnerRole == PartnerRole.BUDDY }
    var renaming by remember { mutableStateOf<Pairing?>(null) }
    var removing by remember { mutableStateOf<Pairing?>(null) }

    Scaffold(
        topBar = { PageTopBar("My buddies", onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onInvite,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add buddy") },
            )
        },
    ) { padding ->
        when {
            buddies == null -> Unit
            buddies.isEmpty() -> EmptyState(WHAT_A_BUDDY_SEES, "Add buddy", onInvite, Modifier.padding(padding))
            else ->
                LazyColumn(Modifier.padding(padding)) {
                    items(buddies, key = { it.id.hex }) { buddy ->
                        ListItem(
                            headlineContent = { Text(buddy.displayName) },
                            supportingContent = { Text(buddyStatusText(buddy)) },
                            trailingContent = {
                                if (buddy.state == PairingState.AWAITING_CONFIRMATION) {
                                    Row {
                                        TextButton(onClick = { scope.launch { app.buddies.decline(buddy.id) } }) {
                                            Text("Decline")
                                        }
                                        Button(onClick = { scope.launch { app.buddies.confirm(buddy.id) } }) {
                                            Text("Confirm")
                                        }
                                    }
                                } else {
                                    IconButton(onClick = { removing = buddy }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove ${buddy.displayName}")
                                    }
                                }
                            },
                            modifier = Modifier.clickable(onClickLabel = "Rename") { renaming = buddy },
                        )
                    }
                }
        }
    }

    renaming?.let { buddy ->
        TextInputDialog(
            title = "Rename ${buddy.partnerName}",
            text = "The name is changed on this phone only. Leave it empty to use the name your buddy chose.",
            label = "Name",
            initial = buddy.nickname.orEmpty(),
            confirm = "Save",
            allowEmpty = true,
            onConfirm = {
                renaming = null
                scope.launch { app.buddies.rename(buddy.id, it) }
            },
            onDismiss = { renaming = null },
        )
    }
    removing?.let { buddy ->
        ConfirmDialog(
            title = "Remove ${buddy.displayName}?",
            text =
                if (buddy.state == PairingState.ENDED) {
                    "The ended pairing is cleared away."
                } else {
                    "${buddy.displayName} will be told and will no longer hear about your limits."
                },
            confirm = "Remove",
            onConfirm = {
                removing = null
                scope.launch { app.buddies.end(buddy.id) }
            },
            onDismiss = { removing = null },
        )
    }
}

/** Shows the QR code a future buddy scans, and asks for confirmation once they did. */
@Composable
fun InviteScreen(
    app: AccountabilityBuddyApp,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf<InvitationCode?>(null) }
    var failed by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf(Duration.ZERO) }
    val pairings by app.pairings.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val request = pairings.firstOrNull { it.id == code?.pairingId && it.state == PairingState.AWAITING_CONFIRMATION }

    LaunchedEffect(attempt) {
        code = null
        failed = false
        try {
            val invitation = app.buddies.invite()
            code = invitation
            do {
                remaining = Duration.between(app.clock.instant(), invitation.expiresAt)
                delay(TICK_MILLIS)
            } while (!remaining.isNegative && !remaining.isZero)
        } catch (_: PushUnavailableException) {
            failed = true
        }
    }

    Scaffold(topBar = { PageTopBar("Add buddy", onBack) }) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .padding(24.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val current = code
            when {
                failed -> {
                    Text(
                        "The code could not be created. Check your internet connection and that Google Play " +
                            "services are available, then try again.",
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { attempt++ }) { Text("Try again") }
                }
                current == null -> CircularProgressIndicator()
                remaining.isNegative || remaining.isZero -> {
                    Text("This code has expired.", textAlign = TextAlign.Center)
                    Button(onClick = { attempt++ }) { Text("New code") }
                }
                else -> {
                    Text(
                        "Ask your buddy to open Accountability-Buddy, go to Supporting, tap " +
                            "\"Support someone\" and scan this code.",
                        textAlign = TextAlign.Center,
                    )
                    QrCode(current.encode())
                    Text(
                        "Valid for %d:%02d".format(remaining.toMinutes(), remaining.toSecondsPart()),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(WHAT_A_BUDDY_SEES, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    request?.let { buddy ->
        ConfirmDialog(
            title = "Pair with ${buddy.displayName}?",
            text = "${buddy.displayName} scanned your code and wants to be your buddy.",
            confirm = "Confirm",
            dismiss = "Decline",
            onConfirm = {
                scope.launch {
                    app.buddies.confirm(buddy.id)
                    onBack()
                }
            },
            onDismiss = {
                scope.launch {
                    app.buddies.decline(buddy.id)
                    onBack()
                }
            },
        )
    }
}

private const val TICK_MILLIS = 1000L
private const val QR_DARK = android.graphics.Color.BLACK
private const val QR_LIGHT = android.graphics.Color.WHITE

/** A QR code, always dark on white with a quiet zone so that it scans in any theme. */
@Composable
private fun QrCode(content: String) {
    val image =
        remember(content) {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
            val pixels =
                IntArray(matrix.width * matrix.height) {
                    if (matrix[it % matrix.width, it / matrix.width]) QR_DARK else QR_LIGHT
                }
            Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    Image(
        bitmap = image,
        contentDescription = "QR code for your buddy to scan",
        filterQuality = FilterQuality.None,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White),
    )
}
