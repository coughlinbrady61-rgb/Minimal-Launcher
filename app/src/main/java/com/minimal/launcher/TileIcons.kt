package com.minimal.launcher

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.ui.graphics.vector.ImageVector

object TileIcons {

    fun forAction(key: String): ImageVector = when (key) {
        "note" -> Icons.Outlined.Description
        "event" -> Icons.Outlined.CalendarToday
        "clock" -> Icons.Outlined.AccessTime
        "todo" -> Icons.Outlined.Checklist
        "call" -> Icons.Outlined.Call
        "message" -> Icons.Outlined.ChatBubbleOutline
        "camera" -> Icons.Outlined.PhotoCamera
        "memo" -> Icons.Outlined.Mic
        "timer" -> Icons.Outlined.Timer
        "alarm" -> Icons.Outlined.Alarm
        "hub" -> Icons.Outlined.Inbox
        else -> Icons.Outlined.Widgets
    }

    // used for app tiles and generic slots
    val app: ImageVector = Icons.Outlined.Widgets

    // hub row icons
    val message: ImageVector = Icons.Outlined.ChatBubbleOutline
    val call: ImageVector = Icons.Outlined.Call
    val email: ImageVector = Icons.Outlined.Email
    val notification: ImageVector = Icons.Outlined.Notifications
    val calendar: ImageVector = Icons.Outlined.CalendarToday
    val checklist: ImageVector = Icons.Outlined.Checklist
}
