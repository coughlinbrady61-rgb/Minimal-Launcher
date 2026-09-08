package com.minimal.launcher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------- categories ----------
enum class NoteCategory(val label: String) {
    PERSONAL("personal"),
    WORK("work"),
    IDEAS("ideas"),
    JOURNAL("journal");

    companion object {
        fun from(name: String?): NoteCategory =
            entries.firstOrNull { it.name == name } ?: PERSONAL
    }
}

fun categoryIcon(c: NoteCategory): ImageVector = when (c) {
    NoteCategory.PERSONAL -> Icons.Outlined.Home
    NoteCategory.WORK -> Icons.Outlined.Work
    NoteCategory.IDEAS -> Icons.Outlined.Lightbulb
    NoteCategory.JOURNAL -> Icons.Outlined.MenuBook
}

// ---------- model ----------
data class Note(
    val id: Long,
    val body: String,
    val updated: Long,
    val category: NoteCategory = NoteCategory.PERSONAL,
    val bookmarked: Boolean = false
) {
    val title: String
        get() = body.lineSequence().firstOrNull()?.trim()?.take(40)?.ifBlank { "(untitled)" } ?: "(untitled)"

    val snippet: String
        get() = body.lineSequence().drop(1)
            .firstOrNull { it.isNotBlank() }
            ?.trim()?.removePrefix("- ")?.removePrefix("x ")?.take(60) ?: ""
}

object NotesStore {
    private const val KEY = "notes_v3"
    private const val REC = "\u0001"
    private const val FLD = "\u0002"

    private fun prefs(c: Context) = c.getSharedPreferences("minimal_store", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Note> = runCatching {
        prefs(ctx).getString(KEY, "")!!
            .split(REC)
            .filter { it.isNotBlank() }
            .mapNotNull { rec ->
                val p = rec.split(FLD)
                if (p.size < 5) null
                else Note(
                    id = p[0].toLongOrNull() ?: return@mapNotNull null,
                    body = p[1],
                    updated = p[2].toLongOrNull() ?: 0L,
                    category = NoteCategory.from(p[3]),
                    bookmarked = p[4] == "1"
                )
            }
            .sortedByDescending { it.updated }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, notes: List<Note>) {
        runCatching {
            val encoded = notes.joinToString(REC) {
                "${it.id}$FLD${it.body}$FLD${it.updated}$FLD${it.category.name}$FLD${if (it.bookmarked) "1" else "0"}"
            }
            prefs(ctx).edit().putString(KEY, encoded).apply()
        }
    }

    fun upsert(
        ctx: Context,
        id: Long?,
        body: String,
        category: NoteCategory = NoteCategory.PERSONAL,
        bookmarked: Boolean = false
    ): Long {
        val notes = all(ctx).toMutableList()
        val now = System.currentTimeMillis()
        val noteId = id ?: now
        val i = notes.indexOfFirst { it.id == noteId }
        val note = Note(noteId, body, now, category, bookmarked)
        if (i >= 0) notes[i] = note else notes.add(note)
        save(ctx, notes)
        return noteId
    }

    fun delete(ctx: Context, id: Long) = save(ctx, all(ctx).filter { it.id != id })

    /** pull in notes from older versions once */
    fun migrateLegacy(ctx: Context) {
        runCatching {
            val p = prefs(ctx)
            if (p.getBoolean("notes_migrated_v3", false)) return
            // v2 format
            p.getString("notes_v2", "")!!.split(REC).filter { it.isNotBlank() }.forEach { rec ->
                val parts = rec.split(FLD)
                if (parts.size >= 2) upsert(ctx, null, parts[1])
            }
            // v1 one-liners
            Store.list(ctx, "notes").forEach { upsert(ctx, null, it) }
            p.edit()
                .putBoolean("notes_migrated_v3", true)
                .putString("notes", "")
                .putString("notes_v2", "")
                .apply()
        }
    }
}

// ---------- checklist parsing ----------
// a line starting with "- " is an open item; "x " is a checked item
private fun isChecklistLine(line: String) =
    line.startsWith("- ") || line.startsWith("x ") || line.startsWith("X ")

private fun isChecked(line: String) = line.startsWith("x ") || line.startsWith("X ")

private fun toggleLine(line: String): String = when {
    isChecked(line) -> "- " + line.drop(2)
    line.startsWith("- ") -> "x " + line.drop(2)
    else -> line
}

private fun lineText(line: String) = line.drop(2).trim()

// ---------- list screen ----------
@Composable
fun NotesScreen(scale: Float, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Note?>(null) }
    var creating by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf<NoteCategory?>(null) }   // null = all
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { NotesStore.migrateLegacy(ctx) }

    val all = remember(refresh) { NotesStore.all(ctx) }

    if (creating || editing != null) {
        NoteEditor(
            scale = scale,
            note = editing,
            onSave = { body, cat, marked ->
                if (body.isNotBlank()) NotesStore.upsert(ctx, editing?.id, body, cat, marked)
                creating = false; editing = null; refresh++
            },
            onDelete = {
                editing?.let { NotesStore.delete(ctx, it.id) }
                creating = false; editing = null; refresh++
            },
            onCancel = { creating = false; editing = null }
        )
        return
    }

    val visible = all
        .filter { tab == null || it.category == tab }
        .filter { query.isBlank() || it.body.contains(query, ignoreCase = true) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(38.dp))

        // header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ ", color = Accent, fontSize = (24 * scale).sp,
                modifier = Modifier.clickable { onClose() }
            )
            Text("notes", color = Ink, fontSize = (24 * scale).sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(
                "${all.size} notes",
                color = Dim, fontFamily = Mono, fontSize = (11 * scale).sp,
                modifier = Modifier.weight(1f)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size((32 * scale).dp)
                    .clip(CircleShape)
                    .border(1.dp, Faint, CircleShape)
                    .clickable { searching = !searching; if (!searching) query = "" }
            ) {
                Icon(
                    Icons.Outlined.Search, contentDescription = "search",
                    tint = if (searching) Accent else Dim,
                    modifier = Modifier.size((16 * scale).dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size((32 * scale).dp)
                    .clip(CircleShape)
                    .background(Accent)
                    .clickable { creating = true }
            ) {
                Text("+", color = Color.Black, fontSize = (20 * scale).sp, fontWeight = FontWeight.Medium)
            }
        }

        if (searching) {
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                textStyle = TextStyle(color = Ink, fontFamily = Mono, fontSize = (14 * scale).sp),
                cursorBrush = SolidColor(Accent),
                singleLine = true,
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("> ", color = Accent, fontFamily = Mono, fontSize = (14 * scale).sp)
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty())
                                Text("search notes", color = Faint, fontFamily = Mono, fontSize = (13 * scale).sp)
                            inner()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Faint, ChipShape)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        // category tabs
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val tabs: List<Pair<NoteCategory?, String>> =
                listOf<Pair<NoteCategory?, String>>(null to "all") +
                    NoteCategory.entries.map { it as NoteCategory? to it.label }
            tabs.forEach { (cat, label) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { tab = cat }
                ) {
                    Text(
                        label.uppercase(),
                        color = if (tab == cat) Ink else Faint,
                        fontFamily = Mono, fontSize = (10 * scale).sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .width((20 * scale).dp).height(2.dp)
                            .background(if (tab == cat) Accent else Color.Transparent)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (visible.isEmpty()) {
            Text(
                if (query.isNotBlank()) "no matches" else "no notes yet",
                color = Faint, fontFamily = Mono, fontSize = (13 * scale).sp
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.id }) { note ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ChipShape)
                        .border(1.dp, Faint, ChipShape)
                        .clickable { editing = note }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(
                        categoryIcon(note.category), contentDescription = null,
                        tint = Accent,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size((15 * scale).dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                note.title, color = Ink, fontSize = (14 * scale).sp,
                                fontWeight = FontWeight.Medium, maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                note.category.label.uppercase(),
                                color = Dim, fontFamily = Mono, fontSize = (9 * scale).sp
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stamp(note.updated),
                                color = Dim, fontFamily = Mono, fontSize = (9 * scale).sp
                            )
                            if (note.bookmarked) {
                                Spacer(Modifier.width(6.dp))
                                Box(Modifier.size(6.dp).clip(CircleShape).background(Accent))
                            }
                        }
                        if (note.snippet.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                note.snippet, color = Dim, fontSize = (12 * scale).sp, maxLines = 1
                            )
                        }
                    }
                }
                Spacer(Modifier.height(7.dp))
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

private fun stamp(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val yesterday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1
    return when {
        sameDay -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts)).lowercase()
        yesterday -> "yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts)).lowercase()
    }
}

// ---------- editor ----------
@Composable
fun NoteEditor(
    scale: Float,
    note: Note?,
    onSave: (String, NoteCategory, Boolean) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    var body by remember { mutableStateOf(note?.body ?: "") }
    var category by remember { mutableStateOf(note?.category ?: NoteCategory.PERSONAL) }
    var bookmarked by remember { mutableStateOf(note?.bookmarked ?: false) }
    var raw by remember { mutableStateOf(note == null) }   // new notes start in typing mode
    var catMenu by remember { mutableStateOf(false) }

    val lines = body.lines()
    val hasChecklist = lines.any { isChecklistLine(it) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(38.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ ", color = Accent, fontSize = (24 * scale).sp,
                modifier = Modifier.clickable { onSave(body, category, bookmarked) }
            )
            Spacer(Modifier.weight(1f))

            Icon(
                if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = "bookmark",
                tint = if (bookmarked) Accent else Faint,
                modifier = Modifier
                    .clickable { bookmarked = !bookmarked }
                    .padding(6.dp)
                    .size((18 * scale).dp)
            )
            if (note != null) {
                Icon(
                    Icons.Outlined.Delete, contentDescription = "delete",
                    tint = Faint,
                    modifier = Modifier
                        .clickable { onDelete() }
                        .padding(6.dp)
                        .size((18 * scale).dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                category.label.uppercase() + " ⌄",
                color = Accent, fontFamily = Mono, fontSize = (11 * scale).sp,
                modifier = Modifier.clickable { catMenu = !catMenu }
            )
        }

        if (catMenu) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteCategory.entries.forEach { c ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(ChipShape)
                            .background(if (c == category) Accent else Color.Transparent)
                            .border(1.dp, if (c == category) Accent else Faint, ChipShape)
                            .clickable { category = c; catMenu = false }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            c.label,
                            color = if (c == category) Color.Black else Ink,
                            fontFamily = Mono, fontSize = (10 * scale).sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // toggle between rendered view (tappable checkboxes) and raw editing
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (raw) "done editing" else "edit text",
                color = Accent, fontFamily = Mono, fontSize = (11 * scale).sp,
                modifier = Modifier.clickable { raw = !raw }
            )
            Spacer(Modifier.weight(1f))
            if (!raw && hasChecklist) {
                val done = lines.count { isChecked(it) }
                val total = lines.count { isChecklistLine(it) }
                Text("$done/$total done", color = Dim, fontFamily = Mono, fontSize = (10 * scale).sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        if (raw) {
            BasicTextField(
                value = body,
                onValueChange = { body = it },
                textStyle = TextStyle(color = Ink, fontFamily = Mono, fontSize = (14 * scale).sp),
                cursorBrush = SolidColor(Accent),
                decorationBox = { inner ->
                    Box {
                        if (body.isEmpty())
                            Text(
                                "first line is the title\n\n- an item to check off\nplain lines are just text",
                                color = Faint, fontFamily = Mono, fontSize = (13 * scale).sp
                            )
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(lines) { index, line ->
                    when {
                        index == 0 -> {
                            Text(
                                line.ifBlank { "(untitled)" },
                                color = Ink, fontSize = (20 * scale).sp, fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        isChecklistLine(line) -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val updated = lines.toMutableList()
                                        updated[index] = toggleLine(line)
                                        body = updated.joinToString("\n")
                                    }
                                    .padding(vertical = 5.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size((17 * scale).dp)
                                        .clip(CircleShape)
                                        .background(if (isChecked(line)) Accent else Color.Transparent)
                                        .border(
                                            1.dp,
                                            if (isChecked(line)) Accent else Dim,
                                            CircleShape
                                        )
                                ) {
                                    if (isChecked(line))
                                        Text("✓", color = Color.Black, fontSize = (10 * scale).sp)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    lineText(line),
                                    color = if (isChecked(line)) Dim else Ink,
                                    fontSize = (14 * scale).sp,
                                    textDecoration = if (isChecked(line)) TextDecoration.LineThrough else null
                                )
                            }
                        }
                        line.isBlank() -> Spacer(Modifier.height(8.dp))
                        else -> {
                            Text(
                                line, color = Ink, fontSize = (14 * scale).sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChipShape)
                .border(1.dp, Accent, ChipShape)
                .clickable { onSave(body, category, bookmarked) }
                .padding(vertical = 11.dp)
        ) {
            Text("save", color = Accent, fontFamily = Mono, fontSize = (13 * scale).sp)
        }
        Spacer(Modifier.height(14.dp))
    }
}
