package com.followup.tracker

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
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
        val points = listOf(Offset(.19f, .69f), Offset(.40f, .49f), Offset(.59f, .57f), Offset(.82f, .25f))
            .map { Offset(it.x * size.width, it.y * size.height) }
        drawLine(color, Offset(size.width * .14f, size.height * .82f),
            Offset(size.width * .87f, size.height * .82f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        points.zipWithNext().forEach { (start, end) -> drawLine(color, start, end, 1.7.dp.toPx(), cap = StrokeCap.Round) }
        points.forEach { drawCircle(color, 1.5.dp.toPx(), it) }
    }
}

@Composable
internal fun OverviewGlyph() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val cell = size.width * .29f
        for (x in listOf(.16f,.55f)) for (y in listOf(.16f,.55f)) {
            drawRoundRect(color, Offset(size.width * x,size.height * y), Size(cell,cell),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = Stroke(1.65.dp.toPx()))
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
    val overview = remember(data, time) { todayOverview(data, time, zone()) }
    val calls = overview.total
    val issues = data.calls.count { localDay(it.endedAt) == today && it.outcome == Outcome.ISSUE }
    val activeCustomers = data.customers.filterNot { it.archived }.mapTo(hashSetOf()) { it.id }
    val followUps = data.followUps.count { it.customerId in activeCustomers && it.status == FollowStatus.OPEN && localDay(it.dueAt) == today }

    val hello = listOf(greeting(time, zone()), data.settings.operatorName).filter { it.isNotBlank() }.joinToString(", ")
    NeonRoad(snapshot, hello, period, setPeriod, playIntro, onIntroFinished)

    Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Deal amount today", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val amounts = overview.deals.amounts.ifEmpty { mapOf("PKR" to "0") }
        amounts.forEach { (currency, amount) ->
            Text("$currency $amount", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
        }
    }
    TodayCallChart(overview)

    val progress = (calls.toFloat() / data.settings.goal).coerceAtMost(1f)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(30.dp), strokeWidth = 3.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
        MissionTile("Call log", calls, Icons.Outlined.Phone, Modifier.weight(1f), showCalls)
        MissionTile("Follow-ups", followUps, Icons.Outlined.DateRange, Modifier.weight(1f), showAgenda)
        MissionTile("Review", issues, Icons.Outlined.Info, Modifier.weight(1f), showCalls)
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
private fun TodayCallChart(data: TodayOverview) {
    val segments = listOf(Triple("Successful", data.successful, cyan), Triple("Pending", data.pending, violet),
        Triple("Other outcomes", data.other, blue))
    val shade = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Calls today", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(98.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.matchParentSize()) {
                    val thickness = 12.dp.toPx()
                    val inset = thickness / 2
                    val area = Size(size.width - thickness, size.height - thickness)
                    drawArc(shade, -90f, 360f, false, Offset(inset, inset), area, style = Stroke(thickness))
                    if (data.total > 0) {
                        var start = -90f
                        segments.forEach { (_, count, color) ->
                            val sweep = 360f * count / data.total
                            if (count > 0) drawArc(color, start, sweep, false, Offset(inset, inset), area, style = Stroke(thickness))
                            start += sweep
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(data.total.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Calls", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                segments.forEach { (label, count, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(count.toString(), style = MaterialTheme.typography.titleSmall)
                    }
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

private data class RoadStop(val x: Float, val y: Float, val cardX: Float, val width: Float, val puck: Int)
private val roadStops = listOf(
    RoadStop(.158f, .255f, .035f, .37f, R.drawable.road_puck_violet),
    RoadStop(.819f, .426f, .55f, .42f, R.drawable.road_puck_blue),
    RoadStop(.151f, .615f, .032f, .40f, R.drawable.road_puck_violet),
    RoadStop(.734f, .806f, .55f, .42f, R.drawable.road_puck_cyan)
)

private fun routeTo(stop: Int): PathMeasure {
    val path = Path().apply {
        moveTo(.962f, .955f)
        for (index in 3 downTo stop) {
            val target = roadStops[index]
            when (index) {
                3 -> cubicTo(.925f, .910f, .826f, .849f, target.x, target.y)
                2 -> cubicTo(.57f, .744f, .14f, .748f, target.x, target.y)
                1 -> cubicTo(.14f, .518f, .88f, .546f, target.x, target.y)
                else -> cubicTo(.78f, .339f, .12f, .355f, target.x, target.y)
            }
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
            if (motionEnabled) animation.animateTo(1f, tween(3900, easing = LinearEasing))
            onIntroFinished()
        } else animation.snapTo(1f)
    }
    Box(Modifier.fillMaxWidth().clip(shape).background(Color(0xff110a35))
        .border(1.dp, Color(0xff7563ac).copy(alpha = .58f), shape)) {
        Image(painterResource(R.drawable.neon_road), null, Modifier.matchParentSize(), alignment = Alignment.BottomCenter,
            contentScale = ContentScale.FillWidth)
        Column {
            Column(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 11.dp, bottom = 7.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("$greeting!", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(period.title, color = Color(0xffdfd5ff), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 3.dp))
            }
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.14f)) {
                val width = maxWidth
                val height = maxHeight
                val names = listOf("New", "Contacted", "Follow-up", "Successful")
                val counts = listOf(data.new, data.contacted, data.followUps, data.deals.successful)
                val icons = listOf(Icons.Outlined.Person, Icons.Outlined.Phone, Icons.Outlined.DateRange, Icons.Outlined.CheckCircle)
                val colors = listOf(violet, blue, violet, cyan)
                val elapsed = animation.value * 3900
                roadStops.forEachIndexed { index, stop ->
                    val delay = (3 - index) * 100f
                    val route = routes[index]
                    val duration = 2700f * route.length / routes[0].length
                    val travel = FastOutSlowInEasing.transform(((elapsed - delay) / duration).coerceIn(0f, 1f))
                    if (elapsed >= delay) {
                        val point = if (travel >= 1f) Offset(stop.x, stop.y) else route.getPosition(route.length * travel)
                        Image(painterResource(stop.puck), null, Modifier.offset(width * point.x - 14.dp, height * point.y - 7.dp)
                            .size(28.dp, 14.dp), contentScale = ContentScale.FillBounds)
                    }
                    val show = FastOutSlowInEasing.transform(((elapsed - 3000f - (3 - index) * 60f) / 700f).coerceIn(0f, 1f))
                    if (show > 0f) {
                        val tip = (stop.x - stop.cardX) / stop.width
                        JourneyPin(names[index], counts[index], icons[index], colors[index], tip,
                            Modifier.offset(width * stop.cardX, height * stop.y).width(width * stop.width)
                                .graphicsLayer {
                                    translationY = -size.height - 5.dp.toPx()
                                    transformOrigin = TransformOrigin(tip, 1f)
                                    alpha = show
                                    scaleX = .88f + .12f * show
                                    scaleY = .88f + .12f * show
                                })
                    }
                }
                DotMenu("Select journey period", Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 8.dp)
                    .clip(CircleShape).background(Color(0xff201946).copy(alpha = .84f)), Color(0xfff0e6ff)) { close ->
                    ReportPeriod.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(if (option == period) "✓  ${option.title}" else option.title) },
                            onClick = { close(); choose(option) })
                    }
                }
            }
        }
    }
}

@Composable
private fun JourneyPin(label: String, count: Int, icon: ImageVector, tint: Color, tip: Float, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier) {
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
        Canvas(Modifier.fillMaxWidth().height(9.dp)) {
            val x = size.width * tip
            val half = 9.dp.toPx()
            val pointer = Path().apply { moveTo(x - half, 0f); lineTo(x + half, 0f); lineTo(x, size.height); close() }
            drawPath(pointer, tint.copy(alpha = .86f))
        }
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
