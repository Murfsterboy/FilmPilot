package com.filmpilot.app.film

import android.content.res.AssetManager
import com.filmpilot.fx.CubeParser
import com.filmpilot.fx.Lut3D
import java.util.concurrent.ConcurrentHashMap

/** A film the user can load: one `.cube` file in `assets/luts/`, or the built-in neutral stock. */
data class FilmStock(val id: String, val name: String)

/**
 * Lists and loads film LUTs. To add a film, drop a `.cube` file into
 * `app/src/main/assets/luts/`. Its `TITLE` line becomes the name shown in the picker
 * (or the file name, prettified, if there is no title). No code changes needed.
 */
class LutCatalog(private val assets: AssetManager) {

    private val cache = ConcurrentHashMap<String, Lut3D>()

    val stocks: List<FilmStock> by lazy {
        val files = assets.list(DIR).orEmpty()
            .filter { it.endsWith(".cube", ignoreCase = true) }
            .sorted()
        files.map { FilmStock(id = it, name = readTitle(it) ?: prettify(it)) } +
            FilmStock(NEUTRAL_ID, "Neutral (no film look)")
    }

    fun stock(id: String?): FilmStock = stocks.firstOrNull { it.id == id } ?: stocks.first()

    /** Parses (once) and returns the LUT for [id]; null for the neutral stock. Call off the main thread. */
    fun load(id: String): Lut3D? {
        if (id == NEUTRAL_ID) return null
        return cache.getOrPut(id) {
            assets.open("$DIR/$id").use { CubeParser.parse(it, fallbackTitle = prettify(id)) }
        }
    }

    /** Reads just the TITLE line from the top of the file, so listing stays fast. */
    private fun readTitle(file: String): String? = runCatching {
        assets.open("$DIR/$file").bufferedReader().useLines { lines ->
            lines.take(20)
                .map { it.trim() }
                .firstOrNull { it.startsWith("TITLE", ignoreCase = true) }
                ?.substring(5)?.trim()?.trim('"')
                ?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun prettify(file: String) = file.substringBeforeLast('.')
        .replace('_', ' ').replace('-', ' ')
        .split(' ').filter { it.isNotBlank() }
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    companion object {
        const val DIR = "luts"
        const val NEUTRAL_ID = "neutral"
    }
}
