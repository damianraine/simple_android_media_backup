package app.carry.core.sync

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The times Carry wakes, local time. Nothing saved means 00:00 and 18:00.
 * The next alarm is at least a minute and a half away, so arming it in the same breath as it fires
 * cannot schedule the slot that just happened.
 */
object SyncClock {
    const val EVENING = 18
    const val NIGHT = 0
    val usual: List<Int> = listOf(0, EVENING * 60)
    private const val DAY = 24 * 60

    private val slotFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun normalize(raw: List<Int>): List<Int> =
        raw.filter { it in 0 until DAY }.distinct().sorted()

    fun clock(minuteOfDay: Int): String {
        val clean = ((minuteOfDay % DAY) + DAY) % DAY
        return "%02d:%02d".format(clean / 60, clean % 60)
    }

    fun label(minutes: List<Int> = usual): String {
        val times = normalize(minutes)
        if (times.isEmpty()) return "Sync now only"
        return times.joinToString(" and ") { clock(it) }
    }

    fun nextAlarm(now: ZonedDateTime, minutes: List<Int> = usual): ZonedDateTime? {
        val times = normalize(minutes)
        if (times.isEmpty()) return null
        val soon = now.plusSeconds(90)
        return times.map { minute ->
            var slot = at(now, minute)
            if (!slot.isAfter(soon)) slot = slot.plusDays(1)
            slot
        }.minOrNull()
    }

    /** The latest saved time at or before this moment. A late backstop uses it so it does not copy twice. */
    fun slotId(now: ZonedDateTime, minutes: List<Int> = usual): String? {
        val times = normalize(minutes)
        if (times.isEmpty()) return null
        val today = times.map { at(now, it) }.filter { !it.isAfter(now) }
        val slot = today.maxOrNull() ?: at(now, times.last()).minusDays(1)
        return slot.format(slotFormat)
    }

    fun alreadyDone(completedSlot: String?, slot: String, manual: Boolean): Boolean =
        !manual && completedSlot != null && completedSlot == slot

    private fun at(now: ZonedDateTime, minuteOfDay: Int): ZonedDateTime =
        now.withHour(minuteOfDay / 60).withMinute(minuteOfDay % 60).withSecond(0).withNano(0)
}
