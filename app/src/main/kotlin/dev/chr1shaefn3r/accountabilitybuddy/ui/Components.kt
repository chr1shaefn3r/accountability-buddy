package dev.chr1shaefn3r.accountabilitybuddy.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The top bar of every page below the two main destinations.
 *
 * A page that edits something is a full-screen dialog in Material terms: it is left with
 * a close icon ([closes]) and offers its confirming action, such as Save, in [actions].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageTopBar(
    title: String,
    onBack: () -> Unit,
    closes: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                if (closes) {
                    Icon(Icons.Default.Close, contentDescription = "Close without saving")
                } else {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = { actions() },
    )
}

/** What a screen without content shows: what it is for, and the one action that fills it. */
@Composable
fun EmptyState(
    text: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

/** The launcher icon of an installed app. Purely decorative: the app's name is always shown next to it. */
@Composable
fun AppIcon(
    app: AppId,
    size: Dp = 40.dp,
) {
    val context = LocalContext.current
    val icon by produceState(loadedIcons[app], app) {
        if (value != null) return@produceState
        value =
            withContext(Dispatchers.IO) {
                try {
                    context.packageManager
                        .getApplicationIcon(app.packageName)
                        .toBitmap(ICON_PIXELS, ICON_PIXELS)
                        .asImageBitmap()
                        .also { loadedIcons[app] = it }
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
    }
    Box(Modifier.size(size)) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

private const val ICON_PIXELS = 96

/** Icons loaded once are shown at once the next time, instead of popping in again. */
private val loadedIcons = ConcurrentHashMap<AppId, ImageBitmap>()

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
    )
}

/** A dialog that asks for one line of text. */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    text: String? = null,
    allowEmpty: Boolean = false,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                text?.let {
                    Text(it)
                    Spacer(Modifier.height(16.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(MAX_NAME_LENGTH) },
                    label = { Text(label) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = allowEmpty || value.isNotBlank()) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

const val MAX_NAME_LENGTH = 30
