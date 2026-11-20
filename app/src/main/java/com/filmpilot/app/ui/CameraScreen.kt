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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filmpilot.app.CameraViewModel
import com.filmpilot.app.camera.DateStamp
import com.filmpilot.app.film.Casing
import java.util.Date
import kotlinx.coroutines.launch

val Amber = Color(0xFFE8A33D)

private enum class Picker { NONE, BODY, LENS, FILM, ADJUST }

@Composable
fun CameraScreen(vm: CameraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val meter by vm.meter.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val flash = remember { Animatable(0f) }
    val casing = state.body.casing

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var picker by remember { mutableStateOf(Picker.NONE) }

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

    Box(Modifier.fillMaxSize().leatherette(casing.covering.color())) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopPlate(
                casing = casing,
                bodyName = state.body.name,
                shotsTaken = state.camera.shotsTaken,
                rollSize = state.camera.rollSize,
                dateStamp = state.camera.dateStamp,
                onDateStamp = vm::setDateStamp,
            )

            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .aspectRatio(VIEWFINDER_ASPECT)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .border(3.dp, Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp)),
                ) {
                    Viewfinder(
                        liveParams = state.liveParams,
                        lens = state.liveLens,
                        focalMm = state.lens.focalMm,
                        modifier = Modifier.fillMaxSize(),
                        onLiveError = vm::onLivePreviewFailed,
                        onMeter = vm::onMeterReading,
                        onImageCapture = { imageCapture = it },
                    )
                    FrameLinesOverlay(state.body.format)
                    if (state.camera.dateStamp) DateStampPreview(Modifier.align(Alignment.BottomEnd))
                    LightMeterScale(
                        reading = meter,
                        compensation = state.camera.exposureEv,
                        onCompensation = vm::setExposure,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    // Shutter "blink".
                    Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.Black))
                    if (state.camera.rollFinished) {
                        RollFinishedOverlay(state.camera.rollSize, state.film.name) { vm.loadNewRoll() }
                    }
                }
            }

            GearRow(casing) {
                GearCard("Body", state.body.name, casing, onClick = { picker = Picker.BODY }) {
                    BodyIcon(casing, Modifier.size(width = 64.dp, height = 40.dp))
                }
                GearCard("Lens", state.lens.name, casing, onClick = { picker = Picker.LENS }) {
                    LensIcon(state.lens.focalMm, state.lens.id == "toy", Modifier.size(width = 64.dp, height = 40.dp))
                }
                GearCard("Film", state.film.name, casing, onClick = { picker = Picker.FILM }) {
                    FilmIcon(Modifier.size(width = 64.dp, height = 40.dp))
                }
            }

            BottomPlate(
                casing = casing,
                thumbnail = state.lastPhoto?.asImageBitmap(),
                developing = state.developing,
                shutterEnabled = state.canShoot && imageCapture != null,
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
                onAdjust = { picker = Picker.ADJUST },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp))
    }

    when (picker) {
        Picker.NONE -> Unit
        Picker.BODY -> BodyPickerSheet(
            camera = state.camera,
            onLoadBody = { vm.loadNewRoll(bodyId = it.id); picker = Picker.NONE },
            onDismiss = { picker = Picker.NONE },
        )
        Picker.LENS -> LensPickerSheet(
            current = state.lens,
            onSelect = { vm.selectLens(it.id); picker = Picker.NONE },
            onDismiss = { picker = Picker.NONE },
        )
        Picker.FILM -> FilmPickerSheet(
            films = state.films,
            current = state.film,
            camera = state.camera,
            onLoadRoll = { vm.loadNewRoll(filmId = it.id); picker = Picker.NONE },
            onDismiss = { picker = Picker.NONE },
        )
        Picker.ADJUST -> AdjustSheet(
            camera = state.camera,
            onExposure = vm::setExposure,
            onGrain = vm::setGrain,
            onVignette = vm::setVignette,
            onLivePreview = vm::setLivePreview,
            liveAvailable = !state.liveFailed,
            onReset = vm::resetEffects,
            onDismiss = { picker = Picker.NONE },
        )
    }
}

/** Top plate: frame counter window, engraved model name, date-imprint switch. */
@Composable
private fun TopPlate(
    casing: Casing,
    bodyName: String,
    shotsTaken: Int,
    rollSize: Int,
    dateStamp: Boolean,
    onDateStamp: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(casing.plate.color(), casing.plate.color().copy(alpha = 0.85f))))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Frame counter, like the little window on top of a film camera.
        Text(
            "%02d/%d".format(shotsTaken, rollSize),
            color = Amber,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF15120F))
                .border(1.dp, Color.Black, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .semantics { contentDescription = "$shotsTaken of $rollSize shots taken" },
        )
        Spacer(Modifier.weight(1f))
        Text(
            bodyName.uppercase(),
            color = casing.engraving.color(),
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
            fontSize = 13.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "DATE",
            color = if (dateStamp) Color(0xFFFF9A2E) else casing.engraving.color().copy(alpha = 0.6f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .border(1.5.dp, if (dateStamp) Color(0xFFFF9A2E) else casing.engraving.color().copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                .clickable { onDateStamp(!dateStamp) }
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics {
                    contentDescription = "Date imprint"
                    stateDescription = if (dateStamp) "On" else "Off"
                    role = Role.Switch
                },
        )
    }
}

/** What the imprint will look like, in the viewfinder corner. */
@Composable
private fun DateStampPreview(modifier: Modifier = Modifier) {
    Text(
        DateStamp.text(Date()),
        color = Color(0xFFFF9A2E),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 1.sp,
        modifier = modifier.padding(16.dp),
    )
}

@Composable
private fun GearRow(casing: Casing, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** One of the BODY / LENS / FILM cards under the viewfinder. Tap to swap. */
@Composable
private fun RowScope.GearCard(
    label: String,
    value: String,
    casing: Casing,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, casing.plate.color().copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value. Tap to change." },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label.uppercase(), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, letterSpacing = 2.sp)
        Text(
            value, color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        icon()
    }
}

/** Bottom plate: last photo, shutter release, adjust. */
@Composable
private fun BottomPlate(
    casing: Casing,
    thumbnail: ImageBitmap?,
    developing: Int,
    shutterEnabled: Boolean,
    onThumbnailClick: () -> Unit,
    onShutter: () -> Unit,
    onAdjust: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .border(1.dp, casing.metal.color().copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .clickable(enabled = thumbnail != null, onClick = onThumbnailClick)
                .semantics { contentDescription = "Last photo" },
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (developing > 0) {
                CircularProgressIndicator(color = Amber, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
            }
        }

        // Shutter release, in the casing's metal.
        Box(
            Modifier
                .size(82.dp)
                .border(4.dp, casing.metal.color().copy(alpha = if (shutterEnabled) 1f else 0.4f), CircleShape)
                .padding(8.dp)
                .clip(CircleShape)
                .background(
                    if (shutterEnabled) Brush.radialGradient(listOf(Color.White, casing.metal.color()))
                    else Brush.radialGradient(listOf(Color.DarkGray, Color.DarkGray))
                )
                .clickable(enabled = shutterEnabled, onClick = onShutter)
                .semantics { contentDescription = "Shutter" },
        )

        Box(Modifier.width(54.dp), contentAlignment = Alignment.Center) {
            IconButton(onClick = onAdjust) {
                Icon(Icons.Filled.Settings, contentDescription = "Adjust exposure, grain and vignette", tint = Color.White)
            }
        }
    }
}

@Composable
private fun RollFinishedOverlay(rollSize: Int, filmName: String, onNewRoll: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Roll finished", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text("All $rollSize frames shot.", color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(16.dp))
            Button(onClick = onNewRoll) { Text("Load new roll of $filmName") }
        }
    }
}
