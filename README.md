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
- Last.fm scrobbling, with plays queued locally so listening offline doesn't lose them
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

### Last.fm

**The easy way needs nothing from this repo.** The official Last.fm app scrobbles from any
app that publishes a MediaSession, which this one does. Open the Last.fm app → Account →
"Scrobble from…" and switch on Phish.in. It appears there once this app has played
something. No API key, no rebuild.

The app publishes the track title as the title, "Phish" as the artist, and the show it was
played at as the album — including for playlist tracks, which are attributed to their own
show rather than to the playlist.

### Built-in scrobbler (optional fallback)

Only needed if you'd rather not run the Last.fm app. **Don't enable both** — two scrobblers
watching the same playback log every track twice.

It needs your own Last.fm API credentials. Create them at
https://www.last.fm/api/account/create, then add to `local.properties` (gitignored, so the
secret stays out of the repo):

```properties
lastfm.apiKey=your_api_key
lastfm.apiSecret=your_shared_secret
```

Rebuild, then connect from the Last.fm row on the home screen. It opens last.fm in a
browser to approve the app — your Last.fm password is never typed into the app. Without
these properties everything else works and Last.fm reports itself unconfigured.

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
| `Auth.kt` | Encrypted token storage and the signed-in session |
| `LastFm.kt` | Last.fm client, request signing, and the scrobble timing rules |
| `Scrobbler.kt` | Tracks listened time and the offline scrobble queue |
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
