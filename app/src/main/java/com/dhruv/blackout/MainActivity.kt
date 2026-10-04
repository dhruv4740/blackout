package com.dhruv.blackout

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Setup + health checklist and manual start. The look lives in [SetupScreen]. */
class MainActivity : AppCompatActivity() {

    private var lastKey: Any? = null
    private val handler = Handler(Looper.getMainLooper())
    private val render = Runnable { render() }

    /** Coalesces bursts (e.g. holding a volume key) into one redraw. */
    private val refresh = {
        handler.removeCallbacks(render)
        handler.postDelayed(render, 150)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refresh() }
    }

    private val a11yObserver = object : ContentObserver(handler) {
        // The service binds a moment after the setting flips.
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(render)
            handler.postDelayed(render, 800)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    override fun onResume() {
        super.onResume()
        render()
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, a11yObserver
        )
        getSharedPreferences("blackout", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(render)
        runCatching { unregisterReceiver(receiver) }
        contentResolver.unregisterContentObserver(a11yObserver)
        getSharedPreferences("blackout", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun render() {
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
            stats = Stats.summary(this),
        )
        val key = listOf(state.armed, state.alarmFloor, state.alarmVolume, state.ringVolume, state.stats,
            state.rows.map { it.ok to it.detail })
        if (key == lastKey) return
        lastKey = key
        val scrollY = (findViewById<android.view.View>(android.R.id.content) as? android.view.ViewGroup)
            ?.getChildAt(0)?.let { it as? ScrollView }?.scrollY ?: 0
        val view = SetupScreen.build(
            this, state,
            onStart = {
                BlackoutService.instance?.arm() ?: startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onAlarmFloor = { Blackout.setAlarmFloor(this, it) },
            onLockedTest = { BlackoutService.instance?.lockAndBlack() },
            onSideloadHelp = open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg = true),
        )
        setContentView(view)
        // Restore scroll before the first frame so the top of the screen never flashes.
        if (scrollY > 0) view.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.viewTreeObserver.removeOnPreDrawListener(this)
                view.scrollTo(0, scrollY)
                return false
            }
        })
    }
}
