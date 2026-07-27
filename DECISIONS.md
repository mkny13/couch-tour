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

## Iteration 5 — tests, history, and card actions

**D35 — Tests are plain JVM unit tests, no device needed.**
92 of them, run with `./gradlew testDebugUnitTest`. JSON parsing runs against trimmed real
API responses kept in `app/src/test/resources/fixtures`, so a decoder that isn't tolerant
of unknown fields fails in CI rather than in your hands. Request-level behaviour is checked
against a local MockWebServer, and the Room work runs under Robolectric.

**D36 — A few pure functions moved out of the Compose files to be testable.**
`fmt`, `plural` and the scrubber's position maths lived in files that import Compose, which
drags UI classes into a plain JVM test. They now live in `Format.kt` and `Queue.kt`. The
queue-key prefix handling became a real parser (`parseQueueKey`) instead of inline
`startsWith` checks, which is both tested and safer — an unrecognised key is skipped rather
than being fetched as the wrong kind of thing.

**D37 — The most valuable tests pin down the traps, not the happy path.**
Specifically: that the JWT goes in `X-Auth-Token` and that no `Authorization` header is
ever sent; that a single-year period uses `year=` and a range uses `year_range=`; that a
401 on a token-bearing request logs out but a rejected login does not; and that both
database migrations preserve real rows. Each of those is a bug that previously shipped or
nearly shipped.

**D38 — Dismissing no longer deletes. This reverses D23.**
You asked for history to include things removed from "Continue listening", which the old
behaviour made impossible. `dismissed` is now its own flag (schema v3): the row leaves the
home row but stays in history, and playing it again clears the flag and brings it back.
Deleting outright is still available, from the history screen.

**D39 — "Archive" became "History", and it holds everything.**
It previously listed only finished shows. It now lists every queue you've played — in
progress, finished, or dismissed — newest first, tagged `✓ completed`, `removed · <time>`,
or `at <time>`.

**D40 — Tapping a "Continue listening" card opens it; a play button plays it.**
Tapping used to start playback immediately, which made it impossible to go look at a
playlist you were partway through. Long-press opens a menu with open / mark completed /
remove. The old X button is gone — removal lives in that menu now.

**D41 — Every screen header has a home button.**
Back only unwinds one step, which is tedious from a playlist several levels deep.

**D42 — Shuffle deliberately records no progress.**
"Shuffle all" on My tracks plays your liked tracks in random order. It is the one queue
that is not resumable, and that is on purpose: resuming would re-fetch and re-shuffle, so
a saved index would point at a different track than the one you left. Rather than record a
position that would silently lie, the shuffle queue carries no queue key at all — and the
saver already skips anything without one, so no special case was needed.

**D43 — The launcher icon is "PH".**
The previous glyph was meant to be abstract and just read as an "H". Still a placeholder.

## Iteration 6 — Last.fm and section dividers

**D44 — The Last.fm API key must come from you; I won't register one.**
Creating an API account is an account action, so it isn't mine to do. The key and shared
secret are read from `local.properties` (already gitignored) into `BuildConfig` at compile
time, which keeps the secret out of the repo. Absent, they compile to empty strings and the
app reports Last.fm as unconfigured rather than failing oddly. The alternative — pasting
them into a settings screen on the phone — is easy to switch to if you'd rather.

**D45 — Browser auth, not the mobile-session flow.**
`auth.getMobileSession` would need your Last.fm password typed into this app. The browser
flow (`auth.getToken` → approve on last.fm → `auth.getSession`) never exposes it, so the app
only ever holds a session key. That key is stored in the same encrypted preferences as the
phish.in JWT.

**D46 — Scrobbles are queued in the database, not fired and forgotten.**
Playing offline is the normal case on a train, and a scrobbler that drops those plays isn't
worth having. Plays go into a `pending_scrobbles` table (schema v4) and drain on the next
successful submission or when playback starts. A failed submission stops the drain and
leaves the row, rather than hammering the API or discarding the play.

**D47 — Scrobble timing follows Last.fm's rules and counts listened time, not the playhead.**
A track counts once it has been played for half its length or four minutes, whichever comes
first, and only if it is longer than 30 seconds. The scrobbler accumulates actual listening
time, so seeking to the end of a track doesn't fake a play and a long pause doesn't keep
counting. The four-minute rule matters here more than for most music: it means a 20-minute
Tweezer registers at four minutes rather than ten.

**D48 — Track duration is filled in late.**
The player reports no duration at the moment a track becomes current — only once it has
prepared the media — so the scrobbler takes the duration on the periodic tick instead. A
scrobbler that read it at transition time would treat every track as ineligible.

**D49 — Sections are separated by a rule and an accent-coloured heading.**
Previously every section was grey small-caps text on the same background, so the home
screen read as one long list.

## Iteration 7 — the Last.fm app makes most of D44 unnecessary

Mike spotted that the official Last.fm app has a "Scrobble from…" list that picks up any
app publishing an Android MediaSession — which this app already does. That route needs no
API key and no built-in scrobbler at all, and it is now the recommended one.

**D50 — The album is the show, not the queue.**
The MediaSession previously published the queue as `albumTitle`, so an external scrobbler
reading it would log a playlist track with the album "Phish.net Key Jams Pt 1 · by
mfhgreyboy · 99 tracks". Every track carries its own `show_date` and `venue_name`, even
inside a playlist, so the album is now built from the track: "1990-11-02 · Glenn Miller
Ballroom". Verified by reading the published session with `dumpsys media_session`, which is
exactly what the Last.fm app sees.

**D51 — Queue identity moved to `subtitle`.**
Fixing D50 would otherwise have broken the earlier requirement that "Continue listening"
and the mini player show the playlist you started from rather than the underlying show.
Those two facts now live in different fields: `albumTitle` is the show (for scrobblers),
`subtitle` is the queue (for our UI).

**D52 — The built-in scrobbler stays, but is now clearly the fallback.**
It still earns its place if you'd rather not run the Last.fm app, and the work is done and
tested. The Last.fm screen leads with the app-based route and warns not to enable both,
because two scrobblers watching the same playback means every track is logged twice.

## Iteration 8 — likes and now-playing navigation

**D53 — The like fields were in the API all along; the models just ignored them.**
`likes_count` and `liked_by_user` come back on shows, tracks, and playlists, and none of
them were in the models. `Show` didn't even read its own `id`, which liking requires.

**D54 — The like button owns its state and rolls back on failure.**
It flips immediately so the row feels responsive, then reverts the heart and the count if
the request fails, rather than leaving a like on screen that never reached the server.
Signed out it still shows the count — that's public — but tapping does nothing rather than
erroring.

**D55 — Tapping the notification resolves its destination on arrival, not in advance.**
A `PendingIntent` is built once when the session is created, but the queue changes as
playback moves, so a baked-in destination would go stale within a track. The intent instead
carries a flag; the activity reads the *current* queue key from the MediaController and
navigates then. Because the activity is `singleTask`, a second tap re-enters through
`onNewIntent` rather than `onCreate`, so both paths set the flag.

**D56 — The now-playing cover opens the queue you started from.**
Same navigation as the notification, so a playlist opens the playlist rather than the show
the current track came from. Shuffle has no queue key, so its cover is deliberately inert —
there is nothing to open.

### Auth — now confirmed

This section previously flagged every authenticated path as untested, because I could not
create an account or type a password. Mike logged in on a real device and confirmed My
Shows, My Tracks, and My Playlists all return his data, so `X-Auth-Token` is correct and
D25 holds.

Still unverified by me, for the same reason: "Shuffle all" on My tracks, which needs a
signed-in account to have any tracks to shuffle. Its queue-building path is shared with
playlists, which is verified, but the button itself has only been exercised by tests.

## Open questions for after you've seen the MVP

- Sleep timer? Playback speed? Neither is in the MVP.
- Should a show auto-advance into the next show, or stop at the encore?
- Do you want the waveform images (`waveform_image_url`) in the player, or is a plain
  scrubber enough?
- Search is not in the MVP (you picked browse-by-year as the entry point). Still wanted?
