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
- Like shows, tracks, and playlists, with like counts shown throughout
- Tap the now-playing cover, or the media notification, to jump to what's playing
- Log in to your phish.in account to see your liked shows, liked tracks, and playlists
  (both created by you and liked)
- Browse and search public playlists; playlist excerpts are clipped correctly
- "Continue listening" shows whichever you played — a show from a show page, a playlist
  from a playlist page
- Cast to a Chromecast or a Google TV: the cast button appears once a device is on the
  network, playback moves to it mid-track, and the same controls, progress saving, and
  scrobbling carry on

## Not in yet

Offline downloads, sleep timer, creating or editing playlists, liking things from inside
the app. Search covers shows, tracks, and playlists; songs, venues, and tags are returned
by the API but have no screen. See [DECISIONS.md](DECISIONS.md).

## Casting

The cast button sits in the top right of every screen, and only appears when there is a
device to cast to. Tap it, pick a device, and playback moves there from wherever it had
got to; the mini player then reads "Casting to <device>". Stop casting from the same
button, which loads the queue back onto the phone at the same position, paused.

Nothing to configure: it uses Google's stock media receiver, so no receiver app is
registered and no API key is involved. A phone without Google Play services simply never
shows the button.

Two things to know:

- Playlist **excerpts play in full** on a Chromecast. A receiver plays whole files, so the
  clipping that makes an excerpt an excerpt is a local-player feature only.
- Stopping the cast session leaves the queue **paused** on the phone rather than resuming
  out loud — the session usually ends because someone else wanted the TV.

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

126 unit tests, no device or emulator required:

```bash
./gradlew testDebugUnitTest
```

They cover JSON parsing against trimmed real API responses, outgoing request shape via
MockWebServer (auth header, query params, path encoding), the Room queries, both
database migrations, and the parts of casting that don't need a Chromecast — that queue
items declare a MIME type, and that the queue survives the round trip through a receiver.
Report lands at
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
| `PlaybackService.kt` | `MediaSessionService` owning both players; also writes progress |
| `PlayerViewModel.kt` | `MediaController` connection and UI-facing player state |
| `MediaItems.kt` | Builds the queue items — metadata, extras, clipping, MIME type |
| `Cast.kt` | Cast options, the session state, and the queue-item converter |
| `CastButton.kt` | Cast button and device picker, driving `MediaRouter` directly |
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
