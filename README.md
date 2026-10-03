# Blackout

Personal, ad-free OLED black-screen app for a OnePlus 13R. No internet permission, no data collection.

Status: **Phase 0 spike** - a minimal accessibility-service app that tests the design's risky assumptions on a real phone before features are built. Design and phases: see the approved plan (kept outside the repo).

## Build

```
.\gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Needs JDK 17+ and the Android SDK (`local.properties` with `sdk.dir`, gitignored).

## Install note

A sideloaded APK needs Settings > Apps > Blackout > menu > "Allow restricted settings" before the Accessibility service can be switched on.
