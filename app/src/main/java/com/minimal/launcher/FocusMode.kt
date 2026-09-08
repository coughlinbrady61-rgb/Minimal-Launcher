package com.minimal.launcher

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Focus mode hides chosen apps from this launcher — search, tiles, everything.
 * It can't stop another launcher or a direct shortcut from opening them; it
 * removes the temptation rather than enforcing a block.
 */
object FocusMode {
    private const val KEY_ON = "focus_on"
    private const val KEY_APPS = "focus_apps"
    private const val KEY_DND = "focus_dnd"

    /** package names commonly worth hiding, offered as a one-tap starting set */
    val suggested = listOf(
        "com.google.android.youtube",
        "com.twitter.android",
        "com.x.android",
        "com.instagram.android",
        "com.zhiliaoapp.musically",       // tiktok
        "com.facebook.katana",
        "com.reddit.frontpage",
        "com.snapchat.android",
        "com.netflix.mediaclient",
        "com.pinterest",
        "com.linkedin.android",
        "com.amazon.mShop.android.shopping"
    )

    private fun prefs(c: Context) = c.getSharedPreferences("minimal_store", Context.MODE_PRIVATE)

    fun isOn(c: Context): Boolean = runCatching { prefs(c).getBoolean(KEY_ON, false) }.getOrDefault(false)

    fun setOn(c: Context, on: Boolean) {
        runCatching { prefs(c).edit().putBoolean(KEY_ON, on).apply() }
        if (dndLinked(c)) applyDnd(c, on)
    }

    fun blocked(c: Context): Set<String> = runCatching {
        prefs(c).getString(KEY_APPS, "")!!.split('\u0001').filter { it.isNotBlank() }.toSet()
    }.getOrDefault(emptySet())

    fun toggleApp(c: Context, pkg: String) {
        val cur = blocked(c).toMutableSet()
        if (!cur.add(pkg)) cur.remove(pkg)
        runCatching {
            prefs(c).edit().putString(KEY_APPS, cur.joinToString("\u0001")).apply()
        }
    }

    fun addAll(c: Context, pkgs: Collection<String>) {
        val cur = blocked(c).toMutableSet()
        cur.addAll(pkgs)
        runCatching {
            prefs(c).edit().putString(KEY_APPS, cur.joinToString("\u0001")).apply()
        }
    }

    fun clearApps(c: Context) {
        runCatching { prefs(c).edit().putString(KEY_APPS, "").apply() }
    }

    /** true when this app should be hidden right now */
    fun hides(c: Context, pkg: String): Boolean = isOn(c) && pkg in blocked(c)

    // ---- optional do not disturb link ----
    fun dndLinked(c: Context): Boolean = runCatching { prefs(c).getBoolean(KEY_DND, false) }.getOrDefault(false)

    fun setDndLinked(c: Context, linked: Boolean) {
        runCatching { prefs(c).edit().putBoolean(KEY_DND, linked).apply() }
    }

    fun hasDndAccess(c: Context): Boolean = runCatching {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.isNotificationPolicyAccessGranted
    }.getOrDefault(false)

    fun requestDndAccess(c: Context) {
        runCatching {
            c.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun applyDnd(c: Context, on: Boolean) {
        if (!hasDndAccess(c)) return
        runCatching {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
        }
    }
}

// ---------- picker screen ----------
@Composable
fun FocusPicker(apps: List<AppEntry>, scale: Float, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val blocked = remember(refresh) { FocusMode.blocked(ctx) }
    val dndLinked = remember(refresh) { FocusMode.dndLinked(ctx) }

    val visible = apps.filter { query.isBlank() || it.label.contains(query, true) }
    val suggestedPresent = apps.filter { it.packageName in FocusMode.suggested }

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
            Text("focus mode", color = Ink, fontSize = (24 * scale).sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            Text(
                "${blocked.size} hidden",
                color = Dim, fontFamily = Mono, fontSize = (11 * scale).sp
            )
        }

        Text(
            "hidden apps disappear from search and tiles while focus is on",
            color = Faint, fontSize = (11 * scale).sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(14.dp))

        // do not disturb link
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChipShape)
                .border(1.dp, Faint, ChipShape)
                .clickable {
                    if (!FocusMode.hasDndAccess(ctx) && !dndLinked) {
                        FocusMode.requestDndAccess(ctx)
                    } else {
                        FocusMode.setDndLinked(ctx, !dndLinked); refresh++
                    }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("also silence notifications", color = Ink, fontSize = (14 * scale).sp)
                Text(
                    if (FocusMode.hasDndAccess(ctx)) "turns on do not disturb"
                    else "tap to grant permission first",
                    color = Faint, fontFamily = Mono, fontSize = (10 * scale).sp
                )
            }
            Box(
                Modifier
                    .size((18 * scale).dp)
                    .clip(CircleShape)
                    .background(if (dndLinked) Accent else Color.Transparent)
                    .border(1.dp, if (dndLinked) Accent else Dim, CircleShape)
            )
        }

        Spacer(Modifier.height(10.dp))

        // quick actions
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(ChipShape)
                    .border(1.dp, Faint, ChipShape)
                    .clickable {
                        FocusMode.addAll(ctx, suggestedPresent.map { it.packageName }); refresh++
                    }
                    .padding(vertical = 10.dp)
            ) {
                Text("hide the usual suspects", color = Ink, fontFamily = Mono, fontSize = (11 * scale).sp)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(0.5f)
                    .clip(ChipShape)
                    .border(1.dp, Faint, ChipShape)
                    .clickable { FocusMode.clearApps(ctx); refresh++ }
                    .padding(vertical = 10.dp)
            ) {
                Text("clear", color = Dim, fontFamily = Mono, fontSize = (11 * scale).sp)
            }
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
                            Text("search apps", color = Faint, fontFamily = Mono, fontSize = (13 * scale).sp)
                        inner()
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Faint, ChipShape)
                .padding(horizontal = 12.dp, vertical = 9.dp)
        )

        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.packageName }) { app ->
                val isBlocked = app.packageName in blocked
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { FocusMode.toggleApp(ctx, app.packageName); refresh++ }
                        .padding(vertical = 9.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size((18 * scale).dp)
                            .clip(CircleShape)
                            .background(if (isBlocked) Accent else Color.Transparent)
                            .border(1.dp, if (isBlocked) Accent else Dim, CircleShape)
                    ) {
                        if (isBlocked) Text("✓", color = Color.Black, fontSize = (10 * scale).sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        app.label.lowercase(),
                        color = if (isBlocked) Dim else Ink,
                        fontSize = (15 * scale).sp
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
