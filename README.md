# Phish.in for Android

An unofficial native Android client for [phish.in](https://phish.in), the open-source live
Phish archive. Built for two things above all: real Android media controls, and never losing
your place.

Not affiliated with phish.in or Phish. Audio is streamed from phish.in's public API.

## What works (MVP)

- Browse by year/era → shows → tracks, grouped by set
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
- Log in to your phish.in account to see your liked shows, liked tracks, and playlists
  (both created by you and liked)
- Browse and search public playlists; playlist excerpts are clipped correctly
- "Continue listening" shows whichever you played — a show from a show page, a playlist
  from a playlist page

## Not in yet

Offline downloads, sleep timer, creating or editing playlists, liking things from inside
the app. Search covers shows, tracks, and playlists; songs, venues, and tags are returned
by the API but have no screen. See [DECISIONS.md](DECISIONS.md).

## Build

Requires JDK 17 and the Android SDK. `local.properties` points at the SDK and is
machine-specific — don't commit it.

Debug APK:

```bash
./gradlew assembleDebug
```

Output lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Tests

92 unit tests, no device or emulator required:

```bash
./gradlew testDebugUnitTest
```

They cover JSON parsing against trimmed real API responses, outgoing request shape via
MockWebServer (auth header, query params, path encoding), the Room queries, and both
database migrations. Report lands at
`app/build/reports/tests/testDebugUnitTest/index.html`.

Install to a connected device or running emulator:

```bash
./gradlew installDebug
```

## Layout

| File | Role |
|---|---|
| `Api.kt` | phish.in v2 client — OkHttp + kotlinx.serialization |
| `Auth.kt` | Encrypted JWT storage and the signed-in session |
| `PlaybackService.kt` | `MediaSessionService` owning ExoPlayer; also writes progress |
| `PlayerViewModel.kt` | `MediaController` connection and UI-facing player state |
| `Progress.kt` | Room table of per-queue playback positions |
| `Waveform.kt` | Waveform scrubber — tints the phish.in waveform PNG by play position |
| `MainActivity.kt` | Compose UI — home, search, shows, show, mini player |

## API notes

Base URL `https://phish.in/api/v2`. No key needed for browsing.

Two things that will bite you if you extend this:

- `/years` returns **periods**, not years. Most are a single year (`"1997"`), but early ones
  are ranges (`"1983-1987"`). Ranges must go to `year_range=`; sending one to `year=`
  returns an empty list rather than an error.
- Most shows in the archive have **no audio**. Always filter with
  `audio_status=complete_or_partial`, and drop individual tracks whose `mp3_url` is null —
  `partial` shows have gaps.
