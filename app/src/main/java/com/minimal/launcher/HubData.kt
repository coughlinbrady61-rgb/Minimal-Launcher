package com.minimal.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat

enum class HubType { MESSAGE, CALL, EMAIL, APP }

data class HubItem(
    val key: String,
    val type: HubType,
    val from: String,
    val preview: String,
    val timestamp: Long,
    val unread: Boolean = true,
    val flagged: Boolean = false
)

object HubRepository {
    val items = mutableStateListOf<HubItem>()

    fun upsert(item: HubItem) {
        val i = items.indexOfFirst { it.key == item.key }
        if (i >= 0) items[i] = item.copy(flagged = items[i].flagged)
        else items.add(0, item)
        items.sortByDescending { it.timestamp }
        while (items.size > 100) items.removeAt(items.lastIndex)
    }

    fun dismiss(key: String) { items.removeAll { it.key == key } }

    fun markRead(key: String) {
        val i = items.indexOfFirst { it.key == key }
        if (i >= 0) items[i] = items[i].copy(unread = false)
    }

    fun toggleFlag(key: String) {
        val i = items.indexOfFirst { it.key == key }
        if (i >= 0) items[i] = items[i].copy(flagged = !items[i].flagged)
    }

    fun unreadCount() = items.count { it.unread }

    fun loadCallLog(ctx: Context) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) return
        val proj = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE
        )
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI, proj, null, null,
            CallLog.Calls.DATE + " DESC LIMIT 15"
        )?.use { c ->
            val idIx = c.getColumnIndex(CallLog.Calls._ID)
            val nameIx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val numIx = c.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIx = c.getColumnIndex(CallLog.Calls.TYPE)
            val dateIx = c.getColumnIndex(CallLog.Calls.DATE)
            while (c.moveToNext()) {
                val callType = c.getInt(typeIx)
                val label = when (callType) {
                    CallLog.Calls.MISSED_TYPE -> "missed call"
                    CallLog.Calls.INCOMING_TYPE -> "incoming call"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing call"
                    else -> "call"
                }
                val name = c.getString(nameIx)?.takeIf { it.isNotBlank() }
                    ?: c.getString(numIx) ?: "unknown"
                upsert(
                    HubItem(
                        key = "call-" + c.getLong(idIx),
                        type = HubType.CALL,
                        from = name.lowercase(),
                        preview = label,
                        timestamp = c.getLong(dateIx),
                        unread = callType == CallLog.Calls.MISSED_TYPE
                    )
                )
            }
        }
    }
}

fun relativeTime(ts: Long): String {
    val diff = (System.currentTimeMillis() - ts) / 1000
    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86400 -> "${diff / 3600}h"
        else -> "${diff / 86400}d"
    }
}
