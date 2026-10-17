package com.wansaw.opensms.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wansaw.opensms.data.ApiKey
import com.wansaw.opensms.ui.AppViewModel
import com.wansaw.opensms.ui.components.*
import com.wansaw.opensms.ui.theme.*

fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText(label, text).apply {
        description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    })
}

@Composable
fun KeysScreen(vm: AppViewModel, back: () -> Unit) {
    val state by vm.keys.collectAsState()
    val ctx = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var revoke by remember { mutableStateOf<ApiKey?>(null) }
    LaunchedEffect(Unit) { vm.loadKeys() }

    Page("API keys", "Keys that let your apps send messages through this phone.", onBack = back) {
        GlassButton("New key", { name = ""; creating = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
        Spacer(Modifier.height(14.dp))
        if (state.loading && state.data == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Blue) }
        }
        state.error?.let { GlassCard(Modifier.fillMaxWidth(), tint = Red) { Text(it) } ; Spacer(Modifier.height(10.dp)) }
        state.data?.let { keys ->
            if (keys.isEmpty() && !state.loading) GlassCard(Modifier.fillMaxWidth()) {
                Text("No keys yet", style = MaterialTheme.typography.titleMedium)
                Text("Create one and send it as a Bearer token to the API.", style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary)
            }
            keys.forEach { k ->
                KeyCard(k, onCopy = { copyToClipboard(ctx, "OpenSMS API key", k.key); vm.toast("Key copied") }, onRevoke = { revoke = k })
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("New API key") },
        text = { GlassField(name, { name = it.take(60) }, "Name", placeholder = "e.g. My website") },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                vm.createKey(name) { k -> copyToClipboard(ctx, "OpenSMS API key", k.key); vm.toast("Key created and copied") }
                creating = false
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
    )
    revoke?.let { k ->
        AlertDialog(
            onDismissRequest = { revoke = null },
            title = { Text("Revoke “${k.name}”?") },
            text = { Text("Apps using this key stop working immediately. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.revokeKey(k.id); revoke = null }) { Text("Revoke", color = Red) } },
            dismissButton = { TextButton(onClick = { revoke = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeyCard(k: ApiKey, onCopy: () -> Unit, onRevoke: () -> Unit) {
    val g = LocalGlass.current
    var show by remember { mutableStateOf(false) }
    val active = k.status == "active"
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Key, if (active) Orange else g.textSecondary, 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(k.name, style = MaterialTheme.typography.titleMedium)
                Text(k.lastUsedAt?.let { "Last used ${it.take(10)}" } ?: "Never used", style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
            }
            StatusChip(k.status.replaceFirstChar { it.uppercase() }, if (active) Green else Red)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (show) k.key else k.key.take(8) + "•".repeat(14),
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 2,
            )
            if (active) {
                IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show or hide") }
                IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, "Copy", tint = Blue) }
                IconButton(onClick = onRevoke) { Icon(Icons.Rounded.Delete, "Revoke", tint = Red) }
            }
        }
    }
}
