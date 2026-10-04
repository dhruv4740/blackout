package com.dhruv.blackout

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/** Renders the real view code to PNGs. Regenerate: .\gradlew.bat :app:recordPaparazziDebug */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 2900),
        theme = "android:Theme.Material.NoActionBar"
    )

    private fun state(armed: Boolean, batteryOk: Boolean) = SetupState(
        armed = armed,
        rows = listOf(
            CheckRow(true, "Accessibility service", "Connected"),
            CheckRow(true, "Screen lock", "Set"),
            CheckRow(batteryOk, "Battery optimization",
                if (batteryOk) "Exempt" else "Exempt Blackout so it isn't stopped overnight") {},
            CheckRow(true, "Ringer", "Normal"),
            CheckRow(true, "Do Not Disturb", "Off"),
        ),
        alarmFloor = true, alarmVolume = "5/7", ringVolume = "4/7",
        stats = listOf(
            "Today" to "7 h 42 min", "Last 7 days" to "41 h 5 min", "All time" to "63 h 20 min", "Sessions" to "12",
            "Longest" to "8 h 10 min", "Last session" to "Sun 23:10, 7 h 42 min",
        ),
    )

    private fun widget(armed: Boolean): android.view.View {
        val box = android.widget.FrameLayout(paparazzi.context).apply {
            setBackgroundColor(0xFF303030.toInt())
            setPadding(40, 40, 40, 40)
        }
        box.addView(
            BlackoutWidget.views(paparazzi.context, armed).apply(paparazzi.context, box),
            android.widget.FrameLayout.LayoutParams(900, 220)
        )
        return box
    }

    @Test fun widget_idle() { paparazzi.snapshot(widget(false)) }
    @Test fun widget_on() { paparazzi.snapshot(widget(true)) }

    @Test fun setup() {
        paparazzi.snapshot(SetupScreen.build(paparazzi.context, state(false, false), {}, {}, {}))
    }

    @Test fun setup_armed() {
        paparazzi.snapshot(SetupScreen.build(paparazzi.context, state(true, true), {}, {}, {}))
    }

    @Test fun cover_peek() {
        paparazzi.snapshot(SetupScreen.coverPreview(paparazzi.context, "62% · charging", false))
    }

    @Test fun cover_ringing() {
        paparazzi.snapshot(SetupScreen.coverPreview(paparazzi.context, null, true))
    }
}
