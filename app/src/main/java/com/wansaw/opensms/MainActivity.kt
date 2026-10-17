package com.wansaw.opensms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.wansaw.opensms.ui.AppViewModel
import com.wansaw.opensms.ui.components.GlassBackground
import com.wansaw.opensms.ui.screens.MainScreen
import com.wansaw.opensms.ui.screens.OnboardingScreen
import com.wansaw.opensms.ui.theme.OpenSmsTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenSmsTheme {
                val snackbar = remember { SnackbarHostState() }
                val setup by vm.setupDone.collectAsState()
                LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
                GlassBackground {
                    // null = still reading preferences: show only the background, no flash of onboarding
                    Crossfade(setup, label = "root") { done ->
                        when (done) {
                            null -> {}
                            false -> OnboardingScreen(vm)
                            true -> MainScreen(vm, snackbar)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.ensureRunning()
        vm.refreshSims()
    }
}
