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
}
