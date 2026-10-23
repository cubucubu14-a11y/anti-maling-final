package com.antitheft.guard

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat

class TheftService : Service() {

    companion object {
        const val ACTION_START = "com.antitheft.guard.START"
        const val ACTION_STOP = "com.antitheft.guard.STOP"
        const val ACTION_DISMISS = "com.antitheft.guard.DISMISS"
        private const val CHANNEL_ID = "anti_theft_channel"
        private const val ALARM_CHANNEL_ID = "anti_theft_alarm_channel"
        private const val NOTIFICATION_ID = 2001
        private const val ALARM_NOTIFICATION_ID = 2002
        private const val COUNTDOWN_SECONDS = 4000L
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var handler: Handler? = null
    private var heartbeatRunnable: Runnable? = null

    private var isArmed = false
    private var isAlarmActive = false
    private var isTriggered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isArmed = true
                    isTriggered = false
                    updateNotification("Mode Siaga: Layar Mati. HP siap diamankan.")
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (isArmed && !isAlarmActive && !isTriggered) {
                        isTriggered = true
                        
                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                        @Suppress("DEPRECATION")
                        val screenWake = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "AntiTheft::ScreenWake"
                        )
                        screenWake.acquire(10000L)

                        updateNotification("⚠️ HP Menyala! Buka kunci dalam 4 detik!")
                        handler?.postDelayed({
                            if (isTriggered) {
                                triggerAlarm()
                            }
                        }, COUNTDOWN_SECONDS)
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (isTriggered || isAlarmActive) {
                        stopAlarmAndReset("Aman: Dibuka oleh pemilik")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        createNotificationChannels()
        handler = Handler(Looper.getMainLooper())

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AntiTheft::WakeLock")

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAlarmAndReset("Dihentikan")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISMISS -> {
                stopAlarmAndReset("Alarm dimatikan")
                return START_STICKY
            }
            else -> {
                startForegroundCompat(buildNotification("Memantau status layar..."))
                if (wakeLock.isHeld.not()) wakeLock.acquire(12 * 60 * 60 * 1000L)
                isArmed = true
                updateNotification("Mode Siaga: HP siap diamankan.")
                startHeartbeat()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartServiceIntent = Intent(applicationContext, TheftService::class.java).apply {
            action = ACTION_START
        }
        val restartServicePendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    private fun startHeartbeat() {
        heartbeatRunnable?.let { handler?.removeCallbacks(it) }
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (!isAlarmActive && !isTriggered) {
                    updateNotification("Sensor aktif (heartbeat OK)")
                }
                handler?.postDelayed(this, 10000)
            }
        }
        handler?.post(heartbeatRunnable!!)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun triggerAlarm() {
        isAlarmActive = true
        isTriggered = false

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(this@TheftService, uri)
            isLooping = true
            prepare()
            start()
        }

        if (Build.VERSION.SDK_INT >= 26) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), 0))
        }

        showAlarmScreen()
        updateNotification("🚨 GERAKAN TERDETEKSI - ALARM AKTIF")
    }

    private fun showAlarmScreen() {
        val intent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("ALARM ANTI-MALING")
            .setContentText("HP baru saja menyala! Ketuk untuk mematikan.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(ALARM_NOTIFICATION_ID, notification)
    }

    private fun stopAlarmAndReset(reason: String) {
        isAlarmActive = false
        isTriggered = false
        isArmed = false

        try {
            mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() }
        } catch (_: Exception) {}
        mediaPlayer = null
        vibrator?.cancel()
        handler?.removeCallbacksAndMessages(null)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(ALARM_NOTIFICATION_ID)
        updateNotification(reason)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val status = NotificationChannel(CHANNEL_ID, "Anti-Maling", NotificationManager.IMPORTANCE_LOW)
            val alarm = NotificationChannel(ALARM_CHANNEL_ID, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
            }
            manager.createNotificationChannel(status)
            manager.createNotificationChannel(alarm)
        }
    }

    private fun buildNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Anti-Maling")
            .setContentText(message)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        stopAlarmAndReset("Service dimatikan")
        heartbeatRunnable?.let { handler?.removeCallbacks(it) }
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
