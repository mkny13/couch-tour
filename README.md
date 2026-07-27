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
- Shows played through to the encore are marked finished and move to an Archive screen, so
  they stop cluttering "Continue listening"; opening one from the archive restarts it
- Any show can be dismissed from "Continue listening" by hand

## Not in yet

Login (so no saved playlists or likes), offline downloads, sleep timer.
Search covers shows and tracks; songs, venues, and playlists are returned by the API but
not yet given screens. See [DECISIONS.md](DECISIONS.md).

## Build

Requires JDK 17 and the Android SDK. `local.properties` points at the SDK and is
machine-specific — don't commit it.

Debug APK:

```bash
./gradlew assembleDebug
```

Output lands at `app/build/outputs/apk/debug/app-debug.apk`.

Install to a connected device or running emulator:

```bash
./gradlew installDebug
```

## Layout

| File | Role |
|---|---|
| `Api.kt` | phish.in v2 client — three endpoints, OkHttp + kotlinx.serialization |
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
