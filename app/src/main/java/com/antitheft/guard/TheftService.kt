
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
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class TheftService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START =
            "com.antitheft.guard.START"
        const val ACTION_STOP =
            "com.antitheft.guard.STOP"
        const val ACTION_DISMISS =
            "com.antitheft.guard.DISMISS"

        private const val TAG = "TheftService"

        private const val CHANNEL_ID =
            "anti_theft_channel"
        private const val ALARM_CHANNEL_ID =
            "anti_theft_alarm_channel"

        private const val NOTIFICATION_ID = 2001
        private const val ALARM_NOTIFICATION_ID = 2002

        private const val PREFS = "anti_theft_prefs"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_ALARM_URI = "alarm_uri"

        private const val WAKELOCK_TIMEOUT =
            12L * 60L * 60L * 1000L

        private const val COOLDOWN_MS = 2500L
    }

    private lateinit var sensorManager: SensorManager

    private var motionSensor: Sensor? = null
    private var linearSensorInUse = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    @Volatile
    private var isArmed = false

    @Volatile
    private var isAlarmActive = false

    private var lastTriggerAt = 0L
    private var baselineInitialized = false

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f

    override fun onCreate() {
        super.onCreate()

        createNotificationChannels()

        sensorManager = getSystemService(
            Context.SENSOR_SERVICE
        ) as SensorManager

        val pm = getSystemService(
            Context.POWER_SERVICE
        ) as PowerManager

        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$TAG:SensorWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val vm = getSystemService(
                Context.VIBRATOR_MANAGER_SERVICE
            ) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(
                Context.VIBRATOR_SERVICE
            ) as Vibrator
        }

        selectMotionSensor()

        Log.i(
            TAG,
            "Created; sensor=${motionSensor?.name}; " +
                "type=${motionSensor?.type}; " +
                "wakeUp=${motionSensor?.isWakeUpSensor}"
        )
    }

    private fun selectMotionSensor() {
        val wakeAccelerometer =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_ACCELEROMETER,
                true
            )

        val wakeLinear =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_LINEAR_ACCELERATION,
                true
            )

        val normalAccelerometer =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_ACCELEROMETER
            )

        val normalLinear =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_LINEAR_ACCELERATION
            )

        // Accelerometer diprioritaskan karena tersedia
        // pada sebagian besar perangkat Android.
        motionSensor = wakeAccelerometer
            ?: wakeLinear
            ?: normalAccelerometer
            ?: normalLinear

        linearSensorInUse =
            motionSensor?.type ==
                Sensor.TYPE_LINEAR_ACCELERATION

        if (motionSensor == null) {
            Log.e(TAG, "No motion sensor available")
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.i(
            TAG,
            "onStartCommand action=${intent?.action}"
        )

        // Foreground wajib dimulai sebelum pemantauan.
        try {
            startForegroundCompat(
                buildStatusNotification(
                    "Menyiapkan perlindungan..."
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                stopProtection()
                return START_NOT_STICKY
            }

            ACTION_DISMISS -> {
                if (!isArmed) {
                    Log.w(
                        TAG,
                        "Dismiss ignored: not armed"
                    )
                    stopForegroundCompat(false)
                    return START_NOT_STICKY
                }

                stopAlarmOnly()
                ensureProtectionRunning()
                return START_STICKY
            }

            else -> {
                isArmed = true
                ensureProtectionRunning()
            }
        }

        return START_STICKY
    }

    private fun ensureProtectionRunning() {
        if (!isArmed) return

        acquireWakeLock()

        val sensor = motionSensor

        if (sensor == null) {
            updateNotification(
                "ERROR: Sensor gerak tidak tersedia"
            )
            return
        }

        try {
            sensorManager.unregisterListener(this)
            baselineInitialized = false

            val registered = sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )

            Log.i(
                TAG,
                "registerListener=$registered; " +
                    "sensor=${sensor.name}; " +
                    "wakeUp=${sensor.isWakeUpSensor}"
            )

            updateNotification(
                if (registered) {
                    if (sensor.isWakeUpSensor) {
                        "Aktif — sensor wake-up terdaftar"
                    } else {
                        "Aktif — sensor biasa terdaftar"
                    }
                } else {
                    "ERROR: Sensor gagal didaftarkan"
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sensor registration error", e)
            updateNotification(
                "ERROR: Gagal mendaftarkan sensor"
            )
        }
    }

    private fun acquireWakeLock() {
        try {
            val lock = wakeLock ?: return

            if (!lock.isHeld) {
                lock.acquire(WAKELOCK_TIMEOUT)
            }

            Log.i(TAG, "WakeLock held=${lock.isHeld}")
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock failed", e)
            updateNotification(
                "PERINGATAN: WakeLock gagal"
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isArmed || isAlarmActive) return
        if (event.values.size < 3) return

        val now = System.currentTimeMillis()

        if (now - lastTriggerAt < COOLDOWN_MS) return

        val magnitude: Float

        if (linearSensorInUse) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            magnitude = sqrt(x * x + y * y + z * z)
        } else {
            val alpha = 0.80f

            if (!baselineInitialized) {
                gravityX = event.values[0]
                gravityY = event.values[1]
                gravityZ = event.values[2]
                baselineInitialized = true
                return
            }

            gravityX =
                alpha * gravityX +
                    (1f - alpha) * event.values[0]

            gravityY =
                alpha * gravityY +
                    (1f - alpha) * event.values[1]

            gravityZ =
                alpha * gravityZ +
                    (1f - alpha) * event.values[2]

            val x = event.values[0] - gravityX
            val y = event.values[1] - gravityY
            val z = event.values[2] - gravityZ

            magnitude = sqrt(x * x + y * y + z * z)
        }

        val sensitivity = getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).getInt(
            KEY_SENSITIVITY,
            5
        ).coerceIn(1, 10)

        val threshold =
            3.2f - ((sensitivity - 1) * (2.45f / 9f))

        if (magnitude >= threshold) {
            lastTriggerAt = now

            Log.w(
                TAG,
                "MOTION DETECTED: magnitude=$magnitude " +
                    "threshold=$threshold"
            )

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

        Log.w(TAG, "ALARM TRIGGERED")

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
        } catch (e: Exception) {
            Log.e(TAG, "Set alarm volume failed", e)
        }

        playAlarmSound()
        startVibration()
        showAlarmNotification()

        updateNotification(
            "ALARM AKTIF — gerakan terdeteksi"
        )
    }

    private fun playAlarmSound() {
        releaseMediaPlayer()

        val selectedUri = try {
            getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            ).getString(
                KEY_ALARM_URI,
                null
            )?.let {
                Uri.parse(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid selected audio URI", e)
            null
        }

        val fallbackUri =
            RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_ALARM
            ) ?: RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_RINGTONE
            )

        val candidates = listOfNotNull(
            selectedUri,
            fallbackUri
        ).distinct()

        for (uri in candidates) {
            try {
                val player = MediaPlayer()

                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            AudioAttributes.USAGE_ALARM
                        )
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_SONIFICATION
                        )
                        .build()
                )

                player.setDataSource(this, uri)
                player.isLooping = true
                player.prepare()
                player.start()

                mediaPlayer = player

                Log.i(TAG, "Alarm audio started: $uri")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Audio playback failed: $uri", e)
                releaseMediaPlayer()
            }
        }

        Log.e(TAG, "No alarm audio source could be played")
    }

    private fun startVibration() {
        try {
            val pattern = longArrayOf(0, 500, 200, 500)

            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(
                        pattern,
                        0
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }

            Log.i(TAG, "Vibration started")
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    private fun releaseMediaPlayer() {
        val player = mediaPlayer
        mediaPlayer = null

        try {
            player?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer release failed", e)
        }
    }

    private fun stopAlarmOnly() {
        isAlarmActive = false

        releaseMediaPlayer()

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Vibrator cancel failed", e)
        }

        try {
            val manager = getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            manager.cancel(ALARM_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Cancel alarm notification failed",
                e
            )
        }
    }

    private fun stopProtection() {
        isArmed = false

        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Unregister sensor failed", e)
        }

        stopAlarmOnly()

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Release WakeLock failed", e)
        }

        try {
            val manager = getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            manager.cancel(NOTIFICATION_ID)
            manager.cancel(ALARM_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Notification cleanup failed", e)
        }

        stopForegroundCompat(true)
        stopSelf()
    }

    private fun showAlarmNotification() {
        try {
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
                .setSmallIcon(
                    android.R.drawable.ic_lock_lock
                )
                .setContentTitle("ALARM ANTI-MALING")
                .setContentText(
                    "Gerakan terdeteksi. Ketuk untuk mengontrol alarm."
                )
                .setCategory(
                    NotificationCompat.CATEGORY_ALARM
                )
                .setPriority(
                    NotificationCompat.PRIORITY_MAX
                )
                .setVisibility(
                    NotificationCompat.VISIBILITY_PUBLIC
                )
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(
                    pendingIntent,
                    true
                )
                .build()

            val manager = getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            manager.notify(
                ALARM_NOTIFICATION_ID,
                notification
            )
        } catch (e: Exception) {
            Log.e(TAG, "Show alarm notification failed", e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < 26) return

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

    private fun buildStatusNotification(
        message: String
    ): Notification {
        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_lock_lock
            )
            .setContentTitle("Anti-Maling")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .build()
    }

    private fun updateNotification(message: String) {
        try {
            val manager = getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            manager.notify(
                NOTIFICATION_ID,
                buildStatusNotification(message)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update notification failed", e)
        }
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

    private fun stopForegroundCompat(remove: Boolean) {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(
                if (remove) {
                    STOP_FOREGROUND_REMOVE
                } else {
                    STOP_FOREGROUND_DETACH
                }
            )
        } else {
            @Suppress("DEPRECATION")
            stopForeground(remove)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(
            TAG,
            "App task removed; armed=$isArmed"
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.w(TAG, "Service destroyed")

        isArmed = false

        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup sensor failed", e)
        }

        stopAlarmOnly()

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup WakeLock failed", e)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
