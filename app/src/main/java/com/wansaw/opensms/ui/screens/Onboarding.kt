package com.wansaw.opensms.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wansaw.opensms.BuildConfig
import com.wansaw.opensms.data.*
import com.wansaw.opensms.ui.AppViewModel
import com.wansaw.opensms.ui.components.*
import com.wansaw.opensms.ui.theme.*

private const val STEPS = 6

@Composable
fun OnboardingScreen(vm: AppViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var termsOk by rememberSaveable { mutableStateOf(false) }
    var home by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf("single") }
    var extra by rememberSaveable { mutableStateOf(listOf<String>()) }
    var simMode by rememberSaveable { mutableStateOf("auto") }
    var name by rememberSaveable { mutableStateOf(android.os.Build.MODEL ?: "") }
    var tick by remember { mutableIntStateOf(0) } // bumps on resume so permission state refreshes
    val ctx = LocalContext.current

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { tick++; vm.refreshSims() } }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    BackHandler(enabled = step > 0) { step-- }

    val smsOk = remember(tick) { granted(ctx, Manifest.permission.SEND_SMS) }
    val phoneOk = remember(tick) { granted(ctx, Manifest.permission.READ_PHONE_STATE) }
    val sims by vm.sims.collectAsState()

    // Suggest the home country from the SIM as soon as the phone permission exists.
    LaunchedEffect(phoneOk, tick) {
        if (home == null && phoneOk) home = vm.homeCountryGuess()
    }

    val canNext = when (step) {
        0 -> termsOk
        1 -> smsOk && phoneOk
        2 -> home != null
        3 -> true
        else -> true
    }
    val last = step == STEPS - 1

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        StepDots(step)
        Box(Modifier.weight(1f).padding(top = 8.dp)) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val fwd = targetState > initialState
                    (slideInHorizontally { if (fwd) it / 4 else -it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (fwd) -it / 4 else it / 4 } + fadeOut()) using SizeTransform(clip = false)
                },
                label = "step",
            ) { s ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                    when (s) {
                        0 -> WelcomeStep(termsOk) { termsOk = it }
                        1 -> PermissionsStep(tick, onChanged = { tick++ })
                        2 -> HomeStep(home, phoneOk, vm.homeCountryGuess()) { home = it }
                        3 -> DestinationStep(home, mode, extra, { mode = it }) { iso ->
                            extra = if (iso in extra) extra - iso else extra + iso
                        }
                        4 -> SimStep(sims, simMode) { simMode = it }
                        else -> NameStep(name, { name = it })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) GlassButton("Back", { step-- }, Modifier.weight(0.4f), kind = ButtonKind.Glass)
            GlassButton(
                if (last) "Start gateway" else "Continue",
                onClick = {
                    if (!last) {
                        if (step == 0) vm.acceptTerms()
                        step++
                    } else {
                        val h = home!!
                        val dest = when (mode) {
                            "all" -> Destination("all", emptyList())
                            "multiple" -> Destination("multiple", (listOf(h) + extra).distinct())
                            else -> Destination("single", listOf(h))
                        }
                        val draft = Settings(
                            homeCountry = h,
                            destination = dest,
                            sims = SimsCfg(SimCfg(enabled = simMode != "sim_2"), SimCfg(enabled = simMode != "sim_1")),
                        )
                        vm.completeSetup(name.ifBlank { android.os.Build.MODEL }, draft)
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = canNext && (!last || name.isNotBlank()),
                icon = if (last) Icons.Rounded.RocketLaunch else null,
            )
        }
    }
}

private fun granted(ctx: android.content.Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@Composable
private fun StepDots(step: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(STEPS) { i ->
            val active = i == step
            Box(
                Modifier.padding(horizontal = 3.dp).height(6.dp).width(if (active) 26.dp else 6.dp).clip(CircleShape)
                    .background(if (i <= step) Blue else LocalGlass.current.fillStrong),
            )
        }
    }
}

@Composable
private fun StepHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, title: String, subtitle: String) {
    Spacer(Modifier.height(28.dp))
    IconBadge(icon, color, 64.dp)
    Spacer(Modifier.height(18.dp))
    Text(title, style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.height(6.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = LocalGlass.current.textSecondary)
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun WelcomeStep(accepted: Boolean, onAccept: (Boolean) -> Unit) {
    StepHeader(Icons.Rounded.Sms, Blue, "Your phone,\nyour SMS gateway", "OpenSMS sends text messages from your own SIM cards, on request from your own server.")
    GlassCard(Modifier.fillMaxWidth()) {
        Bullet(Icons.Rounded.Lock, "Private by design", "No cloud accounts. The phone connects outward to your backend only.")
        Spacer(Modifier.height(12.dp))
        Bullet(Icons.Rounded.SimCard, "Dual SIM aware", "Choose SIM 1, SIM 2, or let the app balance between them.")
        Spacer(Modifier.height(12.dp))
        Bullet(Icons.Rounded.VerifiedUser, "Admin approved", "Each installation must be approved before it can send anything.")
    }
    Spacer(Modifier.height(14.dp))
    GlassCard(Modifier.fillMaxWidth(), onClick = { onAccept(!accepted) }) {
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(accepted, { onAccept(it) }, colors = CheckboxDefaults.colors(checkedColor = Blue))
            Spacer(Modifier.width(6.dp))
            Column {
                Text("I understand and agree", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Messages are sent from my SIM and may incur carrier charges. I am responsible for the content sent through this device and for following the law and my carrier's terms. (Terms ${BuildConfig.TERMS_VERSION})",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalGlass.current.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun Bullet(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Blue, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary)
        }
    }
}

@Composable
private fun PermissionsStep(tick: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChanged() }
    StepHeader(Icons.Rounded.Shield, Indigo, "Permissions", "Only what a gateway needs. No contacts, no location, no call log.")
    GlassCard(Modifier.fillMaxWidth()) {
        PermRow(Icons.Rounded.Sms, Green, "Send SMS", "Required to send messages through your SIM.", granted(ctx, Manifest.permission.SEND_SMS), true) { launcher.launch(Manifest.permission.SEND_SMS) }
        GlassDivider()
        PermRow(Icons.Rounded.SimCard, Blue, "Phone & SIM info", "Required to see your SIMs, carriers and country.", granted(ctx, Manifest.permission.READ_PHONE_STATE), true) { launcher.launch(Manifest.permission.READ_PHONE_STATE) }
        if (Build.VERSION.SDK_INT >= 33) {
            GlassDivider()
            PermRow(Icons.Rounded.Notifications, Orange, "Notifications", "Shows the gateway status while it runs.", granted(ctx, Manifest.permission.POST_NOTIFICATIONS), false) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        GlassDivider()
        val ignoring = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
        PermRow(Icons.Rounded.BatteryChargingFull, Teal, "Run in background", "Lets the gateway stay connected when the screen is off.", ignoring, false) {
            battery.launch(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "Android may ask once more when the first message is sent. Some brands (Xiaomi, Oppo, Vivo, Huawei, Samsung) also need “autostart” enabled; Settings → Reliability explains how.",
        style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary, modifier = Modifier.padding(horizontal = 6.dp),
    )
}

@Composable
private fun PermRow(icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, title: String, body: String, granted: Boolean, required: Boolean, onAllow: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, color, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (required && !granted) { Spacer(Modifier.width(8.dp)); StatusChip("Required", Orange) }
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary)
        }
        Spacer(Modifier.width(8.dp))
        if (granted) Icon(Icons.Rounded.CheckCircle, null, tint = Green, modifier = Modifier.size(28.dp))
        else TextButton(onClick = onAllow) { Text("Allow", color = Blue, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun HomeStep(home: String?, phoneOk: Boolean, guess: String?, onPick: (String) -> Unit) {
    StepHeader(Icons.Rounded.Public, Teal, "Home country", "Numbers typed without a country code are read as numbers from this country.")
    if (guess != null && phoneOk) {
        val c = Countries.byIso(guess)
        if (c != null) GlassCard(Modifier.fillMaxWidth(), tint = Green, onClick = { onPick(c.iso) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Green)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Detected from your SIM", style = MaterialTheme.typography.labelMedium, color = LocalGlass.current.textSecondary)
                    Text(c.label, style = MaterialTheme.typography.titleMedium)
                }
                if (home == c.iso) Icon(Icons.Rounded.CheckCircle, null, tint = Green)
            }
        }
        Spacer(Modifier.height(12.dp))
    } else {
        Text("No SIM country found, please choose one.", style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary)
        Spacer(Modifier.height(8.dp))
    }
    CountryPicker(selected = setOfNotNull(home), multi = false, onToggle = onPick)
}

@Composable
private fun DestinationStep(home: String?, mode: String, extra: List<String>, onMode: (String) -> Unit, onToggle: (String) -> Unit) {
    StepHeader(Icons.Rounded.Language, Purple, "Where can you send?", "Messages to any other country are refused. You can change this later.")
    Segmented(listOf("single" to "Home only", "multiple" to "Selected", "all" to "Anywhere"), mode, onMode)
    Spacer(Modifier.height(14.dp))
    val hc = Countries.byIso(home)
    when (mode) {
        "single" -> GlassCard(Modifier.fillMaxWidth()) { Text("Only numbers in ${hc?.name ?: "your home country"} ${hc?.flag ?: ""} can receive messages.", style = MaterialTheme.typography.bodyLarge) }
        "all" -> GlassCard(Modifier.fillMaxWidth(), tint = Orange) { Text("International messages can be expensive. Make sure your plan and carrier allow them.", style = MaterialTheme.typography.bodyLarge) }
        else -> {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("${hc?.name ?: "Home"} is always included. Pick additional countries:", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(10.dp))
            CountryPicker(selected = extra.toSet(), multi = true, onToggle = onToggle, exclude = home)
        }
    }
}

@Composable
private fun SimStep(sims: List<SimReport>, simMode: String, onMode: (String) -> Unit) {
    StepHeader(Icons.Rounded.SimCard, Blue, "Which SIMs?", "Auto uses both SIMs. You can restrict sending to a single SIM.")
    sims.forEach { s ->
        GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.SimCard, if (s.present) Blue else LocalGlass.current.textSecondary, 40.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("SIM ${s.slot}", style = MaterialTheme.typography.titleMedium)
                    Text(if (s.present) listOfNotNull(s.displayName ?: s.carrierName, s.countryIso).joinToString(" · ") else "No SIM detected", style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary)
                }
                if (s.present) StatusChip("Ready", Green)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Segmented(
        listOf("auto" to "Auto (both)", "sim_1" to "SIM 1", "sim_2" to "SIM 2"), simMode, onMode,
        enabled = { v -> v == "auto" || sims.firstOrNull { "sim_${it.slot}" == v }?.present == true },
    )
}

@Composable
private fun NameStep(name: String, onName: (String) -> Unit) {
    StepHeader(Icons.Rounded.PhoneAndroid, Pink, "Name this phone", "Shown to the administrator when approving this device.")
    GlassField(name, onName, "Device name")
    Spacer(Modifier.height(16.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Bullet(Icons.Rounded.HourglassTop, "Approval needed", "After you start, the administrator must approve this phone. Keep the app installed, it will connect on its own once approved.")
    }
}

/** Searchable country list. */
@Composable
fun CountryPicker(selected: Set<String>, multi: Boolean, onToggle: (String) -> Unit, exclude: String? = null, height: androidx.compose.ui.unit.Dp = 320.dp) {
    var q by remember { mutableStateOf("") }
    val list = remember(q, exclude) { Countries.search(q).filter { it.iso != exclude } }
    GlassField(q, { q = it }, "Search country or code", trailing = { Icon(Icons.Rounded.Search, null) })
    Spacer(Modifier.height(10.dp))
    GlassCard(Modifier.fillMaxWidth().height(height), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.iso }) { c ->
                val on = c.iso in selected
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onToggle(c.iso) }
                        .background(if (on) Blue.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.flag, fontSize = 22.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("+${c.dial}", color = LocalGlass.current.textSecondary, style = MaterialTheme.typography.bodyMedium)
                    if (on) { Spacer(Modifier.width(8.dp)); Icon(if (multi) Icons.Rounded.CheckBox else Icons.Rounded.CheckCircle, null, tint = Blue) }
                }
            }
        }
    }
}
