package app.carry.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.carry.AppContainer
import app.carry.BuildConfig
import app.carry.core.model.Layout
import app.carry.core.sync.SyncClock
import app.carry.core.text.WhenText
import app.carry.data.AppUpdate
import app.carry.data.CarrySchedule
import app.carry.data.ScheduleMirror
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, navigator: Navigator, draftPad: DraftPad) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var updateNote by remember { mutableStateOf("Installed ${BuildConfig.VERSION_NAME}") }
    var updating by remember { mutableStateOf(false) }
    var forgetId by remember { mutableStateOf<String?>(null) }
    val alarms = context.getSystemService(AlarmManager::class.java)
    val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Wordmark(40.sp)
        Text("Settings", color = Mist, fontSize = 32.sp, fontWeight = FontWeight.Black)
        Pill(if (updating) "Updating" else "Update Carry") {
            if (!updating) {
                updating = true
                updateNote = "Checking for a newer build"
                scope.launch {
                    try {
                        AppUpdate(context, container.http).installLatest { updateNote = it }
                    } catch (_: Exception) {
                        updateNote = "The update did not start."
                    } finally {
                        updating = false
                    }
                }
            }
        }
        Text(updateNote, color = Mist, fontSize = 16.sp, modifier = Modifier.fillMaxWidth())
        Text(
            "The published file is signed with the same key, so a newer build installs over this one.",
            color = Dim,
            fontSize = 14.sp,
        )
        Text("Start from date", color = Mist, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            if (settings.startFromEpochDay == null) "Everything on this phone."
            else "Skipping anything before ${WhenText.day(settings.startFromEpochDay!!)}.",
            color = Dim,
            fontSize = 16.sp,
        )
        Pill("Choose a date") {
            val initial = settings.startFromEpochDay?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now().minusYears(1)
            android.app.DatePickerDialog(
                context,
                { _, year, month, day ->
                    val picked = LocalDate.of(year, month + 1, day).toEpochDay()
                    scope.launch { container.settings.update { it.copy(startFromEpochDay = picked) } }
                },
                initial.year,
                initial.monthValue - 1,
                initial.dayOfMonth,
            ).show()
        }
        if (settings.startFromEpochDay != null) {
            Pill("Use everything") {
                scope.launch { container.settings.update { it.copy(startFromEpochDay = null) } }
            }
        }
        Text("On the drive", color = Mist, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Pill(if (settings.layout == Layout.YEAR_MONTH) "Year / month · in use" else "Year / month") {
            scope.launch { container.settings.update { it.copy(layout = Layout.YEAR_MONTH) } }
        }
        Pill(if (settings.layout == Layout.SOURCE_FOLDER) "Source folder · in use" else "Source folder") {
            scope.launch { container.settings.update { it.copy(layout = Layout.SOURCE_FOLDER) } }
        }
        Text("Copy times", color = Mist, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            if (SyncClock.normalize(settings.syncMinutes).isEmpty()) {
                "Nothing is scheduled. Sync now still works. Copies wait for Wi-Fi and do not overwrite a file that is already there."
            } else {
                "Carry copies at ${SyncClock.label(settings.syncMinutes)}, when the phone is on Wi-Fi. It does not overwrite a file that is already there."
            },
            color = Dim,
            fontSize = 16.sp,
        )
        SyncClock.normalize(settings.syncMinutes).forEach { minute ->
            Pill("Remove ${SyncClock.clock(minute)}") {
                scope.launch { saveSchedule(container, context, settings.syncMinutes.filter { it != minute }) }
            }
        }
        Pill("Add a time") {
            android.app.TimePickerDialog(
                context,
                { _, hour, minute ->
                    scope.launch { saveSchedule(container, context, settings.syncMinutes + (hour * 60 + minute)) }
                },
                18,
                0,
                true,
            ).show()
        }
        if (!exact) {
            Text("Exact alarms are off, so the copy may be a bit late. The backstop still runs.", color = Gold, fontSize = 16.sp)
            Pill("Allow exact alarms") {
                if (Build.VERSION.SDK_INT >= 31) {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:${context.packageName}")),
                    )
                }
            }
        }
        Text("Destinations", color = Mist, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (settings.destinations.isEmpty()) {
            Text("None saved.", color = Dim, fontSize = 16.sp)
        }
        settings.destinations.forEach { destination ->
            val active = destination.id == settings.active()?.id
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Text(destination.label, color = Mist, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${destination.host} · ${destination.share}" +
                        if (destination.folder.isBlank()) "" else "/${destination.folder}",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
                if (active) Text("in use", color = Good, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                if (!active) {
                    Pill("Use this") {
                        scope.launch { container.settings.update { it.copy(activeDestinationId = destination.id) } }
                    }
                }
                Pill("Change folder") {
                    draftPad.draft = Draft(
                        existingId = destination.id,
                        host = destination.host,
                        label = destination.label,
                        username = destination.username,
                        password = container.vault.password(destination.id),
                        domain = destination.domain,
                        share = destination.share,
                        folder = destination.folder,
                    )
                    navigator.push(Route.Browse)
                }
                Pill("Forget") { forgetId = destination.id }
            }
        }
        Pill("Look for destinations") { navigator.push(Route.Find) }
        Pill("Back") { navigator.back() }
    }
    forgetId?.let { id ->
        val name = settings.destinations.firstOrNull { it.id == id }?.label ?: "this destination"
        Notice(
            title = "Forget $name?",
            body = "Carry will stop copying there. Files already on the drive stay.",
            confirm = "Forget",
            dismiss = "Keep it",
            onConfirm = {
                scope.launch {
                    container.settings.update { current ->
                        val left = current.destinations.filterNot { it.id == id }
                        val active = if (current.activeDestinationId == id) left.firstOrNull()?.id else current.activeDestinationId
                        current.copy(destinations = left, activeDestinationId = active)
                    }
                    container.vault.forgetPassword(id)
                }
                forgetId = null
            },
            onDismiss = { forgetId = null },
        )
    }
}

private suspend fun saveSchedule(container: AppContainer, context: Context, minutes: List<Int>) {
    val clean = SyncClock.normalize(minutes)
    container.settings.update { it.copy(syncMinutes = clean) }
    ScheduleMirror.write(context, clean)
    CarrySchedule.arm(context)
}
