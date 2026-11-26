package com.followup.tracker

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

internal fun uuid() = UUID.randomUUID().toString()
internal fun now() = System.currentTimeMillis()
internal fun zone() = ZoneId.systemDefault()

enum class Outcome(val title: String) {
    RESOLVED("Successful / Resolved"), FOLLOW_UP("Follow-up Needed"), HANG_UP("Hang-up"),
    NO_ANSWER("No Answer"), ISSUE("Issue / Escalation")
}

enum class Priority(val title: String) { NORMAL("Normal"), HIGH("High priority"), URGENT("Urgent") }
enum class Stage(val title: String) { NEW("New"), CONTACTED("Contacted"), FOLLOW_UP("Follow-up"), RESOLVED("Resolved") }
enum class FollowStatus { OPEN, DONE, DELETED }
enum class Reminder(val title: String) {
    DEFAULT("Default"), DAY("One day before"), SAME_DAY("Same day at 9 AM"), HOUR("One hour before"),
    SHORT("15 minutes before"), CUSTOM("Custom date and time"), OFF("Off")
}
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey val id: String = uuid(),
    val displayName: String,
    val phoneNumber: String,
    val email: String = "",
    val company: String = "",
    val tags: String = "",
    val priority: Priority = Priority.NORMAL,
    val stage: Stage = Stage.NEW,
    val notes: String = "",
    val archived: Boolean = false,
    val demo: Boolean = false,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt
)

@Entity(
    tableName = "followups",
    foreignKeys = [ForeignKey(entity = Customer::class, parentColumns = ["id"], childColumns = ["customerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("customerId"), Index("dueAt"), Index(value = ["status", "dueAt"])]
)
data class FollowUp(
    @PrimaryKey val id: String = uuid(),
    val customerId: String,
    val purpose: String,
    val dueAt: Long,
    val timezoneId: String = zone().id,
    val reminderPolicy: Reminder = Reminder.DEFAULT,
    val customReminderAt: Long? = null,
    val timeCritical: Boolean = false,
    val reminderScheduledAt: Long? = null,
    val reminderStatus: String = "pending",
    val alertedAt: Long? = null,
    val overdueNotifiedAt: Long? = null,
    val snoozedUntil: Long? = null,
    val status: FollowStatus = FollowStatus.OPEN,
    val priority: Priority = Priority.NORMAL,
    val note: String = "",
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt
)

@Entity(
    tableName = "calls",
    foreignKeys = [
        ForeignKey(entity = Customer::class, parentColumns = ["id"], childColumns = ["customerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FollowUp::class, parentColumns = ["id"], childColumns = ["followUpId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("customerId"), Index("followUpId"), Index("endedAt")]
)
data class CallLog(
    @PrimaryKey val id: String = uuid(),
    val customerId: String,
    val startedAt: Long,
    val endedAt: Long,
    val outcome: Outcome,
    val issueCategory: String = "",
    val summaryNote: String = "",
    val reflection: String = "",
    val followUpId: String? = null,
    val createdAt: Long = now()
)

@Entity(
    tableName = "timeline",
    foreignKeys = [ForeignKey(entity = Customer::class, parentColumns = ["id"], childColumns = ["customerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["customerId", "eventTime"])]
)
data class TimelineEvent(
    @PrimaryKey val id: String = uuid(),
    val customerId: String,
    val eventType: String,
    val referenceId: String = "",
    val eventTime: Long = now(),
    val displayPayload: String
)

@Entity(tableName = "restore_journal")
data class RestoreJournal(@PrimaryKey val id: Int = 1, val settingsJson: String)

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val palette: String = "Violet",
    val notifications: Boolean = false,
    val defaultReminder: Reminder = Reminder.DAY,
    val appLock: Boolean = false,
    val lockAfter: Int = 1,
    val privateRecents: Boolean = false,
    val goal: Int = 5,
    val retentionDays: Int = 90,
    val lastExport: Long = 0,
    val exportLabel: String = "",
    val onboardingDone: Boolean = false
)

data class Workspace(
    val customers: List<Customer> = emptyList(),
    val followUps: List<FollowUp> = emptyList(),
    val calls: List<CallLog> = emptyList(),
    val timeline: List<TimelineEvent> = emptyList(),
    val settings: AppSettings = AppSettings()
)

fun stageAfter(outcome: Outcome) = when (outcome) {
    Outcome.RESOLVED -> Stage.RESOLVED
    Outcome.FOLLOW_UP, Outcome.ISSUE -> Stage.FOLLOW_UP
    Outcome.HANG_UP, Outcome.NO_ANSWER -> Stage.CONTACTED
}

fun validCustomer(name: String, phone: String): Boolean =
    name.trim().length in 1..100 && phone.length <= 40 &&
        (phone.isBlank() || (phone.count(Char::isDigit) >= 3 && phone.all { it.isDigit() || it in "+()- .#" }))

fun reminderTime(row: FollowUp, default: Reminder): Long? {
    val due = Instant.ofEpochMilli(row.dueAt).atZone(ZoneId.of(row.timezoneId))
    val rule = if (row.reminderPolicy == Reminder.DEFAULT) default else row.reminderPolicy
    return when (rule) {
        Reminder.DAY, Reminder.DEFAULT -> due.minusDays(1).toInstant().toEpochMilli()
        Reminder.SAME_DAY -> due.toLocalDate().atTime(9, 0).atZone(due.zone).toInstant().toEpochMilli().coerceAtMost(row.dueAt)
        Reminder.HOUR -> due.toInstant().minus(1, ChronoUnit.HOURS).toEpochMilli()
        Reminder.SHORT -> due.toInstant().minus(15, ChronoUnit.MINUTES).toEpochMilli()
        Reminder.CUSTOM -> row.customReminderAt?.takeIf { it <= row.dueAt }
        Reminder.OFF -> null
    }
}

data class Report(
    val calls: Int, val countByOutcome: Map<Outcome, Int>, val completed: Int, val scheduled: Int,
    val issues: Map<String, Int>, val bestHour: Int?, val trend: List<Pair<String, Int>>,
    val tips: List<String>
) {
    val resolved get() = countByOutcome[Outcome.RESOLVED] ?: 0
    val successRate get() = if (calls == 0) 0 else 100 * resolved / calls
    val completionRate get() = if (scheduled == 0) 0 else 100 * completed / scheduled
}

fun report(workspace: Workspace, days: Int, time: Long, timezone: ZoneId, demo: Boolean = false): Report {
    val endDate = Instant.ofEpochMilli(time).atZone(timezone).toLocalDate()
    val first = endDate.minusDays(days.toLong() - 1)
    val start = first.atStartOfDay(timezone).toInstant().toEpochMilli()
    val end = endDate.plusDays(1).atStartOfDay(timezone).toInstant().toEpochMilli()
    val real = workspace.customers.filter { it.demo == demo }.mapTo(hashSetOf()) { it.id }
    val calls = workspace.calls.filter { it.customerId in real && it.endedAt in start until end }
    val due = workspace.followUps.filter { it.customerId in real && it.dueAt in start until end && it.status != FollowStatus.DELETED }
    val outcomes = calls.groupingBy { it.outcome }.eachCount()
    val issues = calls.filter { it.outcome == Outcome.ISSUE }.groupingBy { it.issueCategory.ifBlank { "Uncategorized" } }.eachCount()
    val best = calls.filter { it.outcome == Outcome.RESOLVED }.groupingBy {
        Instant.ofEpochMilli(it.startedAt).atZone(timezone).hour
    }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key
    val completed = due.count { it.status == FollowStatus.DONE }
    val countByDate = calls.groupingBy { Instant.ofEpochMilli(it.endedAt).atZone(timezone).toLocalDate() }.eachCount()
    val trend = (0 until days).map { first.plusDays(it.toLong()) }.map {
        it.format(java.time.format.DateTimeFormatter.ofPattern(if (days <= 7) "EEE" else "MMM d", java.util.Locale.ENGLISH)) to (countByDate[it] ?: 0)
    }
    val tips = buildList {
        if (due.any { it.status == FollowStatus.OPEN && it.dueAt < time }) add("Start with overdue follow-ups to clear the oldest commitments.")
        if ((outcomes[Outcome.NO_ANSWER] ?: 0) + (outcomes[Outcome.HANG_UP] ?: 0) > calls.size / 3) add("Try a different calling window for customers you could not reach.")
        issues.maxByOrNull { it.value }?.let { add("Prepare a short response for the most common issue: ${it.key}.") }
        best?.let { add("Use your strongest calling hour for high-priority customers.") }
        if (isEmpty()) add("Log each outcome and write one clear next action after every call.")
    }.take(3)
    return Report(calls.size, outcomes, completed, due.size, issues, best, trend, tips)
}
