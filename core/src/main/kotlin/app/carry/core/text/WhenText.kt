package app.carry.core.text

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object WhenText {
    fun moment(epochMs: Long, zone: ZoneId, locale: Locale = Locale.UK): String {
        val format = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", locale)
        return Instant.ofEpochMilli(epochMs).atZone(zone).format(format)
    }

    fun day(epochDay: Long, locale: Locale = Locale.UK): String {
        val format = DateTimeFormatter.ofPattern("d MMMM yyyy", locale)
        return LocalDate.ofEpochDay(epochDay).format(format)
    }
}
