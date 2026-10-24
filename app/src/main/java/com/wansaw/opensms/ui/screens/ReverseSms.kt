package com.wansaw.opensms.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wansaw.opensms.ui.AppViewModel
import com.wansaw.opensms.ui.components.*
import com.wansaw.opensms.ui.theme.*

private const val MAX_HINT = 32

/** Reverse SMS (ReverseOTP): incoming SMS that match one global hint are forwarded to the callback URL. */
@Composable
fun ReverseSmsScreen(vm: AppViewModel, back: () -> Unit) {
    val env by vm.settingsEnv.collectAsState()
    val s = env.settings
    val callback by vm.callback.collectAsState()
    val ctx = LocalContext.current
    var enabled by remember(s.revsmsEnabled) { mutableStateOf(s.revsmsEnabled) }
    var hint by remember(s.revsmsHint) { mutableStateOf(s.revsmsHint) }
    var match by remember(s.revsmsMatch) { mutableStateOf(s.revsmsMatch) }
    var tick by remember { mutableIntStateOf(0) }
    val permitted = remember(tick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    LaunchedEffect(Unit) { vm.loadCallback() }

    val callbackOn = callback.data?.let { it.enabled && !it.url.isNullOrBlank() }
    val dirty = enabled != s.revsmsEnabled || hint.trim() != s.revsmsHint || match != s.revsmsMatch
    val hintError = when {
        hint.length > MAX_HINT -> "At most $MAX_HINT characters"
        enabled && hint.isBlank() -> "Enter the hint to look for"
        else -> null
    }

    Page("Reverse SMS", "Someone texts your number with a secret hint, your server is called. Works on both SIMs.", onBack = back) {
        GlassCard(Modifier.fillMaxWidth()) {
            SwitchRow("Reverse SMS", if (enabled) "Matching messages are forwarded to your callback URL." else "Incoming messages are not looked at.", enabled, { enabled = it })
            Spacer(Modifier.height(8.dp))
            GlassField(hint, { hint = it }, "Hint", placeholder = "ABC#", error = hintError)
            Spacer(Modifier.height(12.dp))
            Segmented(listOf("prefix" to "Starts with", "contains" to "Contains"), match, { match = it })
            Spacer(Modifier.height(6.dp))
            Text(
                if (match == "contains") "Any message that contains the hint anywhere is forwarded." else "Only messages that begin with the hint are forwarded.",
                style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary,
            )
            Text("Not case sensitive. The same hint applies to every sender and to both SIMs.", style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary)
            Spacer(Modifier.height(14.dp))
            GlassButton("Save", { vm.updateSettings("Reverse SMS saved") { it.copy(revsmsEnabled = enabled, revsmsHint = hint.trim(), revsmsMatch = match) } }, Modifier.fillMaxWidth(), enabled = dirty && hintError == null)
        }

        if (enabled || s.revsmsEnabled) {
            Spacer(Modifier.height(12.dp))
            if (!permitted) GlassCard(Modifier.fillMaxWidth(), tint = Orange) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Sms, null, tint = Orange)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Allow “Receive SMS”", style = MaterialTheme.typography.titleMedium)
                        Text("Without this permission Android does not show incoming messages to the app.", style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary)
                    }
                    TextButton(onClick = { launcher.launch(Manifest.permission.RECEIVE_SMS) }) { Text("Allow", color = Blue) }
                }
            } else GlassCard(Modifier.fillMaxWidth(), tint = Green) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Green)
                    Spacer(Modifier.width(10.dp))
                    Text("Receive SMS permission granted", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (callbackOn == false) {
                Spacer(Modifier.height(10.dp))
                GlassCard(Modifier.fillMaxWidth(), tint = Red) {
                    Text("Callbacks are off. Turn them on and save a URL (Settings → Callbacks), otherwise matching messages are dropped.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        SectionLabel("How it works")
        GlassCard(Modifier.fillMaxWidth()) {
            Text(
                "Anyone who sends “$HINT_EXAMPLE” (or your own hint) to this phone triggers a signed webhook with the sender’s number, the message, the SIM and the time. " +
                    "Other messages are ignored and never leave this phone. There is no rate limit.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Android does not let apps delete or hide incoming messages, so every message also stays in your normal Messages app. Messages received while the gateway is offline are forwarded when it reconnects.",
                style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

private const val HINT_EXAMPLE = "ABC# 482913"
