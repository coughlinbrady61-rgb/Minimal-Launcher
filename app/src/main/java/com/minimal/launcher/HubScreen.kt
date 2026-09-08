package com.minimal.launcher

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Send
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
import androidx.compose.ui.text.input.ImeAction
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
    var replyingTo by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    val items = HubRepository.items
    val unread = HubRepository.unreadCount()

    LaunchedEffect(flash) {
        if (flash != null) { kotlinx.coroutines.delay(1800); flash = null }
    }

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
            "tap opens · hold marks read",
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

        flash?.let {
            Text(it, color = Accent, fontFamily = Mono, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
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
                HubRow(
                    item = item,
                    isReplying = replyingTo == item.key,
                    onOpen = {
                        val ok = HubRepository.open(ctx, item)
                        if (!ok) flash = "couldn't open that"
                    },
                    onMarkRead = { HubRepository.markRead(item.key) },
                    onToggleReply = {
                        replyingTo = if (replyingTo == item.key) null else item.key
                    },
                    onSendReply = { text ->
                        val ok = HubRepository.sendReply(ctx, item, text)
                        flash = if (ok) "sent" else "reply failed"
                        replyingTo = null
                    },
                    onCallBack = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${item.packageName}"))
                            )
                        }
                    }
                )
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HubRow(
    item: HubItem,
    isReplying: Boolean,
    onOpen: () -> Unit,
    onMarkRead: () -> Unit,
    onToggleReply: () -> Unit,
    onSendReply: (String) -> Unit,
    onCallBack: () -> Unit
) {
    var draft by remember(item.key) { mutableStateOf("") }

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
            .combinedClickable(
                onClick = { if (item.type == HubType.CALL) onCallBack() else onOpen() },
                onLongClick = onMarkRead
            )
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

            if (item.reply != null) {
                Icon(
                    Icons.Outlined.Send, contentDescription = "reply",
                    tint = if (isReplying) Accent else Faint,
                    modifier = Modifier
                        .clickable { onToggleReply() }
                        .padding(6.dp)
                        .size(16.dp)
                )
            }
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

        if (isReplying && item.reply != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("> ", color = Accent, fontFamily = Mono, fontSize = 14.sp)
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = TextStyle(color = Ink, fontFamily = Mono, fontSize = 14.sp),
                    cursorBrush = SolidColor(Accent),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (draft.isNotBlank()) { onSendReply(draft); draft = "" }
                    }),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty())
                                Text("message", color = Faint, fontFamily = Mono, fontSize = 14.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "send",
                    color = if (draft.isBlank()) Faint else Accent,
                    fontFamily = Mono, fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { if (draft.isNotBlank()) { onSendReply(draft); draft = "" } }
                        .padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
                )
            }
        }
    }
}
