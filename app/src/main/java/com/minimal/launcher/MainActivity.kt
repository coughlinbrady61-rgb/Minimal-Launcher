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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private val calendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            val pager = rememberPagerState(initialPage = 1, pageCount = { 3 })
            val scope = rememberCoroutineScope()
            var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
            var scale by remember { mutableStateOf(TextScale.get(ctx)) }
            var tileVersion by remember { mutableStateOf(0) }

            LaunchedEffect(Unit) {
                apps = withContext(Dispatchers.Default) { loadInstalledApps() }
                if (!Agenda.hasPermission(ctx)) {
                    runCatching { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }
                }
            }

            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> SettingsScreen(
                        apps = apps,
                        scale = scale,
                        onScaleChange = { scale = it },
                        onTilesChanged = { tileVersion++ }
                    )
                    1 -> MinimalHome(
                        apps = apps,
                        scale = scale,
                        tileVersion = tileVersion,
                        launchApp = { pkg ->
                            runCatching {
                                packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity)
                            }
                        },
                        runIntent = { runCatching { startActivity(it) } },
                        goToHub = { scope.launch { pager.animateScrollToPage(2) } },
                        requestCalendar = {
                            runCatching { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }
                        }
                    )
                    2 -> HubScreen(
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
        '+' -> body.filter { it.isDigit() }.toIntOrNull()?.let { Command.Timer(it) }
        ':' -> parseTime(body)?.let { (h, m) -> Command.Alarm(h, m) }
        '?' -> Command.Ask(body)
        else -> Command.Search(s)
    }
}

fun parseTime(t: String): Pair<Int, Int>? {
    val lower = t.trim().lowercase()
    val pm = "pm" in lower
    val am = "am" in lower
    val parts = lower.split(':', '.')
    var h = parts.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
    if (pm && h in 1..11) h += 12
    if (am && h == 12) h = 0
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
    apps: List<AppEntry>,
    scale: Float,
    tileVersion: Int,
    launchApp: (String) -> Unit,
    runIntent: (Intent) -> Unit,
    goToHub: () -> Unit,
    requestCalendar: () -> Unit
) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var dateLine1 by remember { mutableStateOf("") }
    var dateLine2 by remember { mutableStateOf("") }
    var todos by remember { mutableStateOf(Store.list(ctx, "todos")) }
    var flash by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf<String?>(null) }
    var showTodos by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    val tiles = remember(tileVersion) { TileConfig.all(ctx) }

    // spacing that tightens as text grows, so tall text doesn't crowd the input
    val topPad = (52f - (scale * 12f)).coerceIn(28f, 46f).dp
    val boxPad = (16f - (scale * 5f)).coerceIn(7f, 12f).dp
    val tilePad = (18f - (scale * 5f)).coerceIn(9f, 15f).dp
    val gap = (12f - (scale * 3f)).coerceIn(6f, 10f).dp

    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            dateLine1 = SimpleDateFormat("EEEE,", Locale.getDefault()).format(now).lowercase()
            dateLine2 = SimpleDateFormat("MMMM d", Locale.getDefault()).format(now).lowercase()
            delay(30_000)
        }
    }
    LaunchedEffect(flash) { if (flash != null) { delay(1800); flash = null } }

    fun handleTile(value: String) {
        if (value.startsWith("app:")) {
            launchApp(value.removePrefix("app:"))
            return
        }
        when (val key = value.removePrefix("action:")) {
            "note" -> { showNotes = !showNotes; showTodos = false }
            "todo" -> { showTodos = !showTodos; showNotes = false }
            "hub" -> goToHub()
            else -> BuiltInActions.intentFor(key)?.let(runIntent)
        }
    }

    fun execute(raw: String) {
        when (val cmd = parseCommand(raw)) {
            is Command.Todo -> { Store.add(ctx, "todos", cmd.text); todos = Store.list(ctx, "todos"); flash = "added to-do" }
            is Command.Note -> { Store.add(ctx, "notes", cmd.text); flash = "noted" }
            is Command.Message -> commandToIntent(cmd)?.let(runIntent)
            is Command.Call -> commandToIntent(cmd)?.let(runIntent)
            is Command.Event -> commandToIntent(cmd)?.let(runIntent)
            is Command.Timer -> commandToIntent(cmd)?.let(runIntent)
            is Command.Alarm -> commandToIntent(cmd)?.let(runIntent)
            is Command.Ask -> {
                if (!Agenda.hasPermission(ctx)) requestCalendar()
                answer = Agenda.answer(ctx, cmd.question)
                showTodos = false; showNotes = false
            }
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
        Spacer(Modifier.height(topPad))

        Text(dateLine1, color = Ink, fontSize = (30 * scale).sp, fontWeight = FontWeight.Medium)
        Text(dateLine2, color = Ink, fontSize = (30 * scale).sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text("sunny", color = Dim, fontFamily = Mono, fontSize = (13 * scale).sp)

        Spacer(Modifier.height(gap))
        DottedDivider()
        Spacer(Modifier.height(gap))

        Chip(onClick = { runIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)) }, pad = boxPad) {
            Icon(
                TileIcons.calendar, contentDescription = null,
                tint = Accent, modifier = Modifier.size((17 * scale).dp)
            )
            Spacer(Modifier.width(10.dp))
            Text("calendar · today", color = Ink, fontSize = (15 * scale).sp)
        }
        Spacer(Modifier.height(8.dp))
        Chip(onClick = { showTodos = !showTodos; showNotes = false; answer = null }, pad = boxPad) {
            Icon(
                TileIcons.checklist, contentDescription = null,
                tint = Accent, modifier = Modifier.size((17 * scale).dp)
            )
            Spacer(Modifier.width(10.dp))
            Text("to-dos", color = Ink, fontSize = (15 * scale).sp)
            Spacer(Modifier.weight(1f))
            if (todos.isNotEmpty()) Badge(todos.size, scale)
        }

        if (showTodos) ItemList(items = todos, empty = "nothing to do", scale = scale) {
            Store.remove(ctx, "todos", it); todos = Store.list(ctx, "todos")
        }
        if (showNotes) ItemList(items = Store.list(ctx, "notes"), empty = "no notes", scale = scale) {
            Store.remove(ctx, "notes", it)
        }

        answer?.let { text ->
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(ChipShape)
                    .border(1.dp, Accent, ChipShape)
                    .padding(12.dp)
            ) {
                Text(
                    text,
                    color = Ink, fontFamily = Mono, fontSize = (13 * scale).sp,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "dismiss",
                    color = Accent, fontFamily = Mono, fontSize = (11 * scale).sp,
                    modifier = Modifier.clickable { answer = null }
                )
            }
        }

        Spacer(Modifier.height(gap))

        Column {
            tiles.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { value ->
                        val icon = tileIconFor(value)
                        val label = tileLabelFor(ctx, value)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clip(ChipShape)
                                .border(1.dp, Faint, ChipShape)
                                .clickable { handleTile(value) }
                                .padding(vertical = tilePad, horizontal = 2.dp)
                        ) {
                            Icon(
                                icon, contentDescription = label,
                                tint = Ink, modifier = Modifier.size((22 * scale).dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                label.take(10),
                                color = Ink, fontSize = (12 * scale).sp,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.weight(1f))

        flash?.let {
            Text(it, color = Accent, fontFamily = Mono, fontSize = (13 * scale).sp)
            Spacer(Modifier.height(6.dp))
        }

        filtered.forEach { app ->
            Text(
                app.label.lowercase(),
                color = Dim, fontSize = (16 * scale).sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { launchApp(app.packageName); input = "" }
                    .padding(vertical = 5.dp)
            )
        }

        BasicTextField(
            value = input,
            onValueChange = { input = it },
            textStyle = TextStyle(color = Ink, fontFamily = Mono, fontSize = (17 * scale).sp),
            cursorBrush = SolidColor(Accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { execute(input) }),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("> ", color = Accent, fontFamily = Mono, fontSize = (17 * scale).sp)
                    Box(Modifier.weight(1f)) {
                        if (input.isEmpty())
                            Text("type to do things", color = Faint, fontFamily = Mono, fontSize = (15 * scale).sp)
                        inner()
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Faint, ChipShape)
                .padding(horizontal = 14.dp, vertical = boxPad)
        )
        Row(Modifier.padding(top = 6.dp, bottom = 14.dp)) {
            Text("← settings", color = Faint, fontFamily = Mono, fontSize = (10 * scale).sp)
            Spacer(Modifier.weight(1f))
            Text("?today ?next ?free", color = Faint, fontFamily = Mono, fontSize = (10 * scale).sp)
            Spacer(Modifier.weight(1f))
            Text("hub →", color = Faint, fontFamily = Mono, fontSize = (10 * scale).sp)
        }
    }
}

@Composable
fun Chip(onClick: () -> Unit, pad: androidx.compose.ui.unit.Dp = 12.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ChipShape)
            .border(1.dp, Faint, ChipShape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = pad),
        content = content
    )
}

@Composable
fun Badge(n: Int, scale: Float = 1f) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size((22 * scale).dp)
            .clip(CircleShape)
            .background(Accent)
    ) {
        Text("$n", color = Color.Black, fontSize = (12 * scale).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DottedDivider() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        repeat(24) { Text("·", color = Faint, fontSize = 14.sp) }
    }
}

@Composable
fun ItemList(items: List<String>, empty: String, scale: Float = 1f, onRemove: (String) -> Unit) {
    Column(Modifier.padding(top = 6.dp, start = 4.dp)) {
        if (items.isEmpty()) Text(empty, color = Faint, fontFamily = Mono, fontSize = (13 * scale).sp)
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("– $item", color = Dim, fontFamily = Mono, fontSize = (14 * scale).sp, modifier = Modifier.weight(1f))
                Text("×", color = Faint, fontSize = (16 * scale).sp,
                    modifier = Modifier.clickable { onRemove(item) }.padding(6.dp))
            }
        }
    }
}
