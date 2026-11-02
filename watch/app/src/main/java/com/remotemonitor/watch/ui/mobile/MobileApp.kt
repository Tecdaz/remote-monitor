package com.remotemonitor.watch.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotemonitor.watch.R
import com.remotemonitor.watch.WatchApplication
import com.remotemonitor.watch.sensor.SensorHealth
import com.remotemonitor.watch.ui.HomeVitals
import com.remotemonitor.watch.ui.OnboardingEvent
import com.remotemonitor.watch.ui.resolveInitialRepairRoute
import com.remotemonitor.watch.ui.startSyncForegroundService
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Root surface for Android phones and tablets. The Wear host keeps the
 * watch-optimized [com.remotemonitor.watch.ui.WatchApp]; this surface
 * uses standard Material 3 components and an adaptive width.
 */
@Composable
fun MobileApp(app: WatchApplication) {
    var route by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val bedNumber = app.identityRepository.getBedNumber()
        val patientId = app.identityRepository.getPatientId()
        route = resolveInitialRepairRoute(bedNumber, patientId)
    }

    DisposableEffect(Unit) {
        startSyncForegroundService(context)
        onDispose { /* service owns its own lifetime */ }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val current = route) {
            null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            "home" -> {
                val viewModel = remember { app.homeViewModelFactory() }
                val state by viewModel.state.collectAsStateWithLifecycle()
                MobileHomeScreen(
                    state = state,
                    onRePair = { route = "onboarding" },
                )
            }
            "repair" -> MobileRepairScreen(
                onTapRePair = { route = "onboarding" },
            )
            else -> {
                val viewModel = remember { app.onboardingViewModelFactory() }
                val state by viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { viewModel.loadSnapshot() }
                LaunchedEffect(viewModel) {
                    viewModel.events.collect { event ->
                        if (event == OnboardingEvent.NavigateToHome) route = "home"
                    }
                }
                MobileOnboardingScreen(
                    snapshot = state.snapshot,
                    snapshotState = state.snapshotState,
                    error = state.error,
                    isSubmitting = state.isSubmitting,
                    dialog = state.dialog,
                    onBedSelected = viewModel::onBedSelected,
                    onSnapshotRetry = viewModel::loadSnapshot,
                    onDialogAccept = viewModel::onDialogAccept,
                    onDialogCancel = viewModel::onDialogCancel,
                )
            }
        }
    }
}

@Composable
private fun MobileHomeScreen(
    state: HomeVitals,
    onRePair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val wide = maxWidth >= 840.dp
        Column(
            modifier = Modifier
                .widthIn(max = 1080.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.band_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    VitalsCard(
                        state = state,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("home-vitals"),
                    )
                    SessionCard(
                        state = state,
                        onRePair = onRePair,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                VitalsCard(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home-vitals"),
                )
                Spacer(Modifier.height(16.dp))
                SessionCard(
                    state = state,
                    onRePair = onRePair,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun VitalsCard(
    state: HomeVitals,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.home_hr_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.hrBpm
                    ?.takeUnless { state.health == SensorHealth.Failed }
                    ?.let { stringResource(R.string.home_hr_value_format, it) }
                    ?: stringResource(R.string.home_hr_placeholder),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.testTag("home-hr"),
            )
            state.lastUpdate?.let { instant ->
                val timeText = LAST_UPDATE_FORMATTER.format(
                    instant.atZone(ZoneId.systemDefault()),
                )
                Text(
                    text = stringResource(R.string.home_last_update_label, timeText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("home-last-update"),
                )
            }
            Spacer(Modifier.height(8.dp))
            SensorHealthText(health = state.health)
        }
    }
}

@Composable
private fun SessionCard(
    state: HomeVitals,
    onRePair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.home_status_label,
                    state.bedNumber?.toIntOrNull() ?: 0,
                    state.pendingCount,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.testTag("home-status"),
            )
            Text(
                text = stringResource(R.string.home_sync_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SensorHealthText(health = state.health)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onRePair,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_repair))
            }
        }
    }
}

@Composable
private fun SensorHealthText(health: SensorHealth) {
    val (text, color) = when (health) {
        SensorHealth.Healthy -> stringResource(R.string.sensor_connected) to
            MaterialTheme.colorScheme.secondary
        SensorHealth.OffWrist -> stringResource(R.string.sensor_off_wrist) to
            MaterialTheme.colorScheme.tertiary
        SensorHealth.Failed -> stringResource(R.string.sensor_failed) to
            MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.testTag("home-sensor-health"),
    )
}

@Composable
private fun MobileRepairScreen(
    onTapRePair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier
                .widthIn(max = 560.dp)
                .testTag("repair-required-screen"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.repair_required_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.repair_required_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onTapRePair) {
                    Text(stringResource(R.string.action_repair))
                }
            }
        }
    }
}

private val LAST_UPDATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
