package com.medicabinet.app.features.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.medicabinet.app.R
import com.medicabinet.app.features.scan.camera.AnalysisSettings
import java.io.Closeable
import kotlinx.coroutines.launch

private const val HINT_ALPHA = 0.85f
private const val BAR_ALPHA = 0.9f
private const val PILL_PERCENT = 50
private val CARD_CORNER = 24.dp

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The Scan tab: owns the camera permission, the scanner and the sheets around the view model. */
@Composable
fun ScanScreen(model: ScanViewModel, services: ScanServices, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showsSessionList by remember { mutableStateOf(false) }
    var showsManualEntry by remember { mutableStateOf(false) }

    fun refreshAccess() {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val rationale = context.findActivity()?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
        } ?: false
        model.onCameraAccess(CameraAccess.resolve(granted, model.cameraRequestedBefore, rationale))
    }

    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.permissionRequested()
        refreshAccess()
    }
    // The tab was opened: Remove is never remembered (R1).
    LaunchedEffect(Unit) { model.resetMode() }
    // Also after the whole cabinet was replaced (an import or a restore): the session ends and the locations reload.
    val revision by model.revision.collectAsState()
    LaunchedEffect(revision) { model.load() }
    // Also when the user comes back from the system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshAccess() }
    FeedbackEffect(model)

    Box(modifier.fillMaxSize()) {
        ScanBody(
            model,
            state,
            services,
            onAllow = { requestCamera.launch(Manifest.permission.CAMERA) },
            onSettings = { openAppSettings(context) },
            onSessionList = { showsSessionList = true },
            onManual = { showsManualEntry = true }
        )
    }

    ScanSheetHost(
        model,
        state,
        showsSessionList,
        showsManualEntry,
        onCloseSessionList = { showsSessionList = false },
        onCloseManual = { showsManualEntry = false }
    )
}

@Composable
private fun ScanBody(
    model: ScanViewModel,
    state: ScanUiState,
    services: ScanServices,
    onAllow: () -> Unit,
    onSettings: () -> Unit,
    onSessionList: () -> Unit,
    onManual: () -> Unit
) {
    when (state.camera) {
        CameraState.CHECKING -> Unit
        CameraState.NEEDS_PERMISSION -> ScanStatus(ScanStatusKind.NEEDS_PERMISSION, onAllow, {}, {}, onManual)
        CameraState.DENIED -> ScanStatus(ScanStatusKind.DENIED, {}, onSettings, {}, onManual)
        CameraState.UNSUPPORTED -> ScanStatus(ScanStatusKind.UNSUPPORTED, {}, {}, {}, onManual)
        CameraState.FAILED -> ScanStatus(ScanStatusKind.FAILED, {}, {}, model::retry, onManual)
        CameraState.STARTING, CameraState.SCANNING -> ScannerContent(model, state, services, onSessionList, onManual)
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** Performs the haptics (and the optional tone) the view model asks for. */
@Composable
private fun FeedbackEffect(model: ScanViewModel) {
    val context = LocalContext.current
    val view = LocalView.current
    val player = remember { ScanFeedbackPlayer(context) }
    DisposableEffect(player) { onDispose { player.close() } }
    LaunchedEffect(model) { model.feedback.collect { player.play(view, it) } }
}

@Composable
private fun ScannerContent(
    model: ScanViewModel,
    state: ScanUiState,
    services: ScanServices,
    onSessionList: () -> Unit,
    onManual: () -> Unit
) {
    val scanner = remember(state.attempt) { services.makeScanner() }
    DisposableEffect(scanner) { onDispose { (scanner as? Closeable)?.close() } }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Frames are only consumed while the app is in the foreground; the camera itself is bound to the lifecycle.
    LaunchedEffect(scanner, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            scanner.snapshots.collect { model.handle(it) }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        scanner.Preview(Modifier.fillMaxSize(), onFailure = {
            model.onCameraFailed()
        }, onStarted = model::onPreviewStarted)
        Viewfinder(Modifier.align(Alignment.Center), removing = state.mode == ScanMode.REMOVE)
        if (state.camera == CameraState.STARTING) {
            Column(
                Modifier.align(Alignment.Center).testTag("scan.camera.starting"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.scan_camera_starting), color = Color.White)
            }
        }
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModePicker(state.mode, onSelect = model::setMode)
                if (state.mode == ScanMode.REMOVE) RemoveBar(model, state) else TopBar(model, state, onManual)
            }
            BottomArea(model, state, onSessionList)
        }
    }
}

@Composable
private fun Viewfinder(modifier: Modifier, removing: Boolean) {
    // The decoded square (centre 70 % of the short side) is what this frame shows.
    val color = if (removing) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.8f)
    Box(
        modifier.fillMaxWidth(
            AnalysisSettings.CROP_FRACTION
        ).aspectRatio(1f).border(3.dp, color, RoundedCornerShape(24.dp))
    )
}

@Composable
private fun BottomBar(state: ScanUiState, onSessionList: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val open = stringResource(R.string.scan_session_open)
        val count = if (state.mode == ScanMode.REMOVE) {
            stringResource(R.string.scan_removal_count, state.removalSession.count)
        } else {
            stringResource(R.string.scan_count, state.session.count)
        }
        OutlinedButton(
            onClick = onSessionList,
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = open
                stateDescription = count
            }.testTag("scan.count")
        ) { Text(count) }
        Box(Modifier.weight(1f))
        Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp).testTag("scan.done")) {
            Text(stringResource(R.string.scan_done))
        }
    }
}

enum class ScanStatusKind { NEEDS_PERMISSION, DENIED, UNSUPPORTED, FAILED }

/** The states of the Scan tab where the camera is not showing. Every one offers manual entry. */
@Composable
fun ScanStatus(
    kind: ScanStatusKind,
    onAllow: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onManual: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (icon, title, message) = when (kind) {
        ScanStatusKind.NEEDS_PERMISSION -> Triple(
            Icons.Outlined.CameraAlt,
            R.string.scan_permission_title,
            R.string.scan_permission_message
        )
        ScanStatusKind.DENIED -> Triple(
            Icons.Outlined.NoPhotography,
            R.string.scan_permission_denied_title,
            R.string.scan_permission_denied_message
        )
        ScanStatusKind.UNSUPPORTED -> Triple(
            Icons.Outlined.QrCodeScanner,
            R.string.scan_unsupported_title,
            R.string.scan_unsupported_message
        )
        ScanStatusKind.FAILED -> Triple(
            Icons.Outlined.ErrorOutline,
            R.string.scan_camera_failed_title,
            R.string.scan_camera_failed_message
        )
    }
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
        when (kind) {
            ScanStatusKind.NEEDS_PERMISSION -> StatusButton(
                R.string.scan_permission_allow,
                "scan.permission.allow",
                onAllow
            )
            ScanStatusKind.DENIED -> StatusButton(
                R.string.scan_permission_settings,
                "scan.permission.settings",
                onSettings
            )
            ScanStatusKind.FAILED -> StatusButton(R.string.scan_camera_retry, "scan.camera.retry", onRetry)
            ScanStatusKind.UNSUPPORTED -> Unit
        }
        OutlinedButton(onClick = onManual, modifier = Modifier.heightIn(min = 48.dp).testTag("scan.manual.open")) {
            Text(stringResource(R.string.scan_manual_open))
        }
    }
}

@Composable
private fun StatusButton(label: Int, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) { Text(stringResource(label)) }
}

@Composable
private fun BottomArea(model: ScanViewModel, state: ScanUiState, onSessionList: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            Modifier.align(Alignment.CenterHorizontally),
            shape = RoundedCornerShape(PILL_PERCENT),
            color = MaterialTheme.colorScheme.surface.copy(alpha = HINT_ALPHA)
        ) {
            Text(
                stringResource(R.string.scan_camera_hint),
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
        val scope = rememberCoroutineScope()
        state.another?.let { another ->
            AnotherCard(another, onAdd = { scope.launch { model.confirmAnother() } })
        }
        state.notice?.let { notice ->
            NoticeCard(
                notice,
                NoticeActions(
                    onUndoAdd = { scope.launch { model.delete(it) } },
                    onPutBack = { scope.launch { model.putBack(it) } },
                    onUndoDelete = { scope.launch { model.undoDelete(it) } },
                    onUndoRemoval = { scope.launch { model.undoRemoval(it) } },
                    onDismiss = model::dismissNotice
                )
            )
        }
        BottomBar(state, onSessionList, onDone = model::finish)
    }
}
