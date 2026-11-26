package com.followup.tracker

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test fun stageTransitionsAndValidation() {
        assertEquals(Stage.RESOLVED, stageAfter(Outcome.RESOLVED))
        assertEquals(Stage.FOLLOW_UP, stageAfter(Outcome.ISSUE))
        assertEquals(Stage.CONTACTED, stageAfter(Outcome.NO_ANSWER))
        assertTrue(validCustomer("Amit", "+44 (20) 1234"))
        assertFalse(validCustomer(" ", "123"))
        assertFalse(validCustomer("Amit", "call me"))
    }

    @Test fun previousDayPreservesLocalClockAcrossDst() {
        val ny = ZoneId.of("America/New_York")
        val due = ZonedDateTime.of(2026, 3, 8, 11, 0, 0, 0, ny)
        val row = FollowUp(customerId = uuid(), purpose = "Test", dueAt = due.toInstant().toEpochMilli(), timezoneId = ny.id)
        val previous = ZonedDateTime.of(2026, 3, 7, 11, 0, 0, 0, ny)
        assertEquals(previous.toInstant().toEpochMilli(), reminderTime(row, Reminder.DAY))
        assertEquals(row.dueAt - 3_600_000L, reminderTime(row, Reminder.HOUR))
        assertEquals(row.dueAt - 900_000L, reminderTime(row, Reminder.SHORT))
        assertEquals(row.dueAt - 42, reminderTime(row.copy(reminderPolicy = Reminder.CUSTOM, customReminderAt = row.dueAt - 42), Reminder.DAY))
        assertNull(reminderTime(row.copy(reminderPolicy = Reminder.CUSTOM, customReminderAt = row.dueAt + 1), Reminder.DAY))
        assertNull(reminderTime(row, Reminder.OFF))
    }

    @Test fun reportCountsOnlyRelevantRecords() {
        val tz = ZoneId.of("Asia/Karachi")
        val now = ZonedDateTime.of(2026, 11, 12, 13, 30, 0, 0, tz).toInstant().toEpochMilli()
        val customer = Customer(displayName = "Real", phoneNumber = "")
        val demo = Customer(displayName = "Demo", phoneNumber = "", demo = true)
        val raw = Workspace(listOf(customer, demo), listOf(
            FollowUp(customerId = customer.id, purpose = "Due", dueAt = now, status = FollowStatus.DONE),
            FollowUp(customerId = customer.id, purpose = "Second", dueAt = now - 3600000),
            FollowUp(customerId = customer.id, purpose = "Deleted", dueAt = now, status = FollowStatus.DELETED)
        ), listOf(
            CallLog(customerId = customer.id, startedAt = now, endedAt = now, outcome = Outcome.RESOLVED),
            CallLog(customerId = customer.id, startedAt = now, endedAt = now, outcome = Outcome.ISSUE, issueCategory = "Price"),
            CallLog(customerId = demo.id, startedAt = now, endedAt = now, outcome = Outcome.NO_ANSWER)
        ))
        val result = report(raw, ReportPeriod.LAST_7_DAYS, now, tz)
        assertEquals(2, result.calls)
        assertEquals(50, result.successRate)
        assertEquals(50, result.completionRate)
        assertEquals(1, result.issues["Price"])
        assertEquals(13, result.bestHour)
        assertEquals(2, result.trend.last().second)
    }
    @Test fun enteredAmountsRetainDecimalPrecision() {
        assertEquals("123456789012345678901234567890.123456789", normalizeAmount("123456789012345678901234567890.123456789"))
        assertEquals("1250.50", normalizeAmount("۱٬۲۵۰٫۵۰"))
        assertEquals("12.50", normalizeAmount("12,50"))
        assertEquals("0.05", normalizeAmount(".05"))
        assertNull(normalizeAmount("-1.00"))
        assertNull(normalizeAmount("1e20"))
    }

    @Test fun dealsUseResolutionDateAndDoNotMixCurrencies() {
        val tz = ZoneId.of("Asia/Karachi")
        val current = ZonedDateTime.of(2026, 11, 12, 1, 0, 0, 0, tz).toInstant().toEpochMilli()
        val yesterday = current - 7_200_000L
        val customer = Customer(displayName = "Real", phoneNumber = "")
        val call = CallLog(customerId = customer.id, startedAt = yesterday, endedAt = yesterday, outcome = Outcome.RESOLVED,
            outcomeAt = current - 1, dealAmount = "9999999999999999999999.123456789", dealCurrency = "PKR")
        val older = call.copy(id = uuid(), outcomeAt = yesterday, dealAmount = "0.000000001")
        val usd = call.copy(id = uuid(), dealCurrency = "USD", dealAmount = "1.50")
        val pending = call.copy(id = uuid(), outcome = Outcome.PENDING, dealAmount = null, dealCurrency = null)
        val data = Workspace(customers = listOf(customer), calls = listOf(call, older, usd, pending))
        val today = report(data, ReportPeriod.TODAY, current, tz)
        assertEquals(0, today.calls)
        assertEquals(2, today.deals.successful)
        assertEquals(mapOf("PKR" to "9999999999999999999999.123456789", "USD" to "1.50"), today.deals.amounts)
        val rolling = report(data, ReportPeriod.LAST_24_HOURS, current, tz)
        assertEquals(4, rolling.calls)
        assertEquals(3, rolling.deals.successful)
        assertEquals("9999999999999999999999.123456790", rolling.deals.amounts["PKR"])
        assertEquals(rolling.calls, rolling.trend.sumOf { it.second })
    }
    @Test fun greetingsSwitchAtLocalAndSeasonalBoundaries() {
        val tz = ZoneId.of("Asia/Karachi")
        fun greet(month: Int, hour: Int) =
            greeting(ZonedDateTime.of(2026, month, 15, hour, 0, 0, 0, tz).toInstant().toEpochMilli(), tz)
        assertEquals("Good night", greet(11, 1))
        assertEquals("Good morning", greet(11, 11))
        assertEquals("Good afternoon", greet(11, 12))
        assertEquals("Good evening", greet(11, 17))
        assertEquals("Good night", greet(11, 20))
        assertEquals("Good afternoon", greet(7, 17))
        assertEquals("Good evening", greet(7, 18))
        assertEquals("Good night", greet(7, 21))
    }

    @Test fun roadFiltersAndRemindersUseTheSelectedCalendarDates() {
        val tz = ZoneId.of("Asia/Karachi")
        val sunday = ZonedDateTime.of(2026, 11, 29, 12, 0, 0, 0, tz)
        val clock = sunday.toInstant().toEpochMilli()
        val customer = Customer(displayName = "Customer", phoneNumber = "", createdAt = sunday.minusDays(4).toInstant().toEpochMilli())
        val rows = (0..2).map { offset ->
            FollowUp(customerId = customer.id, purpose = "Follow-up $offset", dueAt = sunday.plusDays(offset.toLong()).toInstant().toEpochMilli(),
                timezoneId = tz.id)
        }
        val call = CallLog(customerId = customer.id, startedAt = customer.createdAt, endedAt = customer.createdAt, outcome = Outcome.RESOLVED,
            dealAmount = "500.50", dealCurrency = "PKR", outcomeAt = customer.createdAt)
        val data = Workspace(customers = listOf(customer), followUps = rows, calls = listOf(call))
        assertEquals(0, journeySnapshot(data, ReportPeriod.TODAY, clock, tz).new)
        assertEquals(0, journeySnapshot(data, ReportPeriod.LAST_2_DAYS, clock, tz).contacted)
        val snapshot = journeySnapshot(data, ReportPeriod.LAST_20_DAYS, clock, tz)
        assertEquals(1, snapshot.new)
        assertEquals(1, snapshot.contacted)
        assertEquals("500.50", snapshot.deals.amounts["PKR"])
        assertEquals(rows[0], remindersFor(data, ReminderDay.YESTERDAY, clock, tz).single().followUp)
        assertEquals(rows[1], remindersFor(data, ReminderDay.TODAY, clock, tz).single().followUp)
        assertEquals(rows[2], remindersFor(data, ReminderDay.TOMORROW, clock, tz).single().followUp)
    }
    @Test fun reminderListPrefersTheActualRescheduledTime() {
        val tz = ZoneId.of("Asia/Karachi")
        val today = ZonedDateTime.of(2026, 11, 29, 12, 0, 0, 0, tz).toInstant().toEpochMilli()
        val customer = Customer(displayName = "Customer", phoneNumber = "")
        val rescheduled = FollowUp(customerId = customer.id, purpose = "Same-day catch-up",
            dueAt = today + 3_600_000L, timezoneId = tz.id, reminderScheduledAt = today + 1500)
        val data = Workspace(customers = listOf(customer), followUps = listOf(rescheduled))
        assertEquals(today + 1500, remindersFor(data, ReminderDay.TODAY, today, tz).single().at)
        assertTrue(remindersFor(data, ReminderDay.YESTERDAY, today, tz).isEmpty())
        val disabled = data.copy(followUps = listOf(rescheduled.copy(reminderPolicy = Reminder.OFF)))
        assertTrue(remindersFor(disabled, ReminderDay.TODAY, today, tz).isEmpty())
    }
    @Test fun profileCropAndTodayOverviewUseIndependentUnits() {
        assertEquals(PhotoCrop(100, 0, 200), profileCrop(400, 200, 1f, 0f, 0f))
        assertEquals(PhotoCrop(300, 100, 100), profileCrop(400, 200, 2f, 1f, 1f))
        assertEquals(PhotoCrop(0, 0, 66), profileCrop(200, 400, 3f, -1f, -1f))

        val tz = ZoneId.of("Asia/Karachi")
        val now = ZonedDateTime.of(2026, 11, 29, 12, 0, 0, 0, tz).toInstant().toEpochMilli()
        val yesterday = now - 86_400_000L
        val w = Workspace(calls = listOf(
            CallLog(customerId = "c", startedAt = yesterday, endedAt = yesterday, outcome = Outcome.RESOLVED,
                outcomeAt = now - 10L, dealAmount = "1250.50", dealCurrency = "PKR"),
            CallLog(customerId = "c", startedAt = now - 200L, endedAt = now - 100L, outcome = Outcome.PENDING),
            CallLog(customerId = "c", startedAt = now - 30L, endedAt = now - 20L, outcome = Outcome.ISSUE)
        ))
        val result = todayOverview(w, now, tz)
        assertEquals(2, result.total)
        assertEquals(0, result.successful)
        assertEquals(1, result.pending)
        assertEquals("1250.50", result.deals.amounts["PKR"])
    }

    @Test fun paymentSummariesKeepExactCurrencyTotalsAndLocalDates() {
        val tz = ZoneId.of("Asia/Karachi")
        val paid = ZonedDateTime.of(2026, 12, 1, 0, 10, 0, 0, tz)
        val at = paid.toInstant().toEpochMilli()
        val owner = Customer(displayName = "Customer", phoneNumber = "")
        val call = CallLog(customerId = owner.id, startedAt = at - 3_600_000L, endedAt = at - 1_800_000L,
            outcome = Outcome.RESOLVED, outcomeAt = at, dealAmount = "0.005", dealCurrency = "PKR")
        val rows = listOf(
            PaymentEntry(date = "2026-12-01", amount = "9999999999999999999999.100", currency = "PKR"),
            PaymentEntry(date = "2026-12-01", amount = "0.895", currency = "PKR"),
            PaymentEntry(date = "2026-12-01", amount = "20.50", currency = "USD"),
            PaymentEntry(date = "2026-12-02", amount = "2.125", currency = "PKR"),
            PaymentEntry(date = "2026-11-30", amount = "3.00", currency = "PKR")
        )
        val workspace = Workspace(customers = listOf(owner), calls = listOf(call), payments = rows)
        val daily = paymentSummary(workspace, paid.toLocalDate(), false, at, tz)
        assertEquals(mapOf("PKR" to "10000000000000000000000.000", "USD" to "20.50"), daily.total)
        assertEquals(mapOf("PKR" to "0.005"), daily.calls)
        assertEquals("9999999999999999999999.995", daily.manual["PKR"])
        assertEquals("10000000000000000000002.125", paymentSummary(workspace, paid.toLocalDate(), true, at, tz).total["PKR"])
        assertEquals("3.00", paymentSummary(workspace, paid.toLocalDate().minusDays(1), false, at, tz).total["PKR"])
        assertEquals(listOf("50.25", "1.00"), paymentAmounts(" ۵۰٫۲۵\n\n1.00 "))
        assertThrows(IllegalArgumentException::class.java) { paymentAmounts("40\nwrong\n20") }
        assertNull(paymentDate("2026-02-29"))
    }

    @Test fun roadRowsMatchCountsAndUseTheCorrectEventDates() {
        val tz = ZoneId.of("UTC")
        val at = ZonedDateTime.of(2026, 11, 24, 12, 0, 0, 0, tz).toInstant().toEpochMilli()
        val owner = Customer(displayName = "Customer", phoneNumber = "", createdAt = at - 1_000)
        val oldCall = CallLog(customerId = owner.id, startedAt = at - 90_000_000L, endedAt = at - 90_000_000L,
            outcome = Outcome.RESOLVED, outcomeAt = at - 25, dealAmount = "10", dealCurrency = "PKR")
        val lastCall = oldCall.copy(id = uuid(), startedAt = at - 600, endedAt = at - 100, outcome = Outcome.NO_ANSWER,
            outcomeAt = null, dealCurrency = null, dealAmount = null)
        val follow = FollowUp(customerId = owner.id, purpose = "Proposal", dueAt = at + 5000, status = FollowStatus.DONE)
        val w = Workspace(customers = listOf(owner), calls = listOf(lastCall.copy(id = uuid(), endedAt = at - 500), oldCall, lastCall),
            followUps = listOf(follow, follow.copy(id = uuid(), status = FollowStatus.DELETED)))
        val records = journeyRecords(w, ReportPeriod.TODAY, at, tz)
        assertEquals(listOf(owner), records.new)
        assertEquals(listOf(lastCall), records.contacted)
        assertEquals(listOf(follow), records.followUps)
        assertEquals(listOf(oldCall), records.successful)
        assertEquals(1, records.count(JourneyStage.SUCCESSFUL))
        assertTrue(journeyRecords(w, ReportPeriod.LAST_24_HOURS, at, tz).followUps.isEmpty())
    }
}
