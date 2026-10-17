package com.wansaw.opensms.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wansaw.opensms.data.Countries
import com.wansaw.opensms.data.Destination
import com.wansaw.opensms.ui.AppViewModel
import com.wansaw.opensms.ui.components.*
import com.wansaw.opensms.ui.theme.*

@Composable
fun DestinationsScreen(vm: AppViewModel, back: () -> Unit) {
    val env by vm.settingsEnv.collectAsState()
    val cur = env.settings
    var home by remember(cur.homeCountry) { mutableStateOf(cur.homeCountry) }
    var mode by remember(cur.destination.mode) { mutableStateOf(cur.destination.mode) }
    var extra by remember(cur.destination.countries, cur.homeCountry) { mutableStateOf(cur.destination.countries.filter { it != cur.homeCountry }) }

    Page("Destinations", "Where this phone may send messages.", onBack = back) {
        SectionLabel("Home country")
        val hc = Countries.byIso(home)
        GlassCard(Modifier.fillMaxWidth()) {
            Text(hc?.label ?: "Not set", style = MaterialTheme.typography.titleMedium)
            Text("Numbers without a country code are read as numbers from here.", style = MaterialTheme.typography.bodySmall, color = LocalGlass.current.textSecondary)
        }
        Spacer(Modifier.height(10.dp))
        CountryPicker(selected = setOfNotNull(home), multi = false, onToggle = { home = it }, height = 260.dp)

        SectionLabel("Allowed destinations")
        Segmented(listOf("single" to "Home only", "multiple" to "Selected", "all" to "Anywhere"), mode, { mode = it })
        if (mode == "multiple") {
            Spacer(Modifier.height(10.dp))
            CountryPicker(selected = extra.toSet(), multi = true, onToggle = { iso -> extra = if (iso in extra) extra - iso else extra + iso }, exclude = home, height = 260.dp)
        }
        if (mode == "all") {
            Spacer(Modifier.height(10.dp))
            GlassCard(Modifier.fillMaxWidth(), tint = Orange) { Text("International messages can be expensive. Check your plan.", style = MaterialTheme.typography.bodyMedium) }
        }
        Spacer(Modifier.height(18.dp))
        GlassButton("Save", {
            val h = home ?: return@GlassButton
            val dest = when (mode) {
                "all" -> Destination("all", emptyList())
                "multiple" -> Destination("multiple", (listOf(h) + extra).distinct())
                else -> Destination("single", listOf(h))
            }
            vm.updateSettings("Destinations saved") { it.copy(homeCountry = h, destination = dest) }
            back()
        }, Modifier.fillMaxWidth(), enabled = home != null)
    }
}
