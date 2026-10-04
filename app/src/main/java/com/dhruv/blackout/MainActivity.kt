package com.dhruv.blackout

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

/** Setup + health checklist and manual start. The look lives in [SetupScreen]. */
class MainActivity : AppCompatActivity() {

    override fun onResume() {
        super.onResume()
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        fun vol(s: Int) = "${am.getStreamVolume(s)}/${am.getStreamMaxVolume(s)}"
        fun open(a: String, pkg: Boolean = false) = {
            startActivity(Intent(a).apply { if (pkg) data = Uri.parse("package:$packageName") })
        }
        val ringer = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "Normal"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate only"
            else -> "Silent"
        }
        val state = SetupState(
            armed = Blackout.isArmed(this),
            rows = listOf(
                CheckRow(BlackoutService.instance != null, "Accessibility service",
                    if (BlackoutService.instance != null) "Connected" else "Turn Blackout on in Accessibility",
                    open(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                CheckRow(km.isDeviceSecure, "Screen lock",
                    if (km.isDeviceSecure) "Set" else "Set a PIN or fingerprint first"),
                CheckRow(pm.isIgnoringBatteryOptimizations(packageName), "Battery optimization",
                    if (pm.isIgnoringBatteryOptimizations(packageName)) "Exempt" else "Exempt Blackout so it isn't stopped overnight",
                    open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg = true)),
                CheckRow(am.ringerMode == AudioManager.RINGER_MODE_NORMAL, "Ringer", ringer),
                CheckRow(nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL,
                    "Do Not Disturb",
                    if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) "Off" else "On, calls and alarms may be muted"),
            ),
            alarmFloor = Blackout.alarmFloor(this),
            alarmVolume = vol(AudioManager.STREAM_ALARM),
            ringVolume = vol(AudioManager.STREAM_RING),
        )
        setContentView(
            SetupScreen.build(
                this, state,
                onStart = {
                    BlackoutService.instance?.arm() ?: startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onAlarmFloor = { Blackout.setAlarmFloor(this, it) },
                onLockedTest = { BlackoutService.instance?.lockAndBlack() },
                onSideloadHelp = open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg = true),
            )
        )
    }
}
