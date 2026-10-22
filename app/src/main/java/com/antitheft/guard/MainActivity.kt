package com.antitheft.guard

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }

        val btnArm = findViewById<Button>(R.id.btnArm)
        val btnSelectSound = findViewById<Button>(R.id.btnSelectSound)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnSelectSound.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Pilih Suara Alarm Anti-Maling")
                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val currentUri = prefs.getString("alarm_uri", null)
                if (currentUri != null) {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUri))
                }
            }
            startActivityForResult(intent, 999)
        }

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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 999 && resultCode == Activity.RESULT_OK) {
            val uri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().putString("alarm_uri", uri.toString()).apply()
                Toast.makeText(this, "Suara alarm disimpan!", Toast.LENGTH_SHORT).show()
            } else {
                getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().remove("alarm_uri").apply()
            }
        }
    }
}
