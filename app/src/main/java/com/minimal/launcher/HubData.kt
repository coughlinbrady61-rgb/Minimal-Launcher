package com.minimal.launcher

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat

enum class HubType { MESSAGE, CALL, EMAIL, APP }

/**
 * replyAction: the PendingIntent + RemoteInput pair an app supplies for
 * "reply from the shade". Present only when the app offers it.
 */
data class ReplyAction(
    val pendingIntent: PendingIntent,
    val remoteInputs: Array<RemoteInput>,
    val resultKey: String
)

data class HubItem(
    val key: String,
    val type: HubType,
    val from: String,
    val preview: String,
    val timestamp: Long,
    val unread: Boolean = true,
    val flagged: Boolean = false,
    val contentIntent: PendingIntent? = null,
    val reply: ReplyAction? = null,
    val packageName: String = ""
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

    /** Fire the notification's own intent — opens the conversation/app. */
    fun open(ctx: Context, item: HubItem): Boolean {
        markRead(item.key)
        item.contentIntent?.let { pi ->
            val ok = runCatching { pi.send() }.isSuccess
            if (ok) return true
        }
        // fallback: just launch the app that posted it
        if (item.packageName.isNotBlank()) {
            return runCatching {
                ctx.packageManager.getLaunchIntentForPackage(item.packageName)?.let {
                    ctx.startActivity(it); true
                } ?: false
            }.getOrDefault(false)
        }
        return false
    }

    /** Send text back through the app's own reply mechanism. */
    fun sendReply(ctx: Context, item: HubItem, text: String): Boolean {
        val r = item.reply ?: return false
        return runCatching {
            val intent = Intent()
            val bundle = android.os.Bundle().apply { putCharSequence(r.resultKey, text) }
            RemoteInput.addResultsToIntent(r.remoteInputs, intent, bundle)
            r.pendingIntent.send(ctx, 0, intent)
            markRead(item.key)
            true
        }.getOrDefault(false)
    }

    fun loadCallLog(ctx: Context) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) return
        val proj = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE
        )
        runCatching {
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
                    val number = c.getString(numIx) ?: ""
                    val name = c.getString(nameIx)?.takeIf { it.isNotBlank() } ?: number.ifBlank { "unknown" }
                    upsert(
                        HubItem(
                            key = "call-" + c.getLong(idIx),
                            type = HubType.CALL,
                            from = name.lowercase(),
                            preview = label,
                            timestamp = c.getLong(dateIx),
                            unread = callType == CallLog.Calls.MISSED_TYPE,
                            packageName = number   // reused: number to call back
                        )
                    )
                }
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
