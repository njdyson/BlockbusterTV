# BlockbusterTV
Curated lists of movies from streaming sources, for Fire TV and Android TV.

## Current state: Milestone 1, Link Lab
The app currently opens straight into **Link Lab**, a test harness that fires
candidate deep links for a real film on each UK service and records what
happened. The links live in `app/src/main/assets/link_tests.json`.

1. Build: `./gradlew assembleDebug` (needs the Android SDK, API 35).
2. Install: `adb connect <stick-ip>` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. On the stick, open BlockbusterTV, press each link, return, and record the outcome.
4. Press **Show results QR**, scan it with a phone and share the text.

Refresh the test films (they rotate off services): `python3 tools/fetch_link_tests.py`.
