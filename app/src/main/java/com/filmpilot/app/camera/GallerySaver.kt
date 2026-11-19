package com.filmpilot.app.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Saves JPEGs into the shared gallery under Pictures/FilmPilot. */
object GallerySaver {
    private const val ALBUM = "FilmPilot"

    suspend fun saveJpeg(context: Context, file: File, displayName: String): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveScoped(context, file, displayName)
        else saveLegacy(context, file, displayName)

    /** Android 10+: MediaStore with RELATIVE_PATH, no storage permission needed. */
    private fun saveScoped(context: Context, file: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("Couldn't create a gallery entry")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("Couldn't open the gallery entry")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    /** Android 8–9: write the file into Pictures/FilmPilot and let the media scanner index it. */
    @Suppress("DEPRECATION")
    private suspend fun saveLegacy(context: Context, file: File, displayName: String): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!dir.exists() && !dir.mkdirs()) error("Couldn't create ${dir.path}")
        val dest = File(dir, displayName)
        file.copyTo(dest, overwrite = true)
        return suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(dest.path), arrayOf("image/jpeg")) { _, uri ->
                cont.resume(uri ?: Uri.fromFile(dest))
            }
        }
    }
}
