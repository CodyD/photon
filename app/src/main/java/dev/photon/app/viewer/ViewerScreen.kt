package dev.photon.app.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.photon.app.sensors.OrientationSampler
import dev.photon.app.storage.CaptureStore
import dev.photon.app.storage.Manifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val VIEWER_MAX_EDGE = 1080

private sealed interface LoadState {
    data object Loading : LoadState
    data class Ready(val manifest: Manifest, val frames: List<ImageBitmap>) : LoadState
    data class Failed(val message: String) : LoadState
}

@Composable
fun ViewerScreen(bundle: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val load by produceState<LoadState>(LoadState.Loading, bundle) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val store = CaptureStore(context)
                val manifest = store.readManifest(bundle)
                val frames = store.readFrames(bundle, manifest, VIEWER_MAX_EDGE).map { it.asImageBitmap() }
                LoadState.Ready(manifest, frames)
            }.getOrElse { LoadState.Failed(it.message ?: "Could not open capture") }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = load) {
            LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is LoadState.Failed -> Text(s.message, color = Color.White, modifier = Modifier.align(Alignment.Center))
            is LoadState.Ready -> Player(s.manifest, s.frames)
        }
        TextButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp)) { Text("Back") }
    }
}

@Composable
private fun Player(manifest: Manifest, frames: List<ImageBitmap>) {
    val context = LocalContext.current
    val sampler = remember { OrientationSampler(context) }
    val mapper = remember(manifest) { YawFrameMapper(manifest.frames.map { it.yawDeg }, manifest.direction) }

    var index by remember { mutableIntStateOf(frames.size / 2) }
    var angle by remember { mutableDoubleStateOf(mapper.arcDeg / 2) }
    var sensitivity by remember { mutableDoubleStateOf(mapper.sensitivity) }
    var inverted by remember { mutableStateOf(false) }

    DisposableEffect(sampler) {
        sampler.start()
        onDispose { sampler.stop() }
    }
    LaunchedEffect(mapper) {
        sampler.latest.collect { sample ->
            if (sample == null) return@collect
            angle = mapper.angleFor(sample.yawDeg)
            index = mapper.frameIndexFor(angle)
        }
    }

    fun applySettings(sens: Double, inv: Boolean) {
        sensitivity = sens
        inverted = inv
        mapper.sensitivity = sens
        mapper.inverted = inv
        mapper.recenter()
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(mapper) {
                detectHorizontalDragGestures { _, dx ->
                    mapper.nudge(dx / size.width * mapper.arcDeg)
                    sampler.latest.value?.let {
                        angle = mapper.angleFor(it.yawDeg)
                        index = mapper.frameIndexFor(angle)
                    }
                }
            },
    ) {
        Image(
            bitmap = frames[index.coerceIn(frames.indices)],
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .navigationBarsPadding()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "%.1f° / %.1f°   frame %d/%d".format(angle, mapper.arcDeg, index + 1, frames.size),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(1.0, 1.5, 2.5).forEach { v ->
                    FilterChip(
                        selected = sensitivity == v,
                        onClick = { applySettings(v, inverted) },
                        label = { Text("${v}x") },
                    )
                }
                FilterChip(selected = inverted, onClick = { applySettings(sensitivity, !inverted) }, label = { Text("Invert") })
                TextButton(onClick = { mapper.recenter() }) { Text("Recenter") }
            }
        }
    }
}
