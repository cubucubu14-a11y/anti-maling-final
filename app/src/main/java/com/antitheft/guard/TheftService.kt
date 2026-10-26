
package com.antitheft.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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

        private const val WAKELOCK_TIMEOUT = 12L * 60L * 60L * 1000L
    }

    private lateinit var sensorManager: SensorManager

    private var motionSensor: Sensor? = null
    private var linearSensorInUse = false

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

    override fun onCreate() {
        super.onCreate()

        createNotificationChannels()

        sensorManager =
            getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Prioritaskan sensor wake-up jika tersedia.
        val wakeLinear = sensorManager.getDefaultSensor(
            Sensor.TYPE_LINEAR_ACCELERATION, true
        )

        val wakeAccelerometer = sensorManager.getDefaultSensor(
            Sensor.TYPE_ACCELEROMETER, true
        )

        motionSensor = wakeLinear
            ?: wakeAccelerometer
            ?: sensorManager.getDefaultSensor(
                Sensor.TYPE_LINEAR_ACCELERATION
            )
            ?: sensorManager.getDefaultSensor(
                Sensor.TYPE_ACCELEROMETER
            )

        linearSensorInUse =
            motionSensor?.type == Sensor.TYPE_LINEAR_ACCELERATION

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pm = getSystemService(
            Context.POWER_SERVICE
        ) as PowerManager

        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AntiTheft::SensorWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        if (motionSensor == null) {
            updateNotification("ERROR: Sensor gerak tidak tersedia")
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_STOP -> {
                stopProtection()
                return START_NOT_STICKY
            }

            ACTION_DISMISS -> {
                stopAlarmOnly()

                // Alarm berhenti, perlindungan tetap aktif.
                isArmed = true
                acquireWakeLock()
                registerMotionSensor()

                updateNotification(
                    "Perlindungan aktif — sensor tetap memantau"
                )

                return START_STICKY
            }

            else -> {
                startForegroundCompat(
                    buildStatusNotification(
                        "Menyiapkan sensor gerak..."
                    )
                )

                isArmed = true
                acquireWakeLock()
                registerMotionSensor()

                updateNotification(
                    if (motionSensor == null) {
                        "Sensor gerak tidak tersedia"
                    } else {
                        "Perlindungan aktif — sensor terdaftar"
                    }
                )
            }
        }

        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val lock = wakeLock ?: return

            if (!lock.isHeld) {
                lock.acquire(WAKELOCK_TIMEOUT)
            }
        } catch (e: Exception) {
            updateNotification(
                "Peringatan: CPU wakelock gagal diaktifkan"
            )
        }
    }

    private fun registerMotionSensor() {
        sensorManager.unregisterListener(this)
        baselineInitialized = false

        val sensor = motionSensor

        if (sensor == null) {
            updateNotification("ERROR: Tidak ada sensor gerak")
            return
        }

        val registered = sensorManager.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_GAME
        )

        updateNotification(
            if (registered) {
                "Sensor terdaftar — perlindungan aktif"
            } else {
                "ERROR: Sensor gagal didaftarkan"
            }
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isArmed || isAlarmActive) return
        if (event.values.size < 3) return

        val now = System.currentTimeMillis()

        if (now - lastTriggerAt < 2500L) return

        val magnitude: Float

        if (linearSensorInUse) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            magnitude = sqrt(x * x + y * y + z * z)
        } else {
            // Pisahkan gravitasi dari percepatan gerak.
            val alpha = 0.80f

            if (!baselineInitialized) {
                gravityX = event.values[0]
                gravityY = event.values[1]
                gravityZ = event.values[2]
                baselineInitialized = true
                return
            }

            gravityX =
                alpha * gravityX + (1f - alpha) * event.values[0]

            gravityY =
                alpha * gravityY + (1f - alpha) * event.values[1]

            gravityZ =
                alpha * gravityZ + (1f - alpha) * event.values[2]

            val x = event.values[0] - gravityX
            val y = event.values[1] - gravityY
            val z = event.values[2] - gravityZ

            magnitude = sqrt(x * x + y * y + z * z)
        }

        val sensitivity = getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).getInt(KEY_SENSITIVITY, 5).coerceIn(1, 10)

        // Level tinggi = lebih sensitif.
        val threshold =
            3.2f - ((sensitivity - 1) * (2.45f / 9f))

        if (magnitude >= threshold) {
            lastTriggerAt = now
            triggerAlarm()
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) = Unit

    private fun triggerAlarm() {
        if (!isArmed || isAlarmActive) return

        isAlarmActive = true

        try {
            val audioManager = getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager

            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(
                    AudioManager.STREAM_ALARM
                ),
                0
            )
        } catch (_: Exception) {
        }

        val selectedUri = getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).getString(KEY_ALARM_URI, null)?.let {
            Uri.parse(it)
        }

        val fallbackUri =
            RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_ALARM
            ) ?: RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_RINGTONE
            )

        val uri = selectedUri ?: fallbackUri

        var audioStarted = false

        if (uri != null) {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SONIFICATION
                            )
                            .build()
                    )

                    setDataSource(this@TheftService, uri)
                    isLooping = true
                    prepare()
                    start()
                }

                audioStarted = true
            } catch (_: Exception) {
                releaseMediaPlayer()
            }
        }

        // Jika file pilihan gagal diputar, coba suara alarm bawaan.
        if (!audioStarted && fallbackUri != null &&
            fallbackUri != uri
        ) {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SONIFICATION
                            )
                            .build()
                    )

                    setDataSource(
                        this@TheftService,
                        fallbackUri
                    )

                    isLooping = true
                    prepare()
                    start()
                }
            } catch (_: Exception) {
                releaseMediaPlayer()
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 500, 200, 500),
                        0
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(
                    longArrayOf(0, 500, 200, 500),
                    0
                )
            }
        } catch (_: Exception) {
        }

        showAlarmNotification()

        updateNotification(
            "ALARM AKTIF — gerakan terdeteksi"
        )
    }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {
        }

        mediaPlayer = null
    }

    private fun stopAlarmOnly() {
        isAlarmActive = false

        releaseMediaPlayer()

        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }

        val manager = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        manager.cancel(ALARM_NOTIFICATION_ID)
    }

    private fun stopProtection() {
        isArmed = false

        sensorManager.unregisterListener(this)
        stopAlarmOnly()

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {
        }

        val manager = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        manager.cancel(NOTIFICATION_ID)

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
    }

    private fun showAlarmNotification() {
        val alarmIntent = Intent(
            this,
            AlarmActivity::class.java
        ).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            12,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            this,
            ALARM_CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("ALARM ANTI-MALING")
            .setContentText(
                "Gerakan terdeteksi. Ketuk untuk mengontrol alarm."
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        val manager = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        manager.notify(ALARM_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Status Anti-Maling",
                    NotificationManager.IMPORTANCE_LOW
                )
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    ALARM_CHANNEL_ID,
                    "Alarm Anti-Maling",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    lockscreenVisibility =
                        Notification.VISIBILITY_PUBLIC

                    setSound(null, null)
                }
            )
        }
    }

    private fun buildStatusNotification(
        message: String
    ): Notification {
        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Anti-Maling")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        manager.notify(
            NOTIFICATION_ID,
            buildStatusNotification(message)
        )
    }

    private fun startForegroundCompat(
        notification: Notification
    ) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Service foreground dipertahankan saat task dihapus,
        // sesuai konfigurasi stopWithTask=false pada Manifest.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isArmed = false

        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }

        stopAlarmOnly()

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
