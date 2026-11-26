package com.followup.tracker

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
private fun TimeField(title: String, epoch: Long, zoneId: String, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    val selectedZone = ZoneId.of(zoneId)
    val previous = Instant.ofEpochMilli(epoch).atZone(selectedZone)
    OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, h, m ->
                onChange(java.time.LocalDate.of(year, month + 1, day).atTime(h, m).atZone(selectedZone).toInstant().toEpochMilli())
            }, previous.hour, previous.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
        }, previous.year, previous.monthValue - 1, previous.dayOfMonth).show()
    }) { Text("$title · ${dateLabel(epoch)}") }
}

@Composable
internal fun FollowWizard(customers: List<Customer>, initial: String, previous: FollowUp?, addCustomer: () -> Unit, save: (FollowUp) -> Unit) {
    if (customers.isEmpty()) {
        Panel { Text("Add a customer before you schedule a follow-up.") }
        Primary("+ Add Customer", onClick = addCustomer)
        return
    }
    var step by rememberSaveable { mutableStateOf(0) }
    var customerId by rememberSaveable { mutableStateOf(previous?.customerId ?: initial.takeIf { id -> customers.any { it.id == id } } ?: customers.first().id) }
    var purpose by rememberSaveable { mutableStateOf(previous?.purpose.orEmpty()) }
    var dueAt by rememberSaveable { mutableStateOf(previous?.dueAt ?: now() + 86_400_000L) }
    val zoneId = previous?.timezoneId ?: zone().id
    var reminder by rememberSaveable { mutableStateOf(previous?.reminderPolicy ?: Reminder.DEFAULT) }
    var custom by rememberSaveable { mutableStateOf(previous?.customReminderAt ?: dueAt - 3_600_000L) }
    var critical by rememberSaveable { mutableStateOf(previous?.timeCritical ?: false) }
    var priority by rememberSaveable { mutableStateOf(previous?.priority ?: Priority.NORMAL) }
    var note by rememberSaveable { mutableStateOf(previous?.note.orEmpty()) }
    val purposes = listOf("Discuss proposal", "Confirm interest", "Share product info", "Address concern / issue", "Agree next step")
    var customPurpose by rememberSaveable { mutableStateOf(purpose.isNotBlank() && purpose !in purposes) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("Purpose", "Schedule", "Note").forEachIndexed { index, label ->
            AssistChip(onClick = { if (index < step) step = index }, enabled = index <= step,
                label = { Text("${index + 1} $label", fontWeight = if (step == index) FontWeight.Bold else FontWeight.Normal) })
        }
    }
    Panel {
        Text("Step ${step + 1} of 3")
        when (step) {
            0 -> {
                Section("Purpose of Follow-up")
                val selected = customers.firstOrNull { it.id == customerId } ?: customers.first()
                if (previous == null) Choice("Customer", customers, selected, { it.displayName }) { customerId = it.id }
                else Text(selected.displayName)
                (purposes + "Other").forEach { label ->
                    val selectedOption = if (label == "Other") customPurpose else !customPurpose && purpose == label
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selectedOption, role = Role.RadioButton) {
                        customPurpose = label == "Other"
                        purpose = if (customPurpose) "" else label
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selectedOption, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                        Text(label)
                    }
                }
                if (customPurpose) Field("Purpose", purpose, { purpose = it.take(120) })
            }
            1 -> {
                Section("Schedule Follow-up")
                TimeField("Due", dueAt, zoneId) { dueAt = it }
                Text("Time zone · $zoneId")
                Choice("Remind me", Reminder.entries, reminder, { it.title }) { reminder = it }
                if (reminder == Reminder.CUSTOM) {
                    TimeField("Custom reminder", custom, zoneId) { custom = it }
                    if (custom > dueAt) Text("Set the reminder at or before the due time.", color = MaterialTheme.colorScheme.error)
                }
                if (reminder != Reminder.OFF) PreferenceSwitch("Time-critical reminder", critical) { critical = it }
                Text("Exact timing depends on Android alarm access. Settings shows the current status.")
            }
            2 -> {
                Section("Add a Note")
                Field("Notes (optional)", note, { note = it.take(2000) }, 4)
                Choice("Priority", Priority.entries, priority, { it.title }) { priority = it }
                Text("Due ${dateLabel(dueAt)}")
            }
        }
    }
    val valid = when (step) { 0 -> purpose.isNotBlank(); 1 -> reminder != Reminder.CUSTOM || custom <= dueAt; else -> true }
    Primary(if (step == 2) "Save Follow-up" else "Next ›", valid) {
        if (step < 2) step++ else {
            val record = previous ?: FollowUp(customerId = customerId, purpose = purpose.trim(), dueAt = dueAt)
            save(record.copy(purpose = purpose.trim(), dueAt = dueAt, timezoneId = zoneId,
                reminderPolicy = reminder, customReminderAt = custom.takeIf { reminder == Reminder.CUSTOM },
                timeCritical = critical, priority = priority, note = note.trim()))
        }
    }
}

@Composable
internal fun CallResult(customer: Customer, follow: FollowUp?, currency: String, dial: (String) -> Unit, save: (CallLog) -> Unit) {
    var started by rememberSaveable { mutableStateOf(0L) }
    var stopped by rememberSaveable { mutableStateOf(0L) }
    var clock by remember { mutableStateOf(now()) }
    var outcome by rememberSaveable { mutableStateOf<Outcome?>(null) }
    var amount by rememberSaveable { mutableStateOf("") }
    var more by rememberSaveable { mutableStateOf(false) }
    var issue by rememberSaveable { mutableStateOf("Waiting for customer decision") }
    var note by rememberSaveable { mutableStateOf("") }
    var reflection by rememberSaveable { mutableStateOf("") }
    val callId = rememberSaveable { uuid() }
    LaunchedEffect(started, stopped) {
        while (started != 0L && stopped == 0L) { clock = now(); delay(1000) }
    }
    Panel {
        Section("Call with ${customer.displayName}")
        Text(customer.phoneNumber.ifBlank { "No phone number saved" })
        val previous = follow?.note?.takeIf { it.isNotBlank() } ?: customer.notes
        if (previous.isNotBlank()) Text("Prior note · $previous")
        val seconds = if (started == 0L) 0L else (((stopped.takeIf { it > 0 } ?: clock) - started) / 1000).coerceAtLeast(0)
        Text("Timer · ${String.format(Locale.ENGLISH, "%02d:%02d", seconds / 60, seconds % 60)}")
        if (started == 0L) OutlinedButton({ started = now(); clock = started }, Modifier.heightIn(min = 48.dp)) { Text("Start timer") }
        else if (stopped == 0L) OutlinedButton({ stopped = now() }, Modifier.heightIn(min = 48.dp)) { Text("Stop timer") }
        if (customer.phoneNumber.isNotBlank()) Primary("Open Phone Dialer") { dial(customer.phoneNumber) }
    }
    Panel {
        Section("Call Outcome")
        Text("What was the result of this call?")
        entryOutcomes.take(2).forEach { option ->
            OutcomeButton(option, outcome) { outcome = option }
        }
        TextButton({
            more = !more
            if (!more && outcome !in entryOutcomes.take(2)) outcome = null
        }, Modifier.heightIn(min = 48.dp)) { Text(if (more) "Fewer outcomes" else "More outcomes") }
        if (more) entryOutcomes.drop(2).forEach { option ->
            OutcomeButton(option, outcome) { outcome = option }
        }
        if (outcome == Outcome.RESOLVED) AmountField(amount, currency) { amount = it }
        if (outcome == Outcome.ISSUE) Choice("Issue category", listOf("Waiting for customer decision", "Need more information",
            "Price / budget concerns", "Technical issue", "Other"), issue, { it }) { issue = it }
    }
    Field("What went well?  ${note.length}/200", note, { note = it.take(200) }, 3)
    Field("What can I improve next time?  ${reflection.length}/200", reflection, { reflection = it.take(200) }, 3)
    val valid = outcome != null && (outcome != Outcome.RESOLVED || normalizeAmount(amount) != null)
    Primary(if (outcome == Outcome.PENDING) "Save as Pending" else "Save Call Result", valid) {
        val end = stopped.takeIf { it > 0 } ?: now()
        outcome?.let { selected ->
            save(CallLog(id = callId, customerId = customer.id, startedAt = started.takeIf { it in 1..end } ?: end, endedAt = end,
                outcome = selected, issueCategory = issue.takeIf { selected == Outcome.ISSUE }.orEmpty(), summaryNote = note.trim(),
                reflection = reflection.trim(), followUpId = follow?.id,
                dealAmount = amount.takeIf { selected == Outcome.RESOLVED }, dealCurrency = currency.takeIf { selected == Outcome.RESOLVED }))
        }
    }
}

@Composable
private fun OutcomeButton(option: Outcome, selected: Outcome?, select: () -> Unit) {
    if (option == selected) Primary("✓  ${option.title}", onClick = select)
    else OutlinedButton(select, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(option.title) }
}

@Composable
private fun AmountField(value: String, currency: String, update: (String) -> Unit) {
    val invalid = value.isNotBlank() && normalizeAmount(value) == null
    OutlinedTextField(value, update, label = { Text("Deal amount ($currency)") }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, isError = invalid, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
        supportingText = { if (invalid) Text("Enter a non-negative amount, for example 1250.50.") })
}

@Composable
internal fun PendingCalls(data: Workspace, open: (String) -> Unit) {
    val rows = data.calls.filter { it.outcome == Outcome.PENDING }.sortedByDescending { it.endedAt }
    val customers = data.customers.associateBy { it.id }
    var limit by rememberSaveable { mutableIntStateOf(40) }
    if (rows.isEmpty()) Panel { Text("No pending calls. Calls saved as Pending will appear here.") }
    rows.take(limit).forEach { row ->
        Card(onClick = { open(row.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text(customers[row.customerId]?.displayName.orEmpty(), fontWeight = FontWeight.Bold)
                Text("Call made ${dateLabel(row.endedAt)}")
                if (row.summaryNote.isNotBlank()) Text(row.summaryNote, maxLines = 2)
            }
        }
    }
    if (rows.size > limit) Primary("Show more") { limit += 40 }
}

@Composable
internal fun PendingResult(call: CallLog, name: String, currency: String, save: (Outcome, String?) -> Unit) {
    var result by rememberSaveable(call.id) { mutableStateOf<Outcome?>(null) }
    var amount by rememberSaveable(call.id) { mutableStateOf("") }
    Panel {
        Section(name)
        Text("Call made ${dateLabel(call.endedAt)}")
        if (call.summaryNote.isNotBlank()) Text(call.summaryNote)
    }
    Panel {
        Section("Final Outcome")
        pendingOutcomes.forEach { option -> OutcomeButton(option, result) { result = option } }
        if (result == Outcome.RESOLVED) AmountField(amount, currency) { amount = it }
    }
    Primary("Save Result", result != null && (result != Outcome.RESOLVED || normalizeAmount(amount) != null)) {
        result?.let { save(it, amount.takeIf { _ -> it == Outcome.RESOLVED }) }
    }
}

@Composable
internal fun PeriodPicker(period: ReportPeriod, select: (ReportPeriod) -> Unit) {
    ReportPeriod.entries.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { option ->
                FilterChip(selected = option == period, onClick = { select(option) }, label = { Text(option.title) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            }
        }
    }
}

@Composable
internal fun DealSummary(totals: DealTotals) {
    Text("${totals.successful} successful deals")
    if (totals.amounts.isEmpty()) Text("No deal amounts recorded.", style = MaterialTheme.typography.bodySmall)
    totals.amounts.forEach { (currency, amount) -> Text("$currency $amount", fontWeight = FontWeight.Bold) }
}

@Composable
internal fun Performance(data: Workspace, period: ReportPeriod, setPeriod: (ReportPeriod) -> Unit) {
    val perf = report(data, period, now(), zone(), demo = data.customers.firstOrNull()?.demo == true)
    PeriodPicker(period, setPeriod)
    Panel {
        Section("Deal Summary")
        DealSummary(perf.deals)
    }
    Panel {
        Section("Your Performance")
        Text("${perf.calls} calls  ·  ${perf.scheduled} follow-ups  ·  ${perf.resolved} resolved")
        Text("${perf.successRate}% success rate  ·  ${perf.completionRate}% completion rate")
        Outcome.entries.filter { it != Outcome.RESOLVED }.forEach { Text("${it.title} · ${perf.countByOutcome[it] ?: 0}") }
        Text("Best calling hour · ${perf.bestHour?.let { "${it.toString().padStart(2, '0')}:00" } ?: "Need successful calls"}")
    }
    Panel {
        Section("Journey Trend")
        val accent = MaterialTheme.colorScheme.primary
        val levels = MaterialTheme.colorScheme.surfaceVariant
        val counts = perf.trend.map { it.second }
        val maximum = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
        val chartDescription = perf.trend.joinToString { "${it.first}: ${it.second}" }
        Canvas(Modifier.fillMaxWidth().height(112.dp).semantics { contentDescription = chartDescription }) {
            val gap = 6.dp.toPx()
            val w = size.width - 2 * gap
            val h = size.height - 2 * gap
            for (index in 0..4) drawLine(levels, Offset(gap, gap + h * index / 4f), Offset(size.width - gap, gap + h * index / 4f))
            val points = counts.mapIndexed { i, value ->
                Offset(gap + if (counts.size <= 1) w / 2 else w * i / (counts.size - 1), gap + h * (1 - value.toFloat() / maximum))
            }
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            drawPath(path, accent, style = Stroke(2.dp.toPx()))
            points.forEach { drawCircle(accent, 3.dp.toPx(), it) }
        }
        Text(perf.trend.first().first + "  –  " + perf.trend.last().first, style = MaterialTheme.typography.bodySmall)
    }
    Panel {
        Section("Issue Clusters")
        if (perf.issues.isEmpty()) Text("No issues in this period.")
        val total = perf.issues.values.sum().coerceAtLeast(1)
        perf.issues.entries.sortedByDescending { it.value }.forEach { (name, count) ->
            Text("$name · $count (${100 * count / total}%)")
            LinearProgressIndicator(progress = { count.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        }
    }
    Panel {
        Section("Coach Recommendations")
        perf.tips.forEach { Text("✓  $it") }
    }
}

@Composable
internal fun PreferenceSwitch(label: String, value: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, update, modifier = Modifier.semantics { contentDescription = label })
    }
}
