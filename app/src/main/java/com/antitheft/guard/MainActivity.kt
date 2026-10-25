
package com.antitheft.guard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var sensitivityText: TextView
    private var previewPlayer: MediaPlayer? = null

    private val prefs by lazy {
        getSharedPreferences("anti_theft_prefs", Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_ALARM_URI = "alarm_uri"
        private const val PICK_AUDIO_REQUEST = 710
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
        }

        root.addView(
            TextView(this).apply {
                text = "ANTI-MALING"
                textSize = 26f
                gravity = Gravity.CENTER
            },
            matchWrap()
        )

        statusText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(statusText, matchWrap())

        root.addView(makeButton("AKTIFKAN PERLINDUNGAN") {
            startProtection()
        })

        root.addView(makeButton("MATIKAN PERLINDUNGAN") {
            stopProtection()
        })

        root.addView(
            TextView(this).apply {
                text = "SENSITIVITAS GERAK"
                textSize = 18f
                setPadding(0, dp(22), 0, 0)
            },
            matchWrap()
        )

        sensitivityText = TextView(this).apply {
            textSize = 15f
        }
        root.addView(sensitivityText, matchWrap())

        val seek = SeekBar(this).apply {
            max = 9
            progress = (prefs.getInt(KEY_SENSITIVITY, 5) - 1)
                .coerceIn(0, 9)
        }
        root.addView(seek, matchWrap())
        updateSensitivityLabel(seek.progress + 1)

        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val value = progress + 1
                    updateSensitivityLabel(value)

                    if (fromUser) {
                        prefs.edit()
                            .putInt(KEY_SENSITIVITY, value)
                            .apply()
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            }
        )

        root.addView(makeButton("PILIH SUARA ALARM (MP3/AUDIO)") {
            pickAlarmAudio()
        })

        root.addView(makeButton("TES SUARA ALARM") {
            previewAlarm()
        })

        root.addView(makeButton("HENTIKAN TES SUARA") {
            stopPreview()
        })

        root.addView(makeButton("IZINKAN TANPA BATASAN BATERAI") {
            requestIgnoreBattery()
        })

        setContentView(root)
        refreshStatus()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun startProtection() {
        stopPreview()

        val intent = Intent(
            this,
            TheftService::class.java
        ).setAction(TheftService.ACTION_START)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            Toast.makeText(
                this,
                "Perlindungan diaktifkan",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Gagal mengaktifkan perlindungan",
                Toast.LENGTH_LONG
            ).show()
        }

        refreshStatus()
    }

    private fun stopProtection() {
        stopPreview()

        try {
            startService(
                Intent(this, TheftService::class.java)
                    .setAction(TheftService.ACTION_STOP)
            )
        } catch (_: Exception) {
            stopService(Intent(this, TheftService::class.java))
        }

        Toast.makeText(
            this,
            "Perintah mematikan perlindungan dikirim",
            Toast.LENGTH_SHORT
        ).show()

        refreshStatus()
    }

    private fun pickAlarmAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

        startActivityForResult(intent, PICK_AUDIO_REQUEST)
    }

    @Deprecated("Using activity result for Android compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_AUDIO_REQUEST &&
            resultCode == RESULT_OK
        ) {
            val uri = data?.data ?: return

            try {
                val flags = data.flags and
                    Intent.FLAG_GRANT_READ_URI_PERMISSION

                contentResolver.takePersistableUriPermission(
                    uri,
                    flags
                )
            } catch (_: Exception) {
                // Beberapa penyedia file tidak mendukung izin permanen.
            }

            prefs.edit()
                .putString(KEY_ALARM_URI, uri.toString())
                .apply()

            Toast.makeText(
                this,
                "Suara alarm berhasil disimpan",
                Toast.LENGTH_SHORT
            ).show()

            refreshStatus()
        }
    }

    private fun previewAlarm() {
        stopPreview()

        val selectedUri = prefs.getString(KEY_ALARM_URI, null)
            ?.let { Uri.parse(it) }

        val uri = selectedUri
            ?: android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_ALARM
            )

        if (uri == null) {
            Toast.makeText(
                this,
                "Pilih file audio terlebih dahulu",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            previewPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                isLooping = false

                setOnCompletionListener {
                    stopPreview()
                }

                setOnErrorListener { _, _, _ ->
                    stopPreview()
                    true
                }

                prepare()
                start()
            }
        } catch (_: Exception) {
            stopPreview()
            Toast.makeText(
                this,
                "File audio tidak bisa diputar",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopPreview() {
        try {
            previewPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {
        }

        previewPlayer = null
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(
            Context.POWER_SERVICE
        ) as PowerManager

        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(
                this,
                "Pembatasan baterai sudah dinonaktifkan",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }
    }

    private fun refreshStatus() {
        val pm = getSystemService(
            Context.POWER_SERVICE
        ) as PowerManager

        val battery = if (
            pm.isIgnoringBatteryOptimizations(packageName)
        ) {
            "Diizinkan"
        } else {
            "Belum diizinkan"
        }

        val audio = if (
            prefs.getString(KEY_ALARM_URI, null) != null
        ) {
            "Audio pilihan sendiri"
        } else {
            "Suara alarm bawaan"
        }

        statusText.text =
            "Anti-Maling\n" +
            "Baterai: $battery\n" +
            "Suara: $audio"
    }

    private fun updateSensitivityLabel(value: Int) {
        val description = when (value) {
            in 1..3 -> "Rendah"
            in 4..7 -> "Sedang"
            else -> "Tinggi"
        }

        sensitivityText.text =
            "Level $value/10 — $description"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }
}
