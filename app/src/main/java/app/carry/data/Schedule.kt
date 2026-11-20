package app.carry.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.carry.CarryApp
import app.carry.MainActivity
import app.carry.R
import app.carry.core.sync.SyncClock
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** The saved copy times, readable from an alarm without opening the settings store. */
object ScheduleMirror {
    private const val FILE = "carry_schedule"
    private const val KEY = "sync_minutes"

    fun read(context: Context): List<Int> {
        val prefs = prefs(context)
        if (!prefs.contains(KEY)) return SyncClock.usual
        val raw = prefs.getString(KEY, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return SyncClock.normalize(raw.split(',').mapNotNull { it.toIntOrNull() })
    }

    fun write(context: Context, minutes: List<Int>) {
        val clean = SyncClock.normalize(minutes)
        prefs(context).edit().putString(KEY, clean.joinToString(",")).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/**
 * One alarm for the next saved time, plus a quiet WorkManager backstop.
 * The backstop does nothing when that slot already finished, so a phone that slept through
 * the alarm still copies once, and a phone that did not does not copy twice.
 */
object CarrySchedule {
    private const val WORK = "carry-sync"
    private const val BACKSTOP = "carry-sync-backstop"
    const val CHANNEL = "carry-sync"
    private const val MANUAL = "manual"

    fun arm(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = pending(context)
        val next = SyncClock.nextAlarm(ZonedDateTime.now(), ScheduleMirror.read(context))
        if (next == null) {
            alarms.cancel(pending)
            WorkManager.getInstance(context).cancelUniqueWork(BACKSTOP)
            return
        }
        val at = next.toInstant().toEpochMilli()
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
        ensureBackstop(context)
    }

    fun enqueue(context: Context, manual: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setInputData(workDataOf(MANUAL to manual))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK,
            if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun channel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "Copies", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Carry is copying photos and videos."
        manager.createNotificationChannel(channel)
    }

    private fun ensureBackstop(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setInputData(workDataOf(MANUAL to false))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BACKSTOP,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 18, intent, flags)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        CarrySchedule.enqueue(context, manual = false)
        CarrySchedule.arm(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        CarrySchedule.channel(context)
        CarrySchedule.arm(context)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Copying photos and videos")

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        val manual = inputData.getBoolean("manual", false)
        val app = applicationContext as CarryApp
        return app.container.syncer.run(manual)
    }

    private fun foreground(text: String): ForegroundInfo {
        val open = PendingIntent.getActivity(
            applicationContext,
            1,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, CarrySchedule.CHANNEL)
            .setContentTitle("Carry")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(42, notification)
        }
    }
}
