package app.carry.data

import android.content.Context
import androidx.work.ListenableWorker.Result as WorkResult
import app.carry.core.model.SyncReport
import app.carry.core.sync.BackupPaths
import app.carry.core.sync.BackupPlan
import app.carry.core.sync.MediaFilter
import app.carry.core.sync.SyncClock
import app.carry.core.text.Quiet
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SyncLive(
    val running: Boolean = false,
    val line: String = "",
    val copied: Int = 0,
    val total: Int = 0,
)

class Syncer(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val vault: VaultStore,
    private val media: MediaIndex,
    private val smb: SmbDestination,
) {
    private val gate = Mutex()
    private val liveState = MutableStateFlow(SyncLive())
    val live: StateFlow<SyncLive> = liveState.asStateFlow()

    suspend fun run(manual: Boolean): WorkResult = gate.withLock {
        withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            val settings = settingsStore.read()
            val slot = SyncClock.slotId(now, settings.syncMinutes)
            if (!manual && slot == null) return@withContext WorkResult.success()
            if (slot != null && SyncClock.alreadyDone(settings.completedSlot, slot, manual)) {
                return@withContext WorkResult.success()
            }
            val destination = settings.active()
            if (destination == null) return@withContext WorkResult.success()
            if (!MediaAccess.granted(context)) {
                remember(settingsStore, slot, report(0, 0, 0, 0, "Photos permission is off."), markSlot = false)
                return@withContext WorkResult.success()
            }
            val password = vault.password(destination.id)
            liveState.value = SyncLive(running = true, line = "Reading this phone")
            try {
                val scanned = media.scan()
                val keep = scanned.filter { MediaFilter.kept(it.takenEpochMs, settings.startFromEpochDay, zone) }
                val older = scanned.size - keep.size
                if (keep.isEmpty()) {
                    val note = if (older > 0) "Nothing on or after the start date." else "Nothing new."
                    remember(settingsStore, slot, report(0, 0, older, 0, note), markSlot = !manual)
                    return@withContext WorkResult.success()
                }
                val wanted = keep.map { BackupPaths.relative(it, settings.layout, zone) }
                liveState.value = SyncLive(running = true, line = "Checking the drive", total = keep.size)
                var copied = 0
                var failed = 0
                var already = 0
                var lastError: String? = null
                smb.session(destination, password) { io ->
                    val remote = io.list(BackupPaths.parentsOf(wanted))
                    val plan = BackupPlan.plan(keep, remote, settings.layout, zone)
                    already = plan.alreadyThere
                    liveState.value = liveState.value.copy(total = plan.copies.size, line = "Copying")
                    plan.copies.forEachIndexed { index, item ->
                        liveState.value = liveState.value.copy(
                            line = item.media.name,
                            copied = copied,
                            total = plan.copies.size,
                        )
                        val stream = media.open(item.media.key)
                        if (stream == null) {
                            failed++
                            lastError = "Could not read ${item.media.name}."
                            return@forEachIndexed
                        }
                        try {
                            io.put(item.relativePath, stream)
                            copied++
                        } catch (_: AlreadyThere) {
                            already++
                        } catch (error: Exception) {
                            failed++
                            lastError = explain(error, listOfNotNull(password))
                        }
                        if (index % 5 == 0) {
                            liveState.value = liveState.value.copy(copied = copied)
                        }
                    }
                }
                val note = when {
                    failed > 0 && copied == 0 -> lastError ?: "The copy did not finish."
                    failed > 0 -> "Copied $copied. $failed did not finish."
                    copied == 0 -> "Nothing new."
                    else -> "Copied $copied."
                }
                val finished = failed == 0 || copied > 0
                remember(
                    settingsStore,
                    slot,
                    report(copied, already, older, failed, note),
                    markSlot = finished && !manual,
                )
                if (!finished) WorkResult.retry() else WorkResult.success()
            } catch (error: Exception) {
                val note = explain(error, listOfNotNull(password))
                remember(settingsStore, slot, report(0, 0, 0, 1, note), markSlot = false)
                WorkResult.retry()
            } finally {
                liveState.value = SyncLive()
            }
        }
    }
}

private suspend fun remember(store: SettingsStore, slot: String?, report: SyncReport, markSlot: Boolean) {
    store.update { current ->
        current.copy(
            lastSync = report,
            completedSlot = if (markSlot && slot != null) slot else current.completedSlot,
        )
    }
}

private fun report(copied: Int, already: Int, older: Int, failed: Int, note: String) = SyncReport(
    atEpochMs = System.currentTimeMillis(),
    copied = copied,
    alreadyThere = already,
    older = older,
    failed = failed,
    note = note,
)

fun explain(error: Throwable, secrets: Collection<String> = emptyList()): String {
    val raw = generateSequence(error) { it.cause }.mapNotNull { it.message }.firstOrNull { it.isNotBlank() }.orEmpty()
    val message = Quiet.redact(raw, secrets)
    return when {
        error is CarryException -> message.ifBlank { "Something went wrong." }
        message.contains("LOGON_FAILURE", true) -> "That name or password was refused."
        message.contains("STATUS_ACCESS_DENIED", true) -> "This account cannot open that folder."
        message.contains("BAD_NETWORK_NAME", true) -> "That share name is not on this drive."
        message.contains("timed out", true) || message.contains("timeout", true) -> "The drive did not answer in time."
        message.contains("Connection refused", true) || message.contains("ECONNREFUSED", true) ->
            "Nothing is sharing files at that address."
        message.contains("UnknownHost", true) || message.contains("unreachable", true) ->
            "That address could not be found."
        message.isBlank() -> "Something went wrong."
        else -> message.lineSequence().first().take(220)
    }
}
