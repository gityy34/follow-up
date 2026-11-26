package com.followup.tracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    @Test fun transactionalRecordsReopenAndKeepHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-${uuid()}.db"
        val settings = SettingsStore(context)
        fun open() = Room.databaseBuilder(context, FollowDatabase::class.java, name).build()
        val owner = Customer(displayName = "Instrumented test", phoneNumber = "")
        var database = open()
        val repo = Repository(database, settings)
        try {
            repo.saveCustomer(owner)
            repeat(120) { repo.saveFollowUp(FollowUp(customerId = owner.id, purpose = "Test $it", dueAt = now() + it)) }
            val follow = repo.dao.allFollowUps().first()
            for (outcome in Outcome.entries) {
                repo.logCall(CallLog(customerId = owner.id, startedAt = now(), endedAt = now(), outcome = outcome, followUpId = follow.id,
                    dealAmount = "999999999999999999.123".takeIf { outcome == Outcome.RESOLVED },
                    dealCurrency = "PKR".takeIf { outcome == Outcome.RESOLVED }))
            }
            repo.changeFollowStatus(follow.id, FollowStatus.DELETED)
            repo.changeFollowStatus(follow.id, FollowStatus.OPEN, FollowStatus.DELETED)
            val pending = repo.dao.allCalls().single { it.outcome == Outcome.PENDING }
            repo.resolvePending(pending.id, Outcome.RESOLVED, "200.50", "USD")
            try { repo.resolvePending(pending.id, Outcome.CANCELLED, null, null); fail("A resolved pending call is final") }
            catch (_: IllegalStateException) {}
            repo.changeFollowStatus(follow.id, FollowStatus.OPEN)
            val snapshot = repo.snapshot()
            val rollback = snapshot.copy(calls = snapshot.calls + snapshot.calls.first())
            try { repo.replace(rollback); fail("Duplicate insert must fail") } catch (_: android.database.sqlite.SQLiteConstraintException) {}
            assertEquals(snapshot, repo.snapshot())
            database.close()
            database = open()
            val recovered = Repository(database, settings).snapshot()
            assertEquals(120, recovered.followUps.size)
            assertEquals(Outcome.entries.size, recovered.calls.size)
            assertEquals("200.50", recovered.calls.single { it.id == pending.id }.dealAmount)
            assertEquals("USD", recovered.calls.single { it.id == pending.id }.dealCurrency)
            assertEquals(snapshot.timeline.toSet(), recovered.timeline.toSet())
            assertEquals(FollowStatus.OPEN, recovered.followUps.first { it.id == follow.id }.status)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
