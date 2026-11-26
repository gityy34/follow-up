package com.followup.tracker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val violet = Color(0xffecaaff)
private val blue = Color(0xffa1c8ff)
private val cyan = Color(0xff80f5ec)

@Composable
internal fun DotMenu(description: String, modifier: Modifier = Modifier, color: Color = LocalContentColor.current,
                    content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.MoreVert, description, tint = color) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

@Composable
internal fun TrendGlyph() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        for (index in 0..3) {
            val x = size.width * (.19f + index * .205f)
            drawLine(color, Offset(x, size.height * (.68f - index * .155f)), Offset(x, size.height * .88f),
                strokeWidth = 1.9.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun ProfileDialog(settings: AppSettings, initial: Boolean, onDismiss: () -> Unit,
                           save: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(settings.operatorName) }
    var display by rememberSaveable { mutableStateOf(settings.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial) "Make it yours" else "Edit profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Your name", name, { name = it.take(60) })
                Field("Dashboard display name (optional)", display, { display = it.take(60) })
            }
        },
        confirmButton = { TextButton(onClick = { save(name.trim(), display.trim()) },
            enabled = !initial || name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (initial) "Not now" else "Cancel") } }
    )
}

@Composable
internal fun NeonJourney(data: Workspace, time: Long, period: ReportPeriod, setPeriod: (ReportPeriod) -> Unit,
                         start: () -> Unit, pending: () -> Unit, settings: () -> Unit,
                         showCalls: () -> Unit, showAgenda: () -> Unit, openCustomer: (String) -> Unit) {
    val snapshot = remember(data, period, time) { journeySnapshot(data, period, time, zone()) }
    val today = localDay(time)
    val calls = data.calls.count { localDay(it.endedAt) == today }
    val issues = data.calls.count { localDay(it.endedAt) == today && it.outcome == Outcome.ISSUE }
    val activeCustomers = data.customers.filterNot { it.archived }.mapTo(hashSetOf()) { it.id }
    val followUps = data.followUps.count { it.customerId in activeCustomers && it.status == FollowStatus.OPEN && localDay(it.dueAt) == today }

    NeonRoad(snapshot, greeting(time, zone()), period, setPeriod)

    Panel {
        Text("Deal amount", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (snapshot.deals.amounts.isEmpty()) Text("${data.settings.currency} 0")
        else snapshot.deals.amounts.forEach { (currency, amount) ->
            Text("$currency $amount", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Today’s Mission", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            DotMenu("Mission options") { close ->
                DropdownMenuItem(text = { Text("Start call session") }, onClick = { close(); start() })
                val count = data.calls.count { it.outcome == Outcome.PENDING }
                DropdownMenuItem(text = { Text("Pending calls ($count)") }, onClick = { close(); pending() })
                DropdownMenuItem(text = { Text("Change call goal") }, onClick = { close(); settings() })
            }
        }
        Text("Complete ${data.settings.goal} calls today.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { (calls.toFloat() / data.settings.goal).coerceAtMost(1f) },
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape))
        Text("$calls of ${data.settings.goal} calls completed", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MissionTile("Calls", calls, Icons.Outlined.Phone, Modifier.weight(1f), showCalls)
        MissionTile("Follow-ups", followUps, Icons.Outlined.DateRange, Modifier.weight(1f), showAgenda)
        MissionTile("Needs review", issues, Icons.Outlined.Info, Modifier.weight(1f), showCalls)
    }
    var showActivity by rememberSaveable { mutableStateOf(false) }
    Panel {
        TextButton(onClick = { showActivity = !showActivity }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showActivity) "Hide activity and next action" else "Activity and next action")
        }
        if (showActivity) {
            val customers = data.customers.associateBy { it.id }
            val next = data.followUps.filter { it.customerId in activeCustomers && it.status == FollowStatus.OPEN }
                .minByOrNull { it.dueAt }
            Section("Next Best Action")
            Text(next?.let { "${customers[it.customerId]?.displayName.orEmpty()} · ${it.purpose}" }
                ?: "Add a customer and schedule the next call.")
            TextButton(onClick = showAgenda) { Text("Open Agenda") }
            Section("Recent Activity")
            if (data.timeline.isEmpty()) Text("Saved activity will appear here.")
            data.timeline.take(4).forEach { event ->
                TextButton(onClick = { openCustomer(event.customerId) }) {
                    Text("${event.eventType} · ${customers[event.customerId]?.displayName.orEmpty()}")
                }
            }
        }
    }

}

@Composable
private fun MissionTile(label: String, value: Int, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = modifier,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NeonRoad(data: JourneySnapshot, greeting: String, period: ReportPeriod, choose: (ReportPeriod) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(.94f).clip(shape)
        .background(Color(0xff151843)).border(1.dp, Color(0xff7563ac).copy(alpha = .58f), shape)) {
        val width = maxWidth
        val height = maxHeight
        Image(painterResource(R.drawable.neon_road), null, Modifier.matchParentSize(), alignment = Alignment.BottomCenter,
            contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x66090425), Color.Transparent), endY = 285f)))
        Column(Modifier.padding(horizontal = 18.dp, vertical = 17.dp)) {
            Text("$greeting!", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (period == ReportPeriod.TODAY) "Here’s your customer journey today." else "Customer journey · ${period.title}",
                color = Color(0xffdfd5ff), style = MaterialTheme.typography.bodyMedium)
        }
        JourneyPin("New", data.new, Icons.Outlined.Person, violet, Modifier.offset(width * .09f, height * .197f).width(width * .37f))
        JourneyPin("Contacted", data.contacted, Icons.Outlined.Phone, blue, Modifier.offset(width * .56f, height * .326f).width(width * .39f))
        JourneyPin("Follow-up", data.followUps, Icons.Outlined.DateRange, violet, Modifier.offset(width * .09f, height * .515f).width(width * .40f))
        JourneyPin("Resolved", data.deals.successful, Icons.Outlined.CheckCircle, cyan,
            Modifier.offset(width * .55f, height * .661f).width(width * .40f))
        DotMenu("Select journey period", Modifier.offset(width * .39f, height * .655f)
            .clip(CircleShape).background(Color(0xff201946).copy(alpha = .86f)), Color(0xfff0e6ff)) { close ->
            ReportPeriod.entries.forEach { option ->
                DropdownMenuItem(text = { Text(if (option == period) "✓  ${option.title}" else option.title) },
                    onClick = { close(); choose(option) })
            }
        }
    }
}

@Composable
private fun JourneyPin(label: String, count: Int, icon: ImageVector, tint: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = .34f), Color(0xff211e48).copy(alpha = .87f))))
            .border(1.dp, tint.copy(alpha = .65f), shape).padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Column {
                Text(label, color = Color(0xfff2eeff), style = MaterialTheme.typography.labelMedium)
                Text(count.toString(), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        Canvas(Modifier.size(20.dp, 9.dp)) {
            val pointer = Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close() }
            drawPath(pointer, tint.copy(alpha = .86f))
        }
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(12.dp, 7.dp).clip(CircleShape).background(tint))
    }
}

@Composable
internal fun ReminderList(data: Workspace, rows: List<ScheduledReminder>, day: ReminderDay, open: (FollowUp) -> Unit) {
    val customers = data.customers.associateBy { it.id }
    Panel {
        Text("${day.title} · ${rows.size} reminders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (rows.isEmpty()) Text("No reminders scheduled for this day.")
    }
    rows.forEach { reminder ->
        val follow = reminder.followUp
        OutlinedCard(onClick = { open(follow) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(follow.purpose, fontWeight = FontWeight.Bold)
                Text(customers[follow.customerId]?.displayName.orEmpty())
                Text("Reminder · ${dateLabel(reminder.at)}", color = MaterialTheme.colorScheme.primary)
                Text("Follow-up due · ${dateLabel(follow.dueAt)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
