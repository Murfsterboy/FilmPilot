package com.filmpilot.app.ui

import android.content.Intent
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filmpilot.app.CameraViewModel
import com.filmpilot.app.film.CameraState
import kotlinx.coroutines.launch

val Amber = Color(0xFFE8A33D)

@Composable
fun CameraScreen(vm: CameraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val flash = remember { Animatable(0f) }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var showFilms by remember { mutableStateOf(false) }
    var showAdjust by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    fun shoot() {
        val capture = imageCapture ?: return
        if (!vm.beginCapture()) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(220))
        }
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val bytes = try {
                        val buffer = image.planes[0].buffer
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    } finally {
                        image.close()
                    }
                    vm.onCaptured(bytes, rotation)
                }

                override fun onError(exception: ImageCaptureException) = vm.onCaptureFailed(exception)
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(
                filmName = state.film.name,
                shotsTaken = state.camera.shotsTaken,
                onFilmClick = { showFilms = true },
            )

            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(VIEWFINDER_ASPECT)) {
                    Viewfinder(Modifier.fillMaxSize()) { imageCapture = it }
                    // Shutter "blink".
                    Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.Black))
                    if (state.camera.rollFinished) RollFinishedOverlay(state.film.name) { vm.loadNewRoll() }
                }
            }

            BottomBar(
                thumbnail = state.lastPhoto?.asImageBitmap(),
                developing = state.developing,
                shutterEnabled = state.canShoot && imageCapture != null,
                exposureLabel = formatEv(state.camera.exposureEv),
                onThumbnailClick = {
                    state.lastPhotoUri?.let { uri ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            )
                        }
                    }
                },
                onShutter = { shoot() },
                onAdjust = { showAdjust = true },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp))
    }

    if (showFilms) {
        FilmPickerSheet(
            films = state.films,
            current = state.film,
            camera = state.camera,
            onLoadRoll = { vm.loadNewRoll(it.id); showFilms = false },
            onDismiss = { showFilms = false },
        )
    }
    if (showAdjust) {
        AdjustSheet(
            camera = state.camera,
            onExposure = vm::setExposure,
            onGrain = vm::setGrain,
            onVignette = vm::setVignette,
            onReset = vm::resetEffects,
            onDismiss = { showAdjust = false },
        )
    }
}

@Composable
private fun TopBar(filmName: String, shotsTaken: Int, onFilmClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            filmName,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(50))
                .clickable(onClick = onFilmClick)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .semantics { contentDescription = "Film: $filmName. Tap to change film." },
        )
        Spacer(Modifier.weight(1f))
        // Frame counter, like the little window on a film camera.
        Text(
            "%02d/%d".format(shotsTaken, CameraState.ROLL_SIZE),
            color = Amber,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1B1714))
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .semantics { contentDescription = "$shotsTaken of ${CameraState.ROLL_SIZE} shots taken" },
        )
    }
}

@Composable
private fun BottomBar(
    thumbnail: androidx.compose.ui.graphics.ImageBitmap?,
    developing: Int,
    shutterEnabled: Boolean,
    exposureLabel: String,
    onThumbnailClick: () -> Unit,
    onShutter: () -> Unit,
    onAdjust: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(128.dp).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Last developed photo.
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF222222))
                .clickable(enabled = thumbnail != null, onClick = onThumbnailClick)
                .semantics { contentDescription = "Last photo" },
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (developing > 0) {
                CircularProgressIndicator(color = Amber, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            }
        }

        // Shutter.
        Box(
            Modifier
                .size(80.dp)
                .border(4.dp, if (shutterEnabled) Color.White else Color.Gray, CircleShape)
                .padding(8.dp)
                .clip(CircleShape)
                .background(if (shutterEnabled) Color.White else Color.DarkGray)
                .clickable(enabled = shutterEnabled, onClick = onShutter)
                .semantics { contentDescription = "Shutter" },
        )

        // Adjust (exposure, grain, vignette).
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onAdjust) {
                Icon(Icons.Filled.Settings, contentDescription = "Adjust exposure, grain and vignette", tint = Color.White)
            }
            Text(exposureLabel, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun RollFinishedOverlay(filmName: String, onNewRoll: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Roll finished", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text("All ${CameraState.ROLL_SIZE} frames shot.", color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(16.dp))
            Button(onClick = onNewRoll) { Text("Load new roll of $filmName") }
        }
    }
}
