package com.medicabinet.app.features.scan.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.medicabinet.core.SystemClock
import com.medicabinet.core.scan.GS1Parser

internal const val DEBUG_ROUTE = "settings/debug"

/** Debug builds only: the Settings row opening the payload capture screen. */
@Composable
internal fun DebugSettingsRow(onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text("Debug: payload shapes") },
        leadingContent = { androidx.compose.material3.Icon(Icons.Outlined.BugReport, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onOpen)
    )
}

internal fun NavGraphBuilder.debugRoutes() {
    composable(DEBUG_ROUTE) { PayloadCaptureScreen(CameraXScanner(SystemClock)) }
}

/** Newest first, consecutive duplicates collapsed, at most [limit] entries. */
internal fun List<String>.withShape(shape: String, limit: Int = 50): List<String> =
    if (firstOrNull() == shape) this else (listOf(shape) + this).take(limit)

/**
 * Debug builds only: lists the *shape* of each payload the camera sees (prefix, AIs, field lengths, separators),
 * never a lot or serial value, so real boxes can be turned into anonymised fixture cases.
 */
@Composable
internal fun PayloadCaptureScreen(scanner: CodeScanner, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val shapes = remember { mutableStateListOf<String>() }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(scanner, granted) {
        if (!granted) return@LaunchedEffect
        scanner.snapshots.collect { snapshot ->
            snapshot.payloads.forEach { payload ->
                val updated = shapes.toList().withShape(GS1Parser.shape(payload))
                shapes.clear()
                shapes.addAll(updated)
            }
        }
    }
    DisposableEffect(scanner) { onDispose { (scanner as? java.io.Closeable)?.close() } }
    Column(modifier.fillMaxSize()) {
        if (!granted) {
            Button(onClick = { request.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(16.dp)) {
                Text("Allow camera")
            }
        } else {
            scanner.Preview(Modifier.fillMaxWidth().height(260.dp)) { failed = true }
            if (failed) Text("Camera failed to start", modifier = Modifier.padding(16.dp))
        }
        LazyColumn { items(shapes) { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) } }
    }
}
