/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.remotemonitor.watch.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.remotemonitor.watch.WatchApplication
import com.remotemonitor.watch.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as WatchApplication
        setContent {
            MyApplicationTheme {
                WatchApp(app = app)
            }
        }
    }
}

@Composable
fun WatchApp(app: WatchApplication) {
    val navController = rememberNavController()
    val initialDestination = remember {
        // T-WATCH-40: decide the start route from the persisted
        // identity. The read is synchronous because we only inspect
        // the cached DataStore value; the result is sealed in the
        // Compose graph as the initial destination.
        val initial = if (isPaired(app)) "home" else "onboarding"
        initial
    }
    NavHost(
        navController = navController,
        startDestination = initialDestination,
    ) {
        composable("onboarding") {
            val viewModel = remember { app.onboardingViewModelFactory() }
            val state by viewModel.state.collectAsStateWithLifecycle()
            OnboardingScreen(
                patientNumber = state.patientNumber,
                error = state.error,
                isSubmitting = state.isSubmitting,
                onValueChange = viewModel::onPatientNumberChange,
                onSubmit = viewModel::onSubmit,
            )
            LaunchedEffect(Unit) {
                viewModel.events.collect { event ->
                    when (event) {
                        OnboardingEvent.NavigateToHome -> navController.navigate("home") {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    }
                }
            }
        }
        composable("home") {
            val viewModel = remember { app.homeViewModelFactory() }
            val state by viewModel.state.collectAsStateWithLifecycle()
            HomeScreen(state = state)
        }
    }
}

/**
 * Best-effort check for "is this watch already paired?". Reads the
 * DataStore synchronously via a blocking runBlocking on the
 * application scope. The result is read once at activity creation,
 * not on every recomposition.
 */
private fun isPaired(app: WatchApplication): Boolean = kotlinx.coroutines.runBlocking {
    app.identityRepository.getPatientNumber() != null
}
