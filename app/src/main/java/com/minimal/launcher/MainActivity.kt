@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.minimal.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Ink = Color.White
val Dim = Color(0xFF9A9A9A)
val Faint = Color(0xFF4A4A4A)
val Accent = Color(0xFFFF3B2F)
val ChipShape = RoundedCornerShape(14.dp)
val Mono = FontFamily.Monospace

data class AppEntry(val label: String, val packageName: String)

class MainActivity : ComponentActivity() {

    private val callLogPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) runCatching { HubRepository.loadCallLog(this) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val pager = rememberPagerState(pageCount = { 2 })
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> MinimalHome(
                        loadApps = { loadInstalledApps() },
                        launchApp = { pkg ->
                            runCatching {
                                packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity)
                            }
                        },
                        runIntent = { runCatching { startActivity(it) } }
                    )
                    1 -> HubScreen(
                        hasNotificationAccess = hasNotificationAccess(),
                        onRequestCallLog = { requestCallLog() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED)
                HubRepository.loadCallLog(this)
        }
    }

    private fun requestCallLog() {
        runCatching {
            if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED)
                callLogPermission.launch(Manifest.permission.READ_CALL_LOG)
            else HubRepository.loadCallLog(this)
        }
    }

    private fun hasNotificationAccess(): Boolean = runCatching {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        enabled.contains(packageName)
    }.getOrDefault(false)

    private fun loadInstalledApps(): List<AppEntry> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                runCatching {
                    AppEntry(
                        info.loadLabel(packageManager).toString(),
                        info.activityInfo.packageName
                    )
                }.getOrNull()
            }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}

object Store {
    private const val PREFS = "minimal_store"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(c: Context, key: String): List<String> = runCatching {
        prefs(c).getString(key, "")!!.split('\u0001').filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    fun add(c: Context, key: String, item: String) {
        runCatching {
            val cur = list(c, key) + item
            prefs(c).edit().putString(key, cur.joinToString("\u0001")).apply()
        }
    }

    fun remove(c: Context, key: String, item: String) {
        runCatching {
            val cur = list(c, key) - item
            prefs(c).edit().putString(key, cur.joinToString("\u0001")).apply()
        }
    }
}

sealed class Command {
    data class Message(val target: String) : Command()
    data class Call(val target: String) : Command()
    data class Todo(val text: String) : Command()
    data class Note(val text: String) : Command()
    data class Event(val text: String) : Command()
    data class Timer(val minutes: Int) : Command()
    data class Alarm(val hour: Int, val minute: Int) : Command()
    data class Ask(val question: String) : Command()
    data class Search(val query: String) : Command()
}

fun parseCommand(raw: String): Command? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    val body = s.drop(1).trim()
    return when (s.first()) {
        '@' -> if (body.isNotEmpty()) Command.Message(body) else null
        '#' -> if (body.isNotEmpty()) Command.Call(body) else null
        '-' -> if (body.isNotEmpty()) Command.Todo(body) else null
        '!' -> if (body.isNotEmpty()) Command.Note(body) else null
        '*' -> if (body.isNotEmpty()) Command.Event(body) else null
        '+' -> body.toIntOrNull()?.let { Command.Timer(it) }
        ':' -> parseTime(body)?.let { (h, m) -> Command.Alarm(h, m) }
        '?' -> if (body.isNotEmpty()) Command.Ask(body) else null
        else -> Command.Search(s)
    }
}

fun parseTime(t: String): Pair<Int, Int>? {
    val parts = t.split(':', '.').map { it.trim() }
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return if (h in 0..23 && m in 0..59) h to m else null
}

fun commandToIntent(cmd: Command): Intent? = when (cmd) {
    is Command.Message -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
        putExtra("sms_body", "")
    }
    is Command.Call -> Intent(Intent.ACTION_DIAL,
        if (cmd.target.all { it.isDigit() || it == '+' }) Uri.parse("tel:${cmd.target}") else null)
    is Command.Event -> Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, cmd.text)
    }
    is Command.Timer -> Intent(AlarmClock.ACTION_SET_TIMER).apply {
        putExtra(AlarmClock.EXTRA_LENGTH, cmd.minutes * 60)
        putExtra(AlarmClock.EXTRA_MESSAGE, "timer")
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    }
    is Command.Alarm -> Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, cmd.hour)
        putExtra(AlarmClock.EXTRA_MINUTES, cmd.minute)
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    }
    else -> null
}

@Composable
fun MinimalHome(
    loadApps: () -> List<AppEntry>,
    launchApp: (String) -> Unit,
    runIntent: (Intent) -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var dateLine1 by remember { mutableStateOf("") }
    var dateLine2 by remember { mutableStateOf("") }
    var todos by remember { mutableStateOf(Store.list(ctx, "todos")) }
    var flash by remember { mutableStateOf<String?>(null) }
    var showTodos by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { loadApps() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            dateLine1 = SimpleDateFormat("EEEE,", Locale.getDefault()).format(now).lowercase()
            dateLine2 = SimpleDateFormat("MMMM d", Locale.getDefault()).format(now).lowercase()
            delay(30_000)
        }
    }
    LaunchedEffect(flash) { if (flash != null) { delay(1800); flash = null } }

    fun execute(raw: String) {
        when (val cmd = parseCommand(raw)) {
            is Command.Todo -> { Store.add(ctx, "todos", cmd.text); todos = Store.list(ctx, "todos"); flash = "added to-do" }
            is Command.Note -> { Store.add(ctx, "notes", cmd.text); flash = "noted" }
            is Command.Message -> commandToIntent(cmd)?.let(runIntent)
            is Command.Call -> commandToIntent(cmd)?.let(runIntent)
            is Command.Event -> commandToIntent(cmd)?.let(runIntent)
            is Command.Timer -> commandToIntent(cmd)?.let(runIntent)
            is Command.Alarm -> commandToIntent(cmd)?.let(runIntent)
            is Command.Ask -> flash = "ask: not wired up yet"
            is Command.Search -> {
                val hit = apps.firstOrNull { it.label.contains(raw.trim(), true) }
                if (hit != null) launchApp(hit.packageName) else flash = "no app match"
            }
            null -> {}
        }
        input = ""
    }

    val filtered = remember(input, apps) {
        val q = input.trim()
        if (q.isEmpty() || q.first() in "@#-!*+:?") emptyList()
        else apps.filter { it.label.contains(q, true) }.take(6)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(48.dp))

        Text(dateLine1, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Medium)
        Text(dateLine2, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text("☀ sunny", color = Dim, fontFamily = Mono, fontSize = 13.sp)

        Spacer(Modifier.height(14.dp))
        DottedDivider()
        Spacer(Modifier.height(14.dp))

        Chip(onClick = { runIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)) }) {
            Text("▤ ", color = Accent, fontFamily = Mono, fontSize = 14.sp)
            Text("calendar · today", color = Ink, fontSize = 15.sp)
        }
        Spacer(Modifier.height(10.dp))
        Chip(onClick = { showTodos = !showTodos; showNotes = false }) {
            Text("≡ ", color = Accent, fontFamily = Mono, fontSize = 14.sp)
            Text("to-dos", color = Ink, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            if (todos.isNotEmpty()) Badge(todos.size)
        }

        if (showTodos) ItemList(items = todos, empty = "nothing to do") {
            Store.remove(ctx, "todos", it); todos = Store.list(ctx, "todos")
        }
        if (showNotes) ItemList(items = Store.list(ctx, "notes"), empty = "no notes") {
            Store.remove(ctx, "notes", it)
        }

        Spacer(Modifier.height(18.dp))

        val tiles = listOf(
            Tile("▤", "note") { showNotes = !showNotes; showTodos = false },
            Tile("▦", "event") { runIntent(Intent(Intent.ACTION_INSERT).apply { data = CalendarContract.Events.CONTENT_URI }) },
            Tile("◷", "clock") { runIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS)) },
            Tile("≣", "to do") { showTodos = !showTodos; showNotes = false },
            Tile("✆", "call") { runIntent(Intent(Intent.ACTION_DIAL)) },
            Tile("▭", "message") { runIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)) },
            Tile("◉", "camera") { runIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) },
            Tile("●", "memo") { runIntent(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)) },
        )
        TileGrid(tiles)

        Spacer(Modifier.weight(1f))

        flash?.let {
            Text(it, color = Accent, fontFamily = Mono, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }

        filtered.forEach { app ->
            Text(
                app.label.lowercase(),
                color = Dim, fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { launchApp(app.packageName); input = "" }
                    .padding(vertical = 6.dp)
            )
        }

        BasicTextField(
            value = input,
            onValueChange = { input = it },
            textStyle = TextStyle(color = Ink, fontFamily = Mono, fontSize = 17.sp),
            cursorBrush = SolidColor(Accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { execute(input) }),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("> ", color = Accent, fontFamily = Mono, fontSize = 17.sp)
                    Box(Modifier.weight(1f)) {
                        if (input.isEmpty())
                            Text("type to do things", color = Faint, fontFamily = Mono, fontSize = 15.sp)
                        inner()
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Faint, ChipShape)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        )
        Row(Modifier.padding(top = 8.dp, bottom = 20.dp)) {
            Text(
                "@msg #call -todo !note *event +timer :alarm ?ask",
                color = Faint, fontFamily = Mono, fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            Text("hub →", color = Faint, fontFamily = Mono, fontSize = 11.sp)
        }
    }
}

data class Tile(val glyph: String, val label: String, val onClick: () -> Unit)

@Composable
fun TileGrid(tiles: List<Tile>) {
    Column {
        tiles.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(ChipShape)
                            .border(1.dp, Faint, ChipShape)
                            .clickable { t.onClick() }
                            .padding(vertical = 14.dp)
                    ) {
                        Text(t.glyph, color = Ink, fontSize = 20.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(t.label, color = Ink, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
fun Chip(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ChipShape)
            .border(1.dp, Faint, ChipShape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content
    )
}

@Composable
fun Badge(n: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Accent)
    ) {
        Text("$n", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DottedDivider() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        repeat(24) { Text("·", color = Faint, fontSize = 14.sp) }
    }
}

@Composable
fun ItemList(items: List<String>, empty: String, onRemove: (String) -> Unit) {
    Column(Modifier.padding(top = 8.dp, start = 4.dp)) {
        if (items.isEmpty()) Text(empty, color = Faint, fontFamily = Mono, fontSize = 13.sp)
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("– $item", color = Dim, fontFamily = Mono, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("×", color = Faint, fontSize = 16.sp,
                    modifier = Modifier.clickable { onRemove(item) }.padding(6.dp))
            }
        }
    }
}
