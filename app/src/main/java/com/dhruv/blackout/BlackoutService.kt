package com.dhruv.blackout

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.service.quicksettings.TileService
import android.view.GestureDetector
import android.view.MotionEvent
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
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
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
    private var peek: CoverUi.Peek? = null
    private var removing = false
    private var suspended = false
    private var armedAt = 0L
    private var audioMode = AudioManager.MODE_NORMAL
    private var alarmPlaying = false
    private var ringPlaying = false
    private var banner: TextView? = null
    private val ringing get() = audioMode == AudioManager.MODE_RINGTONE || ringPlaying

    private val armed get() = Blackout.isArmed(this)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            Log.i(TAG, "${intent.action} deviceLocked=${km.isDeviceLocked} armed=$armed cover=${cover != null}")
            if (armed) Stats.health(context, "${intent.action?.substringAfterLast('.')} locked=${km.isDeviceLocked} cover=${cover != null}")
            when (intent.action) {
                // The system itself just authenticated the user: always exit.
                // USER_PRESENT also fires when the keyguard is skipped (lock-delay, Smart Lock),
                // so only honour it after we deliberately revealed the keyguard.
                Intent.ACTION_USER_PRESENT -> if (armed && revealed) disarm("keyguard unlocked after reveal")
                // Power button off/on must not drop the cover; make sure it is still there.
                Intent.ACTION_SCREEN_ON -> if (armed && !suspended && cover == null) showCover()
                Intent.ACTION_POWER_DISCONNECTED -> if (armed && Blackout.unplugAlert(context)) onUnplugged()
                Intent.ACTION_POWER_CONNECTED -> getSystemService(NotificationManager::class.java).cancel(NOTE_UNPLUG)
            }
        }
    }

    private fun anyUsage(configs: List<AudioPlaybackConfiguration>, usage: Int) =
        configs.any { it.audioAttributes.usage == usage }

    private fun readPlayback(configs: List<AudioPlaybackConfiguration>) {
        alarmPlaying = anyUsage(configs, AudioAttributes.USAGE_ALARM)
        ringPlaying = anyUsage(configs, AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            readPlayback(configs)
            evaluateYield()
        }
    }

    /** The change callbacks only fire on transitions, so read the current audio state explicitly. */
    private fun seedAudioState() {
        audioMode = audio.mode
        readPlayback(audio.activePlaybackConfigurations)
    }

    // The unlocked-device cover is dropped so the real keyguard can take the unlock.
    private var revealed = false

    private val reraiseAfterReveal = Runnable {
        revealed = false
        if (armed && !suspended) showCover()
        Log.i(TAG, "reveal timed out, cover back")
    }

    // Cap on how long an alarm/ringtone/call may hold the cover down (stale players).
    private val maxSuspend = Runnable {
        if (suspended) {
            Log.w(TAG, "yield exceeded cap, forcing cover back")
            suspended = false
            if (armed) showCover()
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
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        audio.registerAudioPlaybackCallback(playbackCallback, handler)
        audio.addOnModeChangedListener(ContextCompat.getMainExecutor(this), modeListener)
        Log.i(TAG, "service connected, armed=$armed")
        Stats.health(this, "service connected, armed=$armed")
        seedAudioState()
        // Watchdog: process death or rebind while armed puts the cover straight back.
        if (armed) {
            Stats.resume(this)
            startBeat()
            showCover()
        }
        refreshSurfaces()
        Blackout.takePendingArm(this)?.let { arm(it) }
    }

    /** Overnight health: a line every 5 min while armed, so the morning log shows the app stayed alive. */
    private val beat = object : Runnable {
        override fun run() {
            if (!armed) return
            Stats.beat(this@BlackoutService)
            val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val on = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
            Stats.health(
                this@BlackoutService,
                "beat battery=$level% plugged=$plugged screenOn=$on cover=${cover != null} suspended=$suspended"
            )
            handler.postDelayed(this, 5 * 60_000L)
        }
    }

    private fun startBeat() {
        handler.removeCallbacks(beat)
        handler.post(beat)
    }

    private fun refreshSurfaces() {
        TileService.requestListeningState(this, ComponentName(this, BlackoutTileService::class.java))
        BlackoutWidget.refreshAll(this)
    }

    // Events are not used: the service exists only to own the overlay window, so no foreground-app
    // names are read or logged.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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
        Stats.begin(this)
        Stats.health(this, "armed")
        startBeat()
        refreshSurfaces()
        suspended = false
        revealed = false
        seedAudioState()
        runCatching { applyAlarmFloor() }.onFailure { Log.w(TAG, "alarm floor failed", it) }
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
        Stats.end(this)
        Stats.health(this, "disarmed: $reason")
        getSystemService(NotificationManager::class.java).cancel(NOTE_UNPLUG)
        handler.removeCallbacks(beat)
        refreshSurfaces()
        suspended = false
        revealed = false
        handler.removeCallbacks(resumeCover)
        handler.removeCallbacks(maxSuspend)
        handler.removeCallbacks(reraiseAfterReveal)
        removeCover()
        runCatching { restoreAlarmVolume() }
        val failed = Blackout.takeFailures(this)
        if (failed > 0) {
            Toast.makeText(this, "$failed failed unlock attempt(s) while Blackout was on", Toast.LENGTH_LONG).show()
        }
        // Leave no accessibility service enabled while idle (banking apps object to it).
        if (Blackout.canManageService(this)) disableSelf()
    }

    /**
     * Incoming calls never drop the cover (anyone can phone the device): a banner is drawn on it
     * instead and answering needs the normal unlock. Only alarms, which can't be triggered
     * remotely, step the cover aside.
     */
    private fun updateBanner() {
        val b = banner ?: return
        if (ringing && cover != null) {
            val dm = resources.displayMetrics
            b.translationX = Random.nextInt(-dm.widthPixels / 12, dm.widthPixels / 12).toFloat()
            b.translationY = Random.nextInt(-dm.heightPixels / 10, dm.heightPixels / 10).toFloat()
            b.visibility = View.VISIBLE
            setBrightness(0.35f)
        } else if (b.visibility == View.VISIBLE) {
            b.visibility = View.GONE
            setBrightness(if (peek?.visibility == View.VISIBLE) 0.15f else 0f)
        }
    }

    private fun evaluateYield() {
        if (!armed) return
        updateBanner()
        val shouldYield = alarmPlaying
        if (shouldYield) {
            handler.removeCallbacks(resumeCover)
            if (!suspended) {
                suspended = true
                handler.postDelayed(maxSuspend, 3 * 60_000L)
                removeCover()
                Log.i(TAG, "yield: alarm/ring/call active, cover suspended")
            }
        } else if (suspended) {
            handler.removeCallbacks(resumeCover)
            handler.postDelayed(resumeCover, 1500)
        }
    }

    private fun showCover() {
        if (cover != null || revealed) return
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            // Single tap: peek + prompt. Double tap: straight to the prompt, screen stays black.
            val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onCoverTapped(quick = false); return true }
                override fun onDoubleTap(e: MotionEvent): Boolean { onCoverTapped(quick = true); return true }
            })
            setOnTouchListener { _, e -> taps.onTouchEvent(e); true }
        }
        peek = CoverUi.peekText(this).apply {
            unlock.setOnClickListener { launchAuth() }
        }
        root.addView(
            peek,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        banner = CoverUi.banner(this)
        root.addView(
            banner,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
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
                    Stats.health(this@BlackoutService, "watchdog: cover detached, re-adding")
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
        updateBanner()
        Log.i(TAG, "cover shown")
    }

    private fun removeCover() {
        val c = cover ?: return
        removing = true
        runCatching { wm.removeViewImmediate(c) }
        cover = null
        coverParams = null
        peek = null
        banner = null
        removing = false
        Log.i(TAG, "cover hidden")
    }

    private fun onCoverTapped(quick: Boolean) {
        // Guard against the tap that armed us (tile/widget) landing on the fresh cover.
        if (System.currentTimeMillis() - armedAt < 500) return
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (km.isDeviceLocked) {
            // Real keyguard is up (power button); AuthActivity cannot show over it, and the
            // keyguard is itself the security boundary, so reveal it and re-cover if unused.
            Log.i(TAG, "tap while device locked: revealing keyguard for 8 s")
            revealed = true
            removeCover()
            handler.removeCallbacks(reraiseAfterReveal)
            handler.postDelayed(reraiseAfterReveal, 8000)
            return
        }
        launchAuth(withPeek = !quick)
    }

    private fun launchAuth(withPeek: Boolean = true) {
        if (withPeek) showPeek()
        else {
            // The cover pins the panel at brightness 0, which also hides the system prompt and the
            // optical sensor's light. Lift it while the prompt is up, without drawing the peek.
            setBrightness(0.15f)
            handler.removeCallbacks(endPeek)
            handler.postDelayed(endPeek, 12000)
        }
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
        } + if (audioMode == AudioManager.MODE_IN_CALL) " · on a call" else ""
        val now = Date()
        tv.bind(
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
            SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now),
            "$level% · $state"
        )
        val dm = resources.displayMetrics
        val lp = tv.layoutParams as FrameLayout.LayoutParams
        // Fixed width: wrap_content measured the clock one glyph short on the device.
        lp.width = (240 * dm.density).toInt()
        lp.leftMargin = Random.nextInt(dm.widthPixels / 12, dm.widthPixels / 3)
        lp.topMargin = Random.nextInt(dm.heightPixels / 12, dm.heightPixels / 4)
        tv.layoutParams = lp
        tv.visibility = View.VISIBLE
        setBrightness(0.15f)
        handler.removeCallbacks(endPeek)
        handler.postDelayed(endPeek, 12000)
        Log.i(TAG, "peek shown")
    }

    private val endPeek = Runnable {
        peek?.visibility = View.GONE
        setBrightness(if (banner?.visibility == View.VISIBLE) 0.35f else 0f)
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

    /** A loose cable overnight means a flat battery by morning: make noise and light the peek. */
    private fun onUnplugged() {
        Stats.health(this, "charger unplugged")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Charger alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            NOTE_UNPLUG,
            Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("Charger unplugged")
                .setContentText("Blackout is still on and the battery is draining.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
        if (cover != null) showPeek()
    }

    private fun hideBars(c: WindowInsetsController) {
        c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    companion object {
        const val TAG = "Blackout"
        private const val CHANNEL_ALERT = "alerts"
        private const val NOTE_UNPLUG = 1

        @Volatile
        var instance: BlackoutService? = null
            private set
    }
}
