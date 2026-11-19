package com.filmpilot.app.film

import android.content.Context

/** What's loaded in the camera: film stock, how many frames are used, and the effect settings. */
data class CameraState(
    val filmId: String?,
    val shotsTaken: Int,
    val exposureEv: Float,
    val grain: Float,
    val vignette: Float,
) {
    val shotsLeft get() = ROLL_SIZE - shotsTaken
    val rollFinished get() = shotsTaken >= ROLL_SIZE

    companion object {
        const val ROLL_SIZE = 24
        const val DEFAULT_EV = 0f
        const val DEFAULT_GRAIN = 0.35f
        const val DEFAULT_VIGNETTE = 0.3f
    }
}

/** Persists [CameraState] in SharedPreferences so a roll survives app restarts. */
class FilmSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("filmpilot", Context.MODE_PRIVATE)

    fun load() = CameraState(
        filmId = prefs.getString(K_FILM, null),
        shotsTaken = prefs.getInt(K_SHOTS, 0).coerceIn(0, CameraState.ROLL_SIZE),
        exposureEv = prefs.getFloat(K_EV, CameraState.DEFAULT_EV),
        grain = prefs.getFloat(K_GRAIN, CameraState.DEFAULT_GRAIN),
        vignette = prefs.getFloat(K_VIGNETTE, CameraState.DEFAULT_VIGNETTE),
    )

    fun save(s: CameraState) {
        prefs.edit()
            .putString(K_FILM, s.filmId)
            .putInt(K_SHOTS, s.shotsTaken)
            .putFloat(K_EV, s.exposureEv)
            .putFloat(K_GRAIN, s.grain)
            .putFloat(K_VIGNETTE, s.vignette)
            .apply()
    }

    private companion object {
        const val K_FILM = "film_id"
        const val K_SHOTS = "shots_taken"
        const val K_EV = "exposure_ev"
        const val K_GRAIN = "grain"
        const val K_VIGNETTE = "vignette"
    }
}
