package app.carry.core.sync

import app.carry.core.media.MediaPlaces
import app.carry.core.model.Layout
import app.carry.core.model.LocalMedia
import app.carry.core.model.PlannedCopy
import app.carry.core.model.RemoteFile
import app.carry.core.model.SyncPlan
import app.carry.core.text.FileNames
import java.time.Instant
import java.time.ZoneId

object MediaFilter {
    /**
     * Files taken before the start date stay on the phone.
     * A missing timestamp is treated as too old once a start date is set, so an undated file cannot sneak through.
     */
    fun kept(takenEpochMs: Long, startEpochDay: Long?, zone: ZoneId): Boolean {
        if (startEpochDay == null) return true
        if (takenEpochMs <= 0L) return false
        val day = Instant.ofEpochMilli(takenEpochMs).atZone(zone).toLocalDate().toEpochDay()
        return day >= startEpochDay
    }
}

object BackupPaths {
    fun folder(path: String): String {
        val out = ArrayDeque<String>()
        for (part in MediaPlaces.segments(path)) {
            when (part) {
                "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.add(part)
            }
        }
        return out.joinToString("/")
    }

    fun relative(media: LocalMedia, layout: Layout, zone: ZoneId): String {
        val file = FileNames.file(media.name)
        return when (layout) {
            Layout.SOURCE_FOLDER -> "${FileNames.folder(media.source)}/$file"
            Layout.YEAR_MONTH -> {
                if (media.takenEpochMs <= 0L) return "undated/$file"
                val date = Instant.ofEpochMilli(media.takenEpochMs).atZone(zone)
                val month = date.monthValue.toString().padStart(2, '0')
                "${date.year}/$month/$file"
            }
        }
    }

    fun parentsOf(paths: Collection<String>): Set<String> =
        paths.map { it.substringBeforeLast('/', "") }.toSet()
}

/**
 * One-way plan. A file that is already at the destination, with the same size, is not copied.
 * A different file that would land on the same name gets a " (2)" suffix. Nothing is overwritten.
 */
object BackupPlan {
    fun plan(
        local: List<LocalMedia>,
        remote: List<RemoteFile>,
        layout: Layout,
        zone: ZoneId,
    ): SyncPlan {
        val remoteSizes = HashMap<String, Long>()
        for (file in remote) {
            val key = key(file.relativePath)
            if (key.isEmpty()) continue
            remoteSizes.putIfAbsent(key, file.size)
        }
        val claimed = HashMap<String, String>()
        val copies = ArrayList<PlannedCopy>()
        var already = 0
        val ordered = local.sortedWith(compareBy({ it.takenEpochMs }, { it.name.lowercase() }, { it.key }))
        for (media in ordered) {
            if (media.size <= 0L || media.name.isBlank()) continue
            val desired = BackupPaths.relative(media, layout, zone)
            val desiredKey = key(desired)
            val existing = remoteSizes[desiredKey]
            if (existing != null && existing == media.size && desiredKey !in claimed) {
                claimed[desiredKey] = media.key
                already++
                continue
            }
            var candidate = desired
            var number = 2
            while (key(candidate) in remoteSizes || key(candidate) in claimed) {
                candidate = FileNames.suffixed(desired, number)
                number++
                if (number > 500) break
            }
            val candidateKey = key(candidate)
            if (candidateKey in remoteSizes || candidateKey in claimed) continue
            claimed[candidateKey] = media.key
            copies += PlannedCopy(media, candidate)
        }
        return SyncPlan(copies, already)
    }

    private fun key(path: String): String =
        path.replace('\\', '/').trim('/').lowercase()
}
