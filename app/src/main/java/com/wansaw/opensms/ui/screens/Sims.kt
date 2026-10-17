package com.wansaw.opensms.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wansaw.opensms.data.SimCfg
import com.wansaw.opensms.data.SimReport
import com.wansaw.opensms.ui.AppViewModel
import com.wansaw.opensms.ui.components.*
import com.wansaw.opensms.ui.theme.*

@Composable
fun SimsScreen(vm: AppViewModel) {
    val sims by vm.sims.collectAsState()
    val env by vm.settingsEnv.collectAsState()
    val s = env.settings
    LaunchedEffect(Unit) { vm.refreshSims() }

    Page("SIMs", "Choose which SIMs may send and how messages are shared between them.") {
        sims.forEach { sim ->
            val cfg = s.sims.forSlot(sim.slot)
            SimCard(sim, cfg,
                onEnabled = { on ->
                    vm.updateSettings { st ->
                        val n = st.sims
                        st.copy(
                            sims = if (sim.slot == 1) n.copy(sim1 = n.sim1.copy(enabled = on)) else n.copy(sim2 = n.sim2.copy(enabled = on)),
                            preferredSim = if (!on && st.preferredSim == "sim_${sim.slot}") null else st.preferredSim,
                            simStrategy = if (!on && st.preferredSim == "sim_${sim.slot}" && st.simStrategy == "preferred") "round_robin" else st.simStrategy,
                        )
                    }
                },
                onLimit = { lim ->
                    vm.updateSettings { st ->
                        val n = st.sims
                        st.copy(sims = if (sim.slot == 1) n.copy(sim1 = n.sim1.copy(dailyLimit = lim)) else n.copy(sim2 = n.sim2.copy(dailyLimit = lim)))
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        SectionLabel("When both SIMs are on")
        GlassCard(Modifier.fillMaxWidth()) {
            Segmented(
                listOf("round_robin" to "Alternate", "least_used" to "Least used", "preferred" to "Preferred"),
                s.simStrategy,
                { v ->
                    vm.updateSettings { st ->
                        val pref = st.preferredSim ?: listOf(1, 2).firstOrNull { st.sims.forSlot(it).enabled }?.let { "sim_$it" }
                        st.copy(simStrategy = v, preferredSim = if (v == "preferred") pref else st.preferredSim)
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                when (s.simStrategy) {
                    "preferred" -> "Always use the preferred SIM; the other one only when it can't be used."
                    "least_used" -> "Send from the SIM that sent fewer messages today."
                    else -> "Take turns between SIM 1 and SIM 2."
                },
                style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary,
            )
            if (s.simStrategy == "preferred") {
                Spacer(Modifier.height(12.dp))
                Segmented(
                    listOf("sim_1" to "SIM 1", "sim_2" to "SIM 2"), s.preferredSim ?: "sim_1",
                    { v -> vm.updateSettings { it.copy(preferredSim = v) } },
                    enabled = { v -> s.sims.forSlot(v.removePrefix("sim_").toInt()).enabled },
                )
            }
            GlassDivider()
            SwitchRow("Retry on the other SIM", "If a send fails for a network reason, try the other SIM once. Never after an unclear result.", s.retryAlternativeSim, { on -> vm.updateSettings { it.copy(retryAlternativeSim = on) } })
        }
    }
}

@Composable
private fun SimCard(sim: SimReport, cfg: SimCfg, onEnabled: (Boolean) -> Unit, onLimit: (Int?) -> Unit) {
    val g = LocalGlass.current
    GlassCard(Modifier.fillMaxWidth(), tint = if (sim.present && cfg.enabled) Blue else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.SimCard, if (sim.present) Blue else g.textSecondary, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("SIM ${sim.slot}", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (sim.present) listOfNotNull(sim.displayName ?: sim.carrierName, sim.countryIso).joinToString(" · ").ifBlank { "Active" } else "No SIM inserted",
                    style = MaterialTheme.typography.bodyMedium, color = g.textSecondary,
                )
            }
            GlassSwitch(cfg.enabled && sim.present, onEnabled, enabled = sim.present)
        }
        if (sim.present && cfg.enabled) {
            GlassDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Daily limit", style = MaterialTheme.typography.titleMedium)
                    Text(cfg.dailyLimit?.let { "$it segments / day" } ?: "No limit", style = MaterialTheme.typography.bodyMedium, color = g.textSecondary)
                }
                Stepper(cfg.dailyLimit, onLimit)
            }
        }
    }
}

/** − 50 + control for an optional integer limit (null = unlimited). */
@Composable
fun Stepper(value: Int?, onChange: (Int?) -> Unit, step: Int = 50, min: Int = 1, max: Int = 100_000) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = { onChange(if (value == null) null else (value - step).takeIf { it >= min }) }, enabled = value != null) {
            Icon(Icons.Rounded.Remove, "Less")
        }
        IconButton(onClick = { onChange(((value ?: 0) + step).coerceAtMost(max)) }) { Icon(Icons.Rounded.Add, "More", tint = Blue) }
    }
}
