package app.carry.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.carry.core.media.MediaPlaces
import app.carry.core.model.LocalMedia
import java.io.InputStream

object MediaAccess {
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun granted(context: Context): Boolean {
        fun ok(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 34) {
            ok(Manifest.permission.READ_MEDIA_IMAGES) ||
                ok(Manifest.permission.READ_MEDIA_VIDEO) ||
                ok(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else if (Build.VERSION.SDK_INT >= 33) {
            ok(Manifest.permission.READ_MEDIA_IMAGES) || ok(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            ok(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

class MediaIndex(private val context: Context) {
    fun scan(): List<LocalMedia> {
        if (!MediaAccess.granted(context)) return emptyList()
        val images = query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.DATE_TAKEN)
        val videos = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.DATE_TAKEN)
        return (images + videos).distinctBy { it.key }
    }

    fun open(key: String): InputStream? =
        runCatching { context.contentResolver.openInputStream(Uri.parse(key)) }.getOrNull()

    private fun query(collection: Uri, dateTakenColumn: String): List<LocalMedia> {
        return try {
            read(collection, dateTakenColumn)
        } catch (_: IllegalArgumentException) {
            runCatching { read(collection, null) }.getOrDefault(emptyList())
        }
    }

    private fun read(collection: Uri, dateTakenColumn: String?): List<LocalMedia> {
        val modern = Build.VERSION.SDK_INT >= 29
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        if (modern) projection += MediaStore.MediaColumns.RELATIVE_PATH else projection += MediaStore.MediaColumns.DATA
        if (dateTakenColumn != null) projection += dateTakenColumn
        val selection = if (modern) "${MediaStore.MediaColumns.IS_PENDING}=0" else null
        val cursor = context.contentResolver.query(
            collection,
            projection.toTypedArray(),
            selection,
            null,
            null,
        ) ?: return emptyList()
        cursor.use { rows ->
            val id = rows.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val size = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modified = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val added = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val bucket = rows.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val relative = if (modern) rows.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
            val data = if (modern) -1 else rows.getColumnIndex(MediaStore.MediaColumns.DATA)
            val taken = if (dateTakenColumn == null) -1 else rows.getColumnIndex(dateTakenColumn)
            val found = ArrayList<LocalMedia>()
            while (rows.moveToNext()) {
                val bytes = if (size >= 0) rows.getLong(size) else 0L
                val display = rows.getString(name).orEmpty()
                if (bytes <= 0L || display.isBlank()) continue
                val dir = when {
                    relative >= 0 -> rows.getString(relative).orEmpty()
                    data >= 0 -> directoryOf(rows.getString(data).orEmpty())
                    else -> ""
                }
                if (!MediaPlaces.include(dir)) continue
                val takenMs = when {
                    taken >= 0 && rows.getLong(taken) > 0L -> rows.getLong(taken)
                    rows.getLong(modified) > 0L -> rows.getLong(modified) * 1000L
                    else -> rows.getLong(added) * 1000L
                }
                val uri = android.content.ContentUris.withAppendedId(collection, rows.getLong(id))
                found += LocalMedia(
                    key = uri.toString(),
                    name = display,
                    size = bytes,
                    takenEpochMs = takenMs,
                    source = MediaPlaces.source(dir, if (bucket >= 0) rows.getString(bucket) else null),
                )
            }
            return found
        }
    }

    private fun directoryOf(data: String): String {
        val slash = data.lastIndexOf('/')
        if (slash <= 0) return ""
        val full = data.substring(0, slash)
        val marker = "/0/"
        val at = full.indexOf(marker)
        return if (at >= 0) full.substring(at + marker.length) else full.substringAfter(':')
    }
}
