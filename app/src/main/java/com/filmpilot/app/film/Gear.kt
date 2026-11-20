package com.filmpilot.app.film

import com.filmpilot.fx.FrameFormat
import com.filmpilot.fx.LensParams

/** Colours of a camera body's casing, used to dress the whole camera screen. */
data class Casing(
    /** Body covering (leatherette, plastic…). */
    val covering: Long,
    /** Top/bottom plates. */
    val plate: Long,
    /** Engraving and small details on the plates. */
    val engraving: Long,
    /** Shutter button and dials. */
    val metal: Long,
)

/**
 * A camera body: what shape each frame is, how many fit on a roll, and its extras.
 * Changing body means loading a new roll, like on a real camera.
 */
data class CameraBody(
    val id: String,
    val name: String,
    val description: String,
    val format: FrameFormat,
    val exposures: Int,
    val casing: Casing,
    /** Date imprint switched on when this body is loaded. */
    val dateStampByDefault: Boolean = false,
    /** Half-frame: consecutive frames are also saved side by side, as on the negative. */
    val halfFramePairs: Boolean = false,
)

/**
 * A lens. Focal length drives the camera's real zoom (relative to the phone's main camera);
 * the optics are simulated live and on every photo. Lenses can be swapped mid-roll.
 */
data class Lens(
    val id: String,
    val name: String,
    val description: String,
    val focalMm: Float,
    val optics: LensParams,
    /** Extra optical vignetting, added to the film's vignette setting. */
    val vignette: Float,
)

/**
 * The gear cupboard. Add a body or lens by adding an entry here; everything else
 * (pickers, viewfinder framing, developing) follows from the data.
 */
object Gear {
    val bodies = listOf(
        CameraBody(
            id = "classic35", name = "Pilot 35", description = "35 mm rangefinder · 3:2 · 24 exposures",
            format = FrameFormat.FULL_35, exposures = 24,
            casing = Casing(covering = 0xFF1C1A18, plate = 0xFFB9B6AE, engraving = 0xFF2A2724, metal = 0xFFD9D6CF),
        ),
        CameraBody(
            id = "half", name = "Pilot Half", description = "Half-frame · 48 exposures · saves pairs side by side",
            format = FrameFormat.HALF_FRAME, exposures = 48, halfFramePairs = true,
            casing = Casing(covering = 0xFF6B4A2E, plate = 0xFFCFC9BC, engraving = 0xFF3B2A1B, metal = 0xFFE4E0D6),
        ),
        CameraBody(
            id = "square", name = "Pilot Six", description = "Medium format · square 6×6 · 12 exposures",
            format = FrameFormat.SQUARE, exposures = 12,
            casing = Casing(covering = 0xFF2B2B2B, plate = 0xFF151515, engraving = 0xFFB8A27A, metal = 0xFF8E8A82),
        ),
        CameraBody(
            id = "pano", name = "Pilot Wide", description = "Panoramic · 65:24 · 21 exposures",
            format = FrameFormat.PANORAMA, exposures = 21,
            casing = Casing(covering = 0xFF3A3F44, plate = 0xFF9BA1A6, engraving = 0xFF1E2226, metal = 0xFFC3C8CC),
        ),
        CameraBody(
            id = "snap", name = "Pilot Snap", description = "Point & shoot · 3:2 · date imprint · 24 exposures",
            format = FrameFormat.FULL_35, exposures = 24, dateStampByDefault = true,
            casing = Casing(covering = 0xFF2E2D33, plate = 0xFF46454D, engraving = 0xFFE8A33D, metal = 0xFF6E6C76),
        ),
    )

    val lenses = listOf(
        Lens("21", "21mm f/2.8", "Ultra-wide · strong barrel, soft corners", 21f, LensParams(0.06f, 0.0015f, 0.25f), 0.25f),
        Lens("28", "28mm f/2.8", "Wide street lens · mild barrel", 28f, LensParams(0.035f, 0.001f, 0.1f), 0.15f),
        Lens("35", "35mm f/2", "Classic everyday lens", 35f, LensParams(0.015f, 0.0006f, 0f), 0.1f),
        Lens("50", "50mm f/1.8", "Standard · clean and neutral", 50f, LensParams.NONE, 0.05f),
        Lens("90", "90mm f/2.8", "Portrait tele · slight pincushion", 90f, LensParams(-0.012f, 0.0004f, 0f), 0f),
        Lens("toy", "Toy 38mm", "Plastic lens · heavy vignette, fringes, dreamy edges", 38f, LensParams(0.09f, 0.004f, 0.8f), 0.45f),
    )

    val defaultBody = bodies.first()
    val defaultLens = lenses.first { it.id == "35" }

    fun body(id: String?) = bodies.firstOrNull { it.id == id } ?: defaultBody
    fun lens(id: String?) = lenses.firstOrNull { it.id == id } ?: defaultLens
}
