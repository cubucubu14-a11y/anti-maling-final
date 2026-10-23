
package com.antitheft.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class TheftService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "com.antitheft.guard.START"
        const val ACTION_STOP = "com.antitheft.guard.STOP"

        private const val CHANNEL_ID = "anti_theft_channel"
        private const val NOTIFICATION_ID = 2001

        // Ambang lebih rendah khusus untuk pengujian.
        private const val MOTION_THRESHOLD = 1.5f
        private const val COOLDOWN_MS = 2500L
        private const val STATUS_UPDATE_MS = 5000L
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private var monitoring = false
    private var alarmPlaying = false
    private var gravityInitialized = false

    private var gx = 0f
    private var gy = 0f
    private var gz = 0f

    private var lastAlarm = 0L
    private var lastStatusUpdate = 0L
    private var sensorEventCount = 0L

    override fun onCreate() {
        super.onCreate()

        sensorManager =
            getSystemService(Context.SENSOR_SERVICE) as SensorManager

        accelerometer =
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val pm = getSystemService(Context.POWER_SERVICE)
                as PowerManager

        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:AntiTheftMotion"
        )

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> startMonitoring()
        }

        return START_STICKY
    }

    private fun startMonitoring() {
        if (monitoring) return

        if (accelerometer == null) {
            startForegroundCompat(buildNotification("Sensor akselerometer tidak tersedia"))
            stopSelf()
            return
        }

        startForegroundCompat(
            buildNotification("Menyiapkan sensor gerakan...")
        )

        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
            }

            gravityInitialized = false
            sensorEventCount = 0L
            lastAlarm = 0L
            lastStatusUpdate = 0L

            // Sampling lebih cepat daripada SENSOR_DELAY_NORMAL.
            // Max report latency 0 agar event tidak sengaja dibuffer.
            monitoring = sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME,
                0
            )

            updateNotification(
                if (monitoring) {
                    "Pemantauan aktif — menunggu data sensor"
                } else {
                    "Gagal mendaftarkan sensor"
                }
            )

            if (!monitoring) {
                stopMonitoring()
                stopSelf()
            }
        } catch (e: Exception) {
            updateNotification("Gagal memulai sensor: ${e.javaClass.simpleName}")
            stopMonitoring()
            stopSelf()
        }
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

    override fun onSensorChanged(event: SensorEvent) {
        if (!monitoring || event.sensor.type != Sensor.TYPE_ACCELEROMETER) {
            return
        }

        sensorEventCount++

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!gravityInitialized) {
            gx = x
            gy = y
            gz = z
            gravityInitialized = true
            updateNotification("Sensor menerima data — gerakan belum terdeteksi")
            return
        }

        // Filter gravitasi untuk memperkirakan gerakan perangkat.
        val alpha = 0.8f
        gx = alpha * gx + (1f - alpha) * x
        gy = alpha * gy + (1f - alpha) * y
        gz = alpha * gz + (1f - alpha) * z

        val dx = x - gx
        val dy = y - gy
        val dz = z - gz

        val motion = sqrt(dx * dx + dy * dy + dz * dz)
        val now = SystemClock.elapsedRealtime()

        if (motion >= MOTION_THRESHOLD &&
            now - lastAlarm >= COOLDOWN_MS
        ) {
            lastAlarm = now
            startAlarm()
        }

        // Status berkala untuk mengetahui apakah sensor tetap mengirim data
        // ketika layar mati. Tidak memperbarui notifikasi pada setiap event.
        if (now - lastStatusUpdate >= STATUS_UPDATE_MS) {
            lastStatusUpdate = now

            if (!alarmPlaying) {
                updateNotification(
                    "Sensor aktif — event diterima: $sensorEventCount"
                )
            }
        }
    }

    private fun startAlarm() {
        if (alarmPlaying) return
        alarmPlaying = true

        try {
            val uri = RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_ALARM
            ) ?: RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_RINGTONE
            )

            if (uri != null) {
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
            } else {
                alarmPlaying = false
            }
        } catch (e: Exception) {
            mediaPlayer?.release()
            mediaPlayer = null
            alarmPlaying = false
        }

        try {
            val pattern = longArrayOf(0, 500, 200, 500, 200, 700)

            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(pattern, 0)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (_: Exception) {
        }

        updateNotification("GERAKAN TERDETEKSI — ALARM AKTIF")
    }

    private fun stopAlarm() {
        alarmPlaying = false

        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {
        }

        mediaPlayer = null

        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun stopMonitoring() {
        monitoring = false

        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }

        stopAlarm()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Perlindungan Anti-Maling",
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description =
                "Status pemantauan gerakan Anti-Maling"

            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Anti-Maling")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager

        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
    }

    override fun onDestroy() {
        stopMonitoring()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
