package com.followup.tracker

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.*
import org.json.JSONObject

@Dao
internal interface FollowDao {
    @Query("SELECT * FROM customers ORDER BY updatedAt DESC") fun customers(): Flow<List<Customer>>
    @Query("SELECT * FROM followups ORDER BY dueAt ASC, priority DESC") fun followUps(): Flow<List<FollowUp>>
    @Query("SELECT * FROM calls ORDER BY endedAt DESC") fun calls(): Flow<List<CallLog>>
    @Query("SELECT * FROM timeline ORDER BY eventTime DESC") fun timeline(): Flow<List<TimelineEvent>>
    @Query("SELECT * FROM customers WHERE id = :id") suspend fun customer(id: String): Customer?
    @Query("SELECT * FROM followups WHERE id = :id") suspend fun followUp(id: String): FollowUp?
    @Query("SELECT * FROM followups") suspend fun allFollowUps(): List<FollowUp>
    @Query("SELECT * FROM customers") suspend fun allCustomers(): List<Customer>
    @Query("SELECT * FROM calls") suspend fun allCalls(): List<CallLog>
    @Query("SELECT * FROM calls WHERE id = :id") suspend fun call(id: String): CallLog?
    @Query("SELECT id FROM calls WHERE customerId = :customerId ORDER BY endedAt DESC, createdAt DESC, id DESC LIMIT 1")
    suspend fun latestCallId(customerId: String): String?
    @Query("SELECT * FROM timeline") suspend fun allTimeline(): List<TimelineEvent>
    @Query("SELECT * FROM restore_journal WHERE id = 1") suspend fun journal(): RestoreJournal?
    @Upsert suspend fun put(customer: Customer)
    @Upsert suspend fun put(followUp: FollowUp)
    @Upsert suspend fun put(journal: RestoreJournal)
    @Query("UPDATE calls SET followUpId = :followUp WHERE id = :call AND customerId = :customer")
    suspend fun linkCall(call: String, customer: String, followUp: String)
    @Insert suspend fun insert(call: CallLog)
    @Update suspend fun update(call: CallLog)
    @Insert suspend fun insert(event: TimelineEvent)
    @Query("DELETE FROM customers") suspend fun clearRecords()
    @Query("DELETE FROM customers WHERE demo = 1") suspend fun removeDemo()
    @Query("DELETE FROM followups WHERE status = 'DELETED' AND updatedAt < :before") suspend fun purgeDeleted(before: Long): Int
    @Query("DELETE FROM restore_journal WHERE id = 1") suspend fun clearJournal()
}

@Database(entities = [Customer::class, FollowUp::class, CallLog::class, TimelineEvent::class, RestoreJournal::class],
    version = 3, exportSchema = true)
internal abstract class FollowDatabase : RoomDatabase() {
    abstract val dao: FollowDao

    companion object {
        val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS restore_journal (id INTEGER NOT NULL PRIMARY KEY, settingsJson TEXT NOT NULL)")
            }
        }
        val migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calls ADD COLUMN dealAmount TEXT")
                db.execSQL("ALTER TABLE calls ADD COLUMN dealCurrency TEXT")
                db.execSQL("ALTER TABLE calls ADD COLUMN outcomeAt INTEGER")
            }
        }
    }
}

private val Context.settingsStore by preferencesDataStore("journey")

internal class SettingsStore(context: Context) {
    private val data = context.settingsStore
    private val key = stringPreferencesKey("app_preferences")
    val flow = data.data.map { settingsFromJson(JSONObject(it[key] ?: "{}")) }
    suspend fun current() = flow.first()
    suspend fun set(value: AppSettings) {
        data.edit { it[key] = settingsJson(value).toString() }
    }
}

internal class Repository(private val database: FollowDatabase, val settings: SettingsStore) {
    val dao = database.dao
    val workspace = combine(dao.customers(), dao.followUps(), dao.calls(), dao.timeline(), settings.flow) {
            customers, followUps, calls, timeline, preferences ->
        Workspace(customers, followUps, calls, timeline, preferences)
    }

    private suspend fun event(customer: String, kind: String, reference: String, payload: String) {
        dao.insert(TimelineEvent(customerId = customer, eventType = kind, referenceId = reference, displayPayload = payload))
    }

    suspend fun saveCustomer(customer: Customer) = database.withTransaction {
        require(validCustomer(customer.displayName, customer.phoneNumber)) { "Enter a name and a valid phone number." }
        val old = dao.customer(customer.id)
        dao.put(customer.copy(displayName = customer.displayName.trim(), phoneNumber = customer.phoneNumber.trim(), updatedAt = now()))
        when {
            old == null -> event(customer.id, "Customer added", customer.id, customer.displayName)
            old.stage != customer.stage -> event(customer.id, "Status updated", customer.id, customer.stage.title)
            old.archived != customer.archived -> event(customer.id, if (customer.archived) "Archived" else "Restored", customer.id, customer.displayName)
            old.notes != customer.notes -> event(customer.id, "Note updated", customer.id, customer.notes)
            else -> event(customer.id, "Customer updated", customer.id, customer.displayName)
        }
    }

    suspend fun saveFollowUp(follow: FollowUp, sourceCallId: String? = null) = database.withTransaction {
        require(follow.purpose.isNotBlank() && follow.purpose.length <= 120) { "Enter a follow-up purpose." }
        require(follow.reminderPolicy != Reminder.CUSTOM || reminderTime(follow, Reminder.DAY) != null) { "Set a custom reminder at or before the due time." }
        val customer = requireNotNull(dao.customer(follow.customerId)) { "Customer is missing." }
        check(!customer.archived) { "Restore this customer before scheduling." }
        val old = dao.followUp(follow.id)
        val timingChanged = old == null || old.dueAt != follow.dueAt || old.timezoneId != follow.timezoneId ||
            old.customReminderAt != follow.customReminderAt || old.reminderPolicy != follow.reminderPolicy
        val row = if (timingChanged) follow.copy(reminderScheduledAt = null, reminderStatus = "pending",
            alertedAt = null, overdueNotifiedAt = null, snoozedUntil = null) else follow
        dao.put(row.copy(updatedAt = now()))
        if (sourceCallId != null) dao.linkCall(sourceCallId, customer.id, row.id)
        if (customer.stage != Stage.FOLLOW_UP && follow.status == FollowStatus.OPEN) {
            dao.put(customer.copy(stage = Stage.FOLLOW_UP, updatedAt = now()))
            event(customer.id, "Status updated", customer.id, Stage.FOLLOW_UP.title)
        }
        event(customer.id, if (old == null) "Follow-up scheduled" else "Follow-up updated", follow.id, follow.purpose)
    }

    suspend fun changeFollowStatus(id: String, status: FollowStatus, expected: FollowStatus? = null) = database.withTransaction {
        val row = dao.followUp(id) ?: return@withTransaction
        if (row.status == status || (expected != null && row.status != expected)) return@withTransaction
        dao.put(row.copy(status = status, reminderScheduledAt = null, reminderStatus = "pending",
            alertedAt = if (status == FollowStatus.OPEN) null else row.alertedAt,
            overdueNotifiedAt = if (status == FollowStatus.OPEN) null else row.overdueNotifiedAt,
            snoozedUntil = null, updatedAt = now()))
        val label = when (status) {
            FollowStatus.DONE -> "Follow-up completed"
            FollowStatus.DELETED -> "Follow-up deleted"
            FollowStatus.OPEN -> "Follow-up reopened"
        }
        event(row.customerId, label, row.id, row.purpose)
    }

    suspend fun logCall(call: CallLog) = database.withTransaction {
        val customer = requireNotNull(dao.customer(call.customerId)) { "Customer is missing." }
        require(call.startedAt <= call.endedAt && call.summaryNote.length <= 200 && call.reflection.length <= 200) { "Check the call details." }
        require(call.followUpId == null || dao.followUp(call.followUpId)?.customerId == call.customerId) { "This follow-up belongs to a different customer." }
        check(!customer.archived) { "Restore this customer before logging a call." }
        val saved = withOutcome(call, call.outcome, call.dealAmount, call.dealCurrency)
        dao.insert(saved)
        dao.put(customer.copy(stage = stageAfter(saved.outcome), updatedAt = now()))
        event(customer.id, "Call logged", saved.id, listOf(outcomeLabel(saved), saved.issueCategory, saved.summaryNote,
            call.reflection.takeIf { it.isNotBlank() }?.let { "Improve next time: $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
        if (call.outcome == Outcome.RESOLVED && call.followUpId != null) {
            changeFollowStatus(call.followUpId, FollowStatus.DONE, FollowStatus.OPEN)
        }
    }

    suspend fun resolvePending(id: String, outcome: Outcome, amount: String?, currency: String?) = database.withTransaction {
        require(outcome in pendingOutcomes)
        val call = requireNotNull(dao.call(id)) { "Call is missing." }
        check(call.outcome == Outcome.PENDING) { "This call already has a final result." }
        val customer = requireNotNull(dao.customer(call.customerId))
        val saved = withOutcome(call, outcome, amount, currency)
        dao.update(saved)
        // Only the latest call controls the customer's current stage.
        if (dao.latestCallId(customer.id) == id) {
            dao.put(customer.copy(stage = stageAfter(outcome), updatedAt = now()))
        }
        event(customer.id, "Pending call updated", call.id, outcomeLabel(saved))
        if (outcome == Outcome.RESOLVED && call.followUpId != null) {
            changeFollowStatus(call.followUpId, FollowStatus.DONE, FollowStatus.OPEN)
        }
    }

    private fun withOutcome(call: CallLog, outcome: Outcome, amount: String?, currency: String?): CallLog {
        val normalized = if (outcome == Outcome.RESOLVED) requireNotNull(amount?.let(::normalizeAmount)) {
            "Enter a valid deal amount."
        } else null
        require(normalized == null || currency?.let(::validCurrency) == true) { "Choose a valid currency." }
        return call.copy(outcome = outcome, dealAmount = normalized, dealCurrency = currency.takeIf { normalized != null },
            outcomeAt = maxOf(now(), call.endedAt))
    }

    private fun outcomeLabel(call: CallLog) =
        if (call.dealAmount == null) call.outcome.title else "${call.outcome.title} · ${call.dealCurrency} ${call.dealAmount}"

    suspend fun setReminderCache(id: String, dueAt: Long, policy: Reminder, next: Long?, status: String) = database.withTransaction {
        val row = dao.followUp(id) ?: return@withTransaction
        if (row.dueAt == dueAt && row.reminderPolicy == policy &&
            (row.reminderScheduledAt != next || row.reminderStatus != status)) {
            dao.put(row.copy(reminderScheduledAt = next, reminderStatus = status))
        }
    }

    suspend fun claimAlert(id: String, overdue: Boolean, at: Long): FollowUp? = database.withTransaction {
        val row = dao.followUp(id) ?: return@withTransaction null
        val customer = dao.customer(row.customerId) ?: return@withTransaction null
        if (row.status != FollowStatus.OPEN || customer.archived || customer.demo) return@withTransaction null
        val valid = if (overdue) row.dueAt <= at && row.overdueNotifiedAt == null && (row.snoozedUntil ?: 0) <= at
        else row.dueAt > at && row.reminderScheduledAt != null && row.reminderScheduledAt <= at && row.alertedAt == null
        if (!valid) return@withTransaction null
        dao.put(if (overdue) row.copy(overdueNotifiedAt = at, snoozedUntil = null)
                else row.copy(alertedAt = at, reminderStatus = "delivered", snoozedUntil = null))
        row
    }

    suspend fun snooze(id: String) = database.withTransaction {
        val row = dao.followUp(id) ?: return@withTransaction
        if (row.status == FollowStatus.OPEN) {
            dao.put(row.copy(snoozedUntil = now() + 15 * 60_000, alertedAt = null, overdueNotifiedAt = null, reminderStatus = "pending"))
        }
    }

    suspend fun snapshot() = database.withTransaction {
        Workspace(dao.allCustomers(), dao.allFollowUps(), dao.allCalls(), dao.allTimeline(), settings.current())
    }

    suspend fun replace(value: Workspace) = database.withTransaction {
        dao.clearRecords()
        value.customers.forEach { dao.put(it) }
        value.followUps.forEach { dao.put(it.copy(reminderScheduledAt = null, reminderStatus = "pending")) }
        value.calls.forEach { dao.insert(it) }
        value.timeline.forEach { dao.insert(it) }
        dao.put(RestoreJournal(settingsJson = settingsJson(value.settings).toString()))
    }

    suspend fun applyRestoreSettings() = database.withTransaction {
        val journal = dao.journal() ?: return@withTransaction
        settings.set(settingsFromJson(JSONObject(journal.settingsJson)))
        dao.clearJournal()
    }

    suspend fun importLegacy(rows: List<LegacyRow>) = database.withTransaction {
        for (row in rows) {
            val followId = legacyId("follow-up", row.id)
            if (dao.followUp(followId) != null) continue
            val customerId = legacyId("customer", row.contact)
            if (dao.customer(customerId) == null) saveCustomer(Customer(id = customerId, displayName = row.contact, phoneNumber = ""))
            saveFollowUp(FollowUp(id = followId, customerId = customerId, purpose = row.title, dueAt = row.due, note = row.note,
                status = if (row.done) FollowStatus.DONE else FollowStatus.OPEN))
        }
    }

    suspend fun seedDemo() = database.withTransaction {
        if (dao.allCustomers().any { it.demo }) return@withTransaction
        listOf("Amit Verma" to "Acme Supplies", "Neha Singh" to "Northstar Studio", "Rakesh Patel" to "Orbit Works").forEachIndexed { i, (name, company) ->
            val customer = Customer(displayName = name, phoneNumber = "", company = company, tags = "Sample",
                priority = if (i == 0) Priority.HIGH else Priority.NORMAL, demo = true)
            saveCustomer(customer)
            saveFollowUp(FollowUp(customerId = customer.id, purpose = listOf("Discuss proposal", "Confirm interest", "Share product info")[i],
                dueAt = now() + (i - 1) * 86_400_000L, note = "Example only. Demo records do not trigger reminders."))
            if (i != 2) logCall(CallLog(customerId = customer.id, startedAt = now() - 300_000, endedAt = now() - 200_000,
                outcome = if (i == 0) Outcome.PENDING else Outcome.RESOLVED, summaryNote = "Sample customer conversation.",
                dealAmount = "1250.50".takeIf { i == 1 }, dealCurrency = "PKR".takeIf { i == 1 }))
        }
    }

    suspend fun removeDemo() = database.withTransaction { dao.removeDemo() }
    suspend fun purgeDeleted(days: Int) = database.withTransaction { dao.purgeDeleted(now() - days * 86_400_000L) }
}

internal interface SyncAdapter {
    suspend fun sync(snapshot: Workspace): Workspace
}
