package com.wansaw.opensms.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.wansaw.opensms.ui.AppViewModel
import com.wansaw.opensms.ui.components.*
import com.wansaw.opensms.ui.theme.*

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home),
    Sims("SIMs", Icons.Rounded.SimCard),
    History("History", Icons.Rounded.History),
    Settings("Settings", Icons.Rounded.Settings),
}

@Composable
fun MainScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var sub by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = sub != null) { sub = null }
    BackHandler(enabled = sub == null && tab != Tab.Home) { tab = Tab.Home }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = sub ?: tab.name,
            transitionSpec = {
                if (sub != null && initialState != targetState && targetState !in Tab.entries.map { it.name })
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (fadeOut())
                else fadeIn() togetherWith fadeOut()
            },
            label = "main",
        ) { key ->
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                when (key) {
                    "Home" -> HomeScreen(vm)
                    "Sims" -> SimsScreen(vm)
                    "History" -> HistoryScreen(vm)
                    "Settings" -> SettingsScreen(vm) { sub = it }
                    "destinations" -> DestinationsScreen(vm) { sub = null }
                    "keys" -> KeysScreen(vm) { sub = null }
                    "callback" -> CallbackScreen(vm) { sub = null }
                    "revsms" -> ReverseSmsScreen(vm) { sub = null }
                    "reliability" -> ReliabilityScreen { sub = null }
                    "about" -> AboutScreen(vm) { sub = null }
                }
            }
        }
        if (sub == null) {
            FloatingBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp).systemBarsPadding())
    }
}

@Composable
private fun FloatingBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(34.dp)
    Row(
        modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp)
            .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(shape)
            .background(if (g.dark) Color(0xFF1B1F33).copy(alpha = 0.78f) else Color.White.copy(alpha = 0.80f))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (g.dark) 0.10f else 0.4f), Color.Transparent)))
            .border(1.dp, Brush.verticalGradient(listOf(g.borderHi, g.borderLo)), shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab.entries.forEach { t ->
            val sel = t == selected
            val a by animateFloatAsState(if (sel) 1f else 0f, label = "tab")
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(28.dp))
                    .background(Blue.copy(alpha = 0.18f * a))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(t) }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, t.label, tint = if (sel) Blue else g.textSecondary, modifier = Modifier.size(24.dp))
                Text(t.label, style = MaterialTheme.typography.labelMedium, color = if (sel) Blue else g.textSecondary)
            }
        }
    }
}

/** Standard scrolling page: title, content, room for the floating bar. */
@Composable
fun Page(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScrollCompat().padding(horizontal = 20.dp),
    ) {
        if (onBack != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onBack).padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Blue)
                Spacer(Modifier.width(4.dp))
                Text("Back", color = Blue, style = MaterialTheme.typography.titleMedium)
            }
        }
        ScreenTitle(title, subtitle)
        Spacer(Modifier.height(10.dp))
        content()
        Spacer(Modifier.height(if (onBack == null) 120.dp else 40.dp))
    }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))
