package com.wansaw.opensms.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wansaw.opensms.data.JobEntity
import com.wansaw.opensms.data.JobState
import com.wansaw.opensms.gateway.GatewayState
import com.wansaw.opensms.gateway.Phase
import com.wansaw.opensms.ui.AppViewModel
import com.wansaw.opensms.ui.components.*
import com.wansaw.opensms.ui.theme.*

private data class Look(val color: Color, val icon: ImageVector, val title: String, val pulse: Boolean)

private fun look(s: GatewayState): Look = when (s.phase) {
    Phase.ONLINE -> Look(Green, Icons.Rounded.CheckCircle, "Online", true)
    Phase.WAITING_APPROVAL -> Look(Orange, Icons.Rounded.HourglassTop, "Waiting for approval", true)
    Phase.CONNECTING, Phase.LOGGING_IN, Phase.STARTING -> Look(Blue, Icons.Rounded.Sync, "Connecting", true)
    Phase.OFFLINE -> Look(Orange, Icons.Rounded.CloudOff, "Offline", false)
    Phase.REJECTED -> Look(Red, Icons.Rounded.Block, "Not approved", false)
    Phase.BLOCKED -> Look(Red, Icons.Rounded.Lock, "Device blocked", false)
    Phase.UPDATE_REQUIRED -> Look(Orange, Icons.Rounded.SystemUpdate, "Update required", false)
    Phase.PAUSED -> Look(Color(0xFF8E8E93), Icons.Rounded.PauseCircle, "Paused", false)
    Phase.SETUP_REQUIRED -> Look(Blue, Icons.Rounded.Settings, "Setup needed", false)
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    val st by vm.gateway.collectAsState()
    val enabled by vm.gatewayEnabled.collectAsState()
    val sent by vm.sentToday.collectAsState()
    val failed by vm.failedToday.collectAsState()
    val pending by vm.pending.collectAsState()
    val history by vm.history.collectAsState()
    val name by vm.deviceName.collectAsState()
    val env by vm.settingsEnv.collectAsState()
    val l = look(if (!enabled) st.copy(phase = Phase.PAUSED) else st)
    val phase = if (!enabled) Phase.PAUSED else st.phase

    Page("OpenSMS", name.ifBlank { "SMS gateway" }) {
        GlassCard(Modifier.fillMaxWidth(), tint = l.color, contentPadding = PaddingValues(vertical = 30.dp, horizontal = 20.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Orb(l)
                Spacer(Modifier.height(18.dp))
                Text(l.title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(subtitle(phase, st), style = MaterialTheme.typography.bodyLarge, color = LocalGlass.current.textSecondary, textAlign = TextAlign.Center)
                if (phase == Phase.WAITING_APPROVAL && st.requestId != null) {
                    Spacer(Modifier.height(14.dp))
                    StatusChip(st.requestId!!, Orange)
                }
                Spacer(Modifier.height(22.dp))
                when (phase) {
                    Phase.WAITING_APPROVAL -> WaitingActions(vm, st)
                    Phase.REJECTED -> GlassButton("Request approval again", { vm.requestAgain() }, icon = Icons.Rounded.Refresh)
                    Phase.OFFLINE -> GlassButton("Retry now", { vm.retryNow() }, kind = ButtonKind.Glass, icon = Icons.Rounded.Refresh)
                    Phase.BLOCKED, Phase.UPDATE_REQUIRED, Phase.SETUP_REQUIRED -> Unit
                    Phase.PAUSED -> GlassButton("Resume gateway", { vm.setGatewayOn(true) }, icon = Icons.Rounded.PlayArrow)
                    else -> GlassButton("Pause gateway", { vm.setGatewayOn(false) }, kind = ButtonKind.Glass, icon = Icons.Rounded.Pause)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Sent today", sent.toString(), Icons.Rounded.Send, Green, Modifier.weight(1f))
            Stat("Failed", failed.toString(), Icons.Rounded.ErrorOutline, Red, Modifier.weight(1f))
            Stat("Pending", pending.toString(), Icons.Rounded.Schedule, Orange, Modifier.weight(1f))
        }

        val limit = env.effectiveDailyLimit
        if (limit != null) {
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Speed, Indigo, 36.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Daily limit", style = MaterialTheme.typography.titleMedium)
                        Text("$limit messages per day · up to 100 characters each", style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary)
                    }
                }
            }
        }

        SectionLabel("Recent activity")
        if (history.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("No messages yet", style = MaterialTheme.typography.titleMedium)
                Text("Messages requested through your API will show up here.", style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary)
            }
        } else {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                history.take(4).forEachIndexed { i, j ->
                    if (i > 0) GlassDivider()
                    JobRow(j)
                }
            }
        }
    }
}

@Composable
private fun WaitingActions(vm: AppViewModel, st: GatewayState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val g = LocalGlass.current
    var checking by remember { mutableStateOf(false) }
    // The spinner stops as soon as the gateway reports a newer check.
    LaunchedEffect(st.lastCheckedAt) { checking = false }
    LaunchedEffect(checking) { if (checking) { kotlinx.coroutines.delay(8000); checking = false } }
    GlassButton("Refresh status", { checking = true; vm.refreshStatus() }, icon = Icons.Rounded.Refresh, loading = checking)
    st.lastCheckedAt?.let {
        Spacer(Modifier.height(10.dp))
        Text(
            "Last checked " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it)) + " · still waiting",
            style = MaterialTheme.typography.bodySmall, color = g.textSecondary,
        )
    }
    val exempt = remember { ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    if (!exempt) {
        TextButton(onClick = { requestIgnoreBattery(ctx) }) { Text("Allow background activity for instant updates", color = Blue) }
    }
}

private fun subtitle(p: Phase, s: GatewayState): String = when (p) {
    Phase.ONLINE -> "Connected and ready. Keep this phone powered and online."
    Phase.WAITING_APPROVAL -> "The administrator has to approve this phone. It will connect automatically once approved."
    Phase.CONNECTING, Phase.LOGGING_IN, Phase.STARTING -> "Talking to your server…"
    Phase.OFFLINE -> (s.message ?: "No connection") + (s.retryInSeconds?.let { " · retrying in ${it}s" } ?: "")
    Phase.REJECTED -> s.message ?: "The administrator rejected this installation."
    Phase.BLOCKED -> s.message ?: "This device was blocked by the administrator and can no longer send messages."
    Phase.UPDATE_REQUIRED -> "This version of the app is too old for the server. Please install the latest release."
    Phase.PAUSED -> "The gateway is paused. No messages will be sent."
    Phase.SETUP_REQUIRED -> "Finish the setup to start."
}

@Composable
private fun Orb(l: Look) {
    val t = rememberInfiniteTransition(label = "orb")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing)), label = "ring")
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        if (l.pulse) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                drawCircle(l.color.copy(alpha = (1f - ring) * 0.35f), radius = r * (0.55f + 0.45f * ring))
            }
        }
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(Brush.linearGradient(listOf(l.color, l.color.copy(alpha = 0.6f)))),
            contentAlignment = Alignment.Center,
        ) { Icon(l.icon, null, tint = Color.White, modifier = Modifier.size(44.dp)) }
    }
}

@Composable
private fun Stat(label: String, value: String, icon: ImageVector, color: Color, modifier: Modifier) {
    GlassCard(modifier, contentPadding = PaddingValues(14.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(10.dp))
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary, maxLines = 1)
    }
}

fun maskNumber(n: String): String =
    if (n.length <= 6) n else n.take(3) + "•".repeat((n.length - 7).coerceAtLeast(3)) + n.takeLast(4)

fun stateColor(s: String): Color = when (s) {
    JobState.SENT, JobState.DELIVERED -> Green
    JobState.FAILED, JobState.EXPIRED, JobState.UNDELIVERED -> Red
    JobState.UNKNOWN -> Orange
    else -> Blue
}

@Composable
fun JobRow(j: JobEntity) {
    val g = LocalGlass.current
    val c = stateColor(j.state)
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(
            when (j.state) {
                JobState.DELIVERED -> Icons.Rounded.DoneAll
                JobState.SENT -> Icons.Rounded.Done
                JobState.FAILED, JobState.EXPIRED, JobState.UNDELIVERED -> Icons.Rounded.Close
                JobState.UNKNOWN -> Icons.Rounded.QuestionMark
                else -> Icons.Rounded.Schedule
            },
            c, 36.dp,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(j.recipient, style = MaterialTheme.typography.titleMedium)
            val detail = j.errorMessage ?: j.errorCode ?: relative(j.createdAt)
            Text(
                listOfNotNull(j.simUsed?.let { "SIM $it" }, if (j.errorCode != null) j.errorCode else null, relative(j.createdAt)).distinct().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = g.textSecondary, maxLines = 1,
            )
            @Suppress("UNUSED_EXPRESSION") detail
        }
        StatusChip(j.state.replaceFirstChar { it.uppercase() }, c)
    }
}

fun relative(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000} min ago"
        d < 86_400_000 -> "${d / 3_600_000} h ago"
        else -> java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
}
