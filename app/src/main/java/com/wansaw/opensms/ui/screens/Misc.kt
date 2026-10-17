package com.wansaw.opensms.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wansaw.opensms.BuildConfig
import com.wansaw.opensms.ui.AppViewModel
import com.wansaw.opensms.ui.components.*
import com.wansaw.opensms.ui.theme.*

@Composable
fun ReliabilityScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val vendor = Build.MANUFACTURER.lowercase()
    Page("Reliability", "Keep the gateway alive while the screen is off.", onBack = back) {
        GlassCard(Modifier.fillMaxWidth()) {
            Step("1", "Allow background activity", "Exempt OpenSMS from battery optimisation.")
            GlassButton("Open battery setting", { requestIgnoreBattery(ctx) }, Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.Glass, icon = Icons.Rounded.BatteryChargingFull)
        }
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Step("2", "Turn off “Pause app activity if unused”", "In App info → Permissions (or Battery) switch off any automatic clean-up, then set battery to Unrestricted.")
            GlassButton("Open app info", { ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).newTask()) }, Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.Glass, icon = Icons.Rounded.Settings)
        }
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Step("3", "Allow autostart", vendorHint(vendor))
            GlassButton("Open autostart screen", { openAutostart(ctx, vendor) }, Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.Glass, icon = Icons.Rounded.RocketLaunch)
        }
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Step("4", "Lock in recent apps (if your phone has it)", "Xiaomi, Oppo, Vivo, Huawei: open the recent-apps screen, long-press the OpenSMS card and choose Lock.")
        }
        Spacer(Modifier.height(12.dp))
        Text("Do not force-stop the app. A force-stopped app cannot start again until you open it.", style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

@Composable
private fun Step(n: String, title: String, body: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
        Box(Modifier.size(28.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { StatusChip(n, Blue) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary)
        }
    }
}

private fun Intent.newTask() = apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

private fun requestIgnoreBattery(ctx: Context) {
    val direct = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).newTask()
    runCatching { ctx.startActivity(direct) }.onFailure {
        runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).newTask()) }
    }
}

private fun vendorHint(v: String) = when {
    "xiaomi" in v || "redmi" in v || "poco" in v -> "Xiaomi/Redmi/POCO: Security → Permissions → Autostart → enable OpenSMS. Also set Battery saver to “No restrictions”."
    "oppo" in v || "realme" in v || "oneplus" in v -> "Oppo/Realme/OnePlus: Settings → Battery → App battery management → OpenSMS → Allow background activity and auto-launch."
    "vivo" in v || "iqoo" in v -> "Vivo/iQOO: Settings → Battery → Background power consumption → OpenSMS → Allow; enable Autostart in i Manager."
    "huawei" in v || "honor" in v -> "Huawei/Honor: Settings → Battery → App launch → OpenSMS → Manage manually → enable all three switches."
    "samsung" in v -> "Samsung: Settings → Battery → Background usage limits → remove OpenSMS from “Sleeping apps”, add it to “Never sleeping apps”."
    else -> "Some phones have an extra autostart or “protected apps” list. If yours does, enable OpenSMS there."
}

private fun openAutostart(ctx: Context, v: String) {
    val candidates = buildList {
        when {
            "xiaomi" in v || "redmi" in v || "poco" in v -> add(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            "oppo" in v || "realme" in v -> {
                add(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
                add(ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"))
            }
            "vivo" in v || "iqoo" in v -> add(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
            "huawei" in v || "honor" in v -> add(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
            "samsung" in v -> add(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"))
        }
    }
    for (c in candidates) {
        if (runCatching { ctx.startActivity(Intent().setComponent(c).newTask()) }.isSuccess) return
    }
    runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).newTask()) }
}

@Composable
fun AboutScreen(vm: AppViewModel, back: () -> Unit) {
    val name by vm.deviceName.collectAsState()
    val summary by vm.summary.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    Page("About", onBack = back) {
        GlassCard(Modifier.fillMaxWidth()) {
            Info("App", "OpenSMS ${BuildConfig.VERSION_NAME}")
            GlassDivider()
            Info("Device name", name.ifBlank { "—" })
            GlassDivider()
            Info("Device ID", summary?.id?.ifBlank { null } ?: "Not registered yet")
            GlassDivider()
            Info("Server", BuildConfig.API_BASE_URL.removePrefix("https://"))
            GlassDivider()
            Info("Terms", BuildConfig.TERMS_VERSION)
        }
        SectionLabel("Danger zone")
        GlassCard(Modifier.fillMaxWidth(), tint = Red) {
            Text("Reset this phone", style = MaterialTheme.typography.titleMedium)
            Text("Removes the credentials and local history from this phone and returns to setup. Reinstalling needs administrator approval again.", style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary)
            Spacer(Modifier.height(12.dp))
            GlassButton("Reset app", { confirm = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Reset this phone?") },
        text = { Text("Pending messages on this phone are discarded and the gateway stops.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.resetApp { back() } }) { Text("Reset", color = Red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = LocalGlass.current.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
