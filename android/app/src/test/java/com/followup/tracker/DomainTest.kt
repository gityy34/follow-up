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
}
