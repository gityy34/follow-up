package com.followup.tracker

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val displayDateFormat = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ENGLISH)
internal fun dateLabel(time: Long) = displayDateFormat.format(Instant.ofEpochMilli(time).atZone(zone()))
internal fun localDay(time: Long) = Instant.ofEpochMilli(time).atZone(zone()).toLocalDate()
internal fun initials(value: String) = value.trim().split(Regex("\\s+")).take(2).joinToString("") { it.firstOrNull()?.uppercase().orEmpty() }

@Composable
@OptIn(ExperimentalTextApi::class)
internal fun JourneyTheme(dark: Boolean, palette: String, font: AppFont, content: @Composable () -> Unit) {
    val lightPrimary = when (palette) { "Ocean" -> Color(0xff285ab1); "Forest" -> Color(0xff1c6956); else -> Color(0xff662fcd) }
    val darkPrimary = when (palette) { "Ocean" -> Color(0xffafc8ff); "Forest" -> Color(0xff8ed4b9); else -> Color(0xffd4b7ff) }
    val colors = if (dark) darkColorScheme(
        primary = darkPrimary, onPrimary = Color(0xff28164c), primaryContainer = Color(0xff392b68), onPrimaryContainer = Color(0xffeee3ff),
        secondaryContainer = Color(0xff413572), onSecondaryContainer = Color(0xfff3eaff), tertiary = Color(0xff86eee9),
        background = Color(0xff15182d), onBackground = Color(0xfff2efff), surface = Color(0xff202440),
        surfaceVariant = Color(0xff2c3153), onSurface = Color(0xfff3f0ff), onSurfaceVariant = Color(0xffccd0ee),
        outline = Color(0xff858abd), outlineVariant = Color(0xff383d62), error = Color(0xffffaebf))
    else lightColorScheme(primary = lightPrimary, background = Color(0xfff7f4ff), surface = Color.White,
        surfaceVariant = Color(0xffeee7fa), onSurface = Color(0xff241634), onSurfaceVariant = Color(0xff514562),
        primaryContainer = Color(0xffeee1ff), onPrimaryContainer = Color(0xff31135e), error = Color(0xffa41c4b))
    val typography = remember(font) {
        val base = Typography()
        val family = when (font) {
            AppFont.DEFAULT -> FontFamily.SansSerif
            AppFont.DEVICE -> FontFamily.Default
            AppFont.MODERN -> FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
                Font(R.font.manrope, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
            })
            AppFont.EDITORIAL -> FontFamily.Serif
            AppFont.MONO -> FontFamily.Monospace
        }
        Typography(
            displayLarge = base.displayLarge.copy(fontFamily = family), displayMedium = base.displayMedium.copy(fontFamily = family),
            displaySmall = base.displaySmall.copy(fontFamily = family), headlineLarge = base.headlineLarge.copy(fontFamily = family),
            headlineMedium = base.headlineMedium.copy(fontFamily = family), headlineSmall = base.headlineSmall.copy(fontFamily = family),
            titleLarge = base.titleLarge.copy(fontFamily = family), titleMedium = base.titleMedium.copy(fontFamily = family),
            titleSmall = base.titleSmall.copy(fontFamily = family), bodyLarge = base.bodyLarge.copy(fontFamily = family),
            bodyMedium = base.bodyMedium.copy(fontFamily = family), bodySmall = base.bodySmall.copy(fontFamily = family),
            labelLarge = base.labelLarge.copy(fontFamily = family), labelMedium = base.labelMedium.copy(fontFamily = family),
            labelSmall = base.labelSmall.copy(fontFamily = family)
        )
    }
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

@Composable
internal fun JourneyScreen(model: JourneyModel, workspace: Workspace, incoming: String?, dismissIncoming: () -> Unit, secureDevice: () -> Boolean) {
    var route by rememberSaveable { mutableStateOf("Journey") }
    var customerId by rememberSaveable { mutableStateOf("") }
    var followId by rememberSaveable { mutableStateOf("") }
    var backTo by rememberSaveable { mutableStateOf("Journey") }
    var pendingId by rememberSaveable { mutableStateOf("") }
    var period by rememberSaveable { mutableStateOf(ReportPeriod.TODAY) }
    var photoEditor by rememberSaveable { mutableStateOf(false) }
    var roadIntroPlayed by rememberSaveable { mutableStateOf(false) }
    var reminderDay by rememberSaveable { mutableStateOf(ReminderDay.TODAY) }
    var currentTime by remember { mutableLongStateOf(now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = now()
            delay(60_000L - currentTime % 60_000L)
        }
    }
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
        calls = workspace.calls.filter { it.customerId in ids }, timeline = workspace.timeline.filter { it.customerId in ids },
        payments = if (activeDemo) emptyList() else workspace.payments)
    val customer = workspace.customers.firstOrNull { it.id == customerId }
    val follow = workspace.followUps.firstOrNull { it.id == followId }
    val top = setOf("Journey", "Customers", "Agenda", "Performance")
    val activeTime = maxOf(currentTime, now())
    val timezone = zone()
    val todayReminders = remember(data, timezone, localDay(activeTime)) { remindersFor(data, ReminderDay.TODAY, activeTime, timezone) }
    fun startSession() {
        backTo = "Journey"
        if (visible.any { !it.archived }) route = "Start Call Session"
        else { customerId = ""; editingParent = "Journey"; route = "Customer Form" }
    }
    fun back() {
        route = when (route) {
            "Add Follow-up" -> wizardParent
            "Post-Call Reflection" -> callParent
            "Follow-up Detail" -> followParent
            "Customer Form" -> editingParent
            "Settings", "Pending", "Reminders" -> "Journey"
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
    fun saveProfile(name: String, display: String, newProfile: Boolean) {
        scope.launch {
            try {
                val settings = repo.settings.current()
                repo.settings.set(settings.copy(operatorName = name, displayName = display, profileConfigured = true,
                    profileReview = PROFILE_REVIEW, photo = if (newProfile) "" else settings.photo,
                    photoSource = if (newProfile) "" else settings.photoSource))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { snackbar.showSnackbar("The profile could not be saved.") }
        }
    }
    if (photoEditor) ProfilePhotoEditor(workspace.settings, { photoEditor = false }) { photo, source ->
        mutate(if (photo.isEmpty()) "Profile photo removed" else "Profile photo updated",
            { repo.settings.set(repo.settings.current().copy(photo = photo, photoSource = source)) }) { photoEditor = false }
    }
    val needsProfileReview = workspace.settings.profileReview < PROFILE_REVIEW
    if (route == "Journey" && needsProfileReview) {
        ProfileDialog(workspace.settings, true, {
            saveProfile(workspace.settings.operatorName, workspace.settings.displayName, false)
        }, ::saveProfile)
    }
    BackHandler(route != "Journey", onBack = ::back)
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.statusBarsPadding().fillMaxWidth().heightIn(min = 59.dp).padding(horizontal = 14.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (route == "Journey") {
                        IconButton(onClick = { photoEditor = true }) { ProfileAvatar(workspace.settings) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(workspace.settings.displayName.ifBlank { "Customer Journey" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(localDay(activeTime).format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { reminderDay = ReminderDay.TODAY; route = "Reminders" }) {
                            BadgedBox(badge = { if (todayReminders.isNotEmpty()) Badge { Text(todayReminders.size.toString()) } }) {
                                Icon(Icons.Outlined.Notifications, "Reminders, ${todayReminders.size} today")
                            }
                        }
                    } else {
                        IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Go back") }
                        Text(when (route) {
                            "Customers" -> "Contacts"
                            "Agenda" -> "Schedule"
                            "Performance" -> "Insights"
                            else -> route
                        }, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    if (activeDemo) Text("Demo", Modifier.padding(end = 3.dp), color = MaterialTheme.colorScheme.primary)
                    if (route == "Reminders") {
                        DotMenu("Select reminder day") { close ->
                            ReminderDay.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(if (option == reminderDay) "✓  ${option.title}" else option.title) },
                                    onClick = { close(); reminderDay = option })
                            }
                        }
                    } else if (route in top) {
                        DotMenu("More options") { close ->
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { close(); route = "Settings" })
                            DropdownMenuItem(text = { Text("Help · Coming soon") }, enabled = false, onClick = {})
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (route in top) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Journey" to "Overview", "Customers" to "Contacts", "Agenda" to "Schedule",
                    "Performance" to "Insights").forEach { (screen, label) ->
                    NavigationBarItem(selected = route == screen, onClick = { route = screen }, icon = {
                        when (screen) {
                            "Journey" -> OverviewGlyph()
                            "Customers" -> Icon(Icons.Outlined.AccountCircle, null)
                            "Agenda" -> Icon(Icons.AutoMirrored.Outlined.List, null)
                            else -> TrendGlyph()
                        }
                    }, label = { Text(label, maxLines = 1) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { bars ->
        Box(Modifier.fillMaxSize().padding(bars), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 630.dp).fillMaxSize().imePadding().verticalScroll(remember(route) { ScrollState(0) })
                .padding(horizontal = 13.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            key(route) {
                when (route) {
                    "Journey" -> NeonJourney(data, activeTime, period, { period = it }, ::startSession,
                        { route = "Pending" }, { route = "Settings" }, { period = ReportPeriod.TODAY; route = "Performance" },
                        { route = "Agenda" }, ::viewCustomer, !roadIntroPlayed && !needsProfileReview,
                        onIntroFinished = { roadIntroPlayed = true }, onRefresh = { currentTime = now() },
                        changePaymentPeriod = repo.settings::setPaymentPeriod)
                    "Reminders" -> {
                        val rows = remember(data, reminderDay, timezone, localDay(activeTime)) { remindersFor(data, reminderDay, activeTime, timezone) }
                        ReminderList(data, rows, reminderDay) { row ->
                            backTo = "Reminders"; followParent = "Reminders"; customerId = row.customerId
                            followId = row.id; route = "Follow-up Detail"
                        }
                    }
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
                    "Performance" -> {
                        if (!activeDemo) PaymentLedger(data, activeTime, repo)
                        Performance(data, period) { period = it }
                    }
                    "Settings" -> SettingsPanel(workspace, model, secureDevice, activeDemo, { activeDemo = it }, snackbar, { photoEditor = true })
                }
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
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
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
