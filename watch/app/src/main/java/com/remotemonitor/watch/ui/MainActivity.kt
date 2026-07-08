/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.remotemonitor.watch.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.remotemonitor.watch.WatchApplication
import com.remotemonitor.watch.sync.SyncForegroundService
import com.remotemonitor.watch.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as WatchApplication
        // WU-2.19 GREEN: start the IBI sync loop as a foreground service.
        // The service is declared `exported="false"` in AndroidManifest.xml
        // (Android 12+ security default for services with a
        // `foregroundServiceType`), so it MUST be started from within the
        // app — `adb shell am start-foreground-service` fails with
        // "Permission Denial: shell uid 2000 cannot start non-exported
        // service of uid 10000". MainActivity.onCreate is the natural
        // entry point: opening the app starts the sync.
        startForegroundService(Intent(this, SyncForegroundService::class.java))
        setContent {
            MyApplicationTheme {
                WatchApp(app = app)
            }
        }
    }
}

/**
 * Root composable (T-WATCH-40, PR 3 fresh-review follow-up;
 * wear-bed-picker-onboarding D12 / T3.9).
 *
 * Resolves the persisted identity (paired vs. half-paired vs. unpaired)
 * BEFORE creating the [NavHost], but does so asynchronously via a
 * [LaunchedEffect] instead of the previous `runBlocking` on
 * `getPatientNumber()` from `onCreate`. DataStore reads on cold start
 * can take 100ms+; on Wear OS 6 the ANR window is 5s, but blocking the
 * main thread from `onCreate` is still risky and produces a perceptible
 * "freeze" on first launch.
 *
 * D12 routing precedence (delegates to [resolveInitialRepairRoute]):
 *  - `KEY_BED_NUMBER != null`            → `"home"` (paired with bed; flow OK)
 *  - `KEY_PATIENT_ID != null` only       → `"repair"` (legacy operator-typed
 *                                          pair; needs re-pair to populate the
 *                                          bed key)
 *  - nothing persisted                   → `"onboarding"` (no identity at all)
 *
 * Flow:
 *  1. `initialDestination` starts as `null` (blank screen).
 *  2. The `LaunchedEffect(Unit)` reads BOTH `getBedNumber()` and
 *     `getPatientId()` off the main thread (DataStore I/O is main-safe)
 *     and updates `initialDestination` via [resolveInitialRepairRoute].
 *  3. On the first non-null value, the `WatchNavHost` is created with
 *     the resolved `startDestination`. The `key` ensures the NavHost is
 *     only created once per destination value (in case the value ever
 *     changes at runtime).
 */
@Composable
fun WatchApp(app: WatchApplication) {
    var initialDestination by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val bedNumber = app.identityRepository.getBedNumber()
        val patientId = app.identityRepository.getPatientId()
        initialDestination = resolveInitialRepairRoute(bedNumber, patientId)
    }
    val dest = initialDestination
    if (dest != null) {
        WatchNavHost(app = app, startDestination = dest)
    }
    // else: blank screen while we wait for DataStore. Intentionally
    // not a spinner — the read is fast (single-pref DataStore, cached
    // after first read) and a flash of a spinner on first launch
    // would be more jarring than a 50-100ms blank frame.
}

@Composable
private fun WatchNavHost(app: WatchApplication, startDestination: String) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable("onboarding") {
            val viewModel = remember { app.onboardingViewModelFactory() }
            val state by viewModel.state.collectAsStateWithLifecycle()
            // wear-bed-picker-onboarding D33: snapshot fetch fires from
            // LaunchedEffect(Unit) on the screen lifetime (NOT from VM
            // init). One fetch per screen visit.
            LaunchedEffect(Unit) { viewModel.loadSnapshot() }
            OnboardingScreen(
                snapshot = state.snapshot,
                snapshotState = state.snapshotState,
                error = state.error,
                isSubmitting = state.isSubmitting,
                dialog = state.dialog,
                onBedSelected = viewModel::onBedSelected,
                onSnapshotRetry = viewModel::loadSnapshot,
                onDialogAceptar = viewModel::onDialogAccept,
                onDialogCancelar = viewModel::onDialogCancel,
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
        // wear-bed-picker-onboarding D12 / T3.9: paired-but-no-bed watches
        // (legacy operator-typed pair; KEY_PATIENT_ID set, KEY_BED_NUMBER
        // missing) land on the repair screen. The single button sends the
        // operator back to the onboarding carousel so the new pairing
        // write goes through `persistPaired(...)` and atomically populates
        // all three keys (D15 + D24).
        composable("repair") {
            RepairRequiredScreen(
                onTapRePair = {
                    navController.navigate("onboarding") {
                        // Pop the repair entry off the back stack so the
                        // watch operator cannot back-key into the repair
                        // screen mid-pairing.
                        popUpTo("repair") { inclusive = true }
                    }
                },
            )
        }
    }
}
