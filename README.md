# Couch Tour

An unofficial native Android client for [phish.in](https://phish.in), the open-source live
Phish archive, and [Relisten](https://relisten.net), the open-source live-music archive
covering roughly 200 more bands. Built for two things above all: real Android media
controls, and never losing your place.

Not affiliated with phish.in, Relisten, Phish, or any of the artists whose shows they
archive. Audio for Phish streams from phish.in's public API, used with the maintainer's
permission; audio for other artists streams from Relisten's public API, which serves files
hosted on archive.org.

## What works (MVP)

- Browse artist → year/era → shows → tracks, grouped by set
- Search songs, venues, and dates; tap a hit to play it inside its show
- Tap any track to queue the whole show from that point
- Playback stops at the end of the show rather than rolling into the next one
- Lockscreen, notification, Bluetooth, and headset-button controls
- Playback continues when the app is backgrounded or the screen is off
- Position is remembered per show and restored from a "Continue listening" row on the home
  screen — for every show you've opened, not just the last one
- Shows played through to the encore are marked finished and drop out of "Continue
  listening"; opening one again restarts it from the top
- A History screen lists everything you've played — in progress, completed, or removed by
  hand — with the completed ones marked
- On a "Continue listening" card: tap to open it, tap the play button to resume, long-press
  for open / mark completed / remove
- "Shuffle all" on My tracks plays your liked tracks in random order
- Like shows, tracks, and playlists, with like counts shown throughout
- Tap the mini-player, or the media notification, to open the full-screen Now Playing
  view, with a background tinted from the current artwork
- Log in to your phish.in account to see your liked shows, liked tracks, and playlists
  (both created by you and liked)
- Browse and search public playlists; playlist excerpts are clipped correctly
- "Continue listening" shows whichever you played — a show from a show page, a playlist
  from a playlist page
- Cast to a Chromecast or a Google TV: the cast button appears once a device is on the
  network, playback moves to it mid-track, and the same controls, progress saving, and
  scrobbling carry on
- Android Auto: browse Years → shows → tracks and Continue Listening from the car head
  unit, with the same playback controls as the phone
- Home is an artist list — Phish first, then Grateful Dead, Widespread Panic, and the rest
  of Relisten's ~200, most-recorded first — with the same resume, history, and Android Auto
  support across all of them. Shows with more than one taped recording get a tape switcher,
  and each tape keeps its own resume point. Likes, playlists, and login stay phish.in-only
- Search spans every artist, not just Phish: shows, songs, and venues across phish.in and
  Relisten fan out together, with a filter chip row when a query hits more than one band

## Not in yet

Offline downloads, sleep timer, creating or editing playlists, and liking things from
inside the app. See [ROADMAP.md](ROADMAP.md) for the full list and open questions, and
[DECISIONS.md](DECISIONS.md) for why the app looks the way it does today.

## Casting

The cast button sits in the top right of every screen, and only appears when there is a
device to cast to. Tap it, pick a device, and playback moves there from wherever it had
got to; the mini player then reads "Casting to <device>". Stop casting from the same
button, which loads the queue back onto the phone at the same position, paused.

Nothing to configure: it uses Google's stock media receiver, so no receiver app is
registered and no API key is involved. A phone without Google Play services simply never
shows the button.

The phone's volume keys control the TV while casting, and the volume slider follows changes
made at the other end.

Two things to know:

- Playlist **excerpts play in full** on a Chromecast. A receiver plays whole files, so the
  clipping that makes an excerpt an excerpt is a local-player feature only.
- Stopping the cast session leaves the queue **paused** on the phone rather than resuming
  out loud — the session usually ends because someone else wanted the TV.

## Build

Requires JDK 17 and the Android SDK. `local.properties` points at the SDK and is
machine-specific — don't commit it.

### Last.fm

The official Last.fm app scrobbles from any app that publishes a MediaSession, which this
one does. Open the Last.fm app → Account → "Scrobble from…" and switch on Phish.in. It
appears there once this app has played something. No API key, no rebuild.

The app publishes the track title as the title, the artist — "Phish", or the show's own
artist for a Relisten recording — as the artist, and the show it was played at as the
album — including for playlist tracks, which are attributed to their own show rather than
to the playlist.

Debug APK:

```bash
./gradlew assembleDebug
```

Output lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Tests

461 Android unit tests, no device or emulator required:

```bash
./gradlew testDebugUnitTest
```

They cover JSON parsing against trimmed real API responses from both phish.in and Relisten,
outgoing request shape via MockWebServer (auth header, query params, path encoding), the
Room queries, every database migration, the Android Auto browse-tree media IDs, and the
parts of casting that don't need a Chromecast — that queue items declare a MIME type, and
that the queue survives the round trip through a receiver. Report lands at
`app/build/reports/tests/testDebugUnitTest/index.html`.

370 macOS package tests under `macos/Packages/CouchTourKit`:

```bash
cd macos/Packages/CouchTourKit && swift test
```

Install to a connected device or running emulator:

```bash
./gradlew installDebug
```

## Layout

| File | Role |
|---|---|
| `Api.kt` | phish.in v2 client — OkHttp + kotlinx.serialization |
| `Relisten.kt` | Relisten client, DTOs, and mapping into the backend-neutral model |
| `Catalog.kt` | Backend-neutral browse/play model (`ArtistRef`, `ShowDetail`, …) and the `MusicSource` seam both backends and both UIs (phone, Auto) share |
| `Auth.kt` | Encrypted token storage and the signed-in session (phish.in only) |
| `PlaybackService.kt` | `MediaSessionService` owning both players; also writes progress and serves the Android Auto browse tree |
| `Browse.kt` | Android Auto's browse-tree media IDs (`BrowseNode`) |
| `PlayerViewModel.kt` | `MediaController` connection and UI-facing player state |
| `MediaItems.kt` | Builds the queue items — metadata, extras, clipping, MIME type |
| `Cast.kt` | Cast options, the session state, and the queue-item converter |
| `CastButton.kt` | Cast button and device picker, driving `MediaRouter` directly |
| `Progress.kt` | Room table of per-queue playback positions |
| `Waveform.kt` | Waveform scrubber — tints the phish.in waveform PNG by play position |
| `MainActivity.kt` | Compose UI — home, search, shows, artists, recordings, mini player |

## API notes

### phish.in

Base URL `https://phish.in/api/v2`. No key needed for browsing.

Two things that will bite you if you extend this:

- `/years` returns **periods**, not years. Most are a single year (`"1997"`), but early ones
  are ranges (`"1983-1987"`). Ranges must go to `year_range=`; sending one to `year=`
  returns an empty list rather than an error.
- Most shows in the archive have **no audio**. Always filter with
  `audio_status=complete_or_partial`, and drop individual tracks whose `mp3_url` is null —
  `partial` shows have gaps.

### Relisten

Base URL `https://api.relisten.net/api`. No key, no documented rate limit. Artists and
years are `/v3`; the per-show endpoint with every recording is still `/v2`.

- Track `duration` is **seconds**, not milliseconds like everywhere else in this app.
- `sources` (recordings) arrive **pre-sorted by rating**, so the default tape is just the
  first one. Don't tie-break on `is_soundboard` — a soundboard can rank below the top slot.
- `mp3_url` is nullable; drop tracks without one, the same rule phish.in's `Track` follows.
- An artist's `features.sets` and `features.multiple_sources` flags are real and worth
  reading rather than assuming — they differ artist to artist (false and true, respectively,
  for Grateful Dead; the reverse for Phish).
- `/v3/search?q=` takes the term as a **query parameter**, not a path segment like phish.in's
  `/search/{term}`. It returns six buckets, each capped at 20 hits; this app uses Artists,
  Shows, Songs, and Venues, and drops Sources (free-text taper-note matches) and Tours (no
  screen to land on). Songs and venues aren't playable themselves — they resolve to a show
  list through `song:`/`venue:`-namespaced `PeriodRef` ids that route to
  `/v3/artists/{slug}/songs/{uuid}` and `.../venues/{uuid}`.
