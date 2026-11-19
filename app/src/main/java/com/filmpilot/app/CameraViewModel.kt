package com.filmpilot.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.filmpilot.app.camera.FilmDeveloper
import com.filmpilot.app.film.CameraState
import com.filmpilot.app.film.FilmSettingsStore
import com.filmpilot.app.film.FilmStock
import com.filmpilot.app.film.LutCatalog
import com.filmpilot.app.gl.GpuDeveloper
import com.filmpilot.fx.FilmParams
import com.filmpilot.fx.Lut3D
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
    /** True while another frame can be shot on this roll. */
    val canShoot get() = camera.shotsTaken + capturing < CameraState.ROLL_SIZE

    /** What the live viewfinder should show, or null for the plain camera preview. */
    val liveParams: FilmParams?
        get() = if (!camera.livePreview || liveFailed) null else FilmParams(
            lut = lut,
            exposureEv = camera.exposureEv,
            grain = camera.grain,
            vignette = camera.vignette,
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

    private val _state = MutableStateFlow(
        store.load().let { cam ->
            val film = catalog.stock(cam.filmId)
            UiState(camera = cam.copy(filmId = film.id), film = film, films = catalog.stocks)
        }
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

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
        val cam = s.camera
        viewModelScope.launch(darkroom) {
            try {
                val params = FilmParams(
                    lut = catalog.load(filmId),
                    exposureEv = cam.exposureEv,
                    grain = cam.grain,
                    vignette = cam.vignette,
                    grainSeed = Random.nextInt(),
                )
                val photo = developer.develop(jpeg, rotationDegrees, params)
                _state.update { it.copy(lastPhoto = photo.thumbnail, lastPhotoUri = photo.uri) }
            } catch (t: Throwable) {
                Log.e(TAG, "Developing failed", t)
                _state.update { it.copy(message = "Couldn't save photo: ${t.message}") }
            } finally {
                _state.update { it.copy(developing = it.developing - 1) }
            }
        }
    }

    /** Load a fresh roll of [filmId] (or the current film): counter back to 0. */
    fun loadNewRoll(filmId: String = _state.value.film.id) {
        val film = catalog.stock(filmId)
        updateCamera { it.copy(filmId = film.id, shotsTaken = 0) }
        _state.update { it.copy(film = film, lut = if (it.film.id == film.id) it.lut else null) }
        loadLut(film.id)
    }

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
