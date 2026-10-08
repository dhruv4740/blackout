# Blackout

Personal, ad-free OLED black-screen app for a OnePlus 13R. No internet permission, no data collection.

An accessibility-service cover that blacks out the screen while apps keep running, with biometric unlock, a quick tile, widget and shortcut, an alarm/call yield, an unplug alert and an in-app usage and last-session summary. Overnight use is manual-arm only.

## Build

```
.\gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Needs JDK 17+ and the Android SDK (`local.properties` with `sdk.dir`, gitignored).

## Install note

A sideloaded APK needs Settings > Apps > Blackout > menu > "Allow restricted settings" before the Accessibility service can be switched on.

## Accessibility only while armed (banking apps)

Banking apps refuse to open while any accessibility service is enabled. With one adb grant, Blackout switches its own service on when you arm and off when you unlock:

    adb shell pm grant com.dhruv.blackout android.permission.WRITE_SECURE_SETTINGS

On OnePlus/OxygenOS 16 this first needs Developer options > "Disable system optimisation" on; it can be turned off again afterwards, the grant persists (verified 2026-10-08). Without the grant the service simply stays enabled as before. Banking apps still refuse to open while Blackout is armed. Code: `Blackout.requestArm`, `canManageService`, and `disableSelf()` at the end of `BlackoutService.disarm`. Not tested: the grant surviving a reboot or full uninstall.

## Escape hatch

The cover has no timeout. If it ever sticks and the fingerprint fails, from a PC run `adb shell pm clear com.dhruv.blackout` (clears the saved armed state and kills the app). Do not use `settings put secure enabled_accessibility_services ""`: it disables your other accessibility services and the cover returns when Blackout is re-enabled.

## Overnight check
While armed the service writes a heartbeat every 5 min (battery, plugged, screen on, cover up) plus arm/disarm/watchdog events:

    adb shell run-as com.dhruv.blackout cat files/health.log

## Screenshots without a device
`.\gradlew.bat :app:recordPaparazziDebug` renders the setup screen, cover and widget to `app/src/test/snapshots/images/`.
