package com.followup.tracker

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val displayDateFormat = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ENGLISH)
internal fun dateLabel(time: Long) = displayDateFormat.format(Instant.ofEpochMilli(time).atZone(zone()))
internal fun localDay(time: Long) = Instant.ofEpochMilli(time).atZone(zone()).toLocalDate()
internal fun initials(value: String) = value.trim().split(Regex("\\s+")).take(2).joinToString("") { it.firstOrNull()?.uppercase().orEmpty() }

@Composable
internal fun JourneyTheme(dark: Boolean, palette: String, content: @Composable () -> Unit) {
    val lightPrimary = when (palette) { "Ocean" -> Color(0xff285ab1); "Forest" -> Color(0xff1c6956); else -> Color(0xff662fcd) }
    val darkPrimary = when (palette) { "Ocean" -> Color(0xffafc8ff); "Forest" -> Color(0xff8ed4b9); else -> Color(0xffd4b7ff) }
    val colors = if (dark) darkColorScheme(primary = darkPrimary, background = Color(0xff151023), surface = Color(0xff1e172e),
        surfaceVariant = Color(0xff302344), onSurface = Color(0xfff2ebfd), onSurfaceVariant = Color(0xffdbcde9))
    else lightColorScheme(primary = lightPrimary, background = Color(0xfff7f4ff), surface = Color.White,
        surfaceVariant = Color(0xffeee7fa), onSurface = Color(0xff241634), onSurfaceVariant = Color(0xff514562),
        primaryContainer = Color(0xffeee1ff), onPrimaryContainer = Color(0xff31135e), error = Color(0xffa41c4b))
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
internal fun JourneyScreen(model: JourneyModel, workspace: Workspace, incoming: String?, dismissIncoming: () -> Unit, secureDevice: () -> Boolean) {
    var route by rememberSaveable { mutableStateOf("Journey") }
    var customerId by rememberSaveable { mutableStateOf("") }
    var followId by rememberSaveable { mutableStateOf("") }
    var backTo by rememberSaveable { mutableStateOf("Journey") }
    var pendingId by rememberSaveable { mutableStateOf("") }
    var period by rememberSaveable { mutableStateOf(ReportPeriod.TODAY) }
    var callParent by rememberSaveable { mutableStateOf("Customer Detail") }
    var followParent by rememberSaveable { mutableStateOf("Agenda") }
    var editingParent by rememberSaveable { mutableStateOf("Customers") }
    var wizardParent by rememberSaveable { mutableStateOf("Journey") }
    var callForFollowUp by rememberSaveable { mutableStateOf("") }
    var activeDemo by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val repo = model.repo
    val context = LocalContext.current
    val visible = workspace.customers.filter { it.demo == activeDemo }
    val ids = visible.mapTo(hashSetOf()) { it.id }
    val data = workspace.copy(customers = visible, followUps = workspace.followUps.filter { it.customerId in ids },
        calls = workspace.calls.filter { it.customerId in ids }, timeline = workspace.timeline.filter { it.customerId in ids })
    val customer = workspace.customers.firstOrNull { it.id == customerId }
    val follow = workspace.followUps.firstOrNull { it.id == followId }
    val top = setOf("Journey", "Customers", "Agenda", "Performance")
    fun back() {
        route = when (route) {
            "Add Follow-up" -> wizardParent
            "Post-Call Reflection" -> callParent
            "Follow-up Detail" -> followParent
            "Customer Form" -> editingParent
            "Settings", "Pending" -> "Journey"
            "Update Pending Call" -> "Pending"
            in top -> "Journey"
            else -> backTo
        }
    }
    fun viewCustomer(id: String) { backTo = route; customerId = id; route = "Customer Detail" }
    fun wizard(id: String = "", row: String = "") {
        wizardParent = route
        callForFollowUp = ""
        customerId = id
        followId = row
        route = "Add Follow-up"
    }
    fun call(id: String, row: String = "") {
        callParent = route
        customerId = id
        followId = row
        route = "Post-Call Reflection"
    }
    fun mutate(message: String, action: suspend () -> Unit, saved: () -> Unit = {}) {
        scope.launch {
            try {
                action()
                model.reminders.request()
                saved()
                snackbar.showSnackbar(message)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalArgumentException) { snackbar.showSnackbar(error.message ?: "Check the entered details.") }
            catch (error: IllegalStateException) { snackbar.showSnackbar(error.message ?: "The change could not be saved.") }
            catch (_: Exception) { snackbar.showSnackbar("The change could not be saved.") }
        }
    }
    fun delete(row: FollowUp) {
        scope.launch {
            try {
                repo.changeFollowStatus(row.id, FollowStatus.DELETED)
                model.reminders.request()
                if (snackbar.showSnackbar("Follow-up deleted", "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                    repo.changeFollowStatus(row.id, row.status, FollowStatus.DELETED)
                    model.reminders.request()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { snackbar.showSnackbar("The change could not be saved.") }
        }
    }
    LaunchedEffect(incoming) {
        if (incoming != null) {
            workspace.followUps.firstOrNull { it.id == incoming && it.status != FollowStatus.DELETED }?.let {
                activeDemo = workspace.customers.firstOrNull { c -> c.id == it.customerId }?.demo ?: false
                backTo = "Agenda"
                followParent = "Agenda"
                customerId = it.customerId
                followId = it.id
                route = "Follow-up Detail"
            }
            dismissIncoming()
        }
    }
    BackHandler(route != "Journey", onBack = ::back)
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.statusBarsPadding().fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (route in top) route = "Settings" else back() }) {
                        Text(if (route in top) "Settings" else "‹ Back")
                    }
                    Text(if (route == "Journey") "Customer Journey" else if (route == "Performance") "Performance Coach" else route,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (activeDemo) Text("Demo", Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        bottomBar = {
            if (route in top) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Journey" to "⌂", "Customers" to "♙", "Agenda" to "▦", "Performance" to "↗").forEach { (label, icon) ->
                    NavigationBarItem(selected = route == label, onClick = { route = label }, icon = { Text(icon) }, label = { Text(label, maxLines = 1) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { bars ->
        Column(Modifier.fillMaxSize().padding(bars).imePadding().verticalScroll(remember(route) { ScrollState(0) })
            .padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            key(route) {
                when (route) {
                    "Journey" -> Mission(data, { route = "Agenda" }, { wizard() }, {
                        if (visible.any { !it.archived }) { backTo = "Journey"; route = "Start Call Session" }
                        else { customerId = ""; backTo = "Journey"; editingParent = "Journey"; route = "Customer Form" }
                    }, ::viewCustomer, { route = "Pending" }, period, { period = it })
                    "Agenda" -> Agenda(data, { wizard() }, { row ->
                        backTo = "Agenda"; followParent = "Agenda"; customerId = row.customerId; followId = row.id; route = "Follow-up Detail"
                    })
                    "Customers" -> Customers(data, { customerId = ""; backTo = "Customers"; editingParent = "Customers"; route = "Customer Form" }, ::viewCustomer)
                    "Customer Form" -> CustomerForm(customer, activeDemo) {
                        mutate("Customer saved", { repo.saveCustomer(it) }) { customerId = it.id; route = "Customer Detail" }
                    }
                    "Customer Detail" -> customer?.let { record ->
                        CustomerDetail(record, data, {
                            editingParent = "Customer Detail"; route = "Customer Form"
                        }, { call(record.id) }, { wizard(record.id) }, { selected ->
                            followId = selected.id; followParent = "Customer Detail"; route = "Follow-up Detail"
                        }) { updated -> mutate("Customer updated", { repo.saveCustomer(updated) }) }
                    }
                    "Follow-up Detail" -> follow?.let { row ->
                        FollowDetail(row, customer, { wizard(row.customerId, row.id) }, { call(row.customerId, row.id) }, ::delete,
                            { mutate("Follow-up updated", { repo.changeFollowStatus(row.id, it) }) },
                            { route = "Customer Detail" })
                    }
                    "Add Follow-up" -> FollowWizard(visible.filterNot { it.archived }, customerId, follow, {
                        customerId = ""; backTo = "Agenda"; editingParent = "Add Follow-up"; route = "Customer Form"
                    }) { created -> mutate("Follow-up saved", { repo.saveFollowUp(created, callForFollowUp.ifBlank { null }) }) {
                        route = wizardParent
                    } }
                    "Start Call Session" -> Customers(data.copy(customers = visible.filterNot { it.archived }), {}, { call(it) }, true)
                    "Post-Call Reflection" -> customer?.let { record ->
                        CallResult(record, follow, workspace.settings.currency, { number ->
                            if (number.isNotBlank()) {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                                try { context.startActivity(intent) }
                                catch (_: android.content.ActivityNotFoundException) { scope.launch { snackbar.showSnackbar("No phone dialer is available.") } }
                            }
                        }) { result ->
                            mutate("Call result saved", { repo.logCall(result) }) {
                                route = if (result.outcome == Outcome.PENDING) "Pending" else "Customer Detail"
                                if (result.outcome == Outcome.FOLLOW_UP) {
                                    wizard(record.id)
                                    callForFollowUp = result.id
                                }
                            }
                        }
                    }
                    "Pending" -> PendingCalls(data) { pendingId = it; route = "Update Pending Call" }
                    "Update Pending Call" -> {
                        val pending = data.calls.firstOrNull { it.id == pendingId && it.outcome == Outcome.PENDING }
                        if (pending == null) Text("This call no longer needs a result.")
                        else PendingResult(pending, data.customers.firstOrNull { it.id == pending.customerId }?.displayName.orEmpty(),
                            workspace.settings.currency) { outcome, amount ->
                            mutate("Call result updated", { repo.resolvePending(pending.id, outcome, amount, workspace.settings.currency) }) {
                                route = "Pending"
                            }
                        }
                    }
                    "Performance" -> Performance(data, period, { period = it })
                    "Settings" -> SettingsPanel(workspace, model, secureDevice, activeDemo, { activeDemo = it }, snackbar)
                }
            }
        }
    }
}

@Composable
internal fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun Primary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 50.dp), enabled = enabled) { Text(label) }
}

@Composable
internal fun Field(label: String, value: String, update: (String) -> Unit, lines: Int = 1, phone: Boolean = false) {
    OutlinedTextField(value, update, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = lines == 1, minLines = lines,
        keyboardOptions = if (phone) KeyboardOptions(keyboardType = KeyboardType.Phone) else KeyboardOptions.Default)
}

@Composable
internal fun <T> Choice(label: String, options: List<T>, selected: T, title: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("$label · ${title(selected)}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(title(option)) }, onClick = { expanded = false; onSelect(option) }) }
        }
    }
}

@Composable
private fun Metric(label: String, value: Int, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Mission(data: Workspace, agenda: () -> Unit, add: () -> Unit, start: () -> Unit, customer: (String) -> Unit,
                    pending: () -> Unit, period: ReportPeriod, setPeriod: (ReportPeriod) -> Unit) {
    val time = now()
    val today = localDay(time)
    val active = data.customers.filterNot { it.archived }
    val activeIds = active.mapTo(hashSetOf()) { it.id }
    val open = data.followUps.filter { it.status == FollowStatus.OPEN && it.customerId in activeIds }
    val overdue = open.count { it.dueAt < time }
    val due = open.count { localDay(it.dueAt) == today }
    val completed = data.calls.count { localDay(it.endedAt) == today }
    val needsReview = data.calls.count { localDay(it.endedAt) == today && it.outcome == Outcome.ISSUE }
    val closest = open.sortedWith(compareBy<FollowUp> { it.dueAt }.thenByDescending { it.priority }).firstOrNull()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Metric("Today's call goal", data.settings.goal, Modifier.weight(1f))
        Metric("Calls made today", completed, Modifier.weight(1f))
    }
    Primary("Start Call Session", onClick = start)
    OutlinedButton(onClick = pending, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Pending Calls (${data.calls.count { it.outcome == Outcome.PENDING }})")
    }
    Panel {
        Section("Deal Summary")
        PeriodPicker(period, setPeriod)
        DealSummary(report(data, period, time, zone(), demo = data.customers.firstOrNull()?.demo == true).deals)
    }
    Card(Modifier.fillMaxWidth()) {
        Box(Modifier.background(Brush.linearGradient(listOf(Color(0xff5f32c7), Color(0xff7249c4), Color(0xff494eb0), Color(0xff216f91))))) {
            Canvas(Modifier.matchParentSize()) {
                val path = Path().apply {
                    moveTo(size.width * .16f, size.height * .40f)
                    cubicTo(size.width * 1.12f, size.height * .44f, size.width * .90f, size.height * .57f, size.width * .48f, size.height * .61f)
                    cubicTo(size.width * -.04f, size.height * .67f, size.width * .10f, size.height * .79f, size.width * .75f, size.height * .87f)
                }
                drawPath(path, Color.White.copy(alpha = .18f), style = Stroke(width = 27.dp.toPx(), cap = StrokeCap.Round))
                drawPath(path, Color.White.copy(alpha = .7f), style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 7f))))
            }
            Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                val hour = java.time.LocalTime.now().hour
                val greeting = if (hour < 12) "Good morning!" else if (hour < 18) "Good afternoon!" else "Good evening!"
                Text(greeting, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Text("Here’s your customer journey today.", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Stage.entries.forEachIndexed { index, stage ->
                    Surface(Modifier.align(if (index % 2 == 0) Alignment.Start else Alignment.End)
                        .padding(start = if (index % 2 == 0) 20.dp else 0.dp, end = if (index % 2 != 0) 19.dp else 0.dp),
                        shape = MaterialTheme.shapes.medium, color = Color(0xfff4edff), contentColor = Color(0xff23123c)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
                            Text(stage.title, style = MaterialTheme.typography.labelMedium)
                            Text(active.count { it.stage == stage }.toString(), fontWeight = FontWeight.Black,
                                style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Metric("Follow-ups due", due, Modifier.weight(1f))
        Metric("Needs review", needsReview, Modifier.weight(1f))
    }
    Panel {
        Section("Next Best Action")
        Text(closest?.let { "${active.firstOrNull { c -> c.id == it.customerId }?.displayName.orEmpty()} · ${it.purpose}" }
            ?: "Add a customer and schedule the next call.")
        Text("$overdue overdue · ${open.size - overdue} upcoming", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = agenda) { Text("Open Agenda") }
    }
    OutlinedButton(onClick = add, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("+ Add Follow-up") }
    Section("Recent Activity")
    if (data.timeline.isEmpty()) Text("Saved activity will appear here.")
    data.timeline.take(4).forEach { event ->
        TextButton(onClick = { customer(event.customerId) }) { Text("${event.eventType} · ${data.customers.firstOrNull { it.id == event.customerId }?.displayName.orEmpty()}") }
    }
}

@Composable
private fun Agenda(data: Workspace, add: () -> Unit, detail: (FollowUp) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var range by rememberSaveable { mutableStateOf("All dates") }
    var offset by rememberSaveable { mutableStateOf(0L) }
    var status by rememberSaveable { mutableStateOf("Open") }
    var priority by rememberSaveable { mutableStateOf("Any") }
    val today = LocalDate.now()
    val start = today.plusDays(offset * if (range == "Week") 7L else 1L)
    val end = start.plusDays(if (range == "Week") 6 else 0)
    val customer = data.customers.associateBy { it.id }
    val rows = data.followUps.filter {
        val owner = customer[it.customerId]
        val date = localDay(it.dueAt)
        owner != null && !owner.archived && it.status.name.equals(status, ignoreCase = true) &&
            (priority == "Any" || it.priority.title == priority) &&
            (range == "All dates" || (!date.isBefore(start) && !date.isAfter(end))) &&
            listOf(owner.displayName, owner.company, it.purpose, it.note).any { text -> text.contains(search.trim(), true) }
    }.sortedWith(compareBy<FollowUp> { it.dueAt }.thenByDescending { it.priority })
    Primary("+ Add Follow-up", onClick = add)
    Field("Search follow-ups", search, { search = it })
    Choice("Range", listOf("All dates", "Day", "Week"), range, { it }) { range = it; offset = 0 }
    if (range != "All dates") Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { offset-- }) { Text("‹ Previous") }
        Text(if (range == "Week") "$start – $end" else start.toString(), Modifier.weight(1f))
        TextButton(onClick = { offset++ }) { Text("Next ›") }
    }
    Choice("Status", listOf("Open", "Done", "Deleted"), status, { it }) { status = it }
    Choice("Priority", listOf("Any") + Priority.entries.map { it.title }, priority, { it }) { priority = it }
    if (rows.isEmpty()) Panel { Text("No follow-ups match this view.") }
    var limit by rememberSaveable(search, range, offset, status, priority) { mutableIntStateOf(40) }
    rows.take(limit).groupBy {
        when {
            it.status != FollowStatus.OPEN -> if (it.status == FollowStatus.DONE) "Completed" else "Deleted"
            it.dueAt < now() -> "Overdue"
            localDay(it.dueAt) == today -> "Today"
            localDay(it.dueAt) == today.plusDays(1) -> "Tomorrow"
            else -> "Upcoming"
        }
    }.forEach { (title, items) ->
        Section(title)
        items.forEach { row ->
            Card(onClick = { detail(row) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(row.purpose, fontWeight = FontWeight.Bold)
                    Text("${customer[row.customerId]?.displayName.orEmpty()} · ${dateLabel(row.dueAt)}")
                    Text(row.priority.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (rows.size > limit) OutlinedButton({ limit += 40 }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Show more") }
}

@Composable
private fun Customers(data: Workspace, add: () -> Unit, open: (String) -> Unit, selectForCall: Boolean = false) {
    var search by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    if (!selectForCall) Primary("+ Add Customer", onClick = add)
    Field("Search customers", search, { search = it })
    if (!selectForCall) Choice("Show", listOf(false, true), archived, { if (it) "Archived" else "Active" }) { archived = it }
    val rows = data.customers.filter { it.archived == archived && listOf(it.displayName, it.phoneNumber, it.company, it.tags)
        .any { value -> value.contains(search.trim(), ignoreCase = true) } }
    if (rows.isEmpty()) Panel { Text("No customers in this view.") }
    var limit by rememberSaveable(archived, search) { mutableIntStateOf(40) }
    rows.take(limit).forEach { record ->
        Card(onClick = { open(record.id) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(record.displayName)
                Column(Modifier.weight(1f)) {
                    Text(record.displayName, fontWeight = FontWeight.Bold)
                    Text(record.company.ifBlank { record.phoneNumber.ifBlank { record.stage.title } })
                }
                Text(record.stage.title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (rows.size > limit) OutlinedButton({ limit += 40 }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Show more") }
}

@Composable
private fun Avatar(name: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).padding(6.dp), contentAlignment = Alignment.Center) {
            Text(initials(name), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CustomerForm(record: Customer?, demo: Boolean, save: (Customer) -> Unit) {
    var name by rememberSaveable(record?.id) { mutableStateOf(record?.displayName.orEmpty()) }
    var phone by rememberSaveable(record?.id) { mutableStateOf(record?.phoneNumber.orEmpty()) }
    var email by rememberSaveable(record?.id) { mutableStateOf(record?.email.orEmpty()) }
    var company by rememberSaveable(record?.id) { mutableStateOf(record?.company.orEmpty()) }
    var tags by rememberSaveable(record?.id) { mutableStateOf(record?.tags.orEmpty()) }
    var note by rememberSaveable(record?.id) { mutableStateOf(record?.notes.orEmpty()) }
    var priority by rememberSaveable(record?.id) { mutableStateOf(record?.priority ?: Priority.NORMAL) }
    Field("Name", name, { name = it.take(100) }); Field("Phone", phone, { phone = it.take(40) }, phone = true)
    Field("Email (optional)", email, { email = it.take(160) }); Field("Company", company, { company = it.take(150) })
    Field("Tags", tags, { tags = it.take(200) }); Field("Notes", note, { note = it.take(3000) }, 3)
    Choice("Priority", Priority.entries, priority, { it.title }) { priority = it }
    if (name.isNotBlank() && !validCustomer(name, phone)) Text("Enter a valid phone number or leave it blank.", color = MaterialTheme.colorScheme.error)
    Primary("Save Customer", validCustomer(name, phone) && (email.isBlank() || android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches())) {
        save((record ?: Customer(displayName = name, phoneNumber = phone, demo = demo))
            .copy(displayName = name, phoneNumber = phone, email = email.trim(), company = company.trim(), tags = tags.trim(), notes = note.trim(), priority = priority))
    }
}

@Composable
private fun CustomerDetail(record: Customer, data: Workspace, edit: () -> Unit, call: () -> Unit, add: () -> Unit,
                           followDetail: (FollowUp) -> Unit, save: (Customer) -> Unit) {
    var tab by rememberSaveable(record.id) { mutableStateOf("Overview") }
    var historyLimit by rememberSaveable(record.id) { mutableIntStateOf(40) }
    var confirm by remember { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(record.displayName)
            Column(Modifier.weight(1f)) {
                Text(record.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (record.company.isNotBlank()) Text(record.company)
                Text(if (record.archived) "Archived" else record.priority.title, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf("Overview", "History", "Notes").forEachIndexed { index, label ->
            SegmentedButton(tab == label, { tab = label }, SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
        }
    }
    val history = data.timeline.filter { it.customerId == record.id }
    val open = data.followUps.filter { it.customerId == record.id && it.status == FollowStatus.OPEN }.sortedBy { it.dueAt }
    when (tab) {
        "Overview" -> {
            Panel {
                Section("Customer Overview")
                Choice("Status", Stage.entries, record.stage, { it.title }) { save(record.copy(stage = it)) }
                LinearProgressIndicator(progress = { (record.stage.ordinal + 1) / 4f }, modifier = Modifier.fillMaxWidth())
                if (record.phoneNumber.isNotBlank()) Text("Phone · ${record.phoneNumber}")
                if (record.email.isNotBlank()) Text("Email · ${record.email}")
                if (record.tags.isNotBlank()) Text("Tags · ${record.tags}")
                Text("Last contact · ${data.calls.firstOrNull { it.customerId == record.id }?.let { dateLabel(it.endedAt) } ?: "None yet"}")
                Text("Next action · ${open.firstOrNull()?.purpose ?: "None scheduled"}")
            }
            open.forEach { row -> OutlinedButton({ followDetail(row) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("${row.purpose} · ${dateLabel(row.dueAt)}")
            } }
            Section("Interaction History (${history.size})")
            history.take(4).forEach { TimelineLine(it) }
        }
        "History" -> {
            if (history.isEmpty()) Text("No activity yet.")
            history.take(historyLimit).forEach { TimelineLine(it) }
            if (history.size > historyLimit) OutlinedButton({ historyLimit += 40 }) { Text("Show more") }
        }
        "Notes" -> Panel { Text(record.notes.ifBlank { "No customer notes yet." }) }
    }
    if (!record.archived) {
        Primary("Call ${record.displayName}", onClick = call)
        OutlinedButton(add, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("+ Add Follow-up") }
    }
    TextButton(edit) { Text("Edit customer") }
    TextButton({ confirm = true }) { Text(if (record.archived) "Restore customer" else "Archive customer") }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(if (record.archived) "Restore customer?" else "Archive customer?") },
        text = { Text("The customer’s history will be retained.") }, confirmButton = {
            TextButton({ confirm = false; save(record.copy(archived = !record.archived)) }) { Text("Confirm") }
        }, dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

@Composable
private fun TimelineLine(event: TimelineEvent) = Panel {
    Text("●  ${event.eventType}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Text(dateLabel(event.eventTime), style = MaterialTheme.typography.bodySmall)
    if (event.displayPayload.isNotBlank()) Text(event.displayPayload)
}

@Composable
private fun FollowDetail(row: FollowUp, customer: Customer?, edit: () -> Unit, call: () -> Unit, delete: (FollowUp) -> Unit,
                         changeStatus: (FollowStatus) -> Unit, openCustomer: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Panel {
        Section(row.purpose)
        Text(customer?.displayName.orEmpty())
        Text("${dateLabel(row.dueAt)} · ${row.timezoneId}")
        Text("${row.priority.title} · ${row.status.name.lowercase().replaceFirstChar(Char::uppercase)}")
        Text("Reminder · ${row.reminderPolicy.title} (${row.reminderStatus})")
        if (row.note.isNotBlank()) Text(row.note)
    }
    OutlinedButton(openCustomer, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open Customer") }
    if (customer?.archived == false && row.status != FollowStatus.DELETED) {
        Primary(if (row.status == FollowStatus.DONE) "Reopen" else "Mark Done") {
            changeStatus(if (row.status == FollowStatus.DONE) FollowStatus.OPEN else FollowStatus.DONE)
        }
        OutlinedButton(call, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Log a Call") }
        TextButton(edit) { Text("Edit follow-up") }
        TextButton({ confirm = true }) { Text("Delete follow-up") }
    } else if (row.status == FollowStatus.DELETED) Primary("Restore follow-up") { changeStatus(FollowStatus.OPEN) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete this follow-up?") },
        text = { Text("You can undo this or restore it from the Deleted view.") },
        confirmButton = { TextButton({ confirm = false; delete(row) }) { Text("Delete") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}
