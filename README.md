# Mega Drive Solo (prototype)

Android standalone shell for Sega Mega Drive ROMs. This repository does **not** implement a Mega Drive CPU/chipset from scratch: gameplay is provided by EmulatorJS using its `segaMD` system and `genesis_plus_gx` core, loaded from EmulatorJS's stable CDN. The Android application is a lightweight WebView host with a local ROM picker.

## Build APK

Push to `main` or start the `Build Android APK` GitHub Actions workflow manually. Download `MegaDriveSolo-debug-apk` from the workflow run's Artifacts. This is a debug APK and is not signed for Play Store release.

## Use

Install the APK, allow network access, choose a ROM file (`.bin`, `.gen`, `.md`, `.smd`, `.zip`) that you are legally entitled to use. The app includes no games or BIOS. Internet is needed to download the web emulator runtime. The ROM runs in the WebView; performance and device compatibility have not yet been tested on physical Android hardware.

## Legal / attribution

Independent, unofficial project; not affiliated with SEGA. EmulatorJS and the Genesis Plus GX core are third-party open-source software; see their respective repositories and licenses before redistributing. No commercial ROMs or BIOS files are included.
