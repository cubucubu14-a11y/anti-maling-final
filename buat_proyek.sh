#!/bin/bash
echo "Membuat struktur folder..."
mkdir -p app/src/main/java/com/antitheft/guard
mkdir -p app/src/main/res/layout
mkdir -p app/src/main/res/values
mkdir -p .github/workflows

echo "Membuat file settings.gradle.kts..."
cat << 'EOF' > settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "AntiTheftGuard"
include(":app")
EOF

echo "Membuat file build.gradle.kts (Root)..."
cat << 'EOF' > build.gradle.kts
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
EOF

echo "Membuat file gradle.properties..."
cat << 'EOF' > gradle.properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
EOF

echo "Membuat file app/build.gradle.kts..."
cat << 'EOF' > app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.antitheft.guard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.antitheft.guard"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
EOF

echo "Membuat file AndroidManifest.xml..."
cat << 'EOF' > app/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

    <application
        android:allowBackup="false"
        android:label="Anti-Maling"
        android:theme="@android:style/Theme.DeviceDefault.DayNight">
        
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".TheftService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="anti_theft_sensor" />
        </service>
    </application>
</manifest>
EOF

echo "Membuat file strings.xml..."
cat << 'EOF' > app/src/main/res/values/strings.xml
<resources>
    <string name="app_name">Anti-Maling</string>
</resources>
EOF

echo "Membuat file activity_main.xml..."
cat << 'EOF' > app/src/main/res/layout/activity_main.xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="24dp"
    android:gravity="center">

    <TextView
        android:id="@+id/tvStatus"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Status: TIDAK AKTIF"
        android:textSize="24sp"
        android:textStyle="bold"
        android:layout_marginBottom="16dp" />

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Tekan AKTIFKAN, lalu letakkan HP datar di meja (bisa terlentang atau tertelungkup). Aplikasi akan otomatis mengunci diri dalam 5 detik."
        android:textAlignment="center"
        android:layout_marginBottom="32dp" />

    <Button
        android:id="@+id/btnArm"
        android:layout_width="match_parent"
        android:layout_height="60dp"
        android:text="AKTIFKAN GUARD"
        android:textSize="18sp"
        android:backgroundTint="#4CAF50" />
</LinearLayout>
EOF

echo "Membuat file MainActivity.kt..."
cat << 'EOF' > app/src/main/java/com/antitheft/guard/MainActivity.kt
package com.antitheft.guard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val btnArm = findViewById<Button>(R.id.btnArm)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnArm.setOnClickListener {
            val intent = Intent(this, TheftService::class.java).setAction("START")
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tvStatus.text = "Status: MENUNGGU DATAR..."
            btnArm.text = "JANGAN TUTUP APLIKASI"
            btnArm.isEnabled = false
            
            moveTaskToBack(true)
        }
    }
}
EOF

echo "Membuat file TheftService.kt..."
cat << 'EOF' > app/src/main/java/com/antitheft/guard/TheftService.kt
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
EOF

echo "Membuat file GitHub Actions build.yml..."
cat << 'EOF' > .github/workflows/build.yml
name: Build Anti-Theft APK

on:
  push:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
    - name: Checkout Repository
      uses: actions/checkout@v4

    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'

    - name: Setup Gradle
      uses: gradle/actions/setup-gradle@v3

    - name: Build Debug APK
      run: gradle assembleDebug

    - name: Upload APK Artifact
      uses: actions/upload-artifact@v4
      with:
        name: Anti-Theft-App
        path: app/build/outputs/apk/debug/app-debug.apk
EOF

echo "=========================================="
echo "SELESAI! Semua file berhasil dibuat."
echo "Silakan upload folder ini ke GitHub Anda."
echo "=========================================="
