package com.dhruv.blackout

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** View builders shared by the app and the screenshot tests (no XML, no AppCompat needed). */
private fun Context.dp(v: Int) = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
).toInt()

private fun Context.rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
    if (stroke != null) setStroke(dp(1), stroke)
}

object Palette {
    const val BG = 0xFF000000.toInt()
    const val CARD = 0xFF121212.toInt()
    const val LINE = 0xFF262626.toInt()
    const val TEXT = 0xFFF2F2F2.toInt()
    const val DIM = 0xFF8A8A8A.toInt()
    const val OK = 0xFF4ADE80.toInt()
    const val WARN = 0xFFF59E0B.toInt()
}

object CoverUi {
    /** AOD-style block: big dim clock, then date, then battery/charging line. */
    class Peek(ctx: Context) : LinearLayout(ctx) {
        private val time = TextView(ctx).apply {
            setTextColor(Color.rgb(150, 150, 150))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
        }
        private val date = TextView(ctx).apply {
            setTextColor(Color.rgb(120, 120, 120)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        private val status = TextView(ctx).apply {
            setTextColor(Color.rgb(95, 95, 95)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, ctx.dp(6), 0, 0)
        }

        /** Visible way back in if the fingerprint prompt was dismissed or never appeared. */
        val unlock = TextView(ctx).apply {
            text = "Unlock"
            setTextColor(Color.rgb(170, 170, 170)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(ctx.dp(28), ctx.dp(12), ctx.dp(28), ctx.dp(12))
            background = ctx.rounded(Color.BLACK, 24, Color.rgb(90, 90, 90))
        }

        init {
            orientation = VERTICAL
            visibility = GONE
            addView(time); addView(date); addView(status)
            addView(unlock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dp(20)
            })
        }

        fun bind(t: String, d: String, s: String) {
            time.text = t; date.text = d; status.text = s
            // Reserve the widest clock so a stale measure can never clip the last digit.
            time.minWidth = time.paint.measureText("88:88").toInt() + context.dp(8)
            requestLayout()
        }
    }

    fun peekText(ctx: Context) = Peek(ctx)
    /** Large incoming-call notice drawn on the black cover. */
    fun banner(ctx: Context) = TextView(ctx).apply {
        val line1 = "Incoming call"
        val line2 = "\nTap to unlock and answer"
        text = SpannableString(line1 + line2).apply {
            setSpan(RelativeSizeSpan(0.5f), line1.length, length, 0)
            setSpan(ForegroundColorSpan(Color.rgb(160, 160, 160)), line1.length, length, 0)
        }
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.2f)
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
}

data class CheckRow(val ok: Boolean, val title: String, val detail: String, val onFix: (() -> Unit)? = null)

data class SetupState(
    val armed: Boolean,
    val rows: List<CheckRow>,
    val alarmFloor: Boolean,
    val alarmVolume: String,
    val ringVolume: String,
    val stats: List<Pair<String, String>> = emptyList(),
    val lastSession: List<Pair<String, String>> = emptyList(),
    val unplugAlert: Boolean = true,
)

object SetupScreen {
    fun build(
        ctx: Context,
        s: SetupState,
        onStart: () -> Unit,
        onAlarmFloor: (Boolean) -> Unit,
        onUnplugAlert: (Boolean) -> Unit = {},
        onSideloadHelp: () -> Unit = {},
    ): View {
        fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
            this.text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(56), ctx.dp(20), ctx.dp(32))
        }
        col.addView(text("Blackout", 32f, Palette.TEXT, true))
        col.addView(
            text(
                if (s.armed) "On. Tap the black screen to unlock." else "Ready when you are.",
                15f, Palette.DIM
            ).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(24)) }
        )

        val start = text(if (s.armed) "Black screen is on" else "Start black screen", 17f, Color.BLACK, true).apply {
            gravity = Gravity.CENTER
            background = ctx.rounded(if (s.armed) Palette.LINE else Color.WHITE, 28)
            if (s.armed) setTextColor(Palette.DIM)
            setOnClickListener { onStart() }
        }
        col.addView(start, LinearLayout.LayoutParams(-1, ctx.dp(56)).apply { bottomMargin = ctx.dp(28) })

        col.addView(text("SETUP", 12f, Palette.DIM, true).apply {
            letterSpacing = 0.12f
            setPadding(ctx.dp(4), 0, 0, ctx.dp(8))
        })
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.rounded(Palette.CARD, 16, Palette.LINE)
        }
        s.rows.forEachIndexed { i, r ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
                r.onFix?.let { f -> if (!r.ok) setOnClickListener { f() } }
            }
            val dot = View(ctx).apply { background = ctx.rounded(if (r.ok) Palette.OK else Palette.WARN, 8) }
            row.addView(dot, LinearLayout.LayoutParams(ctx.dp(10), ctx.dp(10)).apply { rightMargin = ctx.dp(14) })
            val tx = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            tx.addView(text(r.title, 16f, Palette.TEXT))
            tx.addView(text(r.detail, 13f, Palette.DIM))
            row.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
            if (!r.ok && r.onFix != null) row.addView(text("Fix", 14f, Palette.WARN, true))
            card.addView(row)
            if (i < s.rows.lastIndex) card.addView(
                View(ctx).apply { setBackgroundColor(Palette.LINE) },
                LinearLayout.LayoutParams(-1, ctx.dp(1)).apply { leftMargin = ctx.dp(40) }
            )
        }
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(24) })

        fun infoCard(title: String, rows: List<Pair<String, String>>) {
            if (rows.isEmpty()) return
            col.addView(text(title, 12f, Palette.DIM, true).apply {
                letterSpacing = 0.12f
                setPadding(ctx.dp(4), 0, 0, ctx.dp(8))
            })
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(16), ctx.dp(6), ctx.dp(16), ctx.dp(6))
                background = ctx.rounded(Palette.CARD, 16, Palette.LINE)
            }
            rows.forEach { (k, v) ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, ctx.dp(8), 0, ctx.dp(8))
                }
                row.addView(text(k, 15f, Palette.DIM), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text(v, 15f, Palette.TEXT))
                card.addView(row)
            }
            col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(24) })
        }
        infoCard("LAST SESSION", s.lastSession)
        infoCard("USAGE", s.stats)

        col.addView(text("OPTIONS", 12f, Palette.DIM, true).apply {
            letterSpacing = 0.12f
            setPadding(ctx.dp(4), 0, 0, ctx.dp(8))
        })
        val opts = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            background = ctx.rounded(Palette.CARD, 16, Palette.LINE)
        }
        val otx = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        otx.addView(text("Keep alarms loud", 16f, Palette.TEXT))
        otx.addView(text("Raise alarm volume to 80% while on", 13f, Palette.DIM))
        opts.addView(otx, LinearLayout.LayoutParams(0, -2, 1f))
        opts.addView(Switch(ctx).apply {
            isChecked = s.alarmFloor
            setOnCheckedChangeListener { _, v -> onAlarmFloor(v) }
        })
        col.addView(opts, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(12) })
        val unplug = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            background = ctx.rounded(Palette.CARD, 16, Palette.LINE)
        }
        val utx = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        utx.addView(text("Charger unplugged alert", 16f, Palette.TEXT))
        utx.addView(text("Sound and a notice if the cable comes out while on", 13f, Palette.DIM))
        unplug.addView(utx, LinearLayout.LayoutParams(0, -2, 1f))
        unplug.addView(Switch(ctx).apply {
            isChecked = s.unplugAlert
            setOnCheckedChangeListener { _, v -> onUnplugAlert(v) }
        })
        col.addView(unplug, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(12) })
        col.addView(
            text("Alarm volume ${s.alarmVolume}  ·  Ring volume ${s.ringVolume}", 13f, Palette.DIM)
                .apply { setPadding(ctx.dp(4), 0, 0, ctx.dp(24)) }
        )
        col.addView(text("Toggle greyed out? App info, then Allow restricted settings.", 13f, Palette.DIM).apply {
            setPadding(ctx.dp(4), 0, 0, ctx.dp(8))
            setOnClickListener { onSideloadHelp() }
        })

        return ScrollView(ctx).apply {
            setBackgroundColor(Palette.BG)
            addView(col)
        }
    }

    /** Cover as the user sees it, for previews: black with the optional peek and call banner. */
    fun coverPreview(ctx: Context, peek: String?, ringing: Boolean): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        if (peek != null) root.addView(
            CoverUi.peekText(ctx).apply { bind("03:42", "Sun 4 Oct", peek); visibility = View.VISIBLE; setPadding(ctx.dp(48), ctx.dp(96), 0, 0) },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
        )
        if (ringing) root.addView(
            CoverUi.banner(ctx).apply { visibility = View.VISIBLE },
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)
        )
        return root
    }
}
