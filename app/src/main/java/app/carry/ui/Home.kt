package app.carry.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.carry.AppContainer
import app.carry.BuildConfig
import app.carry.data.CarrySchedule
import app.carry.data.MediaAccess
import app.carry.core.sync.SyncClock
import app.carry.core.text.WhenText
import java.time.ZoneId

@Composable
fun HomeScreen(container: AppContainer, navigator: Navigator) {
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val live by container.syncer.live.collectAsStateWithLifecycle()
    val work by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow("carry-sync")
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val destination = settings.active()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val waiting = work.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    val running = live.running || work.any { it.state == WorkInfo.State.RUNNING }
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Wordmark(48.sp)
        Text("the phone, copied home.", color = Dim, fontSize = 16.sp)
        if (destination == null) {
            Text("No destination yet.", color = Mist, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Pill("Find a destination") { navigator.push(Route.Find) }
        } else {
            Text(destination.label, color = Mist, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text(
                "${destination.host} · ${destination.share}" +
                    if (destination.folder.isBlank()) "" else "/${destination.folder}",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
            Text(
                if (SyncClock.normalize(settings.syncMinutes).isEmpty()) {
                    "Copies when you tap Sync now, on Wi-Fi. Files already there are left alone."
                } else {
                    "Copies on its own at ${SyncClock.label(settings.syncMinutes)}, on Wi-Fi. Files already there are left alone."
                },
                color = Dim,
                fontSize = 16.sp,
            )
        }
        if (!MediaAccess.granted(context)) {
            Text("Carry cannot see photos and videos until you allow it.", color = Gold, fontSize = 16.sp)
            Pill("Allow photos and videos") { permission.launch(MediaAccess.permissions()) }
        }
        if (destination != null) {
            Pill(if (running) "Copying" else "Sync now") {
                if (running) return@Pill
                if (!MediaAccess.granted(context)) {
                    permission.launch(MediaAccess.permissions())
                    return@Pill
                }
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                CarrySchedule.enqueue(context, manual = true)
            }
        }
        when {
            live.running && live.total > 0 -> Text(
                "${live.line} · ${live.copied} of ${live.total}",
                color = Sky,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
            live.running -> Text(live.line, color = Sky, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            waiting -> Text("Waiting for Wi-Fi.", color = Sky, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
        settings.lastSync?.let { report ->
            Text(
                "Last copy · ${WhenText.moment(report.atEpochMs, ZoneId.systemDefault())}",
                color = Mist,
                fontSize = 16.sp,
            )
            Text(report.note, color = Dim, fontSize = 16.sp)
            if (report.copied > 0 || report.alreadyThere > 0 || report.older > 0) {
                Text(
                    "${report.copied} new · ${report.alreadyThere} already there · ${report.older} older than the start date",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
        }
        Pill("Settings") { navigator.push(Route.Settings) }
        Text(
            "Carry ${BuildConfig.VERSION_NAME}",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}
