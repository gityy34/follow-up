package com.followup.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val UPCOMING = "upcoming_followups"
private const val OVERDUE = "overdue_followups"

internal class Reminders(private val context: Context, private val repo: Repository) {
    private val reconciliation = Mutex()
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val ids by lazy { context.getSharedPreferences("reminder_cache", Context.MODE_PRIVATE) }

    fun canNotify(): Boolean = (Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        notifications.areNotificationsEnabled()

    fun canUseExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()

    fun install() {
        notifications.createNotificationChannels(listOf(
            NotificationChannel(UPCOMING, "Upcoming Follow-ups", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(OVERDUE, "Overdue Follow-ups", NotificationManager.IMPORTANCE_DEFAULT)
        ))
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("reconcile-daily", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReconcileWorker>(12, TimeUnit.HOURS).build())
        request()
    }

    fun request() {
        WorkManager.getInstance(context).enqueueUniqueWork("reconcile-reminders", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ReconcileWorker>().build())
    }

    private fun identity(id: String, operation: String) = Uri.Builder().scheme("youfollowups").authority(operation).appendPath(id).build()

    private fun broadcast(id: String, operation: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setData(identity(id, operation)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun open(id: String) = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).setData(identity(id, "open")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @SuppressLint("ScheduleExactAlarm")
    private fun schedule(id: String, operation: String, at: Long, exact: Boolean): Boolean {
        val pending = broadcast(id, operation)
        if (exact && canUseExact()) {
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                return true
            } catch (_: SecurityException) {
                // Exact access can be revoked between the check and scheduling.
            }
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        return false
    }

    private fun cancel(id: String) {
        alarm.cancel(broadcast(id, "upcoming"))
        alarm.cancel(broadcast(id, "overdue"))
        notifications.cancel(id, 1)
    }

    suspend fun reconcile() = reconciliation.withLock {
        repo.applyRestoreSettings()
        val snapshot = repo.snapshot()
        val eligible = snapshot.customers.filter { !it.archived && !it.demo }.mapTo(hashSetOf()) { it.id }
        val on = snapshot.settings.notifications && canNotify()
        val tracked = hashSetOf<String>()
        val original = ids.getStringSet("scheduled", emptySet()).orEmpty().toSet()
        for (row in snapshot.followUps) {
            val time = now()
            val planned = reminderTime(row, snapshot.settings.defaultReminder)
            if (!on || row.status != FollowStatus.OPEN || row.customerId !in eligible || planned == null) {
                cancel(row.id)
                repo.setReminderCache(row.id, row.dueAt, row.reminderPolicy, null, if (on && planned != null) "inactive" else "off")
                continue
            }
            tracked += row.id
            val snoozed = row.snoozedUntil
            val upcomingAt = (snoozed ?: planned).takeIf { row.alertedAt == null && it < row.dueAt && time < row.dueAt }
            if (upcomingAt == null) alarm.cancel(broadcast(row.id, "upcoming"))
            if (row.overdueNotifiedAt == null) {
                schedule(row.id, "overdue", maxOf(row.dueAt, snoozed ?: 0, time + 1_000L), false)
            } else alarm.cancel(broadcast(row.id, "overdue"))
            if (upcomingAt != null) {
                val next = maxOf(upcomingAt, time + 1_500L)
                repo.setReminderCache(row.id, row.dueAt, row.reminderPolicy, next,
                    if (row.timeCritical && canUseExact()) "exact" else "best effort")
                val exact = schedule(row.id, "upcoming", next, row.timeCritical)
                if (row.timeCritical && !exact) repo.setReminderCache(row.id, row.dueAt, row.reminderPolicy, next, "best effort")
            } else repo.setReminderCache(row.id, row.dueAt, row.reminderPolicy, null,
                if (row.alertedAt != null) "delivered" else "pending")
        }
        (original - tracked).forEach(::cancel)
        ids.edit().putStringSet("scheduled", tracked).apply()
    }

    @SuppressLint("MissingPermission")
    suspend fun deliver(id: String, operation: String) {
        if (operation == "done" || operation == "snooze") {
            notifications.cancel(id, 1)
            if (operation == "done") repo.changeFollowStatus(id, FollowStatus.DONE, FollowStatus.OPEN)
            else repo.snooze(id)
            request()
            return
        }
        val overdue = operation == "overdue"
        if (!canNotify() || !repo.settings.current().notifications) return
        val follow = repo.claimAlert(id, overdue, now()) ?: return
        val customer = repo.dao.customer(follow.customerId) ?: return
        val notification = Notification.Builder(context, if (overdue) OVERDUE else UPCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (overdue) "Overdue follow-up" else "Upcoming follow-up")
            .setContentText("${customer.displayName} · ${follow.purpose}")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open(id)).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Open", open(id)).build())
            .addAction(Notification.Action.Builder(null, "Mark Done", broadcast(id, "done")).build())
            .addAction(Notification.Action.Builder(null, "Snooze 15 min", broadcast(id, "snooze")).build())
            .build()
        notifications.notify(id, 1, notification)
    }
}

class ReconcileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as JourneyApp).reminders.reconcile()
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val action = intent.data?.host?.takeIf { it in setOf("upcoming", "overdue", "done", "snooze") } ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { (context.applicationContext as JourneyApp).reminders.deliver(id, action) }
            finally { pending.finish() }
        }
    }
}

class ScheduleChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_DATE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" ->
                (context.applicationContext as JourneyApp).reminders.request()
        }
    }
}
