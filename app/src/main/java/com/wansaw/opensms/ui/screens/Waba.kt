package com.wansaw.opensms.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wansaw.opensms.ui.AppViewModel
import com.wansaw.opensms.ui.components.*
import com.wansaw.opensms.ui.theme.*
import kotlinx.coroutines.delay

private const val ID_MIN = 4
private const val ID_MAX = 10
private val RESERVED = setOf(
    "admin", "root", "opensms", "opensm", "whatsapp", "waba", "wabafy", "meta", "support", "help", "test", "tests", "null", "none", "undefined",
    "official", "system", "verify", "verified", "otp", "code", "login", "signin", "signup", "api", "www", "mail", "email", "info", "security", "service",
)

/** Mirrors the server rules (the server stays the authority): 4-10 characters a-z / 0-9, at least one letter. */
internal fun identifierProblem(raw: String): String? {
    val id = raw.trim().lowercase()
    return when {
        id.isEmpty() -> null
        id.length < ID_MIN -> "At least $ID_MIN characters"
        id.length > ID_MAX -> "At most $ID_MAX characters"
        !id.all { it in 'a'..'z' || it in '0'..'9' } -> "Only letters a-z and digits 0-9"
        id.none { it in 'a'..'z' } -> "Must contain at least one letter"
        id in RESERVED -> "This identifier is reserved"
        else -> null
    }
}

/** WhatsApp OTP: the phone's public identifier, switch, dedicated webhook and verification log. */
@Composable
fun WabaScreen(vm: AppViewModel, back: () -> Unit) {
    val state by vm.waba.collectAsState()
    val log by vm.wabaLog.collectAsState()
    val avail by vm.wabaAvail.collectAsState()
    val busy by vm.wabaBusy.collectAsState()
    val testing by vm.testing.collectAsState()
    val ctx = LocalContext.current
    val w = state.data
    LaunchedEffect(Unit) { vm.loadWaba() }

    var identifier by remember(w?.identifier) { mutableStateOf(w?.identifier.orEmpty()) }
    var url by remember(w?.callback?.url) { mutableStateOf(w?.callback?.url.orEmpty()) }
    var showSecret by remember { mutableStateOf(false) }

    val clean = identifier.trim().lowercase()
    val problem = identifierProblem(identifier)
    val changed = clean.isNotEmpty() && clean != w?.identifier
    // Ask the server whether the name is free, after the user stops typing.
    LaunchedEffect(clean, problem) {
        vm.clearWabaAvailability()
        if (changed && problem == null) {
            delay(450)
            vm.checkWabaIdentifier(clean)
        }
    }
    val availability = avail?.takeIf { it.identifier == clean }
    val idError = problem ?: if (changed && availability != null && !availability.available) availability.reason ?: "Not available" else null
    val canSave = changed && problem == null && availability?.available == true && !busy
    val urlError = if (url.isNotBlank() && !url.trim().startsWith("https://")) "Must start with https://" else null
    val urlDirty = w != null && url.trim() != w.callback.url.orEmpty()

    Page("WhatsApp OTP", "Users prove their phone number by sending you a WhatsApp message with a one-time code.", onBack = back) {
        if (w == null && state.loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Blue) }
        state.error?.let { GlassCard(Modifier.fillMaxWidth(), tint = Red) { Text(it) }; Spacer(Modifier.height(10.dp)) }
        if (w == null) return@Page

        if (!w.platformEnabled) {
            GlassCard(Modifier.fillMaxWidth(), tint = Orange) {
                Text("WhatsApp verification is not switched on for the platform yet. You can prepare your identifier now.", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(10.dp))
        }

        SectionLabel("Your identifier")
        GlassCard(Modifier.fillMaxWidth()) {
            GlassField(
                identifier, { v -> identifier = v.lowercase().filter { it.isLetterOrDigit() && it.code < 128 }.take(ID_MAX) },
                "Identifier", placeholder = "e.g. wansaw", error = idError,
                trailing = {
                    when {
                        changed && problem == null && availability?.available == true -> Icon(Icons.Rounded.CheckCircle, "Available", tint = Green)
                        changed && problem == null && availability == null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Blue)
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "$ID_MIN–$ID_MAX characters, letters and digits, at least one letter. Unique across OpenSMS.",
                style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary,
            )
            w.canChangeAt?.let {
                Spacer(Modifier.height(4.dp))
                Text("You can change it again after ${it.replace('T', ' ').take(16)} UTC.", style = MaterialTheme.typography.bodySmall, color = Orange)
            }
            Spacer(Modifier.height(12.dp))
            GlassButton(
                if (w.identifier == null) "Create identifier" else "Change identifier",
                { vm.saveWaba(clean, null, if (w.identifier == null) "Identifier created" else "Identifier changed") },
                Modifier.fillMaxWidth(), enabled = canSave, loading = busy,
            )
            if (w.identifier != null) {
                Spacer(Modifier.height(4.dp))
                Text("Changing it cancels pending verifications, and the old name stays reserved for you for 30 days.", style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary)
            }
        }

        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            SwitchRow(
                "WhatsApp OTP",
                when {
                    w.identifier == null -> "Create an identifier first."
                    w.enabled -> "Your API can start WhatsApp verifications."
                    else -> "Off: /v1/whatsapp/init is refused for your API key."
                },
                w.enabled, { on -> vm.saveWaba(null, on, if (on) "WhatsApp OTP on" else "WhatsApp OTP off") }, enabled = w.identifier != null && !busy,
            )
        }

        SectionLabel("Status")
        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                Stat("Verified (24 h)", w.stats.verified24h.toString(), Green, Modifier.weight(1f))
                Stat("Verified total", w.stats.verifiedTotal.toString(), Blue, Modifier.weight(1f))
                Stat("Rejected (24 h)", w.stats.failed24h.toString(), if (w.stats.failed24h > 0) Orange else LocalGlass.current.textSecondary, Modifier.weight(1f))
            }
        }

        SectionLabel("Webhook for verifications")
        GlassCard(Modifier.fillMaxWidth()) {
            Text(
                "Called with the user's WhatsApp number as soon as they send the message. Separate from your SMS callback. Signed with the secret below (X-OpenSMS-Signature).",
                style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary,
            )
            Spacer(Modifier.height(10.dp))
            GlassField(url, { url = it }, "Webhook URL", placeholder = "https://example.com/hooks/whatsapp", keyboardType = KeyboardType.Uri, error = urlError)
            Spacer(Modifier.height(12.dp))
            GlassButton("Save", { vm.saveWabaCallback(url) }, Modifier.fillMaxWidth(), enabled = urlDirty && urlError == null)
            Spacer(Modifier.height(10.dp))
            GlassButton("Send test event", { vm.testWabaCallback() }, Modifier.fillMaxWidth(), kind = ButtonKind.Glass, icon = Icons.Rounded.Send, loading = testing, enabled = !w.callback.url.isNullOrBlank() && !urlDirty)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val sec = w.callback.secret.orEmpty()
                Text(
                    if (showSecret) sec else sec.take(4) + "•".repeat(16), Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                )
                IconButton(onClick = { showSecret = !showSecret }) { Icon(if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show or hide") }
                IconButton(onClick = { copyToClipboard(ctx, "OpenSMS WhatsApp secret", sec); vm.toast("Secret copied") }) { Icon(Icons.Rounded.ContentCopy, "Copy", tint = Blue) }
            }
            TextButton(onClick = { vm.rotateWabaSecret() }) { Text("Rotate secret", color = Orange) }
        }

        SectionLabel("How it works")
        GlassCard(Modifier.fillMaxWidth()) {
            Text(
                "Your server calls POST /v1/whatsapp/init with your API key and gets a code and a message such as “${w.exampleMessage ?: "${'$'}identifier#v0xxxxxxxxxxxxx"}”. " +
                    "Your user sends that message from WhatsApp to ${w.wabaNumber ?: "the OpenSMS WhatsApp number"} and your webhook receives their phone number.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "This phone must be online to start a verification. The webhook is only called if the phone was online in the last 30 minutes. Codes work once and expire after ${w.codeTtlSeconds / 60} minutes.",
                style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary,
            )
        }

        SectionLabel("Recent attempts")
        if (log.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) { Text("Nothing yet", color = LocalGlass.current.textSecondary) }
        } else GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            log.take(15).forEachIndexed { i, e ->
                if (i > 0) GlassDivider()
                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.phone ?: "—", style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(e.identifier, e.at?.replace('T', ' ')?.take(16)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary, maxLines = 1,
                        )
                    }
                    StatusChip(e.outcome.replace('_', ' ').replaceFirstChar { it.uppercase() }, if (e.outcome == "verified") Green else Orange)
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary)
    }
}
