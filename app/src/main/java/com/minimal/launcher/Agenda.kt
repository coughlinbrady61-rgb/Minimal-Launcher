package com.minimal.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class CalEvent(
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean
)

object Agenda {

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Events between two timestamps, sorted by start. */
    fun events(ctx: Context, from: Long, to: Long): List<CalEvent> {
        if (!hasPermission(ctx)) return emptyList()
        val out = mutableListOf<CalEvent>()
        val proj = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DELETED
        )
        runCatching {
            ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                proj,
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(from.toString(), to.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { c ->
                val tIx = c.getColumnIndex(CalendarContract.Events.TITLE)
                val sIx = c.getColumnIndex(CalendarContract.Events.DTSTART)
                val eIx = c.getColumnIndex(CalendarContract.Events.DTEND)
                val aIx = c.getColumnIndex(CalendarContract.Events.ALL_DAY)
                val dIx = c.getColumnIndex(CalendarContract.Events.DELETED)
                while (c.moveToNext()) {
                    if (dIx >= 0 && c.getInt(dIx) == 1) continue
                    val title = c.getString(tIx)?.takeIf { it.isNotBlank() } ?: "(no title)"
                    val start = c.getLong(sIx)
                    val end = if (eIx >= 0) c.getLong(eIx) else start
                    val allDay = aIx >= 0 && c.getInt(aIx) == 1
                    out.add(CalEvent(title.lowercase(), start, end, allDay))
                }
            }
        }
        return out.sortedBy { it.start }
    }

    private fun startOfDay(offsetDays: Int = 0): Long {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, offsetDays)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun endOfDay(offsetDays: Int = 0): Long = startOfDay(offsetDays + 1) - 1

    private fun timeFmt(ts: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts)).lowercase()

    private fun dayFmt(ts: Long): String =
        SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ts)).lowercase()

    private fun line(e: CalEvent): String =
        if (e.allDay) "· ${e.title} (all day)"
        else "· ${timeFmt(e.start)}  ${e.title}"

    // ---------- the answers ----------

    fun today(ctx: Context): String {
        val list = events(ctx, System.currentTimeMillis(), endOfDay())
        val todos = Store.list(ctx, "todos")
        val sb = StringBuilder()
        sb.append(if (list.isEmpty()) "nothing left on the calendar today"
                  else "today:\n" + list.joinToString("\n") { line(it) })
        if (todos.isNotEmpty()) {
            sb.append("\n\nto-dos (${todos.size}):\n")
            sb.append(todos.take(5).joinToString("\n") { "· $it" })
        }
        return sb.toString()
    }

    fun tomorrow(ctx: Context): String {
        val list = events(ctx, startOfDay(1), endOfDay(1))
        return if (list.isEmpty()) "nothing on the calendar tomorrow"
        else "tomorrow:\n" + list.joinToString("\n") { line(it) }
    }

    fun week(ctx: Context): String {
        val list = events(ctx, System.currentTimeMillis(), endOfDay(6))
        if (list.isEmpty()) return "nothing on the calendar this week"
        val byDay = list.groupBy { dayFmt(it.start) }
        return "next 7 days:\n" + byDay.entries.joinToString("\n") { (day, evs) ->
            "$day: " + evs.joinToString(", ") {
                if (it.allDay) it.title else "${timeFmt(it.start)} ${it.title}"
            }
        }
    }

    fun next(ctx: Context): String {
        val now = System.currentTimeMillis()
        val list = events(ctx, now, endOfDay(7)).filter { !it.allDay }
        val e = list.firstOrNull() ?: return "nothing scheduled coming up"
        val mins = ((e.start - now) / 60000).toInt()
        val whenStr = when {
            mins < 1 -> "now"
            mins < 60 -> "in $mins min"
            mins < 1440 -> "in ${mins / 60}h ${mins % 60}m"
            else -> "on ${dayFmt(e.start)}"
        }
        return "next: ${e.title}\n${timeFmt(e.start)} · $whenStr"
    }

    /** Gaps of 30+ minutes between now and end of day. */
    fun free(ctx: Context): String {
        val now = System.currentTimeMillis()
        val dayEnd = endOfDay()
        val busy = events(ctx, now, dayEnd).filter { !it.allDay }
        if (busy.isEmpty()) return "free the rest of the day"

        val gaps = mutableListOf<String>()
        var cursor = now
        for (e in busy) {
            val gap = (e.start - cursor) / 60000
            if (gap >= 30) gaps.add("${timeFmt(cursor)} – ${timeFmt(e.start)}")
            if (e.end > cursor) cursor = e.end
        }
        if ((dayEnd - cursor) / 60000 >= 30) gaps.add("${timeFmt(cursor)} – end of day")

        return if (gaps.isEmpty()) "no open blocks left today"
        else "open blocks:\n" + gaps.joinToString("\n") { "· $it" }
    }

    /** Route a ?query to the right answer. */
    fun answer(ctx: Context, query: String): String {
        val q = query.trim().lowercase()
        if (!hasPermission(ctx) && q !in listOf("todos", "to-dos")) {
            return "needs calendar permission — open settings to grant"
        }
        return when {
            q.isEmpty() || q.startsWith("today") || q.startsWith("day") -> today(ctx)
            q.startsWith("tomorrow") -> tomorrow(ctx)
            q.startsWith("week") -> week(ctx)
            q.startsWith("next") -> next(ctx)
            q.startsWith("free") || q.startsWith("open") -> free(ctx)
            q.startsWith("todo") || q.startsWith("to-do") -> {
                val todos = Store.list(ctx, "todos")
                if (todos.isEmpty()) "nothing to do"
                else "to-dos (${todos.size}):\n" + todos.joinToString("\n") { "· $it" }
            }
            else -> "try: ?today  ?tomorrow  ?week  ?next  ?free  ?todos"
        }
    }
}
