package app.carry.core.sync

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Carry wakes at 18:00 and at midnight, local time.
 * The next alarm is at least a minute and a half away, so arming it in the same breath as it fires
 * cannot schedule the slot that just happened.
 */
object SyncClock {
    const val EVENING = 18
    const val NIGHT = 0

    private val slotFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun label(): String = "%02d:00 and %02d:00".format(EVENING, NIGHT)

    fun nextAlarm(now: ZonedDateTime): ZonedDateTime {
        val soon = now.plusSeconds(90)
        return listOf(NIGHT, EVENING).map { hour ->
            var slot = at(now, hour)
            if (!slot.isAfter(soon)) slot = slot.plusDays(1)
            slot
        }.minOrNull() ?: at(now, EVENING).plusDays(1)
    }

    /** The 18:00 or 00:00 that this moment belongs to. Used so a late backstop does not copy twice. */
    fun slotId(now: ZonedDateTime): String {
        val evening = at(now, EVENING)
        val midnight = at(now, NIGHT)
        val slot = if (!now.isBefore(evening)) evening else midnight
        return slot.format(slotFormat)
    }

    fun alreadyDone(completedSlot: String?, slot: String, manual: Boolean): Boolean =
        !manual && completedSlot != null && completedSlot == slot

    private fun at(now: ZonedDateTime, hour: Int): ZonedDateTime =
        now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
}
