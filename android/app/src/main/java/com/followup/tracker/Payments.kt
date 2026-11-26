package com.followup.tracker

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

enum class PaymentPeriod(val title: String) { DAILY("Daily"), MONTHLY("Monthly") }

@Entity(tableName = "payments", indices = [Index("date")])
data class PaymentEntry(
    @PrimaryKey val id: String = uuid(),
    val date: String,
    val amount: String,
    val currency: String,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt
)

internal fun paymentDate(value: String): LocalDate? {
    if (value.length != 10) return null
    return try { LocalDate.parse(value).takeIf { it.year in 1..9999 } }
    catch (_: DateTimeParseException) { null }
}

internal fun paymentAmounts(input: String): List<String> {
    val lines = input.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    require(lines.isNotEmpty()) { "Enter at least one amount." }
    return lines.mapIndexed { index, line ->
        requireNotNull(normalizeAmount(line)) { "Enter a valid amount on line ${index + 1}." }
    }
}

internal data class PaymentSummary(val manual: Map<String, String>, val calls: Map<String, String>) {
    val total: Map<String, String> get() = (manual.keys + calls.keys).sorted().associateWith { code ->
        (manual[code]?.toBigDecimal() ?: BigDecimal.ZERO)
            .add(calls[code]?.toBigDecimal() ?: BigDecimal.ZERO).toPlainString()
    }
}

internal fun paymentSummary(data: Workspace, date: LocalDate, monthly: Boolean, time: Long, timezone: ZoneId): PaymentSummary {
    val first = if (monthly) date.withDayOfMonth(1) else date
    val next = if (monthly) first.plusMonths(1) else first.plusDays(1)
    val firstKey = first.toString()
    val nextKey = next.toString()
    val manual = sortedMapOf<String, BigDecimal>()
    data.payments.forEach { row ->
        if (row.date >= firstKey && (next.year > 9999 || row.date < nextKey)) {
            manual[row.currency] = (manual[row.currency] ?: BigDecimal.ZERO).add(row.amount.toBigDecimal())
        }
    }
    val start = first.atStartOfDay(timezone).toInstant().toEpochMilli()
    val end = minOf(time + 1, next.atStartOfDay(timezone).toInstant().toEpochMilli())
    return PaymentSummary(manual.mapValues { it.value.toPlainString() }, dealTotals(data.calls, start, end).amounts)
}
