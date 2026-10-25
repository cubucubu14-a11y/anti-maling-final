package com.antitheft.guard

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class AlarmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.RED)
            setPadding(36, 36, 36, 36)
        }
        root.addView(TextView(this).apply {
            text = "PERINGATAN!\\nGERAKAN HP TERDETEKSI"
            textSize = 27f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        root.addView(Button(this).apply {
            text = "MATIKAN ALARM"
            setOnClickListener { requestUnlockAndDismiss() }
        })
        setContentView(root)
    }

    private fun requestUnlockAndDismiss() {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= 26 && keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    dismissAlarm()
                }

                override fun onDismissCancelled() {
                    Toast.makeText(this@AlarmActivity, "Buka kunci HP untuk mematikan alarm", Toast.LENGTH_SHORT).show()
                }

                override fun onDismissError() {
                    Toast.makeText(this@AlarmActivity, "Tidak bisa membuka kunci. Coba lagi.", Toast.LENGTH_SHORT).show()
                }
            })
        } else {
            dismissAlarm()
        }
    }

    private fun dismissAlarm() {
        startService(Intent(this, TheftService::class.java).setAction(TheftService.ACTION_DISMISS))
        finish()
    }
}
