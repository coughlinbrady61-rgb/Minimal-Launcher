package com.minimal.launcher

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

// ---------- world clock storage ----------
object WorldClocks {
    private const val KEY = "world_clocks"

    /** short label to timezone id */
    val available: List<Pair<String, String>> = listOf(
        "london" to "Europe/London",
        "paris" to "Europe/Paris",
        "berlin" to "Europe/Berlin",
        "dubai" to "Asia/Dubai",
        "mumbai" to "Asia/Kolkata",
        "singapore" to "Asia/Singapore",
        "hong kong" to "Asia/Hong_Kong",
        "tokyo" to "Asia/Tokyo",
        "seoul" to "Asia/Seoul",
        "sydney" to "Australia/Sydney",
        "adelaide" to "Australia/Adelaide",
        "auckland" to "Pacific/Auckland",
        "honolulu" to "Pacific/Honolulu",
        "anchorage" to "America/Anchorage",
        "los angeles" to "America/Los_Angeles",
        "denver" to "America/Denver",
        "chicago" to "America/Chicago",
        "new york" to "America/New_York",
        "mexico city" to "America/Mexico_City",
        "sao paulo" to "America/Sao_Paulo",
        "buenos aires" to "America/Argentina/Buenos_Aires",
        "reykjavik" to "Atlantic/Reykjavik",
        "lagos" to "Africa/Lagos",
        "cairo" to "Africa/Cairo",
        "johannesburg" to "Africa/Johannesburg",
        "moscow" to "Europe/Moscow",
        "istanbul" to "Europe/Istanbul",
        "bangkok" to "Asia/Bangkok",
        "jakarta" to "Asia/Jakarta",
        "manila" to "Asia/Manila"
    )

    private fun prefs(c: Context) = c.getSharedPreferences("minimal_store", Context.MODE_PRIVATE)

    fun saved(ctx: Context): List<String> = runCatching {
        prefs(ctx).getString(KEY, "")!!.split('\u0001').filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    fun add(ctx: Context, tz: String) {
        val cur = saved(ctx)
        if (tz in cur) return
        prefs(ctx).edit().putString(KEY, (cur + tz).joinToString("\u0001")).apply()
    }

    fun remove(ctx: Context, tz: String) {
        val cur = saved(ctx) - tz
        prefs(ctx).edit().putString(KEY, cur.joinToString("\u0001")).apply()
    }

    fun labelFor(tz: String): String =
        available.firstOrNull { it.second == tz }?.first
            ?: tz.substringAfterLast('/').replace('_', ' ').lowercase()
}

enum class ClockTab(val label: String) {
    ALARM("alarm"), CLOCK("clock"), TIMER("timer"), STOPWATCH("stopwatch")
}

@Composable
fun ClockScreen(scale: Float, onClose: () -> Unit, runIntent: (Intent) -> Unit) {
    var tab by remember { mutableStateOf(ClockTab.CLOCK) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(38.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ ", color = Accent, fontSize = (24 * scale).sp,
                modifier = Modifier.clickable { onClose() }
            )
            Text("clock", color = Ink, fontSize = (24 * scale).sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth()) {
            ClockTab.entries.forEach { t ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f).clickable { tab = t }
                ) {
                    Text(
                        t.label.uppercase(),
                        color = if (tab == t) Ink else Faint,
                        fontFamily = Mono, fontSize = (10 * scale).sp
                    )
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.55f).height(2.dp)
                            .background(if (tab == t) Accent else Color.Transparent)
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        when (tab) {
            ClockTab.CLOCK -> ClockTabView(scale)
            ClockTab.ALARM -> AlarmTabView(scale, runIntent)
            ClockTab.TIMER -> TimerTabView(scale)
            ClockTab.STOPWATCH -> StopwatchTabView(scale)
        }
    }
}

// ---------- clock / world clocks ----------
@Composable
fun ClockTabView(scale: Float) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var picking by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }

    val clocks = remember(refresh) { WorldClocks.saved(ctx) }
    val localTz = TimeZone.getDefault()

    fun fmt(pattern: String, tz: TimeZone): String =
        SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = tz }
            .format(Date(now)).lowercase()

    if (picking) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹ ", color = Accent, fontSize = (20 * scale).sp,
                    modifier = Modifier.clickable { picking = false; query = "" }
                )
                Text("add a city", color = Ink, fontSize = (17 * scale).sp)
            }
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                textStyle = TextStyle(color = Ink, fontFamily = Mono, fontSize = (14 * scale).sp),
                cursorBrush = SolidColor(Accent),
                singleLine = true,
                decorationBox = { inner ->
                    Row {
                        Text("> ", color = Accent, fontFamily = Mono, fontSize = (14 * scale).sp)
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty())
                                Text("search cities", color = Faint, fontFamily = Mono, fontSize = (13 * scale).sp)
                            inner()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Faint, ChipShape)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(
                    WorldClocks.available.filter {
                        query.isBlank() || it.first.contains(query, true)
                    }
                ) { (label, tz) ->
                    Text(
                        label,
                        color = Ink, fontSize = (15 * scale).sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                WorldClocks.add(ctx, tz)
                                picking = false; query = ""; refresh++
                            }
                            .padding(vertical = 10.dp)
                    )
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // big local time
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                fmt("h:mm", localTz),
                color = Ink, fontSize = (52 * scale).sp, fontWeight = FontWeight.Medium
            )
            Text(
                fmt("a", localTz),
                color = Ink, fontSize = (18 * scale).sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
            )
        }
        Text(
            fmt("EEEE, MMMM d", localTz),
            color = Dim, fontSize = (13 * scale).sp,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
        )
        Text(
            WorldClocks.labelFor(localTz.id),
            color = Faint, fontFamily = Mono, fontSize = (11 * scale).sp,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
        )

        Spacer(Modifier.height(26.dp))
        Text("ELSEWHERE", color = Faint, fontFamily = Mono, fontSize = (10 * scale).sp)
        Spacer(Modifier.height(10.dp))

        if (clocks.isEmpty()) {
            Text("no cities added", color = Faint, fontFamily = Mono, fontSize = (12 * scale).sp)
        }

        LazyColumn(Modifier.weight(1f)) {
            items(clocks) { tz ->
                val zone = TimeZone.getTimeZone(tz)
                val offsetMin = (zone.getOffset(now) - localTz.getOffset(now)) / 60000
                val sign = if (offsetMin >= 0) "+" else "-"
                val h = abs(offsetMin) / 60
                val m = abs(offsetMin) % 60
                val offsetLabel = if (m == 0) "$sign${h}h" else "$sign${h}h ${m}m"

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ChipShape)
                        .border(1.dp, Faint, ChipShape)
                        .padding(horizontal = 14.dp, vertical = 11.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(WorldClocks.labelFor(tz), color = Ink, fontSize = (15 * scale).sp,
                            fontWeight = FontWeight.Medium)
                        Text(offsetLabel, color = Dim, fontFamily = Mono, fontSize = (11 * scale).sp)
                    }
                    Text(
                        fmt("h:mm a", zone),
                        color = Ink, fontFamily = Mono, fontSize = (13 * scale).sp
                    )
                    Text(
                        "  ×", color = Faint, fontSize = (14 * scale).sp,
                        modifier = Modifier.clickable { WorldClocks.remove(ctx, tz); refresh++ }
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            item { Spacer(Modifier.height(12.dp)) }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.End)
                .size((40 * scale).dp)
                .clip(ChipShape)
                .border(1.dp, Faint, ChipShape)
                .clickable { picking = true }
        ) {
            Text("+", color = Ink, fontSize = (20 * scale).sp)
        }
        Spacer(Modifier.height(18.dp))
    }
}

// ---------- alarm ----------
@Composable
fun AlarmTabView(scale: Float, runIntent: (Intent) -> Unit) {
    var timeText by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var flash by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(flash) { if (flash != null) { delay(1800); flash = null } }

    Column(Modifier.fillMaxSize()) {
        Text("SET AN ALARM", color = Faint, fontFamily = Mono, fontSize = (10 * scale).sp)
        Spacer(Modifier.height(12.dp))

        LabeledField("time (7:30 pm or 19:30)", timeText, scale) { timeText = it }
        Spacer(Modifier.height(12.dp))
        LabeledField("label (optional)", label, scale) { label = it }

        Spacer(Modifier.height(16.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChipShape)
                .border(1.dp, Accent, ChipShape)
                .clickable {
                    val p = parseTime(timeText)
                    if (p == null) { flash = "couldn't read that time" }
                    else {
                        runIntent(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, p.first)
                            putExtra(AlarmClock.EXTRA_MINUTES, p.second)
                            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        })
                        flash = "alarm set"
                        timeText = ""; label = ""
                    }
                }
                .padding(vertical = 12.dp)
        ) {
            Text("set alarm", color = Accent, fontFamily = Mono, fontSize = (14 * scale).sp)
        }

        flash?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Accent, fontFamily = Mono, fontSize = (12 * scale).sp)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "existing alarms live in the system clock app",
            color = Faint, fontFamily = Mono, fontSize = (11 * scale).sp
        )
        Spacer(Modifier.height(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChipShape)
                .border(1.dp, Faint, ChipShape)
                .clickable { runIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
                .padding(vertical = 11.dp)
        ) {
            Text("open system alarms", color = Dim, fontFamily = Mono, fontSize = (13 * scale).sp)
        }
    }
}

// ---------- timer ----------
@Composable
fun TimerTabView(scale: Float) {
    val ctx = LocalContext.current
    var remaining by remember { mutableStateOf(TimerState.remaining(ctx)) }
    var running by remember { mutableStateOf(TimerState.isRunning(ctx)) }
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(0L) }   // chosen but not started

    // read the clock rather than counting ticks, so time away is accounted for
    LaunchedEffect(Unit) {
        while (true) {
            remaining = TimerState.remaining(ctx)
            running = TimerState.isRunning(ctx)
            delay(250)
        }
    }

    fun display(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    val shown = if (remaining > 0) remaining else pending
    val idle = remaining == 0L && !running

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            display(shown),
            color = if (running) Ink else Dim,
            fontSize = (48 * scale).sp, fontWeight = FontWeight.Medium, fontFamily = Mono
        )
        if (running) {
            Spacer(Modifier.height(4.dp))
            Text("running in background", color = Faint, fontFamily = Mono, fontSize = (10 * scale).sp)
        }

        Spacer(Modifier.height(20.dp))

        if (idle) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 10, 25).forEach { min ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(ChipShape)
                            .border(1.dp, if (pending == min * 60_000L) Accent else Faint, ChipShape)
                            .clickable { pending = min * 60_000L }
                            .padding(vertical = 10.dp)
                    ) {
                        Text("${min}m", color = Ink, fontFamily = Mono, fontSize = (12 * scale).sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LabeledField("or minutes", input, scale) {
                input = it
                val v = it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0
                pending = v * 60_000L
            }
        }

        Spacer(Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(ChipShape)
                    .border(1.dp, Accent, ChipShape)
                    .clickable {
                        when {
                            running -> TimerState.pauseTimer(ctx)
                            TimerState.pausedLeft(ctx) > 0 -> TimerState.resumeTimer(ctx)
                            pending > 0 -> { TimerState.startTimer(ctx, pending); input = "" }
                        }
                        remaining = TimerState.remaining(ctx)
                        running = TimerState.isRunning(ctx)
                    }
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    if (running) "pause" else if (TimerState.pausedLeft(ctx) > 0) "resume" else "start",
                    color = Accent, fontFamily = Mono, fontSize = (14 * scale).sp
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(ChipShape)
                    .border(1.dp, Faint, ChipShape)
                    .clickable {
                        TimerState.clearAndStop(ctx)
                        pending = 0; input = ""
                        remaining = 0; running = false
                    }
                    .padding(vertical = 12.dp)
            ) {
                Text("reset", color = Dim, fontFamily = Mono, fontSize = (14 * scale).sp)
            }
        }
    }
}

// ---------- stopwatch ----------
@Composable
fun StopwatchTabView(scale: Float) {
    val ctx = LocalContext.current
    var elapsed by remember { mutableStateOf(TimerState.swElapsed(ctx)) }
    var running by remember { mutableStateOf(TimerState.swRunning(ctx)) }
    var laps by remember { mutableStateOf(TimerState.swLaps(ctx)) }

    LaunchedEffect(Unit) {
        while (true) {
            elapsed = TimerState.swElapsed(ctx)
            running = TimerState.swRunning(ctx)
            delay(50)
        }
    }

    fun display(ms: Long): String {
        val total = ms / 10
        val h = total / 360000
        val m = (total % 360000) / 6000
        val s = (total % 6000) / 100
        val cs = total % 100
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
        else String.format(Locale.US, "%02d:%02d.%02d", m, s, cs)
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            display(elapsed),
            color = Ink, fontSize = (44 * scale).sp,
            fontWeight = FontWeight.Medium, fontFamily = Mono
        )
        if (running) {
            Spacer(Modifier.height(4.dp))
            Text("keeps running in background", color = Faint, fontFamily = Mono, fontSize = (10 * scale).sp)
        }

        Spacer(Modifier.height(22.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(ChipShape)
                    .border(1.dp, Accent, ChipShape)
                    .clickable {
                        if (running) TimerState.swStop(ctx) else TimerState.swStart(ctx)
                        running = TimerState.swRunning(ctx)
                    }
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    if (running) "stop" else if (elapsed > 0) "resume" else "start",
                    color = Accent, fontFamily = Mono, fontSize = (14 * scale).sp
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(ChipShape)
                    .border(1.dp, Faint, ChipShape)
                    .clickable {
                        if (running) { TimerState.swAddLap(ctx); laps = TimerState.swLaps(ctx) }
                        else { TimerState.swReset(ctx); laps = emptyList(); elapsed = 0 }
                    }
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    if (running) "lap" else "reset",
                    color = Dim, fontFamily = Mono, fontSize = (14 * scale).sp
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(laps.reversed()) { i, lap ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        "lap ${laps.size - i}", color = Dim, fontFamily = Mono, fontSize = (12 * scale).sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(display(lap), color = Ink, fontFamily = Mono, fontSize = (12 * scale).sp)
                }
            }
        }
    }
}
