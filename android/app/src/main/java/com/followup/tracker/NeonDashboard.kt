package com.followup.tracker

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.graphicsLayer
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
internal fun NeonJourney(data: Workspace, time: Long, period: ReportPeriod, setPeriod: (ReportPeriod) -> Unit,
                         start: () -> Unit, pending: () -> Unit, settings: () -> Unit,
                         showCalls: () -> Unit, showAgenda: () -> Unit, openCustomer: (String) -> Unit,
                         playIntro: Boolean, onIntroFinished: () -> Unit) {
    val snapshot = remember(data, period, time) { journeySnapshot(data, period, time, zone()) }
    val today = localDay(time)
    val calls = data.calls.count { localDay(it.endedAt) == today }
    val issues = data.calls.count { localDay(it.endedAt) == today && it.outcome == Outcome.ISSUE }
    val activeCustomers = data.customers.filterNot { it.archived }.mapTo(hashSetOf()) { it.id }
    val followUps = data.followUps.count { it.customerId in activeCustomers && it.status == FollowStatus.OPEN && localDay(it.dueAt) == today }

    val hello = listOf(greeting(time, zone()), data.settings.operatorName).filter { it.isNotBlank() }.joinToString(", ")
    NeonRoad(snapshot, hello, period, setPeriod, playIntro, onIntroFinished)

    val progress = (calls.toFloat() / data.settings.goal).coerceAtMost(1f)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Deal amount", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val amounts = snapshot.deals.amounts.ifEmpty { mapOf(data.settings.currency to "0") }
            amounts.forEach { (currency, amount) ->
                Text("$currency $amount", style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(30.dp), strokeWidth = 3.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant)
        Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Today’s mission", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$calls / ${data.settings.goal} calls", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        DotMenu("Mission options") { close ->
            DropdownMenuItem(text = { Text("Start call session") }, onClick = { close(); start() })
            val count = data.calls.count { it.outcome == Outcome.PENDING }
            DropdownMenuItem(text = { Text("Pending calls ($count)") }, onClick = { close(); pending() })
            DropdownMenuItem(text = { Text("Change call goal") }, onClick = { close(); settings() })
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

private data class RoadStop(val x: Float, val y: Float, val cardX: Float, val cardY: Float, val width: Float)
private val roadStops = listOf(
    RoadStop(.28f, .25f, .09f, .023f, .37f),
    RoadStop(.76f, .43f, .55f, .203f, .42f),
    RoadStop(.27f, .63f, .07f, .406f, .40f),
    RoadStop(.76f, .83f, .55f, .606f, .42f)
)

private fun routeTo(stop: Int): PathMeasure {
    val path = Path().apply {
        moveTo(.96f, .95f)
        for (index in 3 downTo stop) {
            val target = roadStops[index]
            val x = when (index) { 3 -> .88f; 2 -> .12f; 1 -> .91f; else -> .15f }
            cubicTo(x, target.y + .13f, x, target.y, target.x, target.y)
        }
    }
    return PathMeasure().apply { setPath(path, false) }
}

@Composable
private fun NeonRoad(data: JourneySnapshot, greeting: String, period: ReportPeriod, choose: (ReportPeriod) -> Unit,
                     playIntro: Boolean, onIntroFinished: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val animation = remember(playIntro) { Animatable(if (playIntro && motionEnabled) 0f else 1f) }
    val routes = remember { roadStops.indices.map(::routeTo) }
    LaunchedEffect(playIntro) {
        if (playIntro) {
            if (motionEnabled) {
                animation.animateTo(1f, tween(2000, easing = LinearEasing))
            }
            onIntroFinished()
        } else animation.snapTo(1f)
    }
    Column(Modifier.fillMaxWidth().clip(shape)
        .background(Brush.horizontalGradient(listOf(Color(0xff361661), Color(0xff141942))))
        .border(1.dp, Color(0xff7563ac).copy(alpha = .58f), shape)) {
        Column(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 11.dp, bottom = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("$greeting!", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(period.title, color = Color(0xffdfd5ff), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xff171b43).copy(alpha = .72f))
                    .padding(horizontal = 8.dp, vertical = 3.dp))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.14f)) {
            val width = maxWidth
            val height = maxHeight
            Image(painterResource(R.drawable.neon_road), null, Modifier.matchParentSize(), alignment = Alignment.BottomCenter,
                contentScale = ContentScale.Crop)
            val names = listOf("New", "Contacted", "Follow-up", "Successful")
            val counts = listOf(data.new, data.contacted, data.followUps, data.deals.successful)
            val icons = listOf(Icons.Outlined.Person, Icons.Outlined.Phone, Icons.Outlined.DateRange, Icons.Outlined.CheckCircle)
            val colors = listOf(violet, blue, violet, cyan)
            val intro = animation.value
            roadStops.forEachIndexed { index, stop ->
                val start = (3 - index) * .072f
                val end = .82f - index * .12f
                val travel = ((intro - start) / (end - start)).coerceIn(0f, 1f)
                val show = ((intro - end) / .14f).coerceIn(0f, 1f)
                if (intro >= start && travel < 1f) {
                    val route = routes[index]
                    val point = route.getPosition(route.length * travel)
                    Box(Modifier.offset(width * point.x - 6.dp, height * point.y - 6.dp)
                        .size(12.dp).clip(RoundedCornerShape(4.dp)).background(colors[index]))
                }
                if (show > 0f) JourneyPin(names[index], counts[index], icons[index], colors[index],
                    Modifier.offset(width * stop.cardX, height * stop.cardY).width(width * stop.width)
                        .graphicsLayer { alpha = show; scaleX = .82f + .18f * show; scaleY = .82f + .18f * show })
            }
            DotMenu("Select journey period", Modifier.offset(width * .37f, height * .585f)
                .clip(CircleShape).background(Color(0xff201946).copy(alpha = .86f)), Color(0xfff0e6ff)) { close ->
                ReportPeriod.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(if (option == period) "✓  ${option.title}" else option.title) },
                        onClick = { close(); choose(option) })
                }
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
                Text(count.toString(), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
