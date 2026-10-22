package com.antitheft.guard

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class AlarmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Memaksa layar tetap menyala dan menembus layar kunci
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

        // Tampilan layar peringatan merah
        val textView = TextView(this).apply {
            text = "⚠️ HP DIAMBIL ORANG LAIN ⚠️\n\nSilakan Buka Kunci HP Anda!"
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#B71C1C"))
        }
        setContentView(textView)
    }

    override fun onBackPressed() {
        // Mencegah maling keluar dengan tombol Back
        // Biarkan kosong
    }
}
