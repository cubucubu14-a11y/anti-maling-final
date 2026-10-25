package com.antitheft.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class TheftService : Service(), SensorEventListener {
    companion object {
        const val ACTION_START = "com.antitheft.guard.START"
        const val ACTION_STOP = "com.antitheft.guard.STOP"
        const val ACTION_DISMISS = "com.antitheft.guard.DISMISS"
        private const val CHANNEL_ID = "anti_theft_channel"
        private const val ALARM_CHANNEL_ID = "anti_theft_alarm_channel"
        private const val NOTIFICATION_ID = 2001
        private const val ALARM_NOTIFICATION_ID = 2002
        private const val PREFS = "anti_theft_prefs"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_ALARM_URI = "alarm_uri"
    }

    private lateinit var sensorManager: SensorManager
    private var motionSensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var isArmed = false
    private var isAlarmActive = false
    private var lastTriggerAt = 0L
    private var baselineInitialized = false
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var linearSensorInUse = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        motionSensor = linear ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        linearSensorInUse = linear != null

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AntiTheft::SensorWakeLock")
        wakeLock?.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProtection()
                return START_NOT_STICKY
            }
            ACTION_DISMISS -> {
                stopAlarmOnly()
                // Keep protection armed after dismissing the alarm.
                isArmed = true
                updateNotification("Perlindungan aktif — sensor tetap memantau")
                return START_STICKY
            }
            else -> {
                startForegroundCompat(buildStatusNotification("Menyiapkan sensor gerak..."))
                isArmed = true
                try {
                    if (wakeLock?.isHeld != true) wakeLock?.acquire(12 * 60 * 60 * 1000L)
                } catch (_: Exception) {}
                registerMotionSensor()
                updateNotification(if (motionSensor == null) {
                    "Sensor gerak tidak tersedia di perangkat ini"
                } else {
                    "Perlindungan aktif — sensor memantau gerakan"
                })
            }
        }
        return START_STICKY
    }

    private fun registerMotionSensor() {
        sensorManager.unregisterListener(this)
        val sensor = motionSensor
        if (sensor == null) {
            updateNotification("Sensor gerak tidak tersedia")
            return
        }
        baselineInitialized = false
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isArmed || isAlarmActive || event.values.size < 3) return
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < 2500L) return

        val magnitude: Float
        if (linearSensorInUse) {
            magnitude = sqrt(
                event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
            )
        } else {
            // Estimate gravity with a low-pass filter, then measure movement away from gravity.
            val alpha = 0.82f
            if (!baselineInitialized) {
                gravityX = event.values[0]
                gravityY = event.values[1]
                gravityZ = event.values[2]
                baselineInitialized = true
                return
            }
            gravityX = alpha * gravityX + (1 - alpha) * event.values[0]
            gravityY = alpha * gravityY + (1 - alpha) * event.values[1]
            gravityZ = alpha * gravityZ + (1 - alpha) * event.values[2]
            val x = event.values[0] - gravityX
            val y = event.values[1] - gravityY
            val z = event.values[2] - gravityZ
            magnitude = sqrt(x * x + y * y + z * z)
        }

        val sensitivity = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SENSITIVITY, 5).coerceIn(1, 10)
        // Higher level = lower acceleration threshold, so it triggers more easily.
        val threshold = 3.2f - ((sensitivity - 1) * (2.45f / 9f))
        if (magnitude >= threshold) {
            lastTriggerAt = now
            triggerAlarm()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun triggerAlarm() {
        if (isAlarmActive) return
        isAlarmActive = true

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
        } catch (_: Exception) {}

        val selectedUri = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ALARM_URI, null)?.let(Uri::parse)
        val fallbackUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val uri = selectedUri ?: fallbackUri

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
                setOnErrorListener { _, _, _ ->
                    stopAlarmAudio()
                    true
                }
                prepare()
                start()
            }
        } catch (_: Exception) {
            mediaPlayer?.release()
            mediaPlayer = null
            try {
                val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                if (fallback != null) {
                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                        )
                        setDataSource(this@TheftService, fallback)
                        isLooping = true
                        prepare()
                        start()
                    }
                }
            } catch (_: Exception) {}
        }

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 500, 200, 500), 0)
            }
        } catch (_: Exception) {}

        showAlarmNotification()
        updateNotification("ALARM AKTIF — gerakan terdeteksi")
    }

    private fun stopAlarmAudio() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun stopAlarmOnly() {
        isAlarmActive = false
        stopAlarmAudio()
        try { vibrator?.cancel() } catch (_: Exception) {}
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ALARM_NOTIFICATION_ID)
    }

    private fun stopProtection() {
        isArmed = false
        sensorManager.unregisterListener(this)
        stopAlarmOnly()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        stopForegroundCompat()
        stopSelf()
    }

    private fun showAlarmNotification() {
        val alarmIntent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 12, alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("ALARM ANTI-MALING")
            .setContentText("Gerakan terdeteksi. Ketuk untuk membuka kontrol alarm.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(ALARM_NOTIFICATION_ID, notification)

        // Starting an Activity directly from the background is restricted on modern Android;
        // the notification's full-screen intent is the supported path when system policy permits.
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Status Anti-Maling", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(ALARM_CHANNEL_ID, "Alarm Anti-Maling", NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                }
            )
        }
    }

    private fun buildStatusNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Anti-Maling")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(message: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildStatusNotification(message))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Foreground service is configured stopWithTask=false; avoid unreliable self-restart alarms.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isArmed = false
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
        stopAlarmOnly()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
