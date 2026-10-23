package com.antitheft.guard

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
        }
        root.addView(statusText)

        root.addView(makeButton("AKTIFKAN PERLINDUNGAN") { startProtection() })
        root.addView(makeButton("MATIKAN PERLINDUNGAN") { stopProtection() })
        root.addView(makeButton("1. Izinkan Tanpa Batasan Baterai") { requestIgnoreBattery() })
        root.addView(makeButton("2. Izinkan Layar Penuh (layar kunci)") { requestFullScreen() })

        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }
    }

    private fun startProtection() {
        val intent = Intent(this, TheftService::class.java).apply {
            action = TheftService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "Perlindungan diaktifkan", Toast.LENGTH_SHORT).show()
    }

    private fun stopProtection() {
        val intent = Intent(this, TheftService::class.java).apply {
            action = TheftService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "Perlindungan dimatikan", Toast.LENGTH_SHORT).show()
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Sudah diizinkan", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requestFullScreen() {
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.canUseFullScreenIntent()) {
                Toast.makeText(this, "Sudah diizinkan", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {}
        } else {
            Toast.makeText(this, "Tidak perlu di versi Android ini", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStatus() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = if (pm.isIgnoringBatteryOptimizations(packageName)) "OK" else "BELUM"
        statusText.text = "Izin baterai: $battery\n\n" +
                "Tekan AKTIFKAN, lalu kunci HP dan coba gerakkan."
        statusText.visibility = View.VISIBLE
    }
}
