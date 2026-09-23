package dev.photon.app.capture

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.photon.app.storage.NormRect
import java.io.File
import kotlin.math.abs
import kotlin.math.min

private const val SPEED_WARN_DEG_PER_SEC = 30.0
private const val PITCH_WARN_DEG = 10.0

@Composable
fun CaptureScreen(onBack: () -> Unit, onSaved: (File) -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Photon needs the camera to capture.", color = Color.White)
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant camera access") }
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }

    CaptureContent(onBack, onSaved)
}

@Composable
private fun CaptureContent(onBack: () -> Unit, onSaved: (File) -> Unit, vm: CaptureViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by vm.state.collectAsStateWithLifecycle()

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var dragRect by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(Unit) {
        vm.reset()
        vm.bindCamera(lifecycleOwner, previewView.surfaceProvider)
    }
    DisposableEffect(Unit) {
        vm.startSensors()
        onDispose { vm.stopSensors() }
    }
    LaunchedEffect(state.phase, state.savedBundle) {
        val bundle = state.savedBundle
        if (state.phase == CapturePhase.Saved && bundle != null) onSaved(bundle)
    }

    val imageRect = state.frameSize?.let { fitRect(it, viewSize) }
    val canSelect = state.phase == CapturePhase.Aim || state.phase == CapturePhase.Ready

    fun selectViewRect(r: Rect) {
        val img = imageRect ?: return
        val clipped = r.intersect(img)
        if (clipped.width < 8f || clipped.height < 8f) return
        val sel = NormRect(
            x = (clipped.left - img.left) / img.width,
            y = (clipped.top - img.top) / img.height,
            w = clipped.width / img.width,
            h = clipped.height / img.height,
        )
        val c = clipped.center
        vm.select(sel, previewView.meteringPointFactory.createPoint(c.x, c.y))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it },
        )

        // Selection overlay: tap for a default box, drag to draw one.
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(canSelect, imageRect) {
                    if (!canSelect) return@pointerInput
                    detectTapGestures { p ->
                        val img = imageRect ?: return@detectTapGestures
                        if (!img.contains(p)) return@detectTapGestures
                        val half = min(img.width, img.height) * 0.15f
                        selectViewRect(Rect(p.x - half, p.y - half, p.x + half, p.y + half))
                    }
                }
                .pointerInput(canSelect, imageRect) {
                    if (!canSelect) return@pointerInput
                    var start = Offset.Zero
                    detectDragGestures(
                        onDragStart = { start = it; dragRect = Rect(it, it) },
                        onDrag = { change, _ -> dragRect = rectOf(start, change.position) },
                        onDragEnd = { dragRect?.let(::selectViewRect); dragRect = null },
                        onDragCancel = { dragRect = null },
                    )
                },
        ) {
            val img = imageRect
            val sel = state.selection
            if (img != null && sel != null) {
                drawRect(
                    color = if (state.phase == CapturePhase.Recording) Color(0xFF4CAF50) else Color.White,
                    topLeft = Offset(img.left + sel.x * img.width, img.top + sel.y * img.height),
                    size = androidx.compose.ui.geometry.Size(sel.w * img.width, sel.h * img.height),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
            dragRect?.let {
                drawRect(Color.Yellow, it.topLeft, it.size, style = Stroke(width = 2.dp.toPx()))
            }
        }

        TopBar(state, onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
        BottomPanel(state, vm, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun TopBar(state: CaptureUiState, onBack: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0x88000000))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onBack, enabled = state.phase != CapturePhase.Recording) { Text("Back") }
        Text(state.lockStatus, color = Color.White, style = MaterialTheme.typography.labelMedium)
        Text(
            "pitch %+.0f°".format(state.pitchOffsetDeg),
            color = if (abs(state.pitchOffsetDeg) > PITCH_WARN_DEG && state.phase == CapturePhase.Recording) {
                Color(0xFFFF7043)
            } else {
                Color.White
            },
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

@Composable
private fun BottomPanel(state: CaptureUiState, vm: CaptureViewModel, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xAA000000))
            .navigationBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val warning = when {
            state.phase != CapturePhase.Recording -> null
            abs(state.yawSpeedDegPerSec) > SPEED_WARN_DEG_PER_SEC -> "Slow down"
            abs(state.pitchOffsetDeg) > PITCH_WARN_DEG -> "Keep the phone level"
            else -> null
        }
        val status = warning ?: state.message ?: when (state.phase) {
            CapturePhase.Aim -> "Tap or drag around the object"
            CapturePhase.Ready -> "Start, then move around the object"
            CapturePhase.Recording -> "Keep the object in the box"
            CapturePhase.Saving -> "Saving..."
            CapturePhase.Saved -> "Saved"
            CapturePhase.Failed -> "Something went wrong"
        }
        Text(
            status,
            color = if (warning != null) Color(0xFFFF7043) else Color.White,
            style = MaterialTheme.typography.titleMedium,
        )

        if (state.phase == CapturePhase.Recording || state.phase == CapturePhase.Saving) {
            LinearProgressIndicator(
                progress = { (state.progressDeg / state.targetArcDeg).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(8.dp),
            )
            Text(
                "%.0f° / %.0f°   %d frames".format(state.progressDeg, state.targetArcDeg, state.framesKept),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (state.phase == CapturePhase.Aim || state.phase == CapturePhase.Ready) {
            ChipRow("Arc", listOf(30.0, 60.0, 90.0), state.targetArcDeg, vm::setTargetArc) { "%.0f°".format(it) }
            ChipRow("Step", listOf(0.5, 1.0, 2.0), state.stepDeg, vm::setStep) { "%.1f°".format(it) }
        }

        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.phase) {
                CapturePhase.Aim -> Unit
                CapturePhase.Ready -> {
                    TextButton(onClick = vm::clearSelection) { Text("Clear") }
                    Button(onClick = vm::startRecording) { Text("Start sweep") }
                }
                CapturePhase.Recording -> Button(
                    onClick = vm::stopRecording,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                ) { Text("Stop") }
                CapturePhase.Failed -> Button(onClick = vm::reset) { Text("Try again") }
                CapturePhase.Saving, CapturePhase.Saved -> Unit
            }
        }
    }
}

@Composable
private fun ChipRow(
    label: String,
    options: List<Double>,
    selected: Double,
    onSelect: (Double) -> Unit,
    format: (Double) -> String,
) {
    Row(
        Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
        options.forEach { v ->
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(format(v)) })
        }
    }
}

/** Where a frame of [frame] size lands inside a view of [view] size with FIT_CENTER scaling. */
private fun fitRect(frame: Size, view: IntSize): Rect? {
    if (view.width == 0 || view.height == 0) return null
    val scale = min(view.width.toFloat() / frame.width, view.height.toFloat() / frame.height)
    val w = frame.width * scale
    val h = frame.height * scale
    val left = (view.width - w) / 2f
    val top = (view.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

private fun rectOf(a: Offset, b: Offset) =
    Rect(min(a.x, b.x), min(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
