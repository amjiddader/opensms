package com.wansaw.opensms.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wansaw.opensms.data.Countries
import com.wansaw.opensms.ui.AppViewModel
import com.wansaw.opensms.ui.components.*
import com.wansaw.opensms.ui.theme.*

@Composable
fun SettingsScreen(vm: AppViewModel, open: (String) -> Unit) {
    val env by vm.settingsEnv.collectAsState()
    val s = env.settings
    val home = Countries.byIso(s.homeCountry)
    var pacing by remember(s.pacingIntervalMs) { mutableFloatStateOf(s.pacingIntervalMs / 1000f) }

    Page("Settings") {
        SectionLabel("Messaging")
        GlassCard(Modifier.fillMaxWidth()) {
            NavRow(
                Icons.Rounded.Public, Teal, "Destinations",
                when (s.destination.mode) { "all" -> "Anywhere"; "multiple" -> "${s.destination.countries.size} countries"; else -> home?.let { "${it.flag} ${it.iso}" } ?: "Not set" },
            ) { open("destinations") }
            GlassDivider()
            Column(Modifier.padding(vertical = 10.dp)) {
                Row {
                    Text("Pacing", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text("${"%.1f".format(pacing)} s between messages", style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary)
                }
                Slider(
                    value = pacing, onValueChange = { pacing = it },
                    onValueChangeFinished = { vm.updateSettings { it.copy(pacingIntervalMs = (pacing * 1000).toInt().coerceAtLeast(2100)) } },
                    valueRange = 2.1f..30f,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Blue),
                )
                Text("Android limits how fast apps may send SMS; never go below 2.1 s.", style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary)
            }
            GlassDivider()
            Column(Modifier.padding(vertical = 10.dp)) {
                Text("Retry failed messages", style = MaterialTheme.typography.titleMedium)
                Text(
                    "If the phone is told a message was NOT sent (for example a network error), try again. A message that may already have been sent is never sent twice.",
                    style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Segmented(listOf(0 to "Off", 1 to "1×", 2 to "2×", 3 to "3×"), s.retryCount.coerceIn(0, 3), { n -> vm.updateSettings { it.copy(retryCount = n) } })
                if (s.retryCount > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text("Wait before retrying", style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    Segmented(
                        listOf(1000, 2000, 5000, 10000).map { it to "${it / 1000} s" },
                        listOf(1000, 2000, 5000, 10000).minBy { kotlin.math.abs(it - s.retryDelayMs) },
                        { ms -> vm.updateSettings { it.copy(retryDelayMs = ms) } },
                    )
                }
            }
            GlassDivider()
            SwitchRow("Delivery reports", "Ask the carrier to confirm delivery. Not every carrier supports this.", s.deliveryReports, { on -> vm.updateSettings { it.copy(deliveryReports = on) } })
            GlassDivider()
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Daily limit (all SIMs)", style = MaterialTheme.typography.titleMedium)
                    Text(s.dailyLimit?.let { "$it messages / day" } ?: "No limit", style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary)
                }
                Stepper(s.dailyLimit, { v -> vm.updateSettings { it.copy(dailyLimit = v) } })
            }
            GlassDivider()
            Column(Modifier.padding(vertical = 10.dp)) {
                Text("Keep history", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf(7, 30, 90, 365).map { it to (if (it == 365) "1 year" else "$it days") }, s.historyRetentionDays.let { r -> listOf(7, 30, 90, 365).minBy { kotlin.math.abs(it - r) } }, { d -> vm.updateSettings { it.copy(historyRetentionDays = d) } })
            }
        }

        SectionLabel("Integration")
        GlassCard(Modifier.fillMaxWidth()) {
            NavRow(Icons.Rounded.Key, Orange, "API keys") { open("keys") }
            GlassDivider()
            NavRow(Icons.Rounded.Webhook, Purple, "Callbacks") { open("callback") }
            GlassDivider()
            NavRow(Icons.Rounded.MarkEmailRead, Teal, "Reverse SMS", if (s.revsmsEnabled) "On" else "Off") { open("revsms") }
        }

        SectionLabel("Device")
        GlassCard(Modifier.fillMaxWidth()) {
            NavRow(Icons.Rounded.BatteryChargingFull, Green, "Reliability") { open("reliability") }
            GlassDivider()
            NavRow(Icons.Rounded.Info, Blue, "About") { open("about") }
        }
    }
}
