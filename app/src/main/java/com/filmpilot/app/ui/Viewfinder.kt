package com.filmpilot.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.filmpilot.app.camera.LightMeter
import com.filmpilot.app.gl.FilmPreviewEffect
import com.filmpilot.app.gl.FilmSurfaceProcessor
import com.filmpilot.fx.FilmParams
import com.filmpilot.fx.LensParams
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * CameraX viewfinder (back camera, 4:3).
 *  - When [liveParams] is non-null, the preview runs through the lens and film shaders on the
 *    GPU, so you see the look while framing. Null shows the plain camera preview.
 *  - [focalMm] (35 mm equivalent) sets the camera's real zoom, relative to its main lens.
 *  - A small analysis stream feeds the light meter ([onMeter], stops relative to mid-grey).
 *  - Photos follow how the phone is held (portrait or landscape), though the UI stays portrait.
 */
@Composable
fun Viewfinder(
    liveParams: FilmParams?,
    lens: LensParams,
    focalMm: Float,
    modifier: Modifier = Modifier,
    onLiveError: (Throwable) -> Unit,
    onMeter: (Float) -> Unit,
    onImageCapture: (ImageCapture?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val live = liveParams != null
    val currentOnLiveError by rememberUpdatedState(onLiveError)
    val currentOnImageCapture by rememberUpdatedState(onImageCapture)
    val currentOnMeter by rememberUpdatedState(onMeter)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor { Thread(it, "FilmPilot-Meter") } }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    // One GL processor per "live on" period; released when live preview turns off.
    val processor = remember(live) {
        if (live) FilmSurfaceProcessor { error -> onMain(context) { currentOnLiveError(error) } } else null
    }
    DisposableEffect(processor) { onDispose { processor?.release() } }

    // Slider, film and lens changes go straight to the GPU; no camera rebinding needed.
    SideEffect {
        if (liveParams != null) processor?.params = liveParams
        processor?.lens = lens
    }

    DisposableEffect(lifecycleOwner, processor) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val p = providerFuture.get().also { provider = it }
            val ratio = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder().setResolutionSelector(ratio).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val imageCapture = ImageCapture.Builder()
                .setResolutionSelector(ratio)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ratio)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { a -> a.setAnalyzer(analysisExecutor, LightMeter { stops -> currentOnMeter(stops) }) }

            fun bind(withMeter: Boolean): Camera {
                val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(imageCapture)
                if (withMeter) group.addUseCase(analysis)
                if (processor != null) group.addEffect(FilmPreviewEffect(processor) { error -> onMain(context) { currentOnLiveError(error) } })
                p.unbindAll()
                return p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
            }

            try {
                camera = try {
                    bind(withMeter = true)
                } catch (e: IllegalArgumentException) {
                    // Some phones can't run preview + capture + analysis together; drop the meter.
                    Log.w("FilmPilot", "Light meter unavailable with this camera configuration", e)
                    bind(withMeter = false)
                }
                capture = imageCapture
                currentOnImageCapture(imageCapture)
            } catch (e: Exception) {
                Log.e("FilmPilot", "Camera binding failed (live=${processor != null})", e)
                camera = null
                capture = null
                currentOnImageCapture(null)
                if (processor != null) currentOnLiveError(e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            camera = null
            capture = null
            currentOnImageCapture(null)
            provider?.unbindAll()
        }
    }

    // Focal length → real zoom, relative to the main camera's equivalent focal length.
    LaunchedEffect(camera, focalMm) {
        val cam = camera ?: return@LaunchedEffect
        val base = equivalentFocalLength(cam.cameraInfo)
        val zoom = cam.cameraInfo.zoomState.value
        val wanted = focalMm / base
        val ratio = if (zoom != null) wanted.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio) else wanted
        Log.i("FilmPilot", "Lens ${focalMm}mm: main camera ≈${"%.0f".format(base)}mm, zoom $ratio (wanted $wanted)")
        runCatching { cam.cameraControl.setZoomRatio(ratio) }
    }

    // Photos follow the phone's physical orientation even though the UI is portrait-only.
    DisposableEffect(capture) {
        val c = capture
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN || c == null) return
                c.targetRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        if (c != null && listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** 35 mm-equivalent focal length of the bound camera's main lens (≈26 mm on most phones). */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(ExperimentalCamera2Interop::class)
private fun equivalentFocalLength(info: CameraInfo): Float = runCatching {
    val c2 = Camera2CameraInfo.from(info)
    val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
    val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    if (focal == null || size == null) null
    else focal * 43.27f / hypot(size.width, size.height)
}.getOrNull()?.takeIf { it in 12f..60f } ?: 26f

private fun onMain(context: Context, block: () -> Unit) =
    ContextCompat.getMainExecutor(context).execute(block)

/** The viewfinder box is 3:4 portrait, matching the 4:3 sensor capture. */
const val VIEWFINDER_ASPECT = 3f / 4f
