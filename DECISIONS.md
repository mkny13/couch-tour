# Decision log

Conservative choice taken by default; each one is cheap to reverse. Push back on any of these.

## Architecture

**D1 — Native Kotlin client, not a WebView wrapper.**
You said a wrapper would be fine. It isn't, for your two stated priorities. Native Android
media controls (lockscreen, notification shade, Bluetooth, headset button) require a
`MediaSessionService` running in the foreground; a WebView gets none of that, and its audio
is killed or muted when the app backgrounds. Since the API returns plain MP3 URLs, wrapping
buys nothing anyway. Cost of going native: the browse UI has to be written by hand.

**D2 — `minSdk 26`, `targetSdk 35`, `compileSdk 35`.**
You're on Android 13+, so 33 was on the table. 26 costs about ten lines of compatibility
code and removes any chance the APK refuses to install on a phone you own later.

**D3 — Media3 / ExoPlayer for playback, Room for state, Compose for UI.**
All first-party AndroidX. No third-party player.

**D4 — Plain OkHttp + kotlinx.serialization, no Retrofit.**
The app calls exactly three endpoints. Retrofit would be a dependency and a codegen step
to save maybe fifteen lines.

## Scope

**D5 — MVP is browse → play → resume. No login.**
Iteration 2 adds phish.in auth, which unlocks your saved playlists and likes. Splitting it
this way gets a working player into your hands a day sooner and keeps the auth work honest
rather than rushed.

**D6 — Progress is stored under a namespaced `queueKey`, e.g. `show:1997-11-17`.**
You asked for per-show, per-playlist, and full history. The Room table holds one row per
queue keyed by that string, so playlists slot in as `playlist:<slug>` in iteration 2 with
no schema migration. History is every row, ordered by `updatedAt`.

**D7 — Progress is written every 5 seconds while playing, plus on every play/pause and
track change.** A crash or a swipe-away loses at most five seconds. Writing on every
position update would hammer the database for no benefit.

**D8 — No offline downloads.**
Not requested. It pulls in storage permissions, a download manager, and cache eviction
policy. Say the word and it becomes iteration 3.

**D9 — I never handle your phish.in password.**
When auth lands, the app gets its own login screen and you type your password into it.
I won't type it during testing, so I'll verify authenticated features against a throwaway
account you create, or you'll verify them yourself.

## Data quirks found in the API

**D10 — Every show list is filtered to `audio_status=complete_or_partial`.**
Most shows in the archive have no audio at all — 1987 lists 26 `missing` against 10
`complete` and 8 `partial`. Showing unplayable shows would make the app look broken.

**D11 — `/years` returns *periods*, not years.**
Most are a single year (`"1997"`) but the early ones are ranges (`"1983-1987"`). Ranges
need `year_range=`; passing one to `year=` returns an empty list with no error. Handled in
`PhishInApi.showsForPeriod`.

**D12 — Tracks are filtered to those with a usable `mp3_url`.**
On `partial` shows some tracks are `missing`. The queue index therefore refers to the
*filtered* list, and both the UI and the queue builder filter identically.

## Iteration 2

**D13 — "Stop at encore" needed no code.**
The app queues exactly one show and ExoPlayer's default `repeatMode` is `REPEAT_MODE_OFF`,
so playback already ends after the last track. Setting it explicitly would have been a
redundant line that only restated the default, so it isn't there. This is the behaviour to
protect if a "play next show" feature ever gets added.

**D14 — Search covers shows and tracks only.**
`/search/{term}` also returns songs, venues, tags, and playlists. Shows and tracks are the
two that are immediately actionable with screens that already exist — open a show, play a
performance. The rest would each need a new screen; deferred rather than half-built.

**D15 — Tapping a search result queues its whole show.**
Search hits carry `show_date`, so tapping a track fetches its show, finds that track's index
in the playable list, and starts there. The alternative — playing the single track alone —
would strand you at the end of one song mid-set.

**D16 — Search is debounced 300ms and needs 3+ characters.**
The 3-character floor is the API's rule, not mine; shorter terms are rejected. The debounce
comes free from `produceState`, which cancels the prior coroutine when the term changes.

**D17 — The scrubber toggle is temporary.**
The button in the mini player exists only so the two styles can be compared on-device. Once
you pick one, the toggle and the losing branch both come out.

**D18 — Waveforms are tinted, not drawn as-is.**
The PNGs are 1100x70 greyscale-plus-alpha, so the shape lives in the alpha channel. The same
bitmap gets drawn twice — once in a muted colour, once clipped to the play position in the
accent colour. First attempt used `Color.DarkGray` for the unplayed portion, which was almost
invisible against the player background; it's now `onSurfaceVariant` at 45% alpha.

## Iteration 3 — finished shows and the archive

**D19 — A real Room migration, not a destructive one.**
Adding `finished` took the schema to version 2. `fallbackToDestructiveMigration()` would
have been one line instead of six, but it drops the table — and the listening history in
that table is the entire reason the table exists. Verified on a populated v1 database:
both rows survived with the new column defaulted to 0.

**D20 — `finished` is derived from player state, never passed in.**
First attempt marked the flag from inside `onPlaybackStateChanged(STATE_ENDED)`. That
looked right and was wrong: ending a queue also fires `onIsPlayingChanged(false)`, which
ran a moment later and wrote `finished = false` straight back over it. The flag never
survived. It is now computed inside the save itself as
`player.playbackState == Player.STATE_ENDED`, so every listener that fires at the end of a
queue writes the same value and none of them can race. It also self-clears: playing the
show again naturally writes `false`.

**D21 — Rows already at the end of a show were not backfilled as finished.**
The migration defaults every existing row to 0, including shows that had in fact been
played out before the flag existed. Inferring it from `positionMs` against a track
duration the table doesn't store would mean guessing. They sort themselves out the next
time they're played.

**D22 — A finished show restarts from the top.**
Its stored position is the last second of the encore, so resuming there stops again
immediately — the wart that prompted this work. Both entry points are handled: the archive
row opens the show screen, and the show screen hides its resume banner when finished.

**D23 — Dismiss deletes the row rather than hiding it.**
"Remove from Continue listening" is a plain delete, so a dismissed show is genuinely
forgotten rather than accumulating invisible state. Playing it again starts it over as a
new row. The archive has the same control.

**D24 — A plain clickable Box for the dismiss button, not `IconButton`.**
`IconButton` enforces a 48dp minimum touch target that overflowed the 132dp artwork and
spilled onto the neighbouring card.

## Iteration 4 — auth, playlists, and your library

**D25 — The JWT goes in an `X-Auth-Token` header, not `Authorization: Bearer`.**
The OpenAPI spec documents no auth header at all, and probing can't tell you: a missing
token and a bogus `Bearer` token both return an identical bare `401 {"message":
"Unauthorized"}`. Guessing Bearer would have failed in a way that looked like "wrong
password" forever. Confirmed instead from phish.in's source (`jcraigk/phishin`) in four
places, including `app/api/api_v2/helpers/shared_helpers.rb#current_user` and the site's
own JS client. `Authorization: Bearer` exists too, but it carries API keys — a different
mechanism entirely.

**D26 — `?filter=mine` silently returns everything when unauthenticated.**
Not a 401 — a 200 with all 2,504 public playlists. Presenting that as "your playlists"
would be badly wrong, so the filtered calls are only reachable behind a signed-in state.
This is the same class of trap as `year=1983-1987` returning an empty list. By contrast
`liked_by_user=true` does the safe thing and returns 0 entries unauthenticated.

**D27 — The password is never stored, and the token is encrypted at rest.**
The password is used for exactly one request and discarded. The JWT goes into
`EncryptedSharedPreferences`. If the Android keystore is in an unrecoverable state (device
restore, key reset) the store is wiped and reopened once, and failing that the token is
held in memory only — the session ends when the process does, but nothing sensitive is
ever written in the clear and the app doesn't crash on launch.

**D28 — `android:allowBackup` is now false.**
It was true from the first commit, which was harmless when the app stored nothing but
playback positions. With an auth token on disk, cloud backup becomes a way for the token
to leave the device.

**D29 — A 401 on a request that carried a token logs you out.**
Without this, an expired or revoked JWT leaves the app looking signed in while every
personal screen shows an error. Deliberately scoped to requests that actually sent a
token, so a wrong password at the login screen doesn't trip it.

**D30 — Playlist entries are clipped, not played whole.**
Entries carry `starts_at_second` / `ends_at_second`, because a playlist can excerpt a jam
out of a longer track. Ignoring them would play the wrong audio, so entries map to a
Media3 `ClippingConfiguration`. Entry `duration` is the clipped length and is what the UI
shows.

**D31 — Playlist queues use the `playlist:<slug>` key reserved in D6.**
"Continue listening" therefore shows whichever thing you actually played: a show played
from a show page shows the show, a playlist shows the playlist name, its author, and its
track count. No schema change was needed — this is what the namespaced key was for.

**D32 — "My playlists" merges the `mine` and `liked` filters.**
They are separate API calls with no combined option, so the screen requests both and
deduplicates by slug.

**D33 — Search shows playlists, but songs, venues, and tags are still skipped.**
Playlists became actionable once there was a playlist screen to open. The rest still have
no destination.

**D34 — Rows without artwork no longer reserve the image slot.**
Account and playlist rows have no cover, and the empty 48dp box left them looking
mysteriously indented.

### What I could not verify

Every authenticated path is written against the spec and phish.in's source but is
**untested end to end**, because I can't create an account or type a password. Verified:
the login request reaches the server, the JSON body is correct (a malformed one would
return 400, not 401), a rejected login surfaces "Email or password not recognised", and
every unauthenticated screen works. Unverified: that `X-Auth-Token` is accepted on
subsequent requests, and therefore that My Shows, My Tracks, and My Playlists return your
data. The first real login will confirm or refute all of it at once.

## Open questions for after you've seen the MVP

- Sleep timer? Playback speed? Neither is in the MVP.
- Should a show auto-advance into the next show, or stop at the encore?
- Do you want the waveform images (`waveform_image_url`) in the player, or is a plain
  scrubber enough?
- Search is not in the MVP (you picked browse-by-year as the entry point). Still wanted?
