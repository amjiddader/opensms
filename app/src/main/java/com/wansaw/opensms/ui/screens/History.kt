package com.wansaw.opensms.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wansaw.opensms.data.JobState
import com.wansaw.opensms.ui.AppViewModel
import com.wansaw.opensms.ui.components.*
import com.wansaw.opensms.ui.theme.*

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val all by vm.history.collectAsState()
    val env by vm.settingsEnv.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    val list = all.filter {
        when (filter) {
            "sent" -> it.state == JobState.SENT || it.state == JobState.DELIVERED
            "failed" -> it.state in setOf(JobState.FAILED, JobState.EXPIRED, JobState.UNDELIVERED, JobState.UNKNOWN)
            else -> true
        }
    }
    Page("History", "Kept for ${env.settings.historyRetentionDays} days. Message text is never stored after sending.") {
        Segmented(listOf("all" to "All", "sent" to "Sent", "failed" to "Problems"), filter, { filter = it })
        Spacer(Modifier.height(14.dp))
        if (list.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconBadge(Icons.Rounded.Inbox, Indigo, 56.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                list.forEachIndexed { i, j ->
                    if (i > 0) GlassDivider()
                    JobRow(j)
                }
            }
        }
    }
}
