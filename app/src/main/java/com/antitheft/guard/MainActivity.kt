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

        // 1. MINTA IZIN NOTIFIKASI (Khusus Android 13 ke atas)
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val btnArm = findViewById<Button>(R.id.btnArm)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnArm.setOnClickListener {
            // 2. CARA MENGAKTIFKAN SERVICE
            val intent = Intent(this, TheftService::class.java).setAction("START")
            
            // Karena Android 8 (Oreo), kita harus menggunakan startForegroundService
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            // Ubah tampilan UI
            tvStatus.text = "Status: MENUNGGU DATAR..."
            btnArm.text = "JANGAN TUTUP APLIKASI"
            btnArm.isEnabled = false
            
            // 3. MINIMALKAN APLIKASI
            moveTaskToBack(true)
        }
    }
}
