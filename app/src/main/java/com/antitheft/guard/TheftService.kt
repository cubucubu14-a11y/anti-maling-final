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
import android.util.Log
import androidx.core.app.NotificationCompat

class TheftService : Service() {

    companion object {
        const val ACTION_START = "com.antitheft.guard.START"
        const val ACTION_STOP = "com.antitheft.guard.STOP"
        const val ACTION_DISMISS = "com.antitheft.guard.DISMISS"

        private const val TAG = "TheftService"
        private const val CHANNEL_ID = "anti_theft_channel"
        private const val ALARM_CHANNEL_ID = "anti_theft_alarm_channel"
        private const val NOTIFICATION_ID = 2001
        private const val ALARM_NOTIFICATION_ID = 2002
        private const val COUNTDOWN_MS = 4000L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var handler: Handler? = null
    private var countdownRunnable: Runnable? = null

    private var isArmed = false
    private var isAlarmActive = false
    private var isTriggered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isArmed = true
                    isTriggered = false
                    updateNotification("Mode Siaga: Layar Mati.")
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Saat AOD (Angkat untuk menyala) muncul ATAU layar dinyalakan
                    if (isArmed && !isAlarmActive && !isTriggered) {
                        isTriggered = true
                        
                        // Paksa layar menyala terang
                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                        @Suppress("DEPRECATION")
                        screenWakeLock = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "AntiTheft::ScreenWakeLock"
                        )
                        screenWakeLock?.acquire(10000L)

                        updateNotification("⚠️ HP Bergerak! Buka kunci dalam 4 detik!")
                        Log.w(TAG, "SCREEN ON detected. Starting countdown.")

                        countdownRunnable = Runnable {
                            if (isTriggered) {
                                triggerAlarm()
                            }
                        }
                        handler?.postDelayed(countdownRunnable!!, COUNTDOWN_MS)
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
        createNotificationChannels()
        handler = Handler(Looper.getMainLooper())

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AntiTheft::WakeLock")

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                stopProtection()
                return START_NOT_STICKY
            }
            ACTION_DISMISS -> {
                stopAlarmAndReset("Alarm dimatikan")
                return START_STICKY
            }
            else -> {
                startForegroundCompat(buildStatusNotification("Memantau status layar..."))
                if (wakeLock?.isHeld == false) wakeLock?.acquire(12 * 60 * 60 * 1000L)
                isArmed = true
                updateNotification("Perlindungan aktif. Kunci layar HP.")
            }
        }
        return START_STICKY
    }

    private fun triggerAlarm() {
        if (!isArmed || isAlarmActive) return
        isAlarmActive = true
        Log.w(TAG, "ALARM TRIGGERED")

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Audio failed", e)
        }

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), 0))
            }
        } catch (e: Exception) {}

        showAlarmNotification()
        updateNotification("🚨 ALARM AKTIF 🚨")
    }

    private fun stopAlarmAndReset(reason: String) {
        isAlarmActive = false
        isTriggered = false
        isArmed = true // Tetap armed setelah dibuka

        countdownRunnable?.let { handler?.removeCallbacks(it) }

        try {
            mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() }
        } catch (_: Exception) {}
        mediaPlayer = null

        try {
            vibrator?.cancel()
        } catch (_: Exception) {}

        try {
            screenWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(ALARM_NOTIFICATION_ID)
        updateNotification(reason)
    }

    private fun stopProtection() {
        isArmed = false
        isTriggered = false
        stopAlarmAndReset("Perlindungan dihentikan")

        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}

        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}

        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
            manager.cancel(ALARM_NOTIFICATION_ID)
        } catch (_: Exception) {}

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showAlarmNotification() {
        val alarmIntent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(this, 12, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("ALARM ANTI-MALING")
            .setContentText("Gerakan terdeteksi!")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(ALARM_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Status", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(ALARM_CHANNEL_ID, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
            })
        }
    }

    private fun buildStatusNotification(message: String): Notification {
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
        manager.notify(NOTIFICATION_ID, buildStatusNotification(message))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        isArmed = false
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        stopAlarmAndReset("Service dimatikan")
        countdownRunnable?.let { handler?.removeCallbacks(it) }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (screenWakeLock?.isHeld == true) screenWakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
