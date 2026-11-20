package com.filmpilot.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.filmpilot.app.camera.DevelopRequest
import com.filmpilot.app.camera.FilmDeveloper
import com.filmpilot.app.film.CameraBody
import com.filmpilot.app.film.CameraState
import com.filmpilot.app.film.FilmSettingsStore
import com.filmpilot.app.film.FilmStock
import com.filmpilot.app.film.Gear
import com.filmpilot.app.film.Lens
import com.filmpilot.app.film.LutCatalog
import com.filmpilot.app.gl.GpuDeveloper
import com.filmpilot.fx.FilmParams
import com.filmpilot.fx.LensParams
import com.filmpilot.fx.Lut3D
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

data class UiState(
    val camera: CameraState,
    val film: FilmStock,
    val films: List<FilmStock>,
    /** The loaded film's LUT (null while loading, or for Neutral). Used by the live viewfinder. */
    val lut: Lut3D? = null,
    /** The live film preview failed on this device this session; the plain preview is used. */
    val liveFailed: Boolean = false,
    /** Captures sent to the camera but not yet returned. */
    val capturing: Int = 0,
    /** Frames being developed (effects + saving) in the background. */
    val developing: Int = 0,
    val lastPhoto: Bitmap? = null,
    val lastPhotoUri: Uri? = null,
    val message: String? = null,
) {
    val body: CameraBody get() = camera.body
    val lens: Lens get() = camera.lens

    /** True while another frame can be shot on this roll. */
    val canShoot get() = camera.shotsTaken + capturing < camera.rollSize

    /** Film vignette plus the lens's own optical vignetting. */
    private val totalVignette get() = (camera.vignette + lens.vignette).coerceIn(0f, 1f)

    /** What the live viewfinder should show, or null for the plain camera preview. */
    val liveParams: FilmParams?
        get() = if (!camera.livePreview || liveFailed) null else FilmParams(
            lut = lut,
            exposureEv = camera.exposureEv,
            grain = camera.grain,
            vignette = totalVignette,
        )

    /** Lens optics for the live viewfinder (none when the live preview is off). */
    val liveLens: LensParams get() = if (liveParams == null) LensParams.NONE else lens.optics

    fun developParams(lut: Lut3D?) = FilmParams(
        lut = lut,
        exposureEv = camera.exposureEv,
        grain = camera.grain,
        vignette = totalVignette,
        grainSeed = Random.nextInt(),
    )
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val catalog = LutCatalog(app.assets)
    private val store = FilmSettingsStore(app)
    private val gpu = GpuDeveloper()
    private val developer = FilmDeveloper(app, gpu)

    /** Frames are developed one at a time, so a burst never holds several full-size bitmaps. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val darkroom = Dispatchers.Default.limitedParallelism(1)

    /** First half of a half-frame pair, waiting for its partner. Only touched in [darkroom]. */
    private var pendingHalf: Bitmap? = null
    private var pendingHalfRoll = 0
    /** Bumped on every new roll, so a half from the previous roll never pairs with the next. */
    @Volatile private var rollNumber = 0

    private val _state = MutableStateFlow(
        store.load().let { cam ->
            val film = catalog.stock(cam.filmId)
            UiState(camera = cam.copy(filmId = film.id), film = film, films = catalog.stocks)
        }
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Light meter: how the metered frame will be exposed relative to mid-grey, in stops,
     * before exposure compensation. Null until the first reading (or if metering isn't available).
     */
    private val _meter = MutableStateFlow<Float?>(null)
    val meter: StateFlow<Float?> = _meter.asStateFlow()

    init {
        loadLut(_state.value.film.id)
    }

    /** Parse the film's LUT in the background and hand it to the live viewfinder. */
    private fun loadLut(filmId: String) {
        viewModelScope.launch(darkroom) {
            val lut = runCatching { catalog.load(filmId) }
                .onFailure { Log.e(TAG, "Couldn't load film $filmId", it) }
                .getOrNull()
            _state.update { if (it.film.id == filmId) it.copy(lut = lut) else it }
        }
    }

    /** Called by the meter analyser (any thread), smoothed so the needle moves like a real one. */
    fun onMeterReading(stops: Float) {
        _meter.update { old -> if (old == null) stops else old + (stops - old) * 0.35f }
    }

    /** Reserve a frame before asking the camera for a picture. Returns false if the roll is full. */
    fun beginCapture(): Boolean {
        var ok = false
        _state.update { s -> if (s.canShoot) { ok = true; s.copy(capturing = s.capturing + 1) } else s }
        return ok
    }

    fun onCaptureFailed(error: Throwable) {
        Log.w(TAG, "Capture failed", error)
        _state.update { it.copy(capturing = it.capturing - 1, message = "Capture failed: ${error.message}") }
    }

    fun onCaptured(jpeg: ByteArray, rotationDegrees: Int) {
        val s = _state.value
        val frame = s.camera.copy(shotsTaken = s.camera.shotsTaken + 1)
        store.save(frame)
        _state.update { it.copy(camera = frame, capturing = it.capturing - 1, developing = it.developing + 1) }

        val filmId = s.film.id
        val body = s.body
        val roll = rollNumber
        val dateStamp = if (s.camera.dateStamp) Date() else null
        viewModelScope.launch(darkroom) {
            try {
                val request = DevelopRequest(
                    film = s.developParams(catalog.load(filmId)),
                    lens = s.lens.optics,
                    format = body.format,
                    dateStamp = dateStamp,
                    keepForPair = body.halfFramePairs,
                )
                val photo = developer.develop(jpeg, rotationDegrees, request)
                _state.update { it.copy(lastPhoto = photo.thumbnail, lastPhotoUri = photo.uri) }
                photo.pairHalf?.let { pairUp(it, roll) }
            } catch (t: Throwable) {
                Log.e(TAG, "Developing failed", t)
                _state.update { it.copy(message = "Couldn't save photo: ${t.message}") }
            } finally {
                _state.update { it.copy(developing = it.developing - 1) }
            }
        }
    }

    /** Half-frame: every second frame is also saved next to the one before it. */
    private suspend fun pairUp(half: Bitmap, roll: Int) {
        val first = pendingHalf
        if (first == null || pendingHalfRoll != roll) {
            first?.recycle()
            pendingHalf = half
            pendingHalfRoll = roll
            return
        }
        pendingHalf = null
        try {
            developer.savePair(first, half)
            Log.i(TAG, "Saved half-frame pair")
        } finally {
            first.recycle()
            half.recycle()
        }
    }

    /**
     * Load a fresh roll: counter back to 0. Optionally with another film or body
     * (both mean rewinding, like a real camera).
     */
    fun loadNewRoll(filmId: String = _state.value.film.id, bodyId: String = _state.value.camera.bodyId) {
        val film = catalog.stock(filmId)
        val body = Gear.body(bodyId)
        rollNumber++
        updateCamera {
            val bodyChanged = it.bodyId != body.id
            it.copy(
                filmId = film.id, shotsTaken = 0, bodyId = body.id,
                dateStamp = if (bodyChanged) body.dateStampByDefault else it.dateStamp,
            )
        }
        _state.update { it.copy(film = film, lut = if (it.film.id == film.id) it.lut else null) }
        loadLut(film.id)
    }

    /** Lenses can be swapped mid-roll. */
    fun selectLens(lensId: String) = updateCamera { it.copy(lensId = Gear.lens(lensId).id) }

    fun setDateStamp(on: Boolean) = updateCamera { it.copy(dateStamp = on) }

    fun setExposure(ev: Float) = updateCamera {
        it.copy(exposureEv = ev.coerceIn(FilmParams.MIN_EV, FilmParams.MAX_EV))
    }
    fun setGrain(v: Float) = updateCamera { it.copy(grain = v.coerceIn(0f, 1f)) }
    fun setVignette(v: Float) = updateCamera { it.copy(vignette = v.coerceIn(0f, 1f)) }
    fun resetEffects() = updateCamera {
        it.copy(exposureEv = CameraState.DEFAULT_EV, grain = CameraState.DEFAULT_GRAIN, vignette = CameraState.DEFAULT_VIGNETTE)
    }

    fun setLivePreview(on: Boolean) = updateCamera { it.copy(livePreview = on) }

    fun onLivePreviewFailed(error: Throwable) {
        Log.e(TAG, "Live film preview failed; using the plain preview", error)
        _state.update {
            if (it.liveFailed) it
            else it.copy(liveFailed = true, message = "Live film preview isn't available on this phone. Photos still get the look.")
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        gpu.release()
    }

    private fun updateCamera(change: (CameraState) -> CameraState) {
        _state.update { s -> s.copy(camera = change(s.camera)).also { store.save(it.camera) } }
    }

    private companion object { const val TAG = "FilmPilot" }
}
