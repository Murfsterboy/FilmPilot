package com.filmpilot.app.ui

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.filmpilot.app.gl.FilmPreviewEffect
import com.filmpilot.app.gl.FilmSurfaceProcessor
import com.filmpilot.fx.FilmParams

/**
 * CameraX viewfinder (back camera, 4:3). When [liveParams] is non-null, the preview runs
 * through the film shader on the GPU, so you see the film look while framing. Null shows
 * the plain camera preview. Hands the [ImageCapture] use case to the caller once bound.
 */
@Composable
fun Viewfinder(
    liveParams: FilmParams?,
    modifier: Modifier = Modifier,
    onLiveError: (Throwable) -> Unit,
    onImageCapture: (ImageCapture?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val live = liveParams != null
    val currentOnLiveError by rememberUpdatedState(onLiveError)
    val currentOnImageCapture by rememberUpdatedState(onImageCapture)

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // One GL processor per "live on" period; released when live preview turns off.
    val processor = remember(live) {
        if (live) FilmSurfaceProcessor { error -> onMain(context) { currentOnLiveError(error) } } else null
    }
    DisposableEffect(processor) { onDispose { processor?.release() } }

    // Slider and film changes go straight to the GPU; no camera rebinding needed.
    SideEffect { if (liveParams != null) processor?.params = liveParams }

    DisposableEffect(lifecycleOwner, processor) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            try {
                val p = providerFuture.get().also { provider = it }
                val ratio = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val preview = Preview.Builder().setResolutionSelector(ratio).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setResolutionSelector(ratio)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
                if (processor != null) group.addEffect(FilmPreviewEffect(processor) { error -> onMain(context) { currentOnLiveError(error) } })
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
                currentOnImageCapture(capture)
            } catch (e: Exception) {
                Log.e("FilmPilot", "Camera binding failed (live=${processor != null})", e)
                currentOnImageCapture(null)
                if (processor != null) currentOnLiveError(e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            currentOnImageCapture(null)
            provider?.unbindAll()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun onMain(context: android.content.Context, block: () -> Unit) =
    ContextCompat.getMainExecutor(context).execute(block)

/** The viewfinder box is 3:4 portrait, matching the 4:3 sensor capture. */
const val VIEWFINDER_ASPECT = 3f / 4f
