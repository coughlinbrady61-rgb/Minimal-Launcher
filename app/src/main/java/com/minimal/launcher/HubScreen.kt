package com.minimal.launcher

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class HubTab(val label: String) {
    ALL("all"), MESSAGE("message"), CALLS("calls"),
    EMAIL("email"), APPS("apps"), FLAGGED("flagged")
}

@Composable
fun HubScreen(hasNotificationAccess: Boolean, onRequestCallLog: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(HubTab.ALL) }
    val items = HubRepository.items
    val unread = HubRepository.unreadCount()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(48.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text("hub", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Medium)
            if (unread > 0) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "$unread new", color = Accent, fontFamily = Mono, fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
        Text(
            "messages, calls, mail and app alerts in one list",
            color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HubTab.entries.forEach { t ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { tab = t }
                ) {
                    Text(
                        t.label.uppercase(),
                        color = if (tab == t) Ink else Faint,
                        fontFamily = Mono, fontSize = 11.sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .width(18.dp).height(2.dp)
                            .background(if (tab == t) Accent else Color.Transparent)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (!hasNotificationAccess) {
            Chip(onClick = {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }) {
                Text("enable notification access →", color = Accent, fontFamily = Mono, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (tab == HubTab.CALLS || tab == HubTab.ALL) {
            LaunchedEffect(tab) { onRequestCallLog() }
        }

        val visible = items.filter {
            when (tab) {
                HubTab.ALL -> true
                HubTab.MESSAGE -> it.type == HubType.MESSAGE
                HubTab.CALLS -> it.type == HubType.CALL
                HubTab.EMAIL -> it.type == HubType.EMAIL
                HubTab.APPS -> it.type == HubType.APP
                HubTab.FLAGGED -> it.flagged
            }
        }

        if (visible.isEmpty()) {
            Text(
                "nothing here", color = Faint, fontFamily = Mono, fontSize = 13.sp,
                modifier = Modifier.padding(top = 24.dp)
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.key }) { item ->
                HubRow(item)
                Spacer(Modifier.height(8.dp))
            }
        }

        Text(
            "← swipe left goes back home",
            color = Faint, fontFamily = Mono, fontSize = 11.sp,
            modifier = Modifier.padding(vertical = 14.dp)
        )
    }
}

@Composable
fun HubRow(item: HubItem) {
    val icon = when (item.type) {
        HubType.MESSAGE -> TileIcons.message
        HubType.CALL -> TileIcons.call
        HubType.EMAIL -> TileIcons.email
        HubType.APP -> TileIcons.notification
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ChipShape)
            .border(1.dp, if (item.unread) Dim else Color(0xFF303030), ChipShape)
            .clickable { HubRepository.markRead(item.key) }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                item.from, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(relativeTime(item.timestamp), color = Dim, fontFamily = Mono, fontSize = 11.sp)
            if (item.unread) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(Accent))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.preview, color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(
                Icons.Outlined.Flag, contentDescription = "flag",
                tint = if (item.flagged) Accent else Faint,
                modifier = Modifier
                    .clickable { HubRepository.toggleFlag(item.key) }
                    .padding(6.dp)
                    .size(16.dp)
            )
            Icon(
                Icons.Outlined.Close, contentDescription = "dismiss",
                tint = Faint,
                modifier = Modifier
                    .clickable { HubRepository.dismiss(item.key) }
                    .padding(6.dp)
                    .size(16.dp)
            )
        }
    }
}
