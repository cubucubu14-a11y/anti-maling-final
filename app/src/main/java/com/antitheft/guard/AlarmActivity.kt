package com.antitheft.guard

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class AlarmActivity : Activity() {

    companion object {
        const val ACTION_ALARM_STOPPED = "com.antitheft.guard.ALARM_STOPPED"
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finishAndRemoveTask()
        }
    }

    private var receiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tampil di atas layar kunci untuk semua versi Android.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#C62828"))
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "ALARM ANTI-MALING"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val desc = TextView(this).apply {
            text = "Gerakan terdeteksi!\nKetuk tombol di bawah untuk mematikan."
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }
        root.addView(desc)

        val stopBtn = Button(this).apply {
            text = "MATIKAN ALARM"
            setOnClickListener {
                val svc = Intent(this@AlarmActivity, TheftService::class.java).apply {
                    action = TheftService.ACTION_DISMISS
                }
                startService(svc)
                finishAndRemoveTask()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(stopBtn)

        val stopAll = Button(this).apply {
            text = "MATIKAN SEMUA PERLINDUNGAN"
            setOnClickListener {
                stopService(Intent(this@AlarmActivity, TheftService::class.java))
                finishAndRemoveTask()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }
        root.addView(stopAll)

        setContentView(root)

        // Sembunyikan navigation bar sebisa mungkin.
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                stopReceiver,
                IntentFilter(ACTION_ALARM_STOPPED),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(stopReceiver, IntentFilter(ACTION_ALARM_STOPPED))
        }
        receiverRegistered = true
    }

    override fun onBackPressed() {
        // Cegah tombol back menutup alarm tanpa mematikannya.
        // Biarkan tetap di sini sampai user menekan tombol.
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stopReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        super.onDestroy()
    }
}
