package com.alec.hearthtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import com.alec.hearthtv.HearthGraph

object Routes {
    const val REMOTE = "remote"
    const val SETUP = "setup"

    /** The wizard opened at one step, for adding a device to an install that is already set up. */
    fun setupAt(step: SetupViewModel.Step) = "setup/${step.name}"
    const val DIAGNOSTICS = "diagnostics"
}

@Composable
fun HearthTvNavHost() {
    val nav = rememberNavController()
    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val s = HearthGraph.settings.settings.first()
        start = if (s.isConfigured) Routes.REMOTE else Routes.SETUP
    }
    val startRoute = start ?: return
    NavHost(navController = nav, startDestination = startRoute) {
        composable(Routes.REMOTE) {
            val vm: RemoteViewModel = viewModel()
            RemoteScreen(
                vm = vm,
                onOpenSetup = { nav.navigate(Routes.SETUP) },
                onOpenDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
            )
        }
        composable(Routes.SETUP) {
            val vm: SetupViewModel = viewModel()
            SetupScreen(
                vm = vm,
                onDone = {
                    nav.navigate(Routes.REMOTE) { popUpTo(Routes.SETUP) { inclusive = true } }
                },
            )
        }
        composable("setup/{step}") { entry ->
            val vm: SetupViewModel = viewModel()
            val step = runCatching { SetupViewModel.Step.valueOf(entry.arguments?.getString("step").orEmpty()) }
                .getOrDefault(SetupViewModel.Step.FIND_TV)
            SetupScreen(
                vm = vm,
                onDone = { nav.navigate(Routes.REMOTE) { popUpTo(Routes.REMOTE) { inclusive = true } } },
                startStep = step,
            )
        }
        composable(Routes.DIAGNOSTICS) {
            val vm: DiagnosticsViewModel = viewModel()
            DiagnosticsScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onRepair = { nav.navigate(Routes.SETUP) },
                onOpenStep = { step -> nav.navigate(Routes.setupAt(step)) },
            )
        }
    }
}
