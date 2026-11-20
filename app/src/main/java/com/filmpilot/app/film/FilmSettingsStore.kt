package com.filmpilot.app.film

import android.content.Context

/** What's loaded in the camera: film stock, how many frames are used, and the effect settings. */
data class CameraState(
    val filmId: String?,
    val shotsTaken: Int,
    val exposureEv: Float,
    val grain: Float,
    val vignette: Float,
    /** Show the film look live in the viewfinder (GPU). */
    val livePreview: Boolean = true,
    val bodyId: String = Gear.defaultBody.id,
    val lensId: String = Gear.defaultLens.id,
    /** Date imprint in the corner of each frame. */
    val dateStamp: Boolean = false,
) {
    val body get() = Gear.body(bodyId)
    val lens get() = Gear.lens(lensId)
    val rollSize get() = body.exposures
    val shotsLeft get() = rollSize - shotsTaken
    val rollFinished get() = shotsTaken >= rollSize

    companion object {
        const val DEFAULT_EV = 0f
        const val DEFAULT_GRAIN = 0.35f
        const val DEFAULT_VIGNETTE = 0.3f
    }
}

/** Persists [CameraState] in SharedPreferences so a roll survives app restarts. */
class FilmSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("filmpilot", Context.MODE_PRIVATE)

    fun load(): CameraState {
        val body = Gear.body(prefs.getString(K_BODY, null))
        return CameraState(
        filmId = prefs.getString(K_FILM, null),
        shotsTaken = prefs.getInt(K_SHOTS, 0).coerceIn(0, body.exposures),
        exposureEv = prefs.getFloat(K_EV, CameraState.DEFAULT_EV),
        grain = prefs.getFloat(K_GRAIN, CameraState.DEFAULT_GRAIN),
        vignette = prefs.getFloat(K_VIGNETTE, CameraState.DEFAULT_VIGNETTE),
        livePreview = prefs.getBoolean(K_LIVE, true),
        bodyId = body.id,
        lensId = Gear.lens(prefs.getString(K_LENS, null)).id,
        dateStamp = prefs.getBoolean(K_DATE, body.dateStampByDefault),
        )
    }

    fun save(s: CameraState) {
        prefs.edit()
            .putString(K_FILM, s.filmId)
            .putInt(K_SHOTS, s.shotsTaken)
            .putFloat(K_EV, s.exposureEv)
            .putFloat(K_GRAIN, s.grain)
            .putFloat(K_VIGNETTE, s.vignette)
            .putBoolean(K_LIVE, s.livePreview)
            .putString(K_BODY, s.bodyId)
            .putString(K_LENS, s.lensId)
            .putBoolean(K_DATE, s.dateStamp)
            .apply()
    }

    private companion object {
        const val K_FILM = "film_id"
        const val K_SHOTS = "shots_taken"
        const val K_EV = "exposure_ev"
        const val K_GRAIN = "grain"
        const val K_VIGNETTE = "vignette"
        const val K_LIVE = "live_preview"
        const val K_BODY = "body_id"
        const val K_LENS = "lens_id"
        const val K_DATE = "date_stamp"
    }
}
