package com.dhruv.blackout

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/** Renders the real view code to PNGs. Regenerate: .\gradlew.bat :app:recordPaparazziDebug */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 2300),
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
    )

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
