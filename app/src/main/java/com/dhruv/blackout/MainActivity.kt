package com.dhruv.blackout

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Setup + health checklist, manual start button, and the Locked-mode experiment. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 14f }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(status)
            addView(button("Start black screen") {
                BlackoutService.instance?.arm() ?: startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            addView(button("1. App info (Allow restricted settings)") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
            addView(button("2. Accessibility settings (turn Blackout on)") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            addView(button("3. Exempt from battery optimization") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            })
            addView(CheckBox(this@MainActivity).apply {
                text = "While on, keep alarm volume at 80% or higher"
                isChecked = Blackout.alarmFloor(this@MainActivity)
                setOnCheckedChangeListener { _, v -> Blackout.setAlarmFloor(this@MainActivity, v) }
            })
            addView(button("Test: Locked mode (lock + black screen)") {
                BlackoutService.instance?.lockAndBlack()
            })
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        fun vol(s: Int) = "${am.getStreamVolume(s)}/${am.getStreamMaxVolume(s)}"
        fun ok(b: Boolean) = if (b) "OK" else "FIX"
        val ringer = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "silent"
        }
        status.text = listOf(
            "[${ok(BlackoutService.instance != null)}] Accessibility service connected",
            "[${ok(km.isDeviceSecure)}] Screen lock set (needed to unlock)",
            "[${ok(pm.isIgnoringBatteryOptimizations(packageName))}] Battery optimization exempt",
            "[${ok(am.ringerMode == AudioManager.RINGER_MODE_NORMAL)}] Ringer mode: $ringer",
            "[${ok(nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)}] Do Not Disturb off",
            "Alarm volume ${vol(AudioManager.STREAM_ALARM)}, ring volume ${vol(AudioManager.STREAM_RING)}",
            "Blackout is " + if (Blackout.isArmed(this)) "ON" else "off",
            "Logcat: adb logcat -s BlackoutSpike"
        ).joinToString("\n")
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
}
