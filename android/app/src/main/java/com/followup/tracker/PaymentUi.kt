package com.followup.tracker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val paymentDay = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH)
private val paymentMonth = DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PaymentDateButton(label: String, date: LocalDate, enabled: Boolean = true, change: (LocalDate) -> Unit) {
    var calendar by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { calendar = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("$label · ${paymentDay.format(date)}")
    }
    if (calendar) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = 1..9999)
        DatePickerDialog(onDismissRequest = { calendar = false }, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { change(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                calendar = false
            }, enabled = state.selectedDateMillis != null) { Text("Apply") }
        }, dismissButton = { TextButton(onClick = { calendar = false }) { Text("Cancel") } }) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun PaymentValues(amounts: Map<String, String>, zeroCurrency: String? = null, large: Boolean = false) {
    val values = if (zeroCurrency != null && amounts.isEmpty()) mapOf(zeroCurrency to "0") else amounts
    if (values.isEmpty()) Text("None", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    values.toSortedMap().forEach { (currency, amount) ->
        Text("$currency $amount", style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PaymentBreakdown(summary: PaymentSummary, currency: String) {
    listOf("Total" to summary.total, "Manual entries" to summary.manual, "Successful calls" to summary.calls).forEach { (label, amounts) ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PaymentValues(amounts, currency.takeIf { label == "Total" })
        }
    }
}

@Composable
internal fun DashboardPayments(data: Workspace, time: Long, changePeriod: suspend (PaymentPeriod) -> Unit) {
    var chosenDate by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val date = paymentDate(chosenDate) ?: localDay(time)
    val monthly = chosenDate.isEmpty() && data.settings.paymentPeriod == PaymentPeriod.MONTHLY
    val summary = remember(data, date, monthly, time) { paymentSummary(data, date, monthly, time, zone()) }
    val label = when {
        chosenDate.isNotEmpty() -> " · ${paymentDay.format(date)}"
        monthly -> " · ${paymentMonth.format(date)}"
        else -> " today"
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = true }.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Deal amount$label", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PaymentValues(summary.total, data.settings.currency, large = true)
        }
        Text("›", style = MaterialTheme.typography.titleLarge)
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Deal amounts") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentPeriod.entries.forEach { period ->
                    FilterChip(selected = chosenDate.isEmpty() && data.settings.paymentPeriod == period, enabled = !saving,
                        onClick = {
                            if (!saving) {
                                saving = true
                                error = ""
                                scope.launch {
                                    try {
                                        if (data.settings.paymentPeriod != period) changePeriod(period)
                                        chosenDate = ""
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = "The dashboard preference could not be saved." }
                                    finally { saving = false }
                                }
                            }
                        }, label = { Text(period.title) })
                }
            }
            PaymentDateButton("Check a date", date, !saving) { chosenDate = it.toString() }
            PaymentBreakdown(summary, data.settings.currency)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}

@Composable
internal fun PaymentLedger(data: Workspace, time: Long, repo: Repository) {
    var day by rememberSaveable { mutableStateOf(localDay(time).toString()) }
    var limit by rememberSaveable { mutableIntStateOf(40) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val date = paymentDate(day) ?: localDay(time)
    val entries = remember(data.payments, day) { data.payments.filter { it.date == day }.sortedByDescending { it.createdAt } }
    val summary = remember(data, date, time) { paymentSummary(data, date, false, time, zone()) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Payments by date", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(onClick = { editing = "" }) { Text("Add amount") }
        }
        PaymentDateButton("Date", date) { day = it.toString(); limit = 40 }
        PaymentBreakdown(summary, data.settings.currency)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (entries.isEmpty()) Text("No manual entries for this date.", style = MaterialTheme.typography.bodySmall)
        entries.take(limit).forEach { entry ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { editing = entry.id }.padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${entry.currency} ${entry.amount}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("Edit", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (entries.size > limit) TextButton(onClick = { limit += 40 }) { Text("Show more") }
    }
    editing?.let { id ->
        val entry = data.payments.firstOrNull { it.id == id }
        if (id.isEmpty() || entry != null) key(id) {
            PaymentEditor(entry, date, data.settings.currency, repo, close = { editing = null }, saved = {
                day = it
                limit = 40
                editing = null
            })
        }
    }
}

@Composable
private fun PaymentEditor(entry: PaymentEntry?, selected: LocalDate, defaultCurrency: String, repo: Repository,
                          close: () -> Unit, saved: (String) -> Unit) {
    var date by rememberSaveable { mutableStateOf(entry?.date ?: selected.toString()) }
    var amount by rememberSaveable { mutableStateOf(entry?.amount.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val currency = entry?.currency ?: defaultCurrency
    AlertDialog(onDismissRequest = { if (!saving) close() }, title = { Text(if (entry == null) "Add amounts" else "Edit amount") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaymentDateButton("Date", paymentDate(date) ?: selected, !saving) { date = it.toString() }
            OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), enabled = !saving,
                label = { Text(if (entry == null) "Amounts ($currency)" else "Amount ($currency)") },
                singleLine = entry != null, minLines = if (entry == null) 3 else 1, maxLines = if (entry == null) 5 else 1,
                keyboardOptions = KeyboardOptions(keyboardType = if (entry == null) KeyboardType.Text else KeyboardType.Decimal),
                supportingText = if (entry == null) { { Text("Enter one amount per line.") } } else null)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(enabled = !saving && amount.isNotBlank(), onClick = {
            if (!saving) {
                saving = true
                error = ""
                scope.launch {
                    try {
                        if (entry == null) repo.addPayments(date, amount, currency)
                        else repo.editPayment(entry.id, date, amount)
                        saved(date)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (problem: IllegalArgumentException) { error = problem.message ?: "Check the entered amount." }
                    catch (problem: IllegalStateException) { error = problem.message ?: "This payment is unavailable." }
                    catch (_: Exception) { error = "The amount could not be saved." }
                    finally { saving = false }
                }
            }
        }) { Text(if (saving) "Saving…" else "Save") }
    }, dismissButton = { TextButton(onClick = close, enabled = !saving) { Text("Cancel") } })
}
