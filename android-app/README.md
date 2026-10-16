# BedrockConnect LAN for Android

Small Android host for the existing BedrockConnect server. It runs a foreground
service on UDP port `19132` and keeps Wi-Fi multicast awake. Server addresses
are added and selected inside Minecraft, exactly like the desktop server.
`DIOCALANDIA` (`85.14.210.14:21200`) is included as a default choice, while
the normal add/manage-server options remain available.

Build from this repository with an Android SDK and JDK 17 or newer:

```bash
cd android-app
./gradlew :app:assembleDebug
```

Install and open:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.pugmatt.bedrockconnect.android/.MainActivity
```

Tap **Avvia**, then open Minecraft on a console in the same LAN and select the
BedrockConnect entry under **Gioca → Mondi**. The Android app displays the
server log and keeps running via a persistent notification.
