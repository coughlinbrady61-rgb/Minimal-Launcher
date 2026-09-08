package com.minimal.launcher

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object BuiltInActions {
    val all = listOf(
        "note" to "note",
        "event" to "event",
        "clock" to "clock",
        "todo" to "to do",
        "call" to "call",
        "message" to "message",
        "camera" to "camera",
        "memo" to "memo",
        "timer" to "timer",
        "alarm" to "alarm",
        "hub" to "hub"
    )

    fun labelFor(key: String) = all.firstOrNull { it.first == key }?.second ?: key

    fun intentFor(key: String): Intent? = when (key) {
        "event" -> Intent(Intent.ACTION_INSERT).apply { data = CalendarContract.Events.CONTENT_URI }
        "clock" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
        "call" -> Intent(Intent.ACTION_DIAL)
        "message" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
        "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        "memo" -> Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
        "timer" -> Intent(AlarmClock.ACTION_SET_TIMER)
        "alarm" -> Intent(AlarmClock.ACTION_SET_ALARM)
        else -> null
    }
}

object TileConfig {
    private const val KEY_PREFIX = "tile_"
    const val TILE_COUNT = 8

    private val defaults = listOf(
        "action:note", "action:event", "action:clock", "action:todo",
        "action:call", "action:message", "action:camera", "action:memo"
    )

    fun get(ctx: Context, index: Int): String = runCatching {
        ctx.getSharedPreferences("minimal_store", Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + index, null) ?: defaults[index]
    }.getOrDefault(defaults.getOrElse(index) { "action:note" })

    fun set(ctx: Context, index: Int, value: String) {
        runCatching {
            ctx.getSharedPreferences("minimal_store", Context.MODE_PRIVATE)
                .edit().putString(KEY_PREFIX + index, value).apply()
        }
    }

    fun all(ctx: Context): List<String> = (0 until TILE_COUNT).map { get(ctx, it) }
}

object TextScale {
    private const val KEY = "text_scale"

    fun get(ctx: Context): Float = runCatching {
        ctx.getSharedPreferences("minimal_store", Context.MODE_PRIVATE).getFloat(KEY, 1.0f)
    }.getOrDefault(1.0f)

    fun set(ctx: Context, v: Float) {
        runCatching {
            ctx.getSharedPreferences("minimal_store", Context.MODE_PRIVATE)
                .edit().putFloat(KEY, v).apply()
        }
    }
}

// ---------- shared helpers ----------
fun tileIconFor(value: String): ImageVector =
    if (value.startsWith("app:")) TileIcons.app
    else TileIcons.forAction(value.removePrefix("action:"))

fun tileLabelFor(ctx: Context, value: String): String =
    if (value.startsWith("app:")) {
        val pkg = value.removePrefix("app:")
        runCatching {
            ctx.packageManager.getApplicationLabel(
                ctx.packageManager.getApplicationInfo(pkg, 0)
            ).toString().lowercase()
        }.getOrDefault(pkg.substringAfterLast('.'))
    } else {
        BuiltInActions.labelFor(value.removePrefix("action:"))
    }

// ---------- settings ----------
@Composable
fun SettingsScreen(
    apps: List<AppEntry>,
    scale: Float,
    onScaleChange: (Float) -> Unit,
    onTilesChanged: () -> Unit,
    onOpenFocus: () -> Unit = {},
    focusVersion: Int = 0,
    onFocusToggled: () -> Unit = {}
) {
    val ctx = LocalContext.current
    var editingTile by remember { mutableStateOf<Int?>(null) }
    var tiles by remember { mutableStateOf(TileConfig.all(ctx)) }

    if (editingTile != null) {
        TilePicker(
            apps = apps,
            scale = scale,
            onPick = { value ->
                TileConfig.set(ctx, editingTile!!, value)
                tiles = TileConfig.all(ctx)
                editingTile = null
                onTilesChanged()
            },
            onCancel = { editingTile = null }
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Text("settings", color = Ink, fontSize = (30 * scale).sp, fontWeight = FontWeight.Medium)
        Text(
            "tiles and appearance",
            color = Dim, fontSize = (12 * scale).sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(22.dp))

        Text("TILES", color = Faint, fontFamily = Mono, fontSize = (11 * scale).sp)
        Spacer(Modifier.height(10.dp))

        Column {
            tiles.chunked(4).forEachIndexed { rowIx, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { colIx, value ->
                        val index = rowIx * 4 + colIx
                        val label = tileLabelFor(ctx, value)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clip(ChipShape)
                                .border(1.dp, Faint, ChipShape)
                                .clickable { editingTile = index }
                                .padding(vertical = 12.dp, horizontal = 2.dp)
                        ) {
                            Icon(
                                tileIconFor(value), contentDescription = label,
                                tint = Ink, modifier = Modifier.size((20 * scale).dp)
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(label.take(10), color = Ink, fontSize = (10 * scale).sp, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Text("tap a tile to change it", color = Faint, fontFamily = Mono, fontSize = (11 * scale).sp)

        Spacer(Modifier.height(24.dp))

        Text("FOCUS", color = Faint, fontFamily = Mono, fontSize = (11 * scale).sp)
        Spacer(Modifier.height(10.dp))

        val focusOn = remember(focusVersion) { FocusMode.isOn(ctx) }
        val hiddenCount = remember(focusVersion) { FocusMode.blocked(ctx).size }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChipShape)
                .border(1.dp, if (focusOn) Accent else Faint, ChipShape)
                .clickable { FocusMode.setOn(ctx, !focusOn); onFocusToggled() }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (focusOn) "focus mode on" else "focus mode off",
                    color = if (focusOn) Accent else Ink, fontSize = (15 * scale).sp
                )
                Text(
                    "$hiddenCount apps hidden while on",
                    color = Faint, fontFamily = Mono, fontSize = (10 * scale).sp
                )
            }
            Box(
                Modifier
                    .size((20 * scale).dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (focusOn) Accent else Color.Transparent)
                    .border(1.dp, if (focusOn) Accent else Dim, androidx.compose.foundation.shape.CircleShape)
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChipShape)
                .border(1.dp, Faint, ChipShape)
                .clickable { onOpenFocus() }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text("choose hidden apps", color = Ink, fontSize = (14 * scale).sp, modifier = Modifier.weight(1f))
            Text("›", color = Accent, fontSize = (16 * scale).sp)
        }

        Spacer(Modifier.height(24.dp))

        Text("TEXT SIZE", color = Faint, fontFamily = Mono, fontSize = (11 * scale).sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.85f to "85%", 1.0f to "100%", 1.15f to "115%", 1.25f to "125%").forEach { (v, label) ->
                val selected = kotlin.math.abs(scale - v) < 0.01f
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(ChipShape)
                        .background(if (selected) Accent else Color.Transparent)
                        .border(1.dp, if (selected) Accent else Faint, ChipShape)
                        .clickable {
                            TextScale.set(ctx, v)
                            onScaleChange(v)
                        }
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        label,
                        color = if (selected) Color.Black else Ink,
                        fontFamily = Mono, fontSize = (12 * scale).sp
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "← swipe left for home",
            color = Faint, fontFamily = Mono, fontSize = (11 * scale).sp,
            modifier = Modifier.padding(bottom = 20.dp)
        )
    }
}

@Composable
fun TilePicker(
    apps: List<AppEntry>,
    scale: Float,
    onPick: (String) -> Unit,
    onCancel: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ ", color = Accent, fontSize = (26 * scale).sp,
                modifier = Modifier.clickable { onCancel() }
            )
            Text("pick a tile", color = Ink, fontSize = (26 * scale).sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(Modifier.weight(1f)) {
            item {
                Text("ACTIONS", color = Faint, fontFamily = Mono, fontSize = (11 * scale).sp)
                Spacer(Modifier.height(8.dp))
            }
            items(BuiltInActions.all) { (key, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick("action:$key") }
                        .padding(vertical = 10.dp)
                ) {
                    Icon(
                        TileIcons.forAction(key), contentDescription = null,
                        tint = Accent, modifier = Modifier.size((18 * scale).dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(label, color = Ink, fontSize = (16 * scale).sp)
                }
            }
            item {
                Spacer(Modifier.height(18.dp))
                Text("APPS", color = Faint, fontFamily = Mono, fontSize = (11 * scale).sp)
                Spacer(Modifier.height(8.dp))
            }
            items(apps) { app ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick("app:${app.packageName}") }
                        .padding(vertical = 10.dp)
                ) {
                    Icon(
                        TileIcons.app, contentDescription = null,
                        tint = Dim, modifier = Modifier.size((18 * scale).dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(app.label.lowercase(), color = Ink, fontSize = (16 * scale).sp)
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}
