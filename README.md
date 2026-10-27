# BlockbusterTV
Curated lists of movies from streaming sources, for Fire TV and Android TV.

## Current state: Milestone 2 prototype
- **Home:** shelves of films as rental boxes, with the IMDb rating as a price
  sticker and icons for the services that include each film. The focused film's
  backdrop, details and synopsis show at the top.
- **Detail:** the "back of the box": synopsis, runtime, age rating, IMDb rating,
  and a button per way to watch (included, or rent/buy in Prime Video and Apple TV).
- **Settings:** check for updates, refresh availability, and Link Lab.

Shelves are hard-coded in `app/src/main/assets/collections.json`
(regenerate with `python3 tools/build_collections.py`). Film data, UK availability,
IMDb ratings and Fire TV deep links come from JustWatch's unofficial GraphQL API,
cached on the device for a day.

How each service opens is set in `app/src/main/assets/services.json`, based on
Link Lab results on a Fire TV Stick 4K (Fire OS 6.7):

| Service | Opens |
|---|---|
| Netflix, Prime Video, Disney+, Apple TV, Plex | The film's page (JustWatch Fire TV link) |
| BBC iPlayer, ITVX, Channel 4, NOW | The app's home, with the title shown to search for |
| YouTube trailers | `https://www.youtube.com/watch?v=` (not `vnd.youtube://`) |

Trailers need a free TMDB API key: add it as the `TMDB_API_KEY` Actions secret
(or in `secrets.properties` for local builds).

## Link Lab
Settings > Link Lab fires candidate deep links for a real film on each UK service
and records what happened. Its **Inspect** cards list the link formats each installed
app declares. Test links live in `app/src/main/assets/link_tests.json`
(refresh with `python3 tools/fetch_link_tests.py`).

## Installing
1. Get the APK. Every push is built by GitHub Actions and published at
   https://github.com/njdyson/BlockbusterTV/releases/latest/download/BlockbusterTV.apk
   (or build locally with `./gradlew assembleDebug`, needs the Android SDK, API 35).
2. Install: type that URL into the Downloader app on the stick, or use
   `adb connect <stick-ip>` then `adb install -r BlockbusterTV.apk`.
3. On the stick, open BlockbusterTV, press each link, return, and record the outcome.
4. Press **Show results QR**, scan it with a phone and share the text.

After the first install, use the **Check for update** button in the app: it
fetches the newest build from GitHub and opens the installer.

Refresh the test films (they rotate off services): `python3 tools/fetch_link_tests.py`.
