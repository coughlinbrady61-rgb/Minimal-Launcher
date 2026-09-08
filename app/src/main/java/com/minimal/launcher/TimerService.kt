package com.minimal.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.Locale

/**
 * Keeps the countdown alive when the launcher isn't on screen and rings
 * when it finishes. State lives in TimerState so the UI can read it any time.
 */
class TimerService : Service() {

    companion object {
        const val CHANNEL_RUNNING = "minimal_timer_running"
        const val CHANNEL_DONE = "minimal_timer_done"
        const val NOTIF_RUNNING = 1001
        const val NOTIF_DONE = 1002

        const val ACTION_START = "com.minimal.launcher.TIMER_START"
        const val ACTION_STOP = "com.minimal.launcher.TIMER_STOP"
        const val EXTRA_END_AT = "end_at"

        fun start(ctx: Context, endAt: Long) {
            val i = Intent(ctx, TimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_END_AT, endAt)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, TimerService::class.java).apply { action = ACTION_STOP })
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var endAt = 0L
    private var ticker: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelTicker()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                endAt = intent?.getLongExtra(EXTRA_END_AT, 0L) ?: 0L
                if (endAt <= System.currentTimeMillis()) {
                    stopSelf(); return START_NOT_STICKY
                }
                startForeground(NOTIF_RUNNING, runningNotification(endAt - System.currentTimeMillis()))
                scheduleTicker()
            }
        }
        return START_STICKY
    }

    private fun scheduleTicker() {
        cancelTicker()
        val r = object : Runnable {
            override fun run() {
                val left = endAt - System.currentTimeMillis()
                if (left <= 0) {
                    fireDone()
                    TimerState.clear(this@TimerService)
                    stopForegroundCompat()
                    stopSelf()
                    return
                }
                notifyManager().notify(NOTIF_RUNNING, runningNotification(left))
                handler.postDelayed(this, 1000)
            }
        }
        ticker = r
        handler.post(r)
    }

    private fun cancelTicker() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
    }

    private fun notifyManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = notifyManager()
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUNNING, "timer running", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE, "timer finished", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    Notification.AUDIO_ATTRIBUTES_DEFAULT
                )
            }
        )
    }

    private fun homeIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun runningNotification(left: Long): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_RUNNING)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        return b.setContentTitle("timer · ${fmt(left)}")
            .setContentText("counting down")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(homeIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun fireDone() {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_DONE)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        val n = b.setContentTitle("time's up")
            .setContentText("your timer finished")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(homeIntent())
            .setAutoCancel(true)
            .build()

        runCatching { notifyManager().notify(NOTIF_DONE, n) }
        runCatching { buzz() }
    }

    private fun buzz() {
        val pattern = longArrayOf(0, 400, 200, 400, 200, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            @Suppress("DEPRECATION")
            v.vibrate(pattern, -1)
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
        runCatching { notifyManager().cancel(NOTIF_RUNNING) }
    }

    override fun onDestroy() {
        cancelTicker()
        super.onDestroy()
    }
}

/**
 * Timestamp-based state, so the UI is correct no matter how long it was away
 * and both timer and stopwatch survive a reboot.
 */
object TimerState {
    private const val KEY_END = "timer_end_at"
    private const val KEY_PAUSED_LEFT = "timer_paused_left"
    private const val KEY_SW_START = "sw_start"
    private const val KEY_SW_ACC = "sw_accumulated"
    private const val KEY_SW_RUNNING = "sw_running"
    private const val KEY_SW_LAPS = "sw_laps"

    private fun prefs(c: Context) = c.getSharedPreferences("minimal_store", Context.MODE_PRIVATE)

    // ----- timer -----
    fun endAt(c: Context): Long = runCatching { prefs(c).getLong(KEY_END, 0L) }.getOrDefault(0L)

    fun pausedLeft(c: Context): Long = runCatching { prefs(c).getLong(KEY_PAUSED_LEFT, 0L) }.getOrDefault(0L)

    fun remaining(c: Context): Long {
        val paused = pausedLeft(c)
        if (paused > 0) return paused
        val end = endAt(c)
        if (end <= 0) return 0
        return (end - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun isRunning(c: Context) = endAt(c) > System.currentTimeMillis() && pausedLeft(c) == 0L

    fun startTimer(c: Context, durationMs: Long) {
        val end = System.currentTimeMillis() + durationMs
        prefs(c).edit().putLong(KEY_END, end).putLong(KEY_PAUSED_LEFT, 0L).apply()
        TimerService.start(c, end)
    }

    fun pauseTimer(c: Context) {
        val left = remaining(c)
        prefs(c).edit().putLong(KEY_PAUSED_LEFT, left).putLong(KEY_END, 0L).apply()
        TimerService.stop(c)
    }

    fun resumeTimer(c: Context) {
        val left = pausedLeft(c)
        if (left <= 0) return
        startTimer(c, left)
    }

    fun clear(c: Context) {
        prefs(c).edit().putLong(KEY_END, 0L).putLong(KEY_PAUSED_LEFT, 0L).apply()
    }

    fun clearAndStop(c: Context) {
        clear(c)
        TimerService.stop(c)
    }

    // ----- stopwatch -----
    fun swElapsed(c: Context): Long {
        val p = prefs(c)
        val acc = p.getLong(KEY_SW_ACC, 0L)
        return if (p.getBoolean(KEY_SW_RUNNING, false)) {
            acc + (System.currentTimeMillis() - p.getLong(KEY_SW_START, System.currentTimeMillis()))
        } else acc
    }

    fun swRunning(c: Context) = runCatching { prefs(c).getBoolean(KEY_SW_RUNNING, false) }.getOrDefault(false)

    fun swStart(c: Context) {
        prefs(c).edit()
            .putLong(KEY_SW_START, System.currentTimeMillis())
            .putBoolean(KEY_SW_RUNNING, true)
            .apply()
    }

    fun swStop(c: Context) {
        val acc = swElapsed(c)
        prefs(c).edit()
            .putLong(KEY_SW_ACC, acc)
            .putBoolean(KEY_SW_RUNNING, false)
            .apply()
    }

    fun swReset(c: Context) {
        prefs(c).edit()
            .putLong(KEY_SW_ACC, 0L)
            .putBoolean(KEY_SW_RUNNING, false)
            .putString(KEY_SW_LAPS, "")
            .apply()
    }

    fun swLaps(c: Context): List<Long> = runCatching {
        prefs(c).getString(KEY_SW_LAPS, "")!!
            .split(',').filter { it.isNotBlank() }.mapNotNull { it.toLongOrNull() }
    }.getOrDefault(emptyList())

    fun swAddLap(c: Context) {
        val laps = swLaps(c) + swElapsed(c)
        prefs(c).edit().putString(KEY_SW_LAPS, laps.joinToString(",")).apply()
    }
}
