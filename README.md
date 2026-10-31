# AppManager

An Android third-party app manager built around Shizuku.

## Target device
- realme Neo8 / ColorOS 17
- Android 8.0+ (API 26+)
- Shizuku service required for shell-level app disable operations
- Usage access required for launch-event ranking

## Planned behavior
- OLED black-only interface
- Three equal-width app columns
- Left: third-party apps ranked by launch-event count
- Center: running third-party apps; long-press toggles freeze-exclusion whitelist
- Right: pinned apps; long-press toggles pin state
- Alphabet rail filters pinned apps by the pinyin initial of the app label
- Touch-down preview shows the package name at the bottom; tap launches the app
- Freeze action disables running apps except whitelist entries
- Refresh restores the initial scroll positions and right-column filter

## Multi-user / cloned apps
App instances must be identified by both package name and Android user ID. The visible 0–5 marker is an instance slot label, not a promise that Android user IDs are literally 0–5. The mapping must be discovered on-device.

## Build
The GitHub Actions workflow downloads Gradle and builds a debug APK. The APK is uploaded as a workflow artifact.

## Important Android limitations
Android and OEM background-process policies can restrict visibility into all running processes. Usage access reports usage events, not a guaranteed complete process inventory. The app must display only what it can verify rather than inventing a running state.
