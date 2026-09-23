package dev.photon.app.capture

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import dev.photon.app.sensors.OrientationSampler
import dev.photon.app.storage.CaptureStore
import dev.photon.app.storage.FrameMeta
import dev.photon.app.storage.Manifest
import dev.photon.app.storage.NormRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

enum class CapturePhase { Aim, Ready, Recording, Saving, Saved, Failed }

data class CaptureUiState(
    val phase: CapturePhase = CapturePhase.Aim,
    /** Rotated analysis frame size, used to map the preview onto frame coordinates. */
    val frameSize: Size? = null,
    val selection: NormRect? = null,
    val targetArcDeg: Double = 60.0,
    val stepDeg: Double = 1.0,
    val framesKept: Int = 0,
    val progressDeg: Double = 0.0,
    val yawSpeedDegPerSec: Double = 0.0,
    /** Pitch relative to when recording started (or current pitch before that). */
    val pitchOffsetDeg: Double = 0.0,
    val lockStatus: String = "",
    val message: String? = null,
    val savedBundle: File? = null,
)

class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val sampler = OrientationSampler(app)
    private val store = CaptureStore(app)
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Recording state, guarded by recordLock. The analyzer thread and stop() both touch it.
    private val recordLock = Any()
    private var recording: Recording? = null

    private class Recording(
        val id: String,
        val workDir: File,
        val gate: FrameGate,
        val startNs: Long,
        val startPitch: Double,
        val selection: NormRect?,
        val stepDeg: Double,
    ) {
        val frames = mutableListOf<FrameMeta>()
        var width = 0
        var height = 0
    }

    init {
        if (!sampler.isAvailable) {
            _state.update { it.copy(phase = CapturePhase.Failed, message = "This device has no rotation sensor.") }
        }
        viewModelScope.launch {
            // UI-rate telemetry; the sensor itself runs much faster.
            while (isActive) {
                val sample = sampler.latest.value
                if (sample != null) {
                    val startPitch = synchronized(recordLock) { recording?.startPitch }
                    _state.update {
                        it.copy(
                            yawSpeedDegPerSec = sampler.yawSpeedDegPerSec(),
                            pitchOffsetDeg = sample.pitchDeg - (startPitch ?: 0.0),
                        )
                    }
                }
                delay(33)
            }
        }
    }

    fun startSensors() = sampler.start()

    fun stopSensors() = sampler.stop()

    suspend fun bindCamera(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        val provider = ProcessCameraProvider.awaitInstance(getApplication())
        cameraProvider = provider

        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build(),
            )
            .build()
            .also { it.surfaceProvider = surfaceProvider }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::analyze) }

        provider.unbindAll()
        camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }

    fun setTargetArc(deg: Double) = _state.update { it.copy(targetArcDeg = deg) }

    fun setStep(deg: Double) = _state.update { it.copy(stepDeg = deg) }

    /**
     * Selects the object at [selection] (normalized frame coordinates) and locks focus, exposure and
     * white balance on it so brightness doesn't shift during the sweep.
     */
    fun select(selection: NormRect, meteringPoint: MeteringPoint) {
        val phase = _state.value.phase
        if (phase != CapturePhase.Aim && phase != CapturePhase.Ready) return
        val cam = camera ?: return
        _state.update { it.copy(selection = selection, phase = CapturePhase.Ready, lockStatus = "Locking...") }

        setAeAwbLock(cam, false)
        val action = FocusMeteringAction.Builder(
            meteringPoint,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
        ).disableAutoCancel().build()

        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            val focused = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
            val locks = setAeAwbLock(cam, true)
            _state.update { it.copy(lockStatus = buildString {
                append(if (focused) "AF" else "AF?")
                append(if (locks.first) " AE" else " AE✗")
                append(if (locks.second) " AWB" else " AWB✗")
            }) }
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    fun clearSelection() {
        if (_state.value.phase != CapturePhase.Ready) return
        camera?.let {
            it.cameraControl.cancelFocusAndMetering()
            setAeAwbLock(it, false)
        }
        _state.update { it.copy(selection = null, phase = CapturePhase.Aim, lockStatus = "") }
    }

    fun startRecording() {
        val s = _state.value
        if (s.phase != CapturePhase.Ready) return
        val id = UUID.randomUUID().toString()
        synchronized(recordLock) {
            recording = Recording(
                id = id,
                workDir = store.newWorkDir(id),
                gate = FrameGate(s.stepDeg, s.targetArcDeg, MAX_FRAMES),
                startNs = SystemClock.elapsedRealtimeNanos(),
                startPitch = sampler.latest.value?.pitchDeg ?: 0.0,
                selection = s.selection,
                stepDeg = s.stepDeg,
            )
        }
        _state.update { it.copy(phase = CapturePhase.Recording, framesKept = 0, progressDeg = 0.0, message = null) }
    }

    fun stopRecording() {
        viewModelScope.launch(Dispatchers.Default) { finishRecording() }
    }

    /** Returns to aiming after a save or failure so another capture can be taken. */
    fun reset() {
        camera?.let {
            it.cameraControl.cancelFocusAndMetering()
            setAeAwbLock(it, false)
        }
        _state.update {
            CaptureUiState(frameSize = it.frameSize, targetArcDeg = it.targetArcDeg, stepDeg = it.stepDeg)
        }
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            val rotation = image.imageInfo.rotationDegrees
            val size = if (rotation % 180 == 0) Size(image.width, image.height) else Size(image.height, image.width)
            if (_state.value.frameSize != size) _state.update { it.copy(frameSize = size) }

            var done = false
            synchronized(recordLock) {
                val rec = recording ?: return
                val ts = frameTimestampNs(image.imageInfo.timestamp)
                val sample = sampler.sampleAt(ts) ?: return
                val elapsedMs = (ts - rec.startNs) / 1_000_000

                when (val d = rec.gate.offer(sample.yawDeg)) {
                    is FrameGate.Decision.Keep -> {
                        val name = CaptureStore.frameName(rec.frames.size)
                        val bitmap = image.toBitmap().rotated(rotation)
                        File(rec.workDir, name).outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                        }
                        rec.width = bitmap.width
                        rec.height = bitmap.height
                        bitmap.recycle()
                        rec.frames += FrameMeta(name, d.progressDeg, sample.pitchDeg, sample.rollDeg, maxOf(0, elapsedMs))
                    }
                    FrameGate.Decision.Skip -> Unit
                }
                _state.update { it.copy(framesKept = rec.frames.size, progressDeg = rec.gate.progressDeg) }
                done = rec.gate.isDone || elapsedMs > MAX_DURATION_MS
            }
            if (done) viewModelScope.launch(Dispatchers.Default) { finishRecording() }
        }
    }

    private fun finishRecording() {
        val rec = synchronized(recordLock) {
            val r = recording ?: return
            recording = null
            r
        }
        if (rec.frames.size < MIN_FRAMES) {
            rec.workDir.deleteRecursively()
            _state.update {
                it.copy(phase = CapturePhase.Ready, message = "Sweep too short. Move the phone around the object.")
            }
            return
        }
        _state.update { it.copy(phase = CapturePhase.Saving) }
        try {
            val manifest = Manifest(
                created = Instant.now().toString(),
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                width = rec.width,
                height = rec.height,
                arcDeg = rec.frames.last().yawDeg,
                direction = rec.gate.direction,
                stepDeg = rec.stepDeg,
                stabilized = false,
                selection = rec.selection,
                frames = rec.frames.toList(),
            )
            val middle = File(rec.workDir, rec.frames[rec.frames.size / 2].file)
            val thumb = BitmapFactory.decodeFile(middle.path, BitmapFactory.Options().apply { inSampleSize = 4 })
            val bundle = store.finalize(rec.id, rec.workDir, manifest, thumb)
            thumb.recycle()
            _state.update { it.copy(phase = CapturePhase.Saved, savedBundle = bundle) }
        } catch (e: Exception) {
            Log.e(TAG, "Saving capture failed", e)
            rec.workDir.deleteRecursively()
            _state.update { it.copy(phase = CapturePhase.Failed, message = "Saving failed: ${e.message}") }
        }
    }

    /**
     * Converts a camera frame timestamp into the sensor clock (elapsedRealtimeNanos). Most devices
     * already use that base, but some report uptime, which excludes time spent in deep sleep.
     */
    private fun frameTimestampNs(cameraTs: Long): Long {
        val realtime = SystemClock.elapsedRealtimeNanos()
        val uptime = SystemClock.uptimeMillis() * 1_000_000
        return if (abs(cameraTs - realtime) <= abs(cameraTs - uptime)) cameraTs else cameraTs + (realtime - uptime)
    }

    /** Applies AE/AWB lock where the camera supports it. Returns (aeLocked, awbLocked). */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun setAeAwbLock(cam: Camera, lock: Boolean): Pair<Boolean, Boolean> {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val aeAvailable = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        val awbAvailable = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        val options = CaptureRequestOptions.Builder().apply {
            if (aeAvailable) setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lock)
            if (awbAvailable) setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lock)
        }.build()
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options)
        return (lock && aeAvailable) to (lock && awbAvailable)
    }

    override fun onCleared() {
        sampler.stop()
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        synchronized(recordLock) {
            recording?.workDir?.deleteRecursively()
            recording = null
        }
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, m, true).also { if (it !== this) recycle() }
    }

    private companion object {
        const val TAG = "Capture"
        const val MAX_FRAMES = 180
        const val MIN_FRAMES = 5
        const val MAX_DURATION_MS = 30_000L
        const val JPEG_QUALITY = 90
    }
}
