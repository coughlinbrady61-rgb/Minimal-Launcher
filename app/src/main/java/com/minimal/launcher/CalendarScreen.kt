package com.minimal.launcher

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ---------- writing to the calendar ----------
object CalendarWriter {

    /** First writable calendar id on the device, or null. */
    private fun defaultCalendarId(ctx: Context): Long? = runCatching {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj, null, null, null
        )?.use { c ->
            val idIx = c.getColumnIndex(CalendarContract.Calendars._ID)
            val lvlIx = c.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            while (c.moveToNext()) {
                val level = if (lvlIx >= 0) c.getInt(lvlIx) else 0
                if (level >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    return@use c.getLong(idIx)
                }
            }
            null
        }
    }.getOrNull()

    fun create(ctx: Context, title: String, start: Long, durationMin: Int): Boolean = runCatching {
        val calId = defaultCalendarId(ctx) ?: return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + durationMin * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
    }.getOrDefault(false)

    fun updateTitle(ctx: Context, eventId: Long, title: String): Boolean = runCatching {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val values = ContentValues().apply { put(CalendarContract.Events.TITLE, title) }
        ctx.contentResolver.update(uri, values, null, null) > 0
    }.getOrDefault(false)

    fun delete(ctx: Context, eventId: Long): Boolean = runCatching {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        ctx.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)
}

// ---------- events with ids, for editing ----------
data class DayEvent(
    val id: Long,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean
)

object CalendarReader {
    fun eventsBetween(ctx: Context, from: Long, to: Long): List<DayEvent> {
        if (!Agenda.hasPermission(ctx)) return emptyList()
        val out = mutableListOf<DayEvent>()
        val proj = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DELETED
        )
        runCatching {
            ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, proj,
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(from.toString(), to.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { c ->
                val iIx = c.getColumnIndex(CalendarContract.Events._ID)
                val tIx = c.getColumnIndex(CalendarContract.Events.TITLE)
                val sIx = c.getColumnIndex(CalendarContract.Events.DTSTART)
                val eIx = c.getColumnIndex(CalendarContract.Events.DTEND)
                val aIx = c.getColumnIndex(CalendarContract.Events.ALL_DAY)
                val dIx = c.getColumnIndex(CalendarContract.Events.DELETED)
                while (c.moveToNext()) {
                    if (dIx >= 0 && c.getInt(dIx) == 1) continue
                    out.add(
                        DayEvent(
                            id = c.getLong(iIx),
                            title = (c.getString(tIx) ?: "(no title)").lowercase(),
                            start = c.getLong(sIx),
                            end = if (eIx >= 0) c.getLong(eIx) else c.getLong(sIx),
                            allDay = aIx >= 0 && c.getInt(aIx) == 1
                        )
                    )
                }
            }
        }
        return out
    }
}

// ---------- the screen ----------
@Composable
fun CalendarScreen(scale: Float, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val today = remember { Calendar.getInstance() }
    var monthOffset by remember { mutableStateOf(0) }
    var selectedDay by remember { mutableStateOf(today.get(Calendar.DAY_OF_MONTH)) }
    var refresh by remember { mutableStateOf(0) }
    var composing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DayEvent?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }

    val cal = remember(monthOffset) {
        Calendar.getInstance().apply {
            add(Calendar.MONTH, monthOffset)
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }
    val monthLabel = remember(monthOffset) {
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time).lowercase()
    }
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstWeekday = cal.get(Calendar.DAY_OF_WEEK) - 1  // 0 = sunday

    // events for the whole visible month
    val monthStart = remember(monthOffset, refresh) {
        Calendar.getInstance().apply {
            add(Calendar.MONTH, monthOffset)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val monthEnd = remember(monthOffset, refresh) { monthStart + daysInMonth * 86_400_000L }
    val monthEvents = remember(monthOffset, refresh) {
        CalendarReader.eventsBetween(ctx, monthStart, monthEnd)
    }
    val daysWithEvents = remember(monthEvents) {
        monthEvents.map {
            Calendar.getInstance().apply { timeInMillis = it.start }.get(Calendar.DAY_OF_MONTH)
        }.toSet()
    }

    val dayStart = remember(selectedDay, monthOffset, refresh) {
        Calendar.getInstance().apply {
            add(Calendar.MONTH, monthOffset)
            set(Calendar.DAY_OF_MONTH, selectedDay)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val dayEvents = remember(dayStart, refresh, monthEvents) {
        monthEvents.filter { it.start in dayStart until (dayStart + 86_400_000L) }
    }

    LaunchedEffect(flash) { if (flash != null) { kotlinx.coroutines.delay(1800); flash = null } }

    fun timeOf(ts: Long) =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts)).lowercase()

    if (composing) {
        EventComposer(
            scale = scale,
            dayStart = dayStart,
            onSave = { title, hour, minute, dur ->
                val start = Calendar.getInstance().apply {
                    timeInMillis = dayStart
                    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                }.timeInMillis
                val ok = CalendarWriter.create(ctx, title, start, dur)
                flash = if (ok) "event added" else "couldn't add event"
                composing = false
                refresh++
            },
            onCancel = { composing = false }
        )
        return
    }

    editing?.let { ev ->
        EventEditor(
            scale = scale,
            event = ev,
            onRename = { newTitle ->
                val ok = CalendarWriter.updateTitle(ctx, ev.id, newTitle)
                flash = if (ok) "renamed" else "couldn't rename"
                editing = null; refresh++
            },
            onDelete = {
                val ok = CalendarWriter.delete(ctx, ev.id)
                flash = if (ok) "deleted" else "couldn't delete"
                editing = null; refresh++
            },
            onCancel = { editing = null }
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(40.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹", color = Accent, fontSize = (26 * scale).sp,
                modifier = Modifier.clickable { onClose() }.padding(end = 12.dp)
            )
            Text(monthLabel, color = Ink, fontSize = (24 * scale).sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            Text(
                "‹", color = Dim, fontSize = (22 * scale).sp,
                modifier = Modifier.clickable { monthOffset--; selectedDay = 1 }.padding(horizontal = 10.dp)
            )
            Text(
                "›", color = Dim, fontSize = (22 * scale).sp,
                modifier = Modifier.clickable { monthOffset++; selectedDay = 1 }.padding(horizontal = 4.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        // weekday header
        Row(Modifier.fillMaxWidth()) {
            listOf("s", "m", "t", "w", "t", "f", "s").forEach { d ->
                Text(
                    d, color = Faint, fontFamily = Mono, fontSize = (11 * scale).sp,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        // month grid
        val cells = firstWeekday + daysInMonth
        val rows = (cells + 6) / 7
        val isThisMonth = monthOffset == 0
        val todayNum = today.get(Calendar.DAY_OF_MONTH)

        Column {
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val cellIndex = r * 7 + c
                        val day = cellIndex - firstWeekday + 1
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height((42 * scale).dp)
                                .then(
                                    if (day in 1..daysInMonth)
                                        Modifier.clickable { selectedDay = day }
                                    else Modifier
                                )
                        ) {
                            if (day in 1..daysInMonth) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size((28 * scale).dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (day == selectedDay) Accent else Color.Transparent
                                            )
                                    ) {
                                        Text(
                                            "$day",
                                            color = when {
                                                day == selectedDay -> Color.Black
                                                isThisMonth && day == todayNum -> Accent
                                                else -> Ink
                                            },
                                            fontFamily = Mono,
                                            fontSize = (13 * scale).sp
                                        )
                                    }
                                    if (day in daysWithEvents) {
                                        Spacer(Modifier.height(2.dp))
                                        Box(
                                            Modifier
                                                .size(4.dp)
                                                .clip(CircleShape)
                                                .background(if (day == selectedDay) Accent else Dim)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        DottedDivider()
        Spacer(Modifier.height(12.dp))

        flash?.let {
            Text(it, color = Accent, fontFamily = Mono, fontSize = (12 * scale).sp)
            Spacer(Modifier.height(6.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                SimpleDateFormat("EEEE d", Locale.getDefault())
                    .format(Date(dayStart)).lowercase(),
                color = Ink, fontSize = (16 * scale).sp, modifier = Modifier.weight(1f)
            )
            Text(
                "+ new",
                color = Accent, fontFamily = Mono, fontSize = (13 * scale).sp,
                modifier = Modifier.clickable { composing = true }
            )
        }

        Spacer(Modifier.height(8.dp))

        if (dayEvents.isEmpty()) {
            Text("nothing scheduled", color = Faint, fontFamily = Mono, fontSize = (13 * scale).sp)
        }

        LazyColumn(Modifier.weight(1f)) {
            items(dayEvents, key = { it.id }) { ev ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ChipShape)
                        .border(1.dp, Faint, ChipShape)
                        .clickable { editing = ev }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        if (ev.allDay) "all day" else timeOf(ev.start),
                        color = Accent, fontFamily = Mono, fontSize = (11 * scale).sp,
                        modifier = Modifier.width((62 * scale).dp)
                    )
                    Text(ev.title, color = Ink, fontSize = (14 * scale).sp)
                }
                Spacer(Modifier.height(6.dp))
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

// ---------- new event ----------
@Composable
fun EventComposer(
    scale: Float,
    dayStart: Long,
    onSave: (String, Int, Int, Int) -> Unit,
    onCancel: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf("") }
    var durText by remember { mutableStateOf("60") }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ ", color = Accent, fontSize = (26 * scale).sp,
                modifier = Modifier.clickable { onCancel() }
            )
            Text("new event", color = Ink, fontSize = (24 * scale).sp, fontWeight = FontWeight.Medium)
        }
        Text(
            SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
                .format(Date(dayStart)).lowercase(),
            color = Dim, fontFamily = Mono, fontSize = (12 * scale).sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(22.dp))

        LabeledField("what", title, scale) { title = it }
        Spacer(Modifier.height(12.dp))
        LabeledField("time (e.g. 14:30 or 2:30 pm)", timeText, scale) { timeText = it }
        Spacer(Modifier.height(12.dp))
        LabeledField("minutes long", durText, scale) { durText = it }

        Spacer(Modifier.height(22.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChipShape)
                .border(1.dp, Accent, ChipShape)
                .clickable {
                    val parsed = parseTime(timeText) ?: (9 to 0)
                    val dur = durText.filter { it.isDigit() }.toIntOrNull() ?: 60
                    if (title.isNotBlank()) onSave(title, parsed.first, parsed.second, dur)
                }
                .padding(vertical = 12.dp)
        ) {
            Text("save", color = Accent, fontFamily = Mono, fontSize = (14 * scale).sp)
        }
    }
}

// ---------- edit / delete ----------
@Composable
fun EventEditor(
    scale: Float,
    event: DayEvent,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    var title by remember { mutableStateOf(event.title) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ ", color = Accent, fontSize = (26 * scale).sp,
                modifier = Modifier.clickable { onCancel() }
            )
            Text("edit event", color = Ink, fontSize = (24 * scale).sp, fontWeight = FontWeight.Medium)
        }
        Text(
            SimpleDateFormat("EEEE, MMMM d · h:mm a", Locale.getDefault())
                .format(Date(event.start)).lowercase(),
            color = Dim, fontFamily = Mono, fontSize = (12 * scale).sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(22.dp))
        LabeledField("title", title, scale) { title = it }

        Spacer(Modifier.height(22.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChipShape)
                .border(1.dp, Accent, ChipShape)
                .clickable { if (title.isNotBlank()) onRename(title) }
                .padding(vertical = 12.dp)
        ) {
            Text("save", color = Accent, fontFamily = Mono, fontSize = (14 * scale).sp)
        }

        Spacer(Modifier.height(10.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChipShape)
                .border(1.dp, Faint, ChipShape)
                .clickable { onDelete() }
                .padding(vertical = 12.dp)
        ) {
            Text("delete event", color = Dim, fontFamily = Mono, fontSize = (14 * scale).sp)
        }
    }
}

@Composable
fun LabeledField(label: String, value: String, scale: Float, onChange: (String) -> Unit) {
    Column {
        Text(label, color = Faint, fontFamily = Mono, fontSize = (11 * scale).sp)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = Ink, fontFamily = Mono, fontSize = (15 * scale).sp),
            cursorBrush = SolidColor(Accent),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Faint, ChipShape)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}
