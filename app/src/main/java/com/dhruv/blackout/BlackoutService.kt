package com.dhruv.blackout

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Owns the black cover (TYPE_ACCESSIBILITY_OVERLAY) and the single persisted "armed" state.
 * Armed ends only by authenticated unlock or by the system's own unlock (USER_PRESENT);
 * a power-button screen off/on does NOT end it. Alarms and calls only suspend the cover.
 * Logcat tag [TAG].
 */
class BlackoutService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var audio: AudioManager
    private var cover: FrameLayout? = null
    private var coverParams: WindowManager.LayoutParams? = null
    private var peek: TextView? = null
    private var removing = false
    private var suspended = false
    private var armedAt = 0L
    private var audioMode = AudioManager.MODE_NORMAL
    private var loudPlaying = false

    private val armed get() = Blackout.isArmed(this)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            Log.i(TAG, "${intent.action} deviceLocked=${km.isDeviceLocked} armed=$armed cover=${cover != null}")
            when (intent.action) {
                // The system itself just authenticated the user: always exit.
                Intent.ACTION_USER_PRESENT -> if (armed) disarm("user present")
                // Power button off/on must not drop the cover; make sure it is still there.
                Intent.ACTION_SCREEN_ON -> if (armed && !suspended && cover == null) showCover()
            }
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            loudPlaying = configs.any {
                val u = it.audioAttributes.usage
                u == AudioAttributes.USAGE_ALARM || u == AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            }
            evaluateYield()
        }
    }

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        audioMode = mode
        evaluateYield()
    }

    private val resumeCover = Runnable {
        if (armed && suspended) {
            suspended = false
            showCover()
            Log.i(TAG, "yield over, cover back")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        audio.registerAudioPlaybackCallback(playbackCallback, handler)
        audio.addOnModeChangedListener(ContextCompat.getMainExecutor(this), modeListener)
        Log.i(TAG, "service connected, armed=$armed")
        // Watchdog: process death or rebind while armed puts the cover straight back.
        if (armed) showCover()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.i(TAG, "window pkg=${event.packageName} cls=${event.className}")
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        // Keep the persisted armed flag: if the service comes back, the cover returns.
        removeCover()
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { audio.unregisterAudioPlaybackCallback(playbackCallback) }
        runCatching { audio.removeOnModeChangedListener(modeListener) }
        instance = null
        return super.onUnbind(intent)
    }

    /** Start the black screen. [dismissShade] is set by the Quick Settings tile. */
    fun arm(dismissShade: Boolean = false) {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            Toast.makeText(this, "Set a screen lock first: Blackout needs it to unlock", Toast.LENGTH_LONG).show()
            return
        }
        if (armed && cover != null) return
        Blackout.setArmed(this, true)
        suspended = false
        applyAlarmFloor()
        if (dismissShade) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            handler.postDelayed({ if (armed) showCover() }, 300)
        } else {
            showCover()
        }
    }

    /** Clears the persisted flag BEFORE removing the view so the watchdog cannot re-raise it. */
    fun disarm(reason: String) {
        Log.i(TAG, "disarm: $reason")
        Blackout.setArmed(this, false)
        suspended = false
        handler.removeCallbacks(resumeCover)
        removeCover()
        restoreAlarmVolume()
        val failed = Blackout.takeFailures(this)
        if (failed > 0) {
            Toast.makeText(this, "$failed failed unlock attempt(s) while Blackout was on", Toast.LENGTH_LONG).show()
        }
    }

    private fun evaluateYield() {
        if (!armed) return
        val shouldYield = loudPlaying ||
            audioMode == AudioManager.MODE_RINGTONE || audioMode == AudioManager.MODE_IN_CALL
        if (shouldYield) {
            handler.removeCallbacks(resumeCover)
            if (!suspended) {
                suspended = true
                removeCover()
                Log.i(TAG, "yield: alarm/ring/call active, cover suspended")
            }
        } else if (suspended) {
            handler.removeCallbacks(resumeCover)
            handler.postDelayed(resumeCover, 1500)
        }
    }

    private fun showCover() {
        if (cover != null) return
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            setOnClickListener { onCoverTapped() }
        }
        peek = TextView(this).apply {
            setTextColor(Color.rgb(110, 110, 110))
            textSize = 15f
            visibility = View.GONE
        }
        root.addView(
            peek,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            screenBrightness = 0f
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                // Watchdog: the system dropped the cover while we are armed and not yielding.
                if (!removing && armed && !suspended && cover === v) {
                    Log.w(TAG, "cover detached unexpectedly, re-adding")
                    cover = null
                    handler.post { if (armed && !suspended) showCover() }
                }
            }
        })
        wm.addView(root, params)
        cover = root
        coverParams = params
        armedAt = System.currentTimeMillis()
        root.post { root.windowInsetsController?.let(::hideBars) }
        Log.i(TAG, "cover shown")
    }

    private fun removeCover() {
        val c = cover ?: return
        removing = true
        runCatching { wm.removeViewImmediate(c) }
        cover = null
        coverParams = null
        peek = null
        removing = false
        Log.i(TAG, "cover hidden")
    }

    private fun onCoverTapped() {
        // Guard against the tap that armed us (tile/widget) landing on the fresh cover.
        if (System.currentTimeMillis() - armedAt < 500) return
        showPeek()
        startActivity(Intent(this, AuthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** AOD-style peek: dim clock, date and battery at a random spot, then back to pure black. */
    private fun showPeek() {
        val tv = peek ?: return
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val state = when {
            status == BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            plugged -> "plugged in, not charging"
            else -> "on battery"
        }
        val now = Date()
        tv.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) + "\n" +
            SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now) + "\n$level% · $state"
        val dm = resources.displayMetrics
        val lp = tv.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = Random.nextInt(dm.widthPixels / 12, dm.widthPixels / 3)
        lp.topMargin = Random.nextInt(dm.heightPixels / 12, dm.heightPixels / 4)
        tv.layoutParams = lp
        tv.visibility = View.VISIBLE
        setBrightness(0.15f)
        handler.removeCallbacks(endPeek)
        handler.postDelayed(endPeek, 6000)
        Log.i(TAG, "peek shown")
    }

    private val endPeek = Runnable {
        peek?.visibility = View.GONE
        setBrightness(0f)
    }

    private fun setBrightness(b: Float) {
        val c = cover ?: return
        val p = coverParams ?: return
        p.screenBrightness = b
        runCatching { wm.updateViewLayout(c, p) }
    }

    private fun applyAlarmFloor() {
        if (!Blackout.alarmFloor(this)) return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val floor = (max * 0.8).toInt()
        val cur = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        if (cur < floor && Blackout.savedAlarmVolume(this) < 0) {
            Blackout.setSavedAlarmVolume(this, cur)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0)
            Log.i(TAG, "alarm volume raised $cur -> $floor")
        }
    }

    private fun restoreAlarmVolume() {
        val saved = Blackout.savedAlarmVolume(this)
        if (saved >= 0) {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
            Blackout.setSavedAlarmVolume(this, -1)
        }
    }

    /** Locked-mode experiment (spike test 2): real lock, then a black showWhenLocked activity. */
    fun lockAndBlack() {
        val ok = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        Log.i(TAG, "GLOBAL_ACTION_LOCK_SCREEN accepted=$ok")
        handler.postDelayed({
            runCatching {
                startActivity(Intent(this, LockedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.e(TAG, "LockedActivity start failed", it) }
        }, 800)
    }

    private fun hideBars(c: WindowInsetsController) {
        c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    companion object {
        const val TAG = "BlackoutSpike"

        @Volatile
        var instance: BlackoutService? = null
            private set
    }
}
