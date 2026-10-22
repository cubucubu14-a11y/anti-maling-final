package com.antitheft.guard

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlin.math.*

class TheftService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private var gX = 0f
    private var gY = 0f
    private var gZ = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var hasLastData = false
    private var baseRoll = 0f
    private var basePitch = 0f

    private enum class State { WAITING, ARMING, ARMED, TRIGGERED, ALARM }
    private var state = State.WAITING
    
    private var stillStartTime = 0L
    private var motionScore = 0f
    private val TILT_THRESHOLD = 15.0f
    private val MOTION_THRESHOLD = 2.0f
    private val SCORE_LIMIT = 30.0f
    private val AUTO_ARM_DELAY = 5000L
    private val COUNTDOWN_SECONDS = 4000L

    private var handler: Handler? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isTriggered = false

    private var watchdogRunnable: Runnable? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                if (isTriggered || mediaPlayer != null) {
                    stopAlarmAndReset("Dibuka oleh pemilik")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        registerReceiver(screenReceiver, filter)

        createNotificationChannel()
        handler = Handler(Looper.getMainLooper())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification("Mode Siaga", "Menunggu HP diletakkan datar..."))
        
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AntiTheft::WakeLock")
        wakeLock?.acquire(10*60*1000L)

        resetToWaiting()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || state == State.TRIGGERED || state == State.ALARM) return

        val acc = event.values

        if (gX == 0f && gY == 0f && gZ == 0f) {
            gX = acc[0]; gY = acc[1]; gZ = acc[2]
        } else {
            gX = (gX * 0.85f) + (acc[0] * 0.15f)
            gY = (gY * 0.85f) + (acc[1] * 0.15f)
            gZ = (gZ * 0.85f) + (acc[2] * 0.15f)
        }

        val roll = Math.toDegrees(atan2(gX.toDouble(), gZ.toDouble())).toFloat()
        val pitch = Math.toDegrees(atan2(gY.toDouble(), sqrt((gX*gX + gZ*gZ).toDouble()))).toFloat()
        
        val isFlat = abs(gZ) > 7.0f

        var linearAcc = 0f
        if (hasLastData) {
            val dx = acc[0] - lastX
            val dy = acc[1] - lastY
            val dz = acc[2] - lastZ
            linearAcc = sqrt(dx*dx + dy*dy + dz*dz)
        }
        lastX = acc[0]; lastY = acc[1]; lastZ = acc[2]; hasLastData = true

        if (state == State.WAITING || state == State.ARMING) {
            if (isFlat && linearAcc < 0.5f) {
                if (stillStartTime == 0L) stillStartTime = System.currentTimeMillis()
                
                val elapsed = System.currentTimeMillis() - stillStartTime
                if (elapsed > 500) {
                    state = State.ARMING
                    val remaining = ceil((AUTO_ARM_DELAY - elapsed) / 1000.0).toInt()
                    updateNotification("AUTO-ARMING", "Mengunci dalam $remaining detik...")
                }

                if (elapsed >= AUTO_ARM_DELAY) {
                    state = State.ARMED
                    baseRoll = roll
                    basePitch = pitch
                    stillStartTime = 0L
                    motionScore = 0f
                    updateNotification("🛡️ SIAGA (ARMED)", "HP diamankan. Jangan diangkat.")
                }
            } else {
                stillStartTime = 0L
                if (state == State.ARMING) {
                    state = State.WAITING
                    updateNotification("Mode Siaga", "Menunggu HP diletakkan datar...")
                }
            }
        }

        if (state == State.ARMED) {
            val tiltDiff = abs(roll - baseRoll) + abs(pitch - basePitch)

            if (tiltDiff > TILT_THRESHOLD || linearAcc > MOTION_THRESHOLD) {
                motionScore += 2.0f
            } else if (linearAcc > 0.8f) {
                motionScore += 0.5f
            } else {
                motionScore -= 1.0f
            }

            if (motionScore < 0) motionScore = 0f
            if (motionScore > SCORE_LIMIT) motionScore = SCORE_LIMIT

            if (motionScore >= SCORE_LIMIT) {
                triggerTimer()
            }
        }
    }

    private fun triggerTimer() {
        state = State.TRIGGERED
        isTriggered = true
        updateNotification("⚠️ PERINGATAN!", "Buka kunci HP dalam 4 detik atau sirine berbunyi!")

        handler?.postDelayed({
            if (isTriggered) {
                triggerAlarm()
            }
        }, COUNTDOWN_SECONDS)
    }

    private fun triggerAlarm() {
        state = State.ALARM
        updateNotification("🚨 ALARM! 🚨", "HP diambil orang lain! Buka kunci HP untuk mematikan.")
        
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@TheftService, alarmUri)
            setAudioStreamType(AudioManager.STREAM_ALARM)
            isLooping = true
            prepare()
            start()
        }

        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 500, 500), 0)
        }

        startVolumeWatchdog()
    }

    private fun startVolumeWatchdog() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        
        watchdogRunnable = object : Runnable {
            override fun run() {
                if (state == State.ALARM) {
                    if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) < maxVol) {
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
                    }
                    handler?.postDelayed(this, 200)
                }
            }
        }
        handler?.post(watchdogRunnable!!)
    }

    private fun stopAlarmAndReset(reason: String) {
        isTriggered = false
        state = State.WAITING
        motionScore = 0f
        stillStartTime = 0L
        hasLastData = false
        gX = 0f; gY = 0f; gZ = 0f
        
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        watchdogRunnable?.let { handler?.removeCallbacks(it) }
        watchdogRunnable = null

        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.cancel()

        updateNotification("Aman", reason)
    }

    private fun resetToWaiting() {
        state = State.WAITING
        motionScore = 0f
        stillStartTime = 0L
        hasLastData = false
        gX = 0f; gY = 0f; gZ = 0f
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenReceiver)
        sensorManager.unregisterListener(this)
        mediaPlayer?.release()
        watchdogRunnable?.let { handler?.removeCallbacks(it) }
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("guard_channel", "Anti-Maling Service", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, "guard_channel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, buildNotification(title, text))
    }

    override fun onBind(intent: Intent?) = null
}
