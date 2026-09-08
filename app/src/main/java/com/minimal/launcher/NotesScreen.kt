package com.minimal.launcher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Note(
    val id: Long,
    val body: String,
    val updated: Long
) {
    val title: String get() = body.lineSequence().firstOrNull()?.take(40)?.ifBlank { "(untitled)" } ?: "(untitled)"
    val snippet: String get() = body.lineSequence().drop(1).firstOrNull()?.take(50) ?: ""
}

object NotesStore {
    private const val KEY = "notes_v2"
    private const val SEP_RECORD = "\u0001"
    private const val SEP_FIELD = "\u0002"

    private fun prefs(c: Context) = c.getSharedPreferences("minimal_store", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Note> = runCatching {
        prefs(ctx).getString(KEY, "")!!
            .split(SEP_RECORD)
            .filter { it.isNotBlank() }
            .mapNotNull { rec ->
                val parts = rec.split(SEP_FIELD)
                if (parts.size < 3) null
                else Note(
                    id = parts[0].toLongOrNull() ?: return@mapNotNull null,
                    body = parts[1],
                    updated = parts[2].toLongOrNull() ?: 0L
                )
            }
            .sortedByDescending { it.updated }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, notes: List<Note>) {
        runCatching {
            val encoded = notes.joinToString(SEP_RECORD) {
                "${it.id}$SEP_FIELD${it.body}$SEP_FIELD${it.updated}"
            }
            prefs(ctx).edit().putString(KEY, encoded).apply()
        }
    }

    fun upsert(ctx: Context, id: Long?, body: String): Long {
        val notes = all(ctx).toMutableList()
        val now = System.currentTimeMillis()
        val noteId = id ?: now
        val i = notes.indexOfFirst { it.id == noteId }
        val note = Note(noteId, body, now)
        if (i >= 0) notes[i] = note else notes.add(note)
        save(ctx, notes)
        return noteId
    }

    fun delete(ctx: Context, id: Long) {
        save(ctx, all(ctx).filter { it.id != id })
    }

    /** migrate old one-line notes from the !note command */
    fun migrateLegacy(ctx: Context) {
        runCatching {
            val legacy = Store.list(ctx, "notes")
            if (legacy.isEmpty()) return
            legacy.forEach { upsert(ctx, null, it) }
            prefs(ctx).edit().putString("notes", "").apply()
        }
    }
}

@Composable
fun NotesScreen(scale: Float, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Note?>(null) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { NotesStore.migrateLegacy(ctx) }

    val notes = remember(refresh) { NotesStore.all(ctx) }

    if (creating || editing != null) {
        NoteEditor(
            scale = scale,
            note = editing,
            onSave = { body ->
                if (body.isNotBlank()) NotesStore.upsert(ctx, editing?.id, body)
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
                modifier = Modifier.clickable { onClose() }
            )
            Text("notes", color = Ink, fontSize = (26 * scale).sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            Text(
                "+ new",
                color = Accent, fontFamily = Mono, fontSize = (13 * scale).sp,
                modifier = Modifier.clickable { creating = true }
            )
        }

        Spacer(Modifier.height(16.dp))

        if (notes.isEmpty()) {
            Text("no notes yet", color = Faint, fontFamily = Mono, fontSize = (13 * scale).sp)
        }

        LazyColumn(Modifier.weight(1f)) {
            items(notes, key = { it.id }) { note ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(ChipShape)
                        .border(1.dp, Faint, ChipShape)
                        .clickable { editing = note }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            note.title, color = Ink, fontSize = (15 * scale).sp,
                            fontWeight = FontWeight.Medium, maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            SimpleDateFormat("MMM d", Locale.getDefault())
                                .format(Date(note.updated)).lowercase(),
                            color = Dim, fontFamily = Mono, fontSize = (10 * scale).sp
                        )
                    }
                    if (note.snippet.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            note.snippet, color = Dim, fontSize = (12 * scale).sp, maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
fun NoteEditor(
    scale: Float,
    note: Note?,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    var body by remember { mutableStateOf(note?.body ?: "") }

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
            Text(
                if (note == null) "new note" else "note",
                color = Ink, fontSize = (24 * scale).sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "save",
                color = Accent, fontFamily = Mono, fontSize = (13 * scale).sp,
                modifier = Modifier.clickable { onSave(body) }
            )
        }

        Spacer(Modifier.height(16.dp))

        BasicTextField(
            value = body,
            onValueChange = { body = it },
            textStyle = TextStyle(color = Ink, fontFamily = Mono, fontSize = (14 * scale).sp),
            cursorBrush = SolidColor(Accent),
            decorationBox = { inner ->
                Box {
                    if (body.isEmpty())
                        Text("start typing", color = Faint, fontFamily = Mono, fontSize = (14 * scale).sp)
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        if (note != null) {
            Text(
                "delete note",
                color = Dim, fontFamily = Mono, fontSize = (12 * scale).sp,
                modifier = Modifier
                    .clickable { onDelete() }
                    .padding(vertical = 14.dp)
            )
        } else {
            Spacer(Modifier.height(14.dp))
        }
    }
}
