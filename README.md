# BlockbusterTV
Curated lists of movies from streaming sources, for Fire TV and Android TV.

## Current state: Milestone 3, editable shelves
- **Home:** shelves of films as rental boxes, with the IMDb rating as a price
  sticker and icons for the services that include each film. The focused film's
  backdrop, details and synopsis show at the top.
- **Detail:** the "back of the box": synopsis, runtime, age rating, IMDb rating,
  and a button per way to watch (included, or rent/buy in Prime Video and Apple TV).
- **Settings:** check for updates, refresh availability, and Link Lab.

**Editing shelves:**
- **On your phone:** Home > Edit on phone shows a QR code. The stick serves a small
  editing page (`app/src/main/assets/web/index.html`) on the home network, port 8642,
  protected by a secret key in the QR code. Search, add, remove, reorder, rename,
  and download a backup. The page works while the app is open on the TV.
- **On the TV:** Add film (search with the on-screen keyboard), and hold Select or
  press Menu on a box to move it, copy it to another shelf, or remove it.
- **Search** (phone app and TV) finds films by title, or by actor or director (typing a
  name offers matching people), with filters for genre, decade, IMDb rating, streaming
  service and sort order. Filters work without any text, to browse.
- **Random order:** shelves, and the films and sets on them, appear in a new random
  order each time the app is opened (or after 30 minutes away on the TV), so browsing
  stays varied. There are no ordering controls.
- **Sets** (box sets) group films on a shelf into one box, always in release order, e.g.
  Rocky 1–5. Phone app: Make a set on a shelf and tick the films. TV: hold Select on a
  film > Make a set with other films, or on a set > Add films to this set.
- **Film pages** (phone app): tap any poster for the synopsis, runtime, age rating,
  IMDb rating, genres, director and cast (tap a name for their films), where to watch
  and rent/buy prices with links, a trailer link, and the owned-on-YouTube switch.
- **Overview** (phone app and TV) zooms out: every shelf as a tile of its first four
  posters; pick one to jump to that shelf.

Shelves are saved on the stick as `shelves.json` (the same format as the backup).
On first run they start from `app/src/main/assets/collections.json`
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

**Buying on YouTube:** every film page has a YouTube button under Rent or buy
(JustWatch has no UK data for YouTube's film store). It shows a QR code that opens
YouTube's search for the film on your phone, where it can be bought or rented.
Mark it as owned on the TV and its Watch button then opens the YouTube app, where
purchases appear under Library. Owned films are saved with the shelves.

Trailers need a free TMDB API key: add it as the `TMDB_API_KEY` Actions secret
(or in `secrets.properties` for local builds).

## Account server and phone app
`server/` is a PocketBase app for `https://blockbuster.psybob.uk`: user accounts,
each user's shelves and owned films, an installable phone app (PWA), and linking TVs
(Settings > Link to your account shows a code to approve in the phone app). Linked
TVs sync with the account; unlinked TVs keep working on their own, editable from a
phone on the same Wi-Fi. Setup instructions: [server/README.md](server/README.md).

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
