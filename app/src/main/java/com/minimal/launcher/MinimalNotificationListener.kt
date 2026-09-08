package com.minimal.launcher

import android.app.Notification
import android.app.RemoteInput
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MinimalNotificationListener : NotificationListenerService() {

    private val smsPackages = setOf(
        "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "org.thoughtcrime.securesms",
        "com.whatsapp", "org.telegram.messenger"
    )
    private val emailPackages = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook",
        "com.samsung.android.email.provider", "ch.protonmail.android",
        "com.fsck.k9", "me.bluemail.mail", "com.yahoo.mobile.client.android.mail"
    )
    private val ignoredPackages = setOf("android", "com.android.systemui")

    override fun onListenerConnected() {
        runCatching { activeNotifications?.forEach { handle(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { handle(sbn) }
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName in ignoredPackages) return
        if (sbn.packageName == packageName) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        if (title.isNullOrEmpty() && text.isNullOrEmpty()) return

        val type = when (sbn.packageName) {
            in smsPackages -> HubType.MESSAGE
            in emailPackages -> HubType.EMAIL
            else -> HubType.APP
        }

        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrDefault(sbn.packageName)

        HubRepository.upsert(
            HubItem(
                key = "n-${sbn.packageName}-${sbn.id}-${sbn.tag ?: ""}",
                type = type,
                from = (title ?: appLabel).lowercase(),
                preview = (text ?: "").lowercase().take(80),
                timestamp = sbn.postTime,
                contentIntent = n.contentIntent,
                reply = extractReply(n),
                packageName = sbn.packageName
            )
        )
    }

    /**
     * Apps that support "reply from the shade" attach an action carrying a
     * RemoteInput. Find the first one and keep it so the hub can use it.
     */
    private fun extractReply(n: Notification): ReplyAction? = runCatching {
        val actions = n.actions ?: return null
        for (action in actions) {
            val inputs = action.remoteInputs ?: continue
            val textInput = inputs.firstOrNull { it.allowFreeFormInput } ?: continue
            val pi = action.actionIntent ?: continue
            return ReplyAction(
                pendingIntent = pi,
                remoteInputs = inputs,
                resultKey = textInput.resultKey
            )
        }
        null
    }.getOrNull()

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // hub keeps its own record; shade dismissal doesn't clear it
    }
}
