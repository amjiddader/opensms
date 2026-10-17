package com.wansaw.opensms.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wansaw.opensms.ui.AppViewModel
import com.wansaw.opensms.ui.components.*
import com.wansaw.opensms.ui.theme.*

@Composable
fun CallbackScreen(vm: AppViewModel, back: () -> Unit) {
    val state by vm.callback.collectAsState()
    val deliveries by vm.deliveries.collectAsState()
    val testing by vm.testing.collectAsState()
    val ctx = LocalContext.current
    val cfg = state.data
    var enabled by remember(cfg) { mutableStateOf(cfg?.enabled ?: false) }
    var url by remember(cfg) { mutableStateOf(cfg?.url.orEmpty()) }
    var showSecret by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadCallback() }
    val dirty = cfg != null && (enabled != cfg.enabled || url.trim() != cfg.url.orEmpty())
    val urlError = if (url.isNotBlank() && !url.trim().startsWith("https://")) "Must start with https://" else null

    Page("Callbacks", "Your server is told when a message is sent, delivered or fails. Sent by the backend, signed with HMAC.", onBack = back) {
        if (cfg == null && state.loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Blue) }
        state.error?.let { GlassCard(Modifier.fillMaxWidth(), tint = Red) { Text(it) }; Spacer(Modifier.height(10.dp)) }
        if (cfg != null) {
            GlassCard(Modifier.fillMaxWidth()) {
                SwitchRow("Send callbacks", if (enabled) "Events are posted to the URL below." else "Nothing is sent while this is off.", enabled, { enabled = it })
                Spacer(Modifier.height(8.dp))
                GlassField(url, { url = it }, "Callback URL", placeholder = "https://example.com/hooks/sms", keyboardType = KeyboardType.Uri, error = urlError)
                Spacer(Modifier.height(14.dp))
                GlassButton("Save", { vm.saveCallback(enabled, url) }, Modifier.fillMaxWidth(), enabled = dirty && urlError == null && (!enabled || url.isNotBlank()))
                Spacer(Modifier.height(10.dp))
                GlassButton("Send test event", { vm.testCallback() }, Modifier.fillMaxWidth(), kind = ButtonKind.Glass, icon = Icons.Rounded.Send, loading = testing, enabled = !cfg.url.isNullOrBlank() && !dirty)
            }

            SectionLabel("Signing secret")
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Verify the X-OpenSMS-Signature header with this secret.", style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val sec = cfg.secret.orEmpty()
                    Text(
                        if (showSecret) sec else sec.take(4) + "•".repeat(16), Modifier.weight(1f),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    )
                    IconButton(onClick = { showSecret = !showSecret }) { Icon(if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show or hide") }
                    IconButton(onClick = { copyToClipboard(ctx, "OpenSMS callback secret", sec); vm.toast("Secret copied") }) { Icon(Icons.Rounded.ContentCopy, "Copy", tint = Blue) }
                }
                TextButton(onClick = { vm.rotateSecret() }) { Text("Rotate secret", color = Orange) }
            }

            SectionLabel("Recent deliveries")
            if (deliveries.isEmpty()) {
                GlassCard(Modifier.fillMaxWidth()) { Text("Nothing delivered yet", color = LocalGlass.current.textSecondary) }
            } else GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                deliveries.take(15).forEachIndexed { i, d ->
                    if (i > 0) GlassDivider()
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.event, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(d.lastStatusCode?.let { "HTTP $it" }, d.lastError, "${d.attempts} attempt(s)").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary, maxLines = 2,
                            )
                        }
                        StatusChip(d.status.replaceFirstChar { it.uppercase() }, if (d.status == "delivered") Green else if (d.status == "failed") Red else Orange)
                    }
                }
            }
        }
    }
}
