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

**D57 — Hearts belong on every track list, not just the show screen.**
The like button was added to the show screen's own row component, but playlists, search
results and My tracks use the shared `RowItem`, which never got one — so three of the four
places a track appears had no heart at all. `RowItem` now takes a trailing slot. Worth
noting the unit tests could not have caught this: they proved the API and the models were
right, and the models were right. The gap was purely which rows the button was wired into,
which only looking at the screens reveals.

### Auth — now confirmed

This section previously flagged every authenticated path as untested, because I could not
create an account or type a password. Mike logged in on a real device and confirmed My
Shows, My Tracks, and My Playlists all return his data, so `X-Auth-Token` is correct and
D25 holds.

Still unverified by me, for the same reason: "Shuffle all" on My tracks, which needs a
signed-in account to have any tracks to shuffle. Its queue-building path is shared with
playlists, which is verified, but the button itself has only been exercised by tests.

## Iteration 9 — Google Cast

**D58 — Cast is a second player behind the same MediaSession, not a second mode.**
The service now owns two players — the local ExoPlayer and, once the framework has
initialised, a `CastPlayer` — and hands the session whichever one is live. Everything above
the service is unchanged and unaware: the UI's `MediaController`, the progress writer, the
scrobbler and the notification all talk to `session.player` and neither know nor care where
the audio is coming out. The alternative, a parallel cast path with its own controls, would
have meant a second copy of every rule in this file.

**D59 — Queue items now declare a MIME type.**
`audio/mpeg`, hardcoded — the archive is all MP3. ExoPlayer sniffs the container and never
needed it, but media3's Cast converter throws `"The item must specify its mimeType"`, so
without it playback works locally and dies on the first track the moment you cast. That is
the kind of failure a test can catch without a Chromecast in the room, so there is one.

**D60 — Our own `MediaItemConverter`.**
`MediaMetadata.extras` is where the queue key, the queue title and the waveform live, and
media3's default converter drops all of it — it carries only what a TV displays. Media3
caches the items it sent and normally rebuilds its timeline from that cache, so the loss
only surfaces where the cache is empty: a session resumed after the app was killed, or a
queue started by another sender. Those are precisely the cases where losing the queue key
means casting silently stops recording your position. The extras now ride in the queue
item's `customData`, which the receiver echoes back untouched.

**D61 — Switching players never clears the outgoing queue.**
It stops it. `clearMediaItems()` empties the timeline, which puts the player in
`STATE_ENDED`, which the progress writer reads as "played through to the encore" (D20) —
so every cast would have marked the show finished and dropped it out of "Continue
listening". `stop()` leaves the queue where it is and lands in `STATE_IDLE`.

**D62 — Casting continues playing; coming back from the TV lands paused.**
Sending to a Chromecast picks up mid-track and keeps going, which is the whole point. The
reverse is not symmetrical: a cast session usually ends because someone else took the TV or
the network dropped, and a phone that suddenly starts playing out loud in that room is not
what anyone asked for. The queue is loaded at the same position, ready for the play button.

**D63 — The device picker is Compose, not `MediaRouteButton`.**
The Cast SDK's button is a plain Android view whose chooser dialog needs an AppCompat theme
and a `FragmentActivity` to show itself; this app is Compose on a `ComponentActivity` with a
Material theme, so adopting it would have meant changing the activity's base class and the
app theme to satisfy a single button. Driving `MediaRouter` directly is about the same
amount of code, and the dialog matches the rest of the app. Active scanning runs only while
the picker is open; the rest of the time the button sits on passive discovery, and it is
invisible entirely when there is nothing to cast to.

**D64 — The Cast SDK's own notification and media session are switched off.**
`CastMediaOptions` can publish its own `MediaSession` and notification. This app already
publishes one (that is the reason it isn't a WebView, D1), and two of them means two sets of
lockscreen controls and every track scrobbled twice by the Last.fm app.

**D65 — The stock Default Media Receiver, no registered receiver app.**
Registering a custom receiver is a Google Cast Developer Console account action, and it
isn't mine to do (same rule as D44). The default receiver plays progressive MP3 over HTTPS,
which is exactly what phish.in serves.

The visible cost, confirmed on Mike's TV: the receiver's own name, "Default Media Receiver",
sits above the track info, because that string is the app name Google registered for the
stock receiver's ID and no metadata we send can override it. The fix is a **Styled Media
Receiver** — registered in the console under whatever name should appear on the TV, with the
CSS field left empty so there is still nothing to host. It costs a one-off $5 developer
registration and yields an app ID, at which point `RECEIVER_APP_ID` in `Cast.kt` is a
one-line change. Mike's call, since it's his account and his $5.

**D66 — Playlist excerpts cast as whole tracks. Known, unfixed.**
Entries can be clipped (D30) and a receiver plays whole files; Cast has no equivalent of
`ClippingConfiguration`. The excerpt is right on the phone and long on the TV. Fixing it
properly needs the receiver told to seek and stop at a boundary, which the default receiver
won't do — it would take a custom receiver, which D65 rules out for now.

**D67 — A handoff mid-track no longer restarts the scrobble clock.**
Both players announce the same track around a switch, and the scrobbler treated that as a
track change: it reset its accumulated listening time, so a 20-minute jam could scrobble
once on the phone and again four minutes later on the TV. It now ignores an announcement
for the track it is already watching.

**D68 — Cast initialises quietly, and is allowed never to arrive.**
A phone with no Play services, or an outdated one, is a normal phone. Initialisation is
asynchronous and best-effort; the service attaches the cast player whenever it turns up, the
button never appears if it doesn't, and nothing else in the app changes either way.

### Hardware — now confirmed

This section previously flagged the whole feature as untested, because no Chromecast had been
near it. Mike has since cast to a real device: discovery, the picker, the receiver playing a
phish.in URL, and the handoff all work. The one thing that didn't was volume — see D71.

## Iteration 10 — Player layout

**D69 — The player's transport moved to its own row, and the track display shows both labels.**
On a real device the three controls were sharing one row with the artwork and the text, which
made them small targets and squeezed the text column to the point that the queue label was
truncated mid-word. Worse, the show a track came from was not displayed at all — a playlist
jumps between shows every track, so the date and venue are the interesting part. The transport
now sits on its own centred row at the bottom of the player, where the thumb already is, with
targets big enough to hit without looking; the text column gets the full width and three lines:
track, show (`albumTitle`), queue (`subtitle`). Where a track has no show of its own,
`albumFor` already falls back to the queue label, so the show line would repeat the queue line
verbatim — the state layer blanks it in that case rather than printing it twice. The player is
taller than it was; it is the bottom bar of a `Scaffold`, so the list above simply gets
shorter, which is the trade Mike asked for.

The third line is also where "Casting to <device>" goes (D58), so casting costs the queue
label rather than the show — the queue is one tap away on the cover, the device isn't.

## Iteration 11 — removing built-in scrobbling

**D70 — The built-in scrobbler was removed on 2026-07-27, ahead of the Play Store release.
This supersedes D44–D48 and D52.**
The scrobbler was one more thing collecting and transmitting data — a Last.fm API key and
session key, a queue of tracks written to disk, and outbound calls to
ws.audioscrobbler.com — for a feature Iteration 7 had already made optional in practice: the
official Last.fm app's "Scrobble from…" route reads the same track, artist, and album
straight off the MediaSession, with no API key and nothing shipped in this app. Shrinking
the privacy and data-collection surface before going to a public store outweighed keeping a
fallback nobody needed to use. `LastFm.kt`, `Scrobbler.kt`, the Last.fm settings screen, and
the `pending_scrobbles` table are gone; schema v5's `MIGRATION_4_5` drops the now-orphaned
table. External scrobbling via the MediaSession is unaffected.

## Iteration 12 — cast volume

**D71 — Volume on a Chromecast needed media3 1.9+, not code of ours.**
Casting worked, but the volume slider read 0 and wouldn't move. It wasn't a wiring mistake:
media3 1.5.1's `CastPlayer` — the version D58 was built on — declines to implement device
volume at all. `getDeviceVolume()` is `return 0` with the comment "not supported", every
setter is an empty method, its `DeviceInfo` never sets a maximum volume, and none of
`COMMAND_GET_DEVICE_VOLUME` / `COMMAND_SET_DEVICE_VOLUME` / `COMMAND_ADJUST_DEVICE_VOLUME` are
in its available commands. The session layer reads exactly those three things: no adjust
command means the volume provider it publishes to the system is `VOLUME_CONTROL_FIXED`, and a
fixed provider reporting volume 0 out of a maximum of 0 is precisely the dead slider Mike saw.
Nothing this app could do from outside the player would have moved it.

Media3 implemented it in 1.8.0 and split the cast-only player out as `RemoteCastPlayer` in
1.9, which is what the service now builds: real `getDeviceVolume`, setters that call
`CastSession.setVolume`, a 20-step maximum, the three commands advertised, and an
`EVENT_DEVICE_VOLUME_CHANGED` when the volume changes at the other end — so turning it up with
the TV remote moves the phone's slider too. Media3 went 1.5.1 → 1.10.1 in one step, which was
possible without further work because the Play Store preparation had already taken `compileSdk`
to 36; 1.10 requires it. `play-services-cast-framework` and `mediarouter` are pinned to the
versions media3-cast itself depends on, so the declared version is the one that resolves.

The upgrade also makes `CastPlayer` a deprecated wrapper over a new local-plus-remote player
that does its own switching. Not adopted: our handoff rules are deliberate (D61, D62), and
swapping them for someone else's semantics is not a volume fix.

## Iteration 13 — the show line

**D72 — The show line is blanked on a prefix, not on equality. Refines D69.**
D69 blanked the show line only when it matched the queue line exactly, which covered the case
it was written for — a track with no show of its own, where `albumFor` falls back to the queue
label verbatim. It missed the common one. Playing a show from its own page, the queue line is
the show line *plus the city*: `1997-11-17 · McNichols Arena` against
`1997-11-17 · McNichols Arena · Denver, CO`. Not equal, so both rendered, and the player said
the date and venue twice on the path most used. A `startsWith` test catches both cases, keeps
the city, and leaves playlists alone, where the show and the queue are unrelated strings and
both lines earn their place. Seen on a screen, not in a test: the two strings differ, so
nothing short of rendering them together shows the problem.

## Iteration 14 — Android Auto

**D73 — Android Auto browses through the existing MediaSession; `PlaybackService` gained a
browse tree, not a second service.**
`PlaybackService` became a `MediaLibraryService` rather than sitting alongside a separate one —
Auto (and, if it's ever pursued, Automotive) connects through `onGetSession` like every other
controller, and the dual-player handoff (D61, D62) doesn't care who's asking. The tree is
Root → Years → shows, with an extra Tour layer inserted only where a period has more than one
distinct `tour_name` — the early ranged periods (`"1983-1987"` and the like) are usually the
case that needs it; a single-tour or tourless year, which is most of them, goes straight to its
shows. A Continue Listening node is built from `progressDao.inProgress()`, and its resume media
IDs wrap the same namespaced `queueKey` (D6) rather than inventing a second identifier scheme.

Resuming from Continue Listening lands on the right *track*, not the right *second*: Auto plays
from the top of whichever item is tapped, and there's no hook for a mid-track start position on
a system-driven tap the way `PlayerViewModel.resume()` gets one from a direct
`MediaController.setMediaItems` call. Slicing the already-fetched track list at `trackIndex`
gets the right track for free; true position resumption would need Media3's
`onPlaybackResumption` callback, which is a separate, real piece of work and not done here.

A parent id that fails to parse, or a `PhishInApi` call that throws — no signal in a moving
car is the expected case, not an edge one — both return an empty child list rather than an
error result. `LibraryResult.ofError` wants a `SessionError`; an empty folder is a safe, always-
available fallback that needs none of the guessing that picking the "right" error code would.

No `onConnect` override was needed: `MediaSession.ConnectionResult.AcceptedResultBuilder`
already switches on `session is MediaLibrarySession` to grant the library browsing commands
alongside the usual playback ones.

Android Automotive OS — the full in-car OS with no phone, as opposed to Auto's phone
projection — is out of scope, same as the issue that asked for this scoped it:
`automotive_app_desc.xml` declares `<uses name="media"/>` for Auto only, with no
`minCarApiLevel`, and there's no handling of the "recent root" request Automotive uses for its
resume-on-boot flow.

This environment couldn't run `./gradlew`: Google's Maven, where every `androidx.media3`
artifact is hosted, wasn't reachable from it. The Media3 API surface this relies on —
`MediaLibrarySession.Builder`'s constructor, the `Callback` method signatures, the
`LibraryResult` factories — was checked against the `androidx/media` source on GitHub instead
of an actual compile. Run `testDebugUnitTest` (it now includes `BrowseTest`) on a machine with
the Android SDK before this ships.

## Iteration 15 — multi-artist support via Relisten

**D74 — Relisten, not archive.org directly, as the second backend.** archive.org's raw
`/metadata/{id}` is free-text taper filenames — no setlists, no song identity, no set
boundaries. Relisten already normalises archive.org into exactly that: artists, years,
shows, and recordings with real track titles. Going straight to archive.org would mean
rebuilding Relisten, for worse data.

**D75 — phish.in stays the Phish backend; Relisten adds everyone else.** Relisten also
carries Phish (sourced from phish.in upstream), but without waveforms, cover art, likes,
playlists, or login. Keeping phish.in for Phish means zero regression in the existing test
suite and zero churn to the login/likes/playlists paths, which stay phish.in-only features.

**D76 — `Progress` gained an `artist` column (schema v6) rather than folding the band into
`subtitle`.** Grouping history by artist off a display string would mean splitting on a
separator a venue name is free to contain — the first "Barton Hall · Ithaca, NY" show breaks
it. `MIGRATION_5_6` backfills every existing row to "Phish", which isn't a guess: until
Relisten existed, phish.in was the only thing the app could play, so every row already in
the table genuinely was Phish.

**D77 — A recording's source UUID is part of the queue key, not just its date.** Relisten
carries around nine tapes of an average Grateful Dead show, each with its own track
boundaries — Cornell's ten sources run 20 to 25 tracks each. A stored `trackIndex` means
different music depending on the tape, so the key is `relisten:<slug>/<date>/<uuid>`, with
`/` as the inner delimiter to sidestep the first-colon-only rule the other two prefixes use.

**D78 — Relisten's own `features` flags drive the UI, not guesses.** `features.sets` is
false for Grateful Dead (a single wrapper set literally named "Set" on every source) and
true for Phish; `features.multiple_sources` is the inverse. Both were verified against the
live API rather than assumed — the next decision is exactly the kind of thing a guess would
have gotten wrong.

**D79 — The default tape is never tie-broken on `is_soundboard`.** Sources arrive
pre-sorted by `avg_rating_weighted` descending, so the default tape is
`sources.firstOrNull()`. Cornell's soundboard ranks 4th (8.21 against the top tape's 8.26);
preferring soundboards would override Relisten's own ranking and hand the user a
worse-rated recording.

**D80 — Relisten track duration is seconds; converted to milliseconds on the way in.**
Everything else in the app — `Format.fmt`, `MediaItems.kt`, Cast — is milliseconds. Pinned
with a test, since a duration 1000× off looks fine until someone opens the scrubber.

**D81 — MP3 only; Relisten's FLAC is ignored.** Media3 plays FLAC, but Cast's MIME type is
hardcoded to `audio/mpeg` (D59) and the stock receiver expects progressive MP3. Taking
MP3-only keeps the Cast path working unchanged; FLAC support is its own future piece of
work.

**D82 — Android Auto's browse tree and the phone's screens share one `MusicSource` seam.**
`sourceFor(backend)` resolves to `PhishInSource` or `RelistenCatalogSource`, and both the
Compose screens and `PlaybackService`'s browse tree call through it, rather than a second,
Auto-only implementation of the same browsing logic.

See [MULTI-ARTIST-PLAN.md](MULTI-ARTIST-PLAN.md) for the full working history — the API
facts pinned down against the live service, the phase-by-phase build order, and the open
questions O1 through O5.

## Iteration 16 — search across every artist

**D83 — `MusicSource` gained a `search` capability, fanned out by `searchAll` with
per-backend failure isolation.** Iteration 15 gave every backend a shared browse seam but
left `searchFor` calling `PhishInApi.search` alone, so Relisten's ~200 artists were
unreachable from the search box. `searchAll` runs both backends' `search(term)`
concurrently and merges the results with `SearchHits.plus`; a backend that throws
contributes `SearchHits(failed = setOf(that backend))` instead of failing the whole query,
so one backend being down costs its own section, not the other's results.

**D84 — Results are grouped by type, with an artist filter-chip row, not grouped by
artist.** The existing Shows/Tracks/Playlists section layout stayed rather than being
replaced by per-artist grouping, which keeps the phish.in-only path visually unchanged. A
`FilterChip` row appears only when a query's hits span more than one artist — "Scarlet
Begonias" hits eight — and filtering is pure and client-side over the already-fetched
`SearchHits`, so switching chips costs no refetch.

**D85 — Relisten's own Phish hits are dropped from search, same as browsing.** phish.in is
the Phish backend (D75); a Relisten hit for the `phish` slug would be a near-duplicate row
leading to a screen with no waveform, cover art, likes, or playlists. `toSearchHits()`
filters every bucket on the slug before mapping.

**D86 — Songs and venues travel as `song:`/`venue:`-namespaced `PeriodRef` ids, reusing the
existing shows route rather than a new screen.** `PeriodRef.id` was already opaque to
everything but the backend that issues it (phish.in already branches on shape to pick
`year_range=` over `year=`), so a song or venue hit becomes a `PeriodRef` whose id carries a
prefix `RelistenCatalogSource.shows()` dispatches on to `/v3/artists/{slug}/songs/{uuid}` or
`.../venues/{uuid}` instead of the ordinary year lookup. Tapping a hit lands on
`ArtistShowsScreen` exactly as browsing a year does — no new screen, no new route, and
nothing for Android Auto's browse tree to learn about.

**D87 — The `Sources` and `Tours` search buckets are ignored.** `Sources` matches free text
in taper notes and descriptions — a "scarlet begonias" query returns 20 tapes whose notes
happen to mention the song, capped and arbitrary against the hundreds that exist, and
strictly worse than the precise `Songs` hit for the same query. `Tours` has no destination
screen in the app, same reasoning as D14 for tags. `RelistenSearchResults` declares no DTO
for either bucket; `ignoreUnknownKeys` drops them for free.

**D88 — A Relisten "Shows" search hit has no venue, unlike a browsed show.** `/v3/search`'s
`Shows` bucket carries `slim_artist`, `display_date`, `source_count`, and `avg_rating`, but
only a `venue_uuid` — no populated venue. A show row reached from search therefore shows
just the date and band; the venue only appears once the show itself is opened and fetched
through the ordinary per-show endpoint, which does return one.

## Iteration 17 — Home becomes an artist list, and a real player

**D89 — Home is a merged artist list, Phish pinned first.** P7's "Artists" screen
deliberately left Phish out of its own list because Home was already Phish's page
(`ArtistsScreen`'s old doc comment said so explicitly). That made Home and Auto's browse
root disagree — Auto already puts Artists above Years, on the reasoning that Relisten
carries far more artists than phish.in has years. `mergeArtists` (`Catalog.kt`) merges
`PhishInSource.artists()` and `RelistenCatalogSource.artists()`, keeping Phish first — it's
the only artist with an account, likes, and playlists behind it — and sorting the rest by
show count. If one backend fails, the other still renders; only failure on both surfaces an
error, so a Relisten outage can't hide Phish. Relisten separately archives its own Phish
collection (slug `phish`, a different show count than phish.in's — the same fact D85 in
Iteration 16 found for search), so `mergeArtists` drops it by name rather than showing
"Phish" twice.

**D90 — Phish keeps its own show/track screens rather than folding into the generic
Relisten path.** Both paths already share `ArtistScreen` for year browsing (`ArtistScreen`
already worked for any backend via `sourceFor(backend).periods(artist)`), but a period tap
branches: Phish goes to `shows/{period}` → `show/{date}`, Relisten goes to
`artist/{backend}/{id}/{period}` → `recording/…`. The Phish-only screens carry the
`LikeButton` and the "partial" audio-status badge, neither of which exists on the Relisten
DTOs — collapsing to one screen would mean adding backend branches inside it rather than
keeping two small ones.

**D91 — The theme stopped being `darkColorScheme()` with zero overrides.** The player's
small text was `Color.Gray` (`#888888`) hardcoded over `surfaceVariant` (`#49454F`) — a
2.9:1 contrast ratio, under WCAG AA's 4.5:1 floor, at 11–12sp on the timestamps and queue
line. `Theme.kt` replaces the M3 baseline-purple scheme with a dark-only palette built from
the launcher icon's green (`colors.xml`'s `#1B3A2F`), and every `Color.Gray`/`Color.LightGray`
literal (25 call sites) became `MaterialTheme.colorScheme.onSurfaceVariant`, ~9:1 against
the new `surfaceContainer` tokens. `themes.xml` keeps its `android:Theme.Material.NoActionBar`
parent — the Cast chooser's chooser dialog depends on not being AppCompat (D-earlier in
Iteration 9) — and only gains a matching `windowBackground` plus transparent system bars for
`enableEdgeToEdge()`.

**D92 — The bottom bar shrank to a compact strip; a full Now Playing screen does what the
bar used to.** The whole player used to be one 200dp+ bottom bar: art, title, waveform,
timestamps, and an oversized 60/68/60dp transport row, all stacked. `MiniPlayer` is now ~72dp
— art, title, one play/pause button, a 2dp progress line — and tapping it opens
`NowPlayingScreen` (`NowPlaying.kt`) with the waveform, full transport, and a background
gradient tinted from the artwork's dark-vibrant swatch (`androidx.palette`, falling back to
`primaryContainer` when there's no art — every Relisten show today). This also fixed a real
bug: `EXTRA_OPEN_NOW_PLAYING` waited for a queue *key* and opened the show's track list, so
shuffle queues (no key) never opened anything and even a keyed queue landed one screen short
of the player. It now waits for `hasQueue` and navigates straight to `NowPlayingScreen`. The
`Scaffold`'s bottom bar also now hides on the `player` route itself — otherwise the compact
bar and the full player it opens into would show stacked on top of each other, caught by
running the app in the emulator rather than by the unit suite, which has no Compose UI tests.

**D93 — Audio focus is requested by hand instead of via ExoPlayer's `handleAudioFocus = true`
(issue #23).** `PlaybackService`'s audio attributes use `AUDIO_CONTENT_TYPE_MUSIC`, which
Media3's `AudioFocusManager` marks `willPauseWhenDucked = false` when building the platform
`AudioFocusRequest`. On API 26+ that tells the OS it may duck the stream itself at the mixer
without ever calling back into the app — so `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` never
reaches ExoPlayer's own duck-to-20% code path, and mixer-level ducking turned out to be
inaudible on real hardware. `PlaybackService` now builds the player with
`handleAudioFocus = false` and runs its own `AudioFocusRequest`/`OnAudioFocusChangeListener`,
setting `player.volume` to `0.2f`/`1f` directly on duck/regain and pausing (remembering to
resume) on a full transient loss. Scoped to `localPlayer` only — casting doesn't touch phone
audio, so `RemoteCastPlayer` is untouched.

## Iteration 18 — a native macOS client (D94-D115)

ROADMAP.md's desktop bullet had sat unanswered since the ROADMAP was split out of this file.
This iteration answers the form-factor question and starts the macOS client at
`macos/Packages/CouchTourKit`, a SwiftPM package holding every piece of logic (API clients,
the backend-neutral catalog model, the queue-key grammar, and progress storage), with an
Xcode app target on top still to come. Personal-use MVP: browse and play both backends,
resume, and a History screen. No login, likes, playlists, casting, or search.

**D94 — Native macOS (SwiftUI + AVFoundation), not Compose Desktop or a web wrapper.**
The Android app has roughly 1,000 lines that are already Android-free — `Api.kt`,
`Relisten.kt`, `Catalog.kt`, `Queue.kt`, `Format.kt` — plus around 113 plain JUnit tests, all
of which Compose Desktop would have reused verbatim. Going native throws that away and adds
Swift as a third language to the project. What it buys, matching D1's original reasoning for
Android over a WebView, is real OS integration: `MPNowPlayingInfoCenter`, hardware media
keys, and AirPlay, none of which the JVM exposes without JNI. Because the logic is
reimplemented rather than shared, the two clients' contracts (queue-key grammar, the
`progress` schema) are pinned by porting the Android tests 1:1 rather than by convention.

**D95 — Logic lives in a local SwiftPM package (`CouchTourKit`), not directly in the app
target.** Testable with `swift test` alone, no Xcode required — which mattered concretely:
Xcode isn't installed in the environment this was built in, so everything above the app
shell had to be written and, in principle, verified without it. Also keeps the door open to
an iOS client later without re-splitting the code.

**D96 — GRDB over SwiftData for progress storage.** The schema is written by hand to match
Android's `progress` table at schema v6 exactly: same columns, same types, same
`queueKey` primary key
(`app/schemas/dev.mike.couchtour.PhishInDb/6.json`). SwiftData's on-disk format is opaque
and Apple-controlled, which would have made that parity — and therefore any future sync —
materially harder to guarantee. No sync is being built now, but keeping the schema
byte-identical is what would keep it an additive change later instead of a migration on
both sides.

**D97 — The desktop database starts at the v6 shape in a single initial migration.** It does
not replay Android's `MIGRATION_1_2` through `MIGRATION_5_6`, because there is no desktop
data predating v6 to migrate — a fresh install has nothing to preserve. Same filename,
`phishin.db`, at `~/Library/Application Support/dev.mike.couchtour/`, for the same reason
Android kept that filename through its own app rename: a future "import from your phone"
step becomes a file copy, not a translation.

**D98 — No sync in this MVP; the schema and queue-key grammar are kept identical so it stays
additive.** `QueueKeyTests` ports `QueueTest.kt`'s cases verbatim to pin this. The one thing
explicitly deferred rather than solved: `clear()` deletes a row today on both clients, and a
deletion can't be reconciled by last-write-wins on `updatedAt` alone — a future sync design
will need a tombstone (`deletedAt`), added to both clients together, not to macOS alone.

**D99 — Test fixtures are copied from the Android test resources, not shared by reference,**
with `macos/scripts/check-fixtures.sh` diffing the two copies so they can't silently drift.
A shared fixture directory would have meant either client reaching outside its own module
boundary; a duplicate-with-a-guard was simpler and keeps each package self-contained.

**D100 — Swift 5 language mode, not Swift 6's strict concurrency, for the MVP.** The
`async`/`await` API surface ports 1:1 either way; strict data-race checking is a cost worth
deferring until there's UI code exercising it. Revisit before any public release.

**D101 — Deployment target macOS 14.** Every API used (SwiftUI's `NavigationSplitView`,
`AVQueuePlayer`, `MPRemoteCommandCenter`) exists there; targeting the latest OS would cost
real users nothing more than closing off a public release to older machines for no benefit
this MVP needs.

**D102 — The GitHub repo was renamed from `couch-tour-android` to `couch-tour`,** since it now
holds two clients. Old URLs redirect. Done — the local `origin` remote was updated to match.

**D103 — `CouchTour.xcodeproj` is generated by XcodeGen from `macos/project.yml`, not
committed.** The project file itself is machine-generated, mostly-opaque XML that merges
badly; `project.yml` is the actual source of truth for targets, sources, and build settings,
regenerated with `xcodegen generate` after adding files or changing settings. This is the
Swift-ecosystem analogue of never hand-editing Room's generated schema JSON — the generated
artifact is derived, not authored.

**D104 — The app target defaults to App Sandbox on, with only the network-client
entitlement.** Both API clients only ever make outbound HTTPS `GET` requests, so that's all
the entitlement surface needs today. Sandboxed by default rather than opting in later keeps
a future Mac App Store submission from being a retrofit, matching the Android app's own
Play Store preparation.

**D105 — Playback is `AVQueuePlayer` over the whole remaining track list, not one item at a
time.** `AVQueuePlayer` only ever advances forward and drains to empty when the last item
finishes — there is no "next show" to roll into because only one show's tracks are ever
loaded, so stopping at the encore (D13) falls out of the design rather than needing code for
it. "Previous track" has no native support in `AVQueuePlayer`, so `skipToPrevious()` rebuilds
the queue from one track earlier — the same path a mid-show tap uses.

**D106 — Now Playing metadata mirrors Android's MediaSession exactly (D50): album is the show,
not the queue.** `Player.albumTitle(for:show:)` ports `albumFor` from Android's
`MediaItems.kt` — `"1997-11-17 · McNichols Arena"`, falling back to the queue title/subtitle
only when a track carries no show info of its own. This is what would let an external
scrobbler (e.g. the Last.fm app, which reads `MPNowPlayingInfoCenter` the same way it reads
Android's MediaSession) work without the app doing anything scrobbling-specific — same
reasoning as D70's removal of built-in scrobbling.

**D107 — Verified live, not just by inspection: the system Now Playing widget's own pause
button paused the app, and the mini player reflected it back.** `MPRemoteCommandCenter`
requires no special sandboxing entitlement to work — it's registered automatically for any
regular foreground app. Artwork (`MPMediaItemPropertyArtwork`) is not wired up yet; the Now
Playing widget shows title/artist/album/elapsed time only. Logged here rather than silently
shipped, since it's the one piece of D106's metadata parity still missing.

**D108 — Progress writing lives in `Player`/`ProgressRecorder`, not the UI**, the same
ownership Android's `PlaybackService.saveNow()` has and for the same reason: the player is
what actually knows when a track changed or the queue drained, not whichever screen happened
to trigger playback. `ProgressRecorder` is a separate type from `Player` (matching the plan's
file layout) purely for readability — it holds no state of its own beyond the 5s save
throttle.

**D109 — `Player.play(detail:startIndex:resumePositionMs:)` takes a whole `ShowDetail`, not a
bare show+tracks pair.** `ShowDetail.queueKey` is what the recorder needs to write a row, and
computing it requires the recording (D-equivalent of Android's `ShowDetail.queueKey` getter,
ported in Catalog.swift). Passing the pair separately, as the M3 signature did, would have
left the player unable to know what key it was even playing.

**D110 — Resuming re-fetches the show over the network rather than trusting the stored
denormalised fields for anything but display.** `Resume.swift`'s `resolveShowDetail` parses
the queue key back into backend/date/recording-id and calls the same `MusicSource.show(...)`
browsing already uses — mirroring Android's `PlayerViewModel.resume`. The row's `artist`
field is used to skip a second Relisten artist lookup (denormalised for exactly this, per
`ProgressStore.swift`), but track URLs and durations always come fresh from the API.

**D111 — A finished queue resumes at track 0, not at its last-known position** (D22): `resume`
checks `progress.finished` before deciding where to start, rather than blindly trusting
`trackIndex`/`positionMs` on every row. `markFinished` itself never rewrites those fields, so
the stopping point survives as a historical fact even once the flag flips.

**D112 — Verified live end-to-end, not just read: a show played for several seconds, the app
was fully quit (not backgrounded) and relaunched, and Continue Listening resumed the correct
track at the correct position.** Separately, playing one Relisten tape, switching the browsed
tape via the picker, and playing a track on the second tape produced two independent History
rows for the same date — tapping each resumed its own tape and track, never the other's.
This is what confirms the queue-key design (D77) actually delivers the property it was built
for, not just that it compiles.

**D113 — Installed to `/Applications` via `macos/scripts/install.sh`, ad-hoc signed, no Apple
Developer account.** Gatekeeper only quarantines files downloaded from the internet; a
locally built `.app` never gets that flag, so `codesign`'s `adhoc` signature is enough to run
normally with no security prompt. The script rebuilds Release, replaces whatever's at
`/Applications/Couch Tour.app`, and relaunches — the same one-liner for every future update
until this needs real notarization (a paid account, only relevant if this is ever distributed
to a machine other than the one that built it).

**D114 — Two Swift traps worth remembering, both only caught by the real compiler, not by
static review.**
1. `Progress` collides with Foundation's bridged `NSProgress` the moment any file imports
   Foundation (which `XCTest` does implicitly) — the type in `ProgressStore.swift` is named
   `PlaybackProgress` for exactly this reason. Don't rename it back.
2. Swift's synthesized `Decodable` does **not** apply a property's default value when a JSON
   key is simply absent — unlike kotlinx.serialization on the Android side, which does. Every
   DTO in `PhishInAPI.swift`/`RelistenAPI.swift` that has optional-looking fields carries a
   hand-written `init(from decoder:)` with `decodeIfPresent(...) ?? default` for this reason;
   a new DTO that skips this will crash on the first response missing an optional key, not on
   `swift build`.

**D115 — The environment blocker this iteration hit is now moot, noted only so it isn't
mistaken for a live issue.** Command Line Tools alone (no Xcode) could not compile *any*
SwiftPM manifest here — confirmed with an empty, unrelated test package — which blocked `swift
test` until Xcode was installed. Once it was, both `swift test` and `xcodebuild` worked on the
first real attempt. If a future session hits the identical `PackageDescription.Package
.__allocating_init` linker error, the fix is the same: install Xcode, don't debug the Command
Line Tools install.

## Iteration 19 — the deletedAt tombstone (D116-D118)

First step of cross-client progress sync, designed but not yet built beyond this: the schema
change D98 flagged as a prerequisite, landed on both clients ahead of any network code so it
can be exercised and tested on its own.

**D116 — `clear()` tombstones the row instead of deleting it; every read query filters the
tombstone back out.** A real `DELETE` is indistinguishable, to a future sync client, from a
row that was simply never pushed yet — nothing left behind to say "this was removed," so a
device that synced before the delete would push its own copy back and silently resurrect it.
Filtering `deletedAt IS NULL` into `inProgress`, `history`, `historyCount`, `artists`,
`historyFor`, and `get` keeps the tombstone invisible to the rest of the app; only a raw query
against the table sees it, which is exactly what the new tests exercise to pin the behavior
down. `clear()` also bumps `updatedAt`, the same field any other write would, so a delete
competes on equal footing with a concurrent play under ordinary last-write-wins.

**D117 — `deletedAt` is a nullable epoch-millis column, not a boolean alongside a separate
timestamp.** `NULL` means live, a value means "cleared at this time," in one column instead of
two that could disagree. `finished` and `dismissed` stay boolean because both are pure UI
state a user can toggle back and forth; `deletedAt` is closer to `updatedAt` in kind — a fact
about when something happened — so it follows that column's shape instead.

**D118 — macOS registers the tombstone as a second migration, `v7_deletedAt`, not folded into
`v6_initial`.** The MVP shipped and is presumably already installed; GRDB's migrator replays
only migrations a given database hasn't seen, so folding the new column into the first
migration would mean an existing install never runs it. Same reasoning as why `MIGRATION_6_7`
is a new migration on the Android side rather than a change to `MIGRATION_5_6`.

## Iteration 20 — the sync backend, built and deployed (D119-D127)

A Cloudflare Worker + D1 service at `sync/`, built and exercised end to end against a local
D1 instance first (`wrangler dev`, no Cloudflare account touched), then deployed for real
once Mike authenticated `wrangler login` under his own account.

**D119 — Cloudflare Workers + D1, signed off before any code.** $0 at this scale (100k
requests/day free tier against a two-device app doing maybe 50/day), no idle-pause unlike a
free-tier Supabase project pausing after 7 days, and D1 being SQLite means the server's
`progress` table is the client's table plus two columns rather than a schema translation.

**D120 — Device tokens are opaque random strings hashed at rest, not JWTs, with two-slot
rotation.** `devices.tokenHash` is `SHA-256` of the live token; a database leak yields no
working credentials. Not a JWT: there's no third party to verify one against, and JWT
revocation needs a denylist anyway, so a row lookup is both simpler and instantly revocable —
`DELETE /devices/{id}` takes effect on literally the next request. Rotation (past 90 days)
writes the old hash into `previousTokenHash`/`previousTokenExpiresAt` rather than just
overwriting `tokenHash`, so a client that crashes between receiving `X-Sync-Token-Rotated` and
persisting it has 48 hours to retry before it's actually locked out.

**D121 — the stale-cursor check compares `since` against a seq-scale `retentionFloorSeq`, not
a timestamp.** The first implementation compared `since` (a `seq` value — a small monotonic
per-group counter) against `now - 180 days` (an epoch-millis threshold), which is a unit
mismatch: it fired the `410 Gone` path on every request, caught by the very first live test
against local D1 rather than by `tsc`, since both sides were typed as plain `number`.
`retentionFloorSeq` lives on the `seqs` table, one seq-scale value per group, and rises only
when a future purge job removes old tombstones — which doesn't exist yet, so the floor is
permanently 0 today and this path cannot fire in practice. It's implemented and tested now
(by hand-setting the floor in a local DB) so the contract exists before the purge job does,
rather than being retrofitted around whatever the job happens to produce.

**D122 — `POST /pair/claim` takes a `pairingId` alongside the code, not the code alone.**
**Superseded by D127** — a UUID pairing id isn't something a human can type, which broke the
one thing text-entry pairing exists for. A wrong-code guess against a bare code has no row to
attach an attempt count to — `codeHash` only matches on an exact hit, so there's nothing to
increment on a miss. Carrying the pairing id (embedded in the same QR/deep-link payload as the
code) gives the five-attempts-then-burn rule an actual row to burn.

**D123 — conflict resolution accepts an incoming write when `updatedAt >= existing.updatedAt`,
not `>`.** On an exact tie this favors whichever push reaches the server later, which is
`seq`'s tie-break in effect without a separate comparison: the later arrival is, by
construction, the one being applied right now. Applying accepted rows and bumping the `seqs`
counter happen in one `env.DB.batch()` — D1 has no interactive transactions, so a
read-the-counter-then-write-the-rows sequence across two round trips would race if two devices
pushed to the same group at once.

**D124 — verified live against local D1, every path exercised with real HTTP requests, not
just written and typechecked:** bootstrap pairing and claim, a device joining an existing
group, wrong-code attempts burning a pairing on the fifth try, a push/pull round trip between
two devices, an older write losing to a newer one and a newer one overwriting it, `GET
/devices` listing both with the right `isSelf`, `DELETE /devices/{id}` taking effect on the
very next request, the `410` path at and around the retention floor, and token rotation
(backdating `tokenIssuedAt` past 90 days) confirming both the rotated and the pre-rotation
token work afterward.

**D125 — deployed to production, smoke-tested, and the test data cleaned back out.**
`wrangler login` (OAuth) needed a second attempt: the first run was launched with a shell `&`
inside one tool call rather than kept alive as its own tracked process, so the callback
listener was already dead by the time the browser redirected back to it — "localhost refused
to connect" was that death, not a Cloudflare-side failure. Once actually kept running,
`wrangler d1 create couch-tour-sync` and `wrangler deploy` succeeded on the first real
attempt; the service is live at `https://couch-tour-sync.mkastellec.workers.dev`.
`wrangler.toml`'s `database_id` now points at the real database. A smoke-test pairing was
run against production to confirm the deployed Worker actually answers (not just that `wrangler
deploy` exited 0), then deleted by hand — the one thing this MVP has no delete-a-whole-group
endpoint for yet, so cleanup went straight through `wrangler d1 execute --remote`.

**D126 — `since = 0` never triggers the retention-floor `410`, no matter how far
`retentionFloorSeq` has moved.** Found and fixed while starting the client work, before any
purge job existed to expose it live: the check as first written was `since <
retentionFloorSeq`, which rejects every brand-new pairing the instant the floor ever moves off
0, since 0 is less than any positive number. A fresh client has nothing to distrust — it isn't
resuming an old cursor, it's asking for the entire current table — so the floor only applies
once `since` is actually a prior position, not the starting one. Verified both sides of the
boundary against local D1 (`since=0` succeeds under a floor of 5; `since=1` still 410s), then
redeployed to production.

**D127 — pairing is claimed by the code alone; `pairingId` is gone from the wire contract
(supersedes D122).** Caught while starting the Android pairing screen: D122's fix for
attempt-counting needed a `pairingId` on the claim request, but that id is a UUID — asking
someone to type it defeats the entire reason "the code is also shown as text so it can be
typed" was a requirement in the first place. `POST /pair/claim` now looks the pairing up by
`codeHash` directly, and the per-row `attempts` counter (and its column) is gone with it — an
8-character base32 code is roughly 10^12 possibilities against a 10-minute TTL, which is
already impractical to brute-force without a counter bolted on. `pairings.attempts` was
dropped via `ALTER TABLE ... DROP COLUMN` on both local and production D1 rather than left as
dead weight; production had zero real rows at the time, so this cost nothing to do cleanly.
Re-verified end to end against production after redeploying: bootstrap, claim by code alone,
cleanup.

## Iteration 21 — wiring sync into both clients (D128-D136)

The backend from Iteration 20 talks to nothing yet. This iteration adds the client half on
both platforms: pairing, the push/pull cycle, token storage, background cadence, and a
minimal settings screen — text-code pairing only, no QR (D131).

**D128 — Android's sync client mirrors `Api.kt`/`Auth.kt`'s existing shape exactly.**
`SyncApi` is OkHttp + kotlinx.serialization with `Authorization: Bearer`, its own client
deliberately separate from `PhishInApi`'s `X-Auth-Token` JWT — an unrelated service with an
unrelated identity. `SyncTokenStore` is `EncryptedSharedPreferences` under `couchtour_sync`,
not `phishin_auth`, so signing out of phish.in can't unpair this device from sync.

**D129 — `lastSeq`/`lastPushWatermark` were missing the memory fallback `deviceToken`/`deviceId`
already had; found by a real test failure, not by inspection.** `EncryptedSharedPreferences
.create()` itself throws under Robolectric (no real Android Keystore in the test JVM) — which
`TokenStore` survives because every field falls back to an in-memory copy when the encrypted
store is unavailable, and `SyncTokenStore` copied that pattern for `deviceToken`/`deviceId`
but not the two cursor fields, whose setters silently no-op'd instead. A round-trip test
caught it immediately (`expected:<42> but was:<0>`). Fixed with the same
`memoryLastSeq`/`memoryLastPushWatermark` shape, so all four fields now degrade consistently
— on a real device this only matters if the Keystore itself is in a bad state, but it's the
same resilience the rest of the class already promises.

**D130 — `androidx.work` (2.11.2) is a new dependency, for a periodic sync job constrained to
`NetworkType.CONNECTED`; scheduling it is wrapped in try/catch.** 15 minutes is WorkManager's
own floor for periodic work, matched on the macOS side (D133) for the same cadence on both
platforms. `WorkManager.getInstance()` throws when WorkManager's own initialization hasn't
run — true of every Robolectric test (deliberately: no `Configuration.Provider` wired up for
tests, so no other test needs to know sync exists) and conceivably true of a real device in
some unanticipated state. Background scheduling failing is not a reason to crash app
startup — the immediate on-launch sync in `CouchTourApp` runs regardless, so pairing still
becomes useful even if the periodic job never registers.

**D131 — pairing is claimed by typing the code; no QR in this pass, on either platform.**
Generating a QR (a new dependency, trivial) and scanning one (camera + a scanning library,
not trivial on Android) don't change the wire protocol at all — D127 already made the code
alone sufficient — so this is a pure follow-up rather than something blocking the rest of the
feature. Tracked in ROADMAP.md.

**D132 — macOS Keychain access sits behind a `KeychainStoring` protocol so `swift test` never
touches the real system keychain.** `SystemKeychain` is the real `SecItem`-based
implementation; tests inject `InMemoryKeychain`. An unattended CI or dev run should not be
able to trigger a Keychain access prompt, which a raw `SecItemAdd`/`SecItemCopyMatching` call
against the real login keychain risks doing depending on the machine's state. The same
reasoning extended to `SyncTokenStore`'s non-secret cursors: they're constructor-injectable
`UserDefaults`, defaulting to `.standard` in the app but a freshly-named suite per test in
`SyncTests.swift` (`removePersistentDomain` in `tearDown`) — an earlier draft reused `#file`
as the suite name, which would have shared one real on-disk UserDefaults domain across every
test in the file and left it there after the run, since unlike `.standard` a named suite is
real persistent storage, not something Xcode resets between runs on its own.

**D133 — macOS sync cadence is a `Task` loop plus `NSApplication.didBecomeActiveNotification`,
not `BGTaskScheduler`.** The MVP has no background-execution entitlement request, and
`RootView`'s `.task` modifier already spans the whole app run in practice, so a 15-minute
`Task.sleep` loop (matching Android's WorkManager floor, D130) needs nothing extra — sync
fires immediately on launch, again whenever the app returns to the foreground, and every 15
minutes it stays open. A real background-refresh mechanism is a bigger, separate piece of
work than this MVP needed to unblock the rest of the feature.

**D134 — `MockServer`'s `URLProtocol` shim needed a body reader, because `httpBody` comes
back `nil` for exactly the requests that pass through it.** Every existing use of
`MockServer` was GET-only, so nothing had exercised a POST/DELETE body until `Sync.swift`'s
tests — the first attempt asserted directly against `request.httpBody` and failed
(`"{"since":1,...}" is not equal to ""`) because Foundation converts a request's body to
`httpBodyStream` before handing the canonical request to a custom `URLProtocol`, and
`URLRequest.httpBody` never reflects that back. `MockServer.swift` gained
`URLRequest.bodyString`, draining the stream by hand when `httpBody` is absent.

**D135 — verified with real builds on both platforms, not just the unit suites.**
`./gradlew assembleDebug` succeeds (`testDebugUnitTest` is 214/214 — 21 new sync tests over
Iteration 20's baseline). `xcodebuild` succeeds for the macOS app target, and the built app
was launched and left running for several seconds with nothing in the system log — the
Keychain calls `AppModel.init()` makes via `SyncSession()` happen on literally every launch,
so this exercised the real `SystemKeychain` path, not a mock. `swift test` is 117/117.

**D136 — a Swift test flaked on rerun from an assertion this codebase's own conventions
should have caught: exact-string equality against `JSONEncoder` output.** `testSyncDoesNot
RepushARowAlreadyAtTheWatermark` passed the first time and failed the next `swift test` run
with the identical JSON content in a different key order
(`{"since":1,"changes":[]}` vs. `{"changes":[],"since":1}`) — `JSONEncoder`'s key order is not
guaranteed stable across process launches on Apple's Foundation, unlike kotlinx.serialization
on the Android side, which does guarantee declaration order (confirmed: the equivalent Kotlin
test asserts exact-string equality safely). Fixed by checking content
(`body.contains(#""since":1"#)`) rather than the exact byte layout, the same approach the
sibling push-test already used. Re-run five times clean afterward before trusting it.

## Iteration 22 — a side-installable beta build (D137)

**D137 — `-PsideInstall=true` builds `dev.mike.couchtour.beta` ("Couch Tour Beta"), gated
behind a Gradle property so every ordinary debug build is untouched.** Wanted for exactly one
reason: testing the new sync feature (Iteration 21) without disturbing the working v0.12
sideload — every prior release has shared one `applicationId` and one debug signing key
specifically so a new CI build updates the previous install in place (see
`build-debug-apk.yml`'s own comment on that, and the v0.2a incident that motivated it), which
is right for normal releases but wrong for a beta the tester wants to run *alongside* the
known-good build. `android:label` moved from `@string/app_name` to a manifest placeholder
(`${appLabel}`) so the two installs are distinguishable in the launcher without a duplicate
resource declaration — `resValue` was the first attempt and fails outright, since `app_name`
already exists in `strings.xml`; a manifest placeholder is a separate mechanism from generated
resources and doesn't collide. `strings.xml` itself is now empty of purpose and was removed
rather than left holding a value nothing reads. `namespace` (the compiled Kotlin package,
`dev.mike.couchtour`) is untouched — only `applicationId` (the install identity) changes, so
the one fully-qualified class reference in the manifest (`CastOptionsProvider`) keeps
resolving correctly. Wired into `build-debug-apk.yml` as an opt-in `side_install` input,
alongside a `prerelease` input (existing releases v0.1-v0.12 never used GitHub's own
pre-release flag; this one does, being genuinely experimental).

## Iteration 23 — a real pairing attempt, and two bugs it found (D138-D139)

The first actual cross-device pairing attempt — phone code, typed into the Mac app — failed
immediately, before any of the sync logic itself was exercised. Both bugs were purely in the
claim path.

**D138 — macOS's code field didn't uppercase input; Android's already did.** Pairing codes
are generated all-uppercase (`sync/src/crypto.ts`'s `randomPairingCode`) and looked up by
exact `codeHash`, so a code typed or autocompleted in lowercase hashes to a different value
and gets a flat 401. Caught live: the phone showed `7S9UGDQP`, the Mac's field held `7s9ugdqp`
verbatim. Fixed with `.onChange(of: claimCode) { claimCode = $0.uppercased() }` on the
TextField, matching what Android's `onValueChange` already did — this was an asymmetry
between the two clients' pairing screens, not a protocol bug.

**D139 — `SyncException` didn't conform to `LocalizedError`, so every failure looked
identical.** The actual 401 was invisible: `error.localizedDescription` fell back to Swift's
generic bridged-NSError text, `"The operation couldn't be completed. (CouchTourKit
.SyncException error 1.)"`, for a plain wrong-code rejection — indistinguishable on screen
from a network failure or a server bug. Fixed by adding `errorDescription { message }`, so
the UI shows what the server actually said (`"incorrect code"`, `"pairing expired"`, etc.).
This is the kind of bug unit tests don't catch on their own — nothing was asserting against
`localizedDescription` before this, only against `.unauthorized`/`.gone` on the typed error
directly (D124's tests). A regression test now pins the string itself.

## Iteration 24 — sync never actually worked; the emulator round trip that proved it (D140-D143)

Pairing succeeded, then nothing synced and the Android app crashed on every subsequent
launch. One root cause under both symptoms, plus two things that turned a recoverable error
into an unrecoverable one.

**D140 — both clients omitted null optionals, and D1's `bind()` rejects `undefined`, so every
push 500'd.** kotlinx.serialization writes only properties differing from their default unless
`encodeDefaults` is set; Swift's synthesized `encode(to:)` uses `encodeIfPresent` for
Optionals. Both therefore dropped `artUrl`/`deletedAt` entirely rather than sending null — and
`artUrl` is null for every Relisten row, so this was every push, not an edge case. Confirmed
by replaying both payload shapes against production: the documented shape 200s, the shape the
clients actually sent 500s. Fixed in three places, deliberately: the server normalizes
`?? null` (a client getting this wrong must not be able to 500 the endpoint), Android sets
`encodeDefaults = true`, and `SyncProgressWire` gained a hand-written `encode(to:)`. Server
side alone would have been enough to unbreak it; the client fixes keep the wire format
matching `ProgressFields` as documented.

**D141 — an exception escaping the launch-sync coroutine took the whole app down.**
`CoroutineScope(Dispatchers.IO).launch { SyncSession.sync(...) }` with no handler: anything
`sync` rethrows (D140's 500, an offline device, a captive portal) reached the default uncaught
handler and killed the process on launch. Unpaired devices were unaffected only because
`sync` returns early with no token — which is exactly why this appeared the moment pairing
started working. The comment already claimed failures were "covered by the periodic job's own
retry"; that is now true rather than aspirational.

**D142 — pairing didn't trigger a sync, so a working pair still looked broken.** Both clients
only synced on launch, on foreground, or on a 15-minute timer — none of which fire while
sitting on the Sync screen having just paired. Even with D140 fixed, the first thing a user
sees after pairing would have been an unchanged, empty History. Both clients now sync
immediately on a successful claim, surfacing "Paired, but the first sync failed" rather than
silently doing nothing.

**D143 — verified on a real emulator, end to end, not just by unit test.** `phishin_test` AVD:
installed the beta, paired via the UI, played a track (Mac muted), force-stopped and
relaunched — no crash, and the launch sync pushed `show:2026-01-28` / "Hey Stranger" at
60770ms to the server. A second device then joined by code and pulled that exact row back
down. Test group and all its rows deleted afterward; the real Pixel/Mac group was never
touched. Two incidental findings worth keeping: the emulator's default DNS is broken (fixed
with `-dns-server 8.8.8.8`, nothing to do with the app), and the app's own error reporting was
already correct — a DNS failure surfaced "Couldn't start pairing: Unable to resolve host"
exactly as intended.

## Iteration 25 — tightening sync latency: a debounced push on play/pause/track-change (D144)

**D144 — both clients now push within ~2s of a play/pause/track-change event, instead of
waiting up to 15 minutes for the next launch/foreground/timer sync.** `SyncSession` on both
platforms gained `requestDebouncedPush` — cancel any in-flight debounce, wait (2s in
production, overridable for tests), then run the same `sync()` push/pull cycle. Called from
exactly the events that already bypass the local 5s throttle: Android's `playerListener`
(`onIsPlayingChanged`/`onMediaItemTransition`/`onPlaybackStateChanged` in
`PlaybackService.kt`) and macOS's `saveProgress(force: true)` call sites in `Player.swift`
(rate change, track change, seek, queue start) — never the periodic 5s local-write tick, which
would otherwise reset the debounce every half-second while merely sitting on a paused screen.
The debounce coalesces bursts — several of those listeners fire for the same real event — into
one push rather than one per callback.

Verified live against the real production backend, not just unit tests (per the project's own
sync-testing standard): paired the Android emulator fresh, joined its pairing code from a
`curl`-simulated second device (deliberately not Mike's real Mac, which was already paired to
his actual Pixel/Mac group — touching that risked disrupting a live pairing to prove a client
timing change), then toggled play/pause via `adb shell input keyevent
KEYCODE_MEDIA_PLAY_PAUSE` and polled the sync endpoint. The push's server-recorded `updatedAt`
landed roughly 1.7s after the toggle — consistent with the 2s debounce plus network/clock
variance, and nowhere near the old 15-minute worst case. The disposable test group (both
devices, plus its `progress`/`pairings`/`seqs`/`devices`/`groups` rows) was deleted from
production D1 afterward via `wrangler d1 execute --remote`; Mike's real Pixel 9 Pro
XL/Mac Mini group was confirmed untouched (`revokedAt IS NULL` on both, same as before the
test).

Coalescing behavior itself is covered by a unit test on each platform (`SyncTest.kt`,
`SyncTests.swift`) — three rapid `requestDebouncedPush` calls with a short test-only delay
produce exactly one push, using the same mock-server harness the rest of the sync suite uses,
rather than depending on the live round trip above to catch a regression here.

## Iteration 26 — a beta-badged icon variant (D145)

**D145 — The side-installed beta build gets its own icon, not just its own label.**
D137 made `-PsideInstall=true` install alongside the regular app under
`dev.mike.couchtour.beta` with the label "Couch Tour Beta," but both builds still showed the
identical launcher icon — the only way to tell them apart in the app drawer was reading a
label that gets truncated ("Couch Tour…" on a narrow grid). `ic_launcher_beta_foreground.png`
adds a small amber "BETA" pill inside the ring, low enough to sit clear of the adaptive
icon's circular safe zone (verified against a simulated true-circle mask, the same check the
base icon used). Wired the same way `appLabel` already was: a new `appIcon` manifest
placeholder (`ic_launcher` normally, `ic_launcher_beta` under `sideInstall`), with
`AndroidManifest.xml`'s `android:icon` now `@mipmap/${appIcon}` instead of a literal
resource.

**D146 — macOS finally has a real app icon, base and beta.** The desktop app has shipped
since Iteration 18 without ever setting `ASSETCATALOG_COMPILER_APPICON_NAME` — every build
carried Xcode's default placeholder icon. Added `AppIcon.appiconset` (all mac idiom sizes,
16 through 512 at 1x/2x) generated from the same source art as the Android icon, and a
second `AppIcon-Beta.appiconset` with a diagonal ribbon banner — the standard desktop
convention, since macOS icons aren't circle-masked the way Android's adaptive icons are, so
Android's inline pill wouldn't read as clearly here. Only the base `AppIcon` is wired into
the one macOS target that exists (`ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon` in
`project.yml`); the beta set is a ready asset, not yet attached to a target, since macOS has
no beta-build mechanism analogous to Android's `sideInstall` yet — building one (bundle id,
signing, `install.sh` changes) is a separate decision.

## Iteration 27 — QR pairing, generation on both platforms, scanning on Android (D147)

**D147 — pairing codes are now also shown as a QR, and Android can scan one to fill the Code
field.** A pure follow-up on top of D127/D131's existing text-code protocol, not a protocol
change — the code itself is still the only thing either endpoint ever sees. **Generation**:
Android encodes with `zxing-core` (`QRCodeWriter` → `BitMatrix` → a hand-painted `Bitmap`,
`Qr.kt`); macOS uses `CIQRCodeGenerator` directly (`Qr.swift`) — CoreImage already ships with
the OS, so no new dependency there, matching the ROADMAP's own prediction. Both render at "H"
error-correction and scale the generator's one-pixel-per-module output up before rasterizing,
or the result is a handful of pixels blurred across whatever frame SwiftUI/Compose gives it.
**Scanning** (Android only — ROADMAP.md never called for a macOS scanner, and this iteration
didn't add one): CameraX + ML Kit's on-device barcode scanner, gated behind a runtime `CAMERA`
permission request (`ScanScreen` in `Qr.kt`), landing on a new `"scan"` nav route. A scanned
payload only ever autofills the existing Code field — it's checked against
`BASE32_NO_AMBIGUOUS` (`sync/src/crypto.ts`)'s exact 8-character alphabet
(`looksLikePairingCode`) before being trusted, so pointing the camera at an unrelated QR before
finding the right one doesn't briefly populate the field with garbage. The regex is a UX
filter, not a security boundary — the server already validates the code independently on
claim.

Verified two different ways, deliberately not by hand-eyeballing a scan through the emulator's
synthetic "emulated" camera backend (its default test-pattern feed has nothing to scan a real
QR from, and reconfiguring it to VirtualScene for one image is disproportionate to what's
being checked here): the Android encoder's output was decoded back with `pyzbar`
(a different library from the one that encoded it) and matched the source code exactly; the
macOS encoder's algorithm was run standalone (outside the signed app, which was mid-fight with
repeated Keychain re-prompts from an unrelated ad-hoc-signing quirk — same binary, different
signature every rebuild, D113) through Apple's own `VNDetectBarcodesRequest`, which also
decoded it correctly. `looksLikePairingCode`'s matching rule and the `qrCodeBitmap` encoder
both got unit tests of their own (`QrTest.kt`) alongside the live decode checks, so a
regression here doesn't depend on either external verification path catching it again.

## Iteration 28 — the 180-day tombstone purge, finally wired to a job (D148)

**D148 — a daily Cloudflare Cron Trigger now actually raises `retentionFloorSeq` and deletes
the tombstones it covers, exercising the `410` path D121/D126 built and tested but that has
never once fired in production.** `purgeOldTombstones` (`sync/src/index.ts`) finds every
group with a `deletedAt`-tombstoned row older than 180 days, and per group, in one
`env.DB.batch()`: raises `retentionFloorSeq` to the highest `seq` among the rows about to be
purged (never lowers it — `MAX(retentionFloorSeq, ?)`), then deletes those rows. The order
inside the batch is load-bearing, not incidental: raising the floor *before* deleting means a
client can never observe a state where the gap exists but nothing has told it to distrust that
gap yet. Wired to `wrangler.toml`'s `[triggers] crons = ["0 4 * * *"]` (an arbitrary off-peak
UTC hour — this app has no real peak) via the Worker's `scheduled` export, `ctx.waitUntil`-ed
so the platform doesn't tear the isolate down mid-purge.

No admin-triggerable HTTP endpoint was added to fire this on demand in production — it would
be one more unauthenticated surface on a service that currently has none, for a job whose only
consumer is a cron schedule. Verified instead with `wrangler dev --test-scheduled`'s
`/__scheduled` endpoint, which runs the exact deployed code against a real (local) D1 instance
over a real HTTP round trip — not a mock, and a stronger check than a hand-invoked function
call: seeded a group with an old tombstone at seq 4 flanked by live rows at seq 1-3 and 5,
confirmed `since=3` synced clean beforehand, ran `/__scheduled`, then confirmed all three
boundary cases the contract promises actually hold — `since=3` now `410`s exactly as designed,
`since=4` (a device that had already synced through the tombstone's own seq) still succeeds,
and `since=0` (D126's full-resync exemption) still succeeds regardless of how far the floor has
moved. This is the first time any of the three has been observed against the job's real SQL
rather than a hand-set `retentionFloorSeq` in a unit test. Deployed to production and
smoke-tested (a disposable pair/start round trip, cleaned up immediately after) to confirm the
Worker with the new `scheduled` export still serves ordinary requests; the cron itself will
next fire for real at its scheduled UTC hour, or can be fired early from the Cloudflare
dashboard's own "Trigger Cron" test button if that's wanted sooner. Harmless either way at the
current 2-device scale — there are no tombstones anywhere near 180 days old yet.

## Iteration 29 — sync status visibility: last-synced, sync now, last-played (D149)

**D149 — the Sync screen now shows when it last actually talked to the server, plus a manual
"Sync now" button; History and Continue Listening tiles show when each show was last played.**
All of sync's activity was previously invisible between launches — no way to tell "sync is
working, just hasn't fired yet" apart from "sync is broken." `SyncTokenStore` gained a
`lastSyncedAt` wall-clock timestamp
(0/`nil` = never), written at the same point `lastSeq` already is: the end of a *successful*
`sync()` round trip, not on the unpaired no-op or a failed attempt — the debounced push from
D144 already syncs frequently enough that this reads as "how long ago sync last actually
succeeded," not just "the user's own last tap." `SyncSession` also gained `isSyncing`/`syncing`
(Swift/Kotlin), for the button's disabled/label state — set at the top of `sync()`, cleared in
a `finally`/`defer` so it can't get stuck true on a thrown `SyncException`. macOS's `SyncView`
had its `onPaired` closure renamed to `sync` and reused for the new button, rather than adding
a second closure parameter that would do the exact same thing under a different name.

"Last played" labels ("5m ago", "3h ago", "2d ago", falling back to "MMM d" past a week) use a
new `relativeTime` helper, ported identically to both platforms' existing `Format.kt`/
`Format.swift` — no new column needed, since `Progress.updatedAt`/`PlaybackProgress.updatedAt`
already means "last touched," which is "last played" for a row that isn't actively playing
(nothing else updates a finished/dismissed row's timestamp). Shown on both History and Continue
Listening. Android: History's `RowItem` gained an optional `trailingSecondary` slot (default
`nil`, every other call site unaffected) for a second dimmer line under the existing
position/status text; `ResumeCard` (Continue Listening) gained a third `Text` line the same
way. macOS: rather than duplicate it per screen, it lives once in the shared `ProgressRow` —
used by both `HistoryView` and `ContinueListeningView` — as a third line under the subtitle;
`HistoryView`'s own trailing column went back to just its status text once the row itself
started carrying the timestamp, so it isn't shown twice.

Verified live end to end on Android (a disposable test pairing, not Mike's real group):
"Never synced" showing correctly before any pull/push had happened (pairing only mints a code,
it doesn't sync until the other side joins), tapping "Sync now" flipping it to "Last synced
just now," a real history tile showing "at 4:05" over "5h ago", and the same "5h ago" showing
on that show's Continue Listening card — both against the actual `updatedAt` in the local
database. Test group deleted from production D1 afterward. The macOS side is covered by
`SyncTokenStoreTests`/`SyncSessionTests` (round-tripping `lastSyncedAt`,
`sync()` setting it on success) rather than a live GUI check — the installed app was mid the
same ad-hoc-signing Keychain-reprompt loop D147 ran into, and denying that prompt makes the
paired-only UI this feature lives in impossible to reach without either Mike's login password
(never entered) or touching his real pairing (declined for the same reason as D147).

## Iteration 30 — a pre-release security pass over sync (D150-D154)

A deliberate sweep across the sync backend and both clients ahead of the Play Store release
(issue #26) — not a hunt for a known vulnerability, but a walk through each attack surface to
confirm what already held and fix what didn't. Most of it held. What follows is what changed.

### D150 — rate limiting only the one unauthenticated path that grows the database

`POST /pair/start` with no bearer token bootstraps a group, a device, a seq counter, and a
pairing row on every call. That is the only endpoint where a caller holding no credentials can
make D1 grow, and nothing capped it: a script could have filled the 10 GB database with orphan
groups. `/pair/claim` needs no limit of its own — D127's argument still holds, 8 base32
characters against a 10-minute window — and `/sync` and `/devices` need none either, since
abusing them costs an attacker a valid device token first and revoking one is a single request.

Implemented with the Workers `ratelimit` binding (`[[ratelimits]]` in `wrangler.toml`) rather
than a Cloudflare Rate Limiting rule or a D1-backed counter. A dashboard rule would be
configuration living outside the repo, invisible to anyone reading this code and lost on any
account rebuild; a D1 counter would answer a flood of requests by adding a database write to
each one, which is the thing being defended against. 5 per 10s per IP sits far above a human
tapping "Pair" and far below useful abuse. The binding counts per Cloudflare location rather
than globally, so this raises the cost of scripted bootstrap rather than making it impossible
— worth stating plainly, since the limit reads stricter than it is.

### D151 — `POST /sync` validates every element, and caps how many it will take

`handleSync` type-checked `since` and `changes` at the top level and then cast the array
`as ProgressFields[]` — a claim the type system accepted and nothing had checked. A row with a
numeric `title` or a missing `queueKey` would either write nonsense into D1 or throw inside
`.bind()` and 500 the endpoint. Every field is now parsed individually (`parseProgressFields`),
with lengths bounded so a single row can't approach D1's 2 MB per-row ceiling, and a malformed
element returns a 400 naming the offending field and index rather than a 500.

`changes` was also unbounded. It now caps at 500 entries with a 413, plus a cheap
`Content-Length` pre-check that rejects an oversized body before it is ever parsed. The
Content-Length check is a fast path, not the guarantee — it is absent on a chunked upload — so
the entry cap is what actually bounds the work.

### D152 — the D1 bound-parameter limit was a live bug, not just a hardening gap

Found while auditing the above, and the most consequential thing in this pass: D1 allows at
most **100 bound parameters per query**, and `applyIncomingChanges` built a
`queueKey IN (?, ?, …)` lookup binding one per incoming row plus the `groupId`. Any push of 100
or more rows died on `D1_ERROR: too many SQL variables`. Confirmed against `wrangler dev`: 99
changes returned 200, 100 returned 500, and the boundary sat exactly where the arithmetic said
it would.

This was reachable in normal use, not just under attack. Both clients pushed the entire
`changedSince` result in one request, and a first pair starts from watermark 0 — so the whole
progress table is "changed." Any user with 100+ shows of history would have had their first
sync fail permanently, in the one table this app exists to never lose. It had not been hit only
because no test pairing had ever carried that much history.

The lookup is now chunked at 90 keys per query. The clients chunk too (D153), so the two limits
are independent: the server no longer breaks regardless of what a client sends, and a
well-behaved client never approaches the cap anyway.

### D153 — both clients drain a push backlog across several round trips

With the server capping a push at 500, a client that sent everything at once would simply fail
differently, so `sync` on both platforms now loops: push a bounded batch (400, leaving headroom
under the server's own 500), apply what comes back, and go again while rows remain. In normal
use this is still one round trip — only a first pair has the backlog to need more.

The subtle part is the batch boundary. The push watermark advances to the batch's highest
`updatedAt`, and `changedSince` is strictly `>`, so cutting through a run of rows that share one
millisecond would leave the remainder permanently unoffered — a silent lost write. A batch is
therefore trimmed back to the run boundary, and only when the run actually continues past the
cut; a single millisecond holding more rows than the batch size is sent whole rather than
stalling forever, which the gap between 400 and 500 leaves room for. The first cut of this
trimmed unconditionally and quietly dropped one row per batch, which is exactly the class of
bug this table cannot afford — the tests covering all four cases exist because of it.

### D154 — errors are ours to shape, and CORS stays absent on purpose

An uncaught throw left the response to the platform. Under `wrangler dev` that means the
exception, the stack trace, and the developer's absolute filesystem paths returned to the
client; in production it means Cloudflare's generic 1101 page. The second is fine and the first
is not, but neither is ours to depend on, and the `no seq counter for group ${groupId}` throw
was the thing standing to leak. The `fetch` handler now wraps its router: details go to
`console.error`, the client gets a bare `{"error":"internal error"}` 500. Verified by forcing
that throw locally.

No `Access-Control-*` headers anywhere, and that stays deliberate rather than accidental. Both
clients are native — OkHttp and URLSession — and no browser ever calls this. Omitting
`Access-Control-Allow-Origin` means a page on any origin can still send a request but cannot
read the response, which is the right default for an API whose entire auth model is a bearer
token. Adding permissive CORS "just in case" would be a strict downgrade.

### What was checked and found already correct

Recorded because the value of a security pass is as much in what it rules out as in what it
changes. Every D1 query is `groupId`-scoped to the authenticated device, with two deliberate
exceptions that are correct: the pairing lookup is by code alone (the claimer has no group
yet — the code is the authorization), and the revoke path looks a device up by id and then
403s if it isn't in the caller's group. Every query is parameterized; no user data is ever
interpolated into SQL. The cron-only tombstone purge is not reachable over `fetch` —
`/cdn-cgi/handler/scheduled` is a `wrangler dev` affordance, and production returns Cloudflare
error 1042 without the request ever reaching the Worker.

On the clients: Android's `EncryptedSharedPreferences` fallback degrades to memory-only and
never writes a token in the clear, and the only thing it logs is that the store was
unavailable. macOS keeps the device token and id in Keychain (not synchronizable, so a future
iOS client can't silently inherit this Mac's identity) and only non-sensitive cursors in
`UserDefaults`. No token, JWT, or pairing code reaches `Log.*`, `os_log`, or `print` on either
platform. All three services are HTTPS-only with no cleartext fallback and no ATS exemption.
The two `exported="true"` manifest components are the launcher activity, which reads a single
boolean extra, and the Media3 service, whose browse tree parses media ids through
`BrowseNode.parse` — already returning null for anything unrecognised rather than guessing.
`npm audit` is clean; the Android release tree has no known-vulnerable dependency.

## Iteration 31 — sharing a show or track (D155-D156)

Standard Android sharing (#19), Relisten parity — the app had no `Intent.ACTION_SEND` at all
before this. Android-only: no equivalent request exists yet for the macOS client.

### D155 — the share link comes from each backend's real web app, confirmed live, not guessed

`ShowSummary`/`PlayableTrack` are backend-neutral (Catalog.kt) on purpose, so the URL a share
sheet should link to is a function of `Backend` (`showShareUrl`/`trackShareUrl` in Catalog.kt)
rather than a field threaded through the model — the same pattern the file already uses for
its backend-dispatch mapping (D36).

Both URL schemes were checked against the real sites rather than assumed, since a share
feature whose links don't resolve is worse than no share feature:

- **phish.in** publishes a real page per show (`/<date>`) and per track (`/<date>/<slug>`) —
  confirmed by fetching both live and checking `og:url`/`og:title` echo back exactly, and
  that an unrecognised track slug falls back to the show's own title rather than 404ing (so
  the slug is genuinely validated server-side, not decorative). The slug already existed in
  every API response `Track` decodes (`show.json`'s fixture had it uncaptured) — it just
  wasn't mapped to a field. It is now (`Track.slug`).
- **Relisten**'s web app (`relisten.net`, distinct from the `api.relisten.net` this app
  already talks to) serves `/<artist-slug>/<date>` for a show — confirmed the same way, and
  confirmed Relisten's server actually 404s an unknown route (`<title>404 - Page Not
  Found</title>`) rather than a catch-all SPA shell always returning 200, so a 200 here means
  the page is real. No equivalent per-track or per-source page exists:
  `/<artist>/<date>/<source-uuid>` 404s live the same way. `trackShareUrl` returns null for
  Relisten rather than construct a link to a page that doesn't exist; callers fall back to
  the show's link, keeping the track's title in the shared text even though the link points
  at the show.

### D156 — track sharing lives on the track row, not the Now Playing screen

The issue named either placement as acceptable for "share the specific track." Now Playing's
`PlayerState` carries only display strings and a `queueKey` (which identifies a queue, not an
individual track within it) — the same gap already noted against the not-yet-built like
button on that screen (ROADMAP.md). Extending `PlayerState` with a track id was out of scope
for this issue on its own; the track row already has the full `PlayableTrack`/`Track` in
hand, so that's where the share action landed, next to the existing like button
(`TrackRow`/`RecordingTrackRow`), following the same self-contained-row-composable shape
`LikeButton` already established rather than threading an `onShare` callback up through both
screens.

Tested at the two levels the issue anticipated: `ShareUrlTest` covers `showShareUrl`/
`trackShareUrl`/`showShareText`/`trackShareText` as plain functions (including phish.in's
slug-present/absent cases and Relisten's fallback), and a Robolectric `LaunchShareTest`
constructs the actual `Intent` and asserts `ACTION_CHOOSER` wraps a `text/plain`
`ACTION_SEND` with the right `EXTRA_TEXT` — the chooser itself, and whether recipients can
actually open the link, still need a manual on-device check per the issue's own testing note.

### D157 — "Surprise me" pulls from the full merged catalog, not just the browsed artist

Neither `PhishInApi` nor `Relisten.kt` exposes a random-show endpoint, so `pickRandomShow`
(Catalog.kt) walks the same artist → period → show path the browse screens do: a random
artist from `mergeArtists()`'s merged list, then a random period, then a random show — 2-3
sequential calls, same cost as browsing by hand, and no attempt to weight or cache across
artists for a first pass.

Global rather than scoped to whatever artist is currently being browsed: the button lives on
the Home screen, one level above any single artist's screens, and Relisten's own version is
global too. Phish being pinned first in the merged list (D-noted in `mergeArtists`'s doc
comment) doesn't change the odds here — every artist gets an equal, not weighted, chance,
same as any other entry in the list.

A period whose shows are all `partial` (audio known incomplete) still returns one of them
rather than costing another round trip to find a period with a complete show — an edge case,
not the common path, so it wasn't worth a second fetch to avoid.

Tested with a fake `MusicSource` and an injected `Random` (`CatalogTest.kt`): one test
confirms every artist in a merged set is reachable across many seeds, two more confirm the
partial-audio filter and its fallback. The button itself (`SurpriseMeButton`, MainActivity.kt)
follows the same busy/error-state shape `LoginScreen` already established, and is the first
item in the Home screen's list — always the first thing visible below the search field.

See [ROADMAP.md](ROADMAP.md) for what's not built yet and the open questions about what's
next.

## Iteration 32 — browse shows by top rated / popular (#21, D158-D159)

### D158 — the two backends' data shapes are genuinely asymmetric, so the two browse surfaces are too

phish.in's `/shows` endpoint already sorts server-side (`sort=likes_count:desc`, confirmed
live), so "Popular" is one query with a different sort param — no client-side work at all.
Relisten's per-tape rating (`RelistenSource.avgRatingWeighted`) is one fetch per show, which
doesn't scale to a global "top rated" browse, so the two features aren't the same shape and
weren't forced into one.

Implemented as a synthetic period rather than a new screen: `PhishInSource.periods()`
prepends `PeriodRef(POPULAR_PERIOD_ID, "Popular")` ahead of the real years (Catalog.kt),
so "Popular" rides the same `periods()`/`shows()` seam MainActivity's `ArtistScreen` and
Android Auto's `yearsChildren`/`yearChildren` already consume, rather than a one-off route
bolted on beside it. `"Popular" > "2024"` lexicographically (`'P' > '9'`), so it sorts first
wherever periods are ordered by label descending, with no separate pinning logic. Auto's
`yearChildren` short-circuits the popular id straight to a flat show list rather than the
usual tour-grouping branch — grouping ~100 shows spanning 30-some years by tour name would
produce close to one folder per show, not the handful of tours a real year produces.

### D159 — Relisten's shows list carries `avg_rating` and `popularity` for free; only the first is used this pass

The issue that opened this work assumed Relisten had no bulk-rated endpoint and rating lived
only on `RelistenSource` (fetched per show, one round trip each) — reasonable, since that's
all `RelistenShowSummary` (Relisten.kt) captured, and `ignoreUnknownKeys` had been silently
dropping the rest. Checking the live API (`/v3/artists/{uuid}/years/{yearUuid}`) directly
found otherwise: every show in a year's list already carries `avg_rating` (0-10, populated
for the near-totality of shows checked) and a `popularity` object — `momentum_score`,
`trend_ratio`, and a `hot_score` per 48h/7d/30d window. Both ride along in the exact fetch
`ArtistShowsScreen` already makes; sorting by either costs nothing extra.

Only `avg_rating` shipped this pass, as a Date/Top rated toggle (`FilterChip` row, matching
the pattern `SearchResultsList`'s artist chips already established) on `ArtistShowsScreen` —
scoped to a period already drilled into, same as the issue's own fallback suggestion, except
it turned out to need no bounding at all since the data was already in hand. `popularity` was
deliberately left unused: which window makes a good "trending" signal is a real product
call (48h swings on tour-opener hype vs. 30d for something more stable), not a decision to
make silently inside an unrelated PR, and phish.in has no equivalent recency-weighted number
against it (`likes_count` is a raw, unweighted total) — a Relisten-only "Trending" mode would
be another backend asymmetry to justify. Left concrete for a follow-up (field names,
`RelistenSourceSet` reasoning, ROADMAP.md's #21 entry) rather than re-flagged as "no data
source", which is what the original issue text assumed and is no longer true.

`RelistenShowSummary.avgRating` maps straight to a new `ShowSummary.rating` (Catalog.kt,
default `0.0` — meaningless for phish.in, which has no per-show rating concept). Pinned
against the real `relisten_year.json` fixture already in the test suite, which turned out to
already contain the field: Cornell 5/8/77 rates `9.438597` (`RelistenParsingTest`).
Request-shape coverage for the phish.in side lives in `ApiRequestTest` (`popularShows()`'s
query params, and `PhishInSource.periods()`/`.shows()` routing the synthetic period
correctly). Both browse surfaces were also driven live end-to-end on the `phishin_test`
emulator, not just unit-tested: Phish → Popular shows Big Cypress '99 (428 likes) first, and
Grateful Dead → 1995 → Top rated re-sorts around a real 10.0.

**D160 — Likes for Relisten tracks (#11) are local-only, following #14's favorites pattern
rather than phish.in's `LikeButton`.** phish.in's existing `LikeButton` (MainActivity.kt) is
gated on a signed-in `Session.username` and calls `PhishInApi.like`/`.unlike` with a `Long`
id against a server-side, public `likesCount` — none of that holds for Relisten, which has no
account system, no server-side likes, and a `String` (`uuid`) track id
(`PlayableTrack.id`). Rather than bend `LikeButton`/`Likable`/`PhishInApi` to fit, this adds a
second, Relisten-only store, `LikedTracks` (LikedTracks.kt) — a `SharedPreferences`-backed
`Set<String>` of track ids with a `MutableStateFlow`, `init(context)` called from
`CouchTourApp.onCreate()` alongside `Favorites.init` — and a plain heart `IconButton`
(`LikeTrackButton`) on `RecordingTrackRow`, where phish.in's own `LikeButton` already lives on
its equivalent (`TrackRow`).

Scope was deliberately kept to track-row screens that already hold a `PlayableTrack`, not
extended into `PlayerState`/`NowPlayingScreen`: the issue and ROADMAP.md both flag that
`PlayerState` (PlayerViewModel.kt) carries no track id or liked-state field at all today, so
*neither* backend's likes show up on Now Playing — that gap predates this change and isn't
specific to Relisten. Closing it means adding a track id (and, per backend, its liked state)
to `PlayerState` and reading it from both `LikeButton` and `LikedTracks`, which is real,
separable work; folding it into this pass would have mixed a wiring change with a new
account-free feature. Left as the open ROADMAP.md follow-up it already was.

`LikedTracks`'s shape — a settable/queryable `Set<String>` of track keys — is deliberately the
same as `Favorites`'s, since #12 (cross-backend playlists) is expected to share this storage
layer per ROADMAP.md; #12 can build on it as-is rather than needing rework.
Tested the same way as `Favorites` (`LikedTracksTest.kt`, Robolectric + `ApplicationProvider`):
toggle on/off, and persistence across a fresh `init` on the same context.

**D161 — Local playlists (#12) use Room, not `SharedPreferences`, reversing D160's own
expectation; a stored track needs enough context to be refetched, which is more than a bare
id.** D160 assumed #12 would reuse `LikedTracks`'/`Favorites`' `Set<String>` shape — reasonable
before actually reading what a playlist needs to store. It doesn't hold: a playlist is
*ordered*, holds *structured per-entry data*, and there can be several of them, which is
relational data `SharedPreferences`-as-one-JSON-blob handles by rewriting the whole blob on
every single-track mutation. `PhishInDb` already has exactly this shape of table (`Progress`)
and an established migration pattern, so `local_playlists` (`LocalPlaylistEntity`: id, name,
a denormalised `trackCount`, timestamps) and `local_playlist_tracks`
(`LocalPlaylistTrackEntity`, one row per entry, `MIGRATION_7_8`, `local_playlist_tracks`
`ON DELETE CASCADE`s its playlist) followed that instead (`LocalPlaylist.kt`).

The harder problem `PlayableTrack.id` alone doesn't solve: neither backend has a fetch-track-
by-id endpoint, so playing a stored track later means refetching the show it lives in and
finding the track inside it — the same trick `PlayerViewModel.playTrack()`/`.resume()`
already use for a single track. A `LocalPlaylistTrackEntity` row is that trick's inputs, made
storable: `backend` + `trackId` + `showDate`, plus Relisten-only `artistSlug` (no
fetch-by-slug-less lookup exists) and `recordingId` (a show can have several tapes with
different track splits — null falls back to the default tape, same as `MusicSource.show`).
The rest (`title`, `durationMs`, `venueName`, `artUrl`) is denormalised display data, so the
playlist screen renders without a fetch per row, same tradeoff `Progress` already makes.

Resolution happens at play time, not at add time: `resolveLocalPlaylistTracks`
(`LocalPlaylist.kt`) groups a playlist's stored rows by distinct show/tape, fetches each once
(not once per track), and looks the track up inside the result. A reference that no longer
resolves — deleted show, track dropped from a tape — is skipped rather than failing the whole
playlist, since there's no precedent anywhere else in the app for "a stored reference stopped
resolving" and skip-not-crash matches how a missing recording id already degrades elsewhere
(`RelistenShowWithSources.toShowDetail`'s fallback to the default tape). This mirrors
resuming rather than caching a URL: phish.in URLs aren't guaranteed stable, and every other
resumable queue in the app already refetches rather than trusting a stored one.

A local playlist gets its own `QueueKind.LOCAL_PLAYLIST` / `"local-playlist:"` key prefix
(`Queue.kt`) rather than reusing `QueueKind.PLAYLIST`'s `"playlist:"` — a local id routed
through that kind would hit `PhishInApi.playlist(id)`, which has no such slug. Wired into
`PlayerViewModel.resume()` and `PlaybackService`'s Auto "Continue listening" resume path
(`resumeChildren`) the same way every other kind already is, sharing one
`localPlaylistQueueItems(dao, id)` builder between phone and Auto (D73's contract: the two
must produce identical queues for the same inputs).

One real cross-backend wrinkle: every existing queue (`QueueInfo.artist`) publishes one
artist for the whole queue to the MediaSession external scrobblers read (D50) — true for a
show, a phish.in playlist, or a Relisten tape, but not for a playlist that mixes both. Rather
than widen `QueueInfo` itself, `coreMediaItem` (MediaItems.kt) grew an `artist` param
defaulting to `info.artist` — every existing caller is unaffected — and the new
`localPlaylistTrackItems` is the one caller that passes a real per-track artist through
(`ResolvedLocalTrack.artistName`). This also fixes `Progress.artist` for free: `saveNow()`
(`PlaybackService.kt`) already reads the *currently playing item's* own artist metadata
rather than a queue-wide value, so "Continue listening" groups a mixed playlist's history
entries under whichever artist is actually playing, not a queue-wide guess.

Deliberately out of scope for this pass, to keep it from ballooning into #12 plus half of
phish.in's playlist feature: importing an existing phish.in playlist's tracks into a local
one (there's no write API on phish.in's side to build against either way — playlists there
are still browse-only, per ROADMAP.md's "Not in the app yet"); excerpts
(`startsAtSecond`/`endsAtSecond`, phish.in's own playlists support this — D30 — but the issue
never asked for parity); renaming a playlist after creation; and manually reordering tracks
(adding always appends; removing is supported). None of these are precluded by the schema —
`LocalPlaylistTrackEntity.position` already supports a real reorder, an excerpt pair of
columns would slot in next to it — they're just not built. Also out of scope: browsing local
playlists from Android Auto (resuming one that's already "Continue listening" works, wired
above, but there's no new browse-tree root to pick one from scratch, unlike the phone's
Library screen) — Auto's browse tree already has a documented follow-up to unify
(ROADMAP.md #28), and a third root wasn't worth adding ahead of that.

Tested at three layers: `LocalPlaylistDaoTest.kt` (Room, in-memory db — add/remove
transactions, cascade delete, ordering), `LocalPlaylistResolveTest.kt` (`MockWebServer` per
backend, matching `SearchFanOutTest`'s pattern — a phish.in-only, a Relisten-only, and a
genuinely mixed playlist resolve correctly, and a stale or unreachable reference is skipped,
not thrown), `MigrationTest.kt`'s new v7→v8 section (existing `progress` rows survive
untouched; the migrated db accepts a real playlist write), and `MediaItemsTest.kt`'s new
cases (a mixed playlist's `MediaItem`s carry each track's own artist, not the queue's).

### D162 — the request bound is split by backend, and the daily answer is cached in memory not Room

Home screen "on this date" playlist (#13), sitting on top of favorite artists (#14). Neither
backend has a month/day-across-years query — phish.in's `/shows` only takes `year=` or
`year_range=` (`PhishInApi.showsForPeriod`), Relisten's catalog is a per-year fetch
(`RelistenApi.year`) — so finding matches means fetching shows a period at a time and
filtering client-side on the date string. That makes this a cost problem more than a UI one,
and the cost is lopsided between backends, which is why the two get different bounds instead
of one shared cap:

- **phish.in**: no year bound. The whole archive is under 2,000 shows with audio across ~35
  periods; `phishInRanges` (`OnThisDate.kt`) greedily batches consecutive periods into
  `year_range=` requests capped at 900 shows each — comfortably under the API's
  `per_page=1000` — so covering every Phish year end to end costs about four requests. The
  bound is self-sizing against each period's own `showsWithAudioCount` rather than a
  hardcoded year list, so it keeps holding as the archive grows.
- **Relisten**: `RELISTEN_YEAR_BUDGET` (12) year-fetches total, split evenly across at most
  `MAX_RELISTEN_ARTISTS` (3) favorited artists, most recent years first. Relisten has no
  range endpoint — one request per year per artist — so without a cap, favoriting a handful
  of deep-archive artists would mean dozens of requests on every Home screen visit.
  Favoriting twelve Relisten artists costs exactly what favoriting three does; the extras
  simply don't participate.
- Worst case is about nineteen requests. Run at most once a day: `OnThisDate` wraps
  `showsOnDate` in a one-entry in-memory cache keyed on today's date plus the sorted
  favorited-artist keys, the same shape as `RelistenCatalogSource.cachedArtists`'s in-memory
  cache rather than a new Room table — the answer only changes once a day, and the `progress`
  table has no business holding throwaway catalog data. Process death re-fetches, which is
  the right trade for a once-a-day result.

Wired into `HomeScreen` as a second, independent `loadOnce` (`MainActivity.kt`) keyed on the
date and the favorited artists' keys, separate from `loadArtistsByBackend`'s — it must not
block the screen's first paint on a multi-request fetch. The section (`SectionHeader("On this
date", divided = true)` + a `LazyRow` of `AnniversaryCard`s, geometry matching `ResumeCard`)
renders only inside `loaded()`'s success branch and only when non-empty: no header, spinner,
or error text on a day with no matches, or before any artist is favorited — it's a discovery
extra layered on top of the screen, not something the user asked for, so its absence should
read as nothing rather than as breakage.

`pickAnniversaryShows` reuses `pickRandomShow`'s injectable-`Random` idiom (D36) rather than
inventing a second random-pick pattern for the "random selection" the issue asked for:
shuffle the matches, take a bounded handful (`MAX_ANNIVERSARY_SHOWS`, 8), then sort
date-descending so the row doesn't reshuffle on every recomposition.

Everything lives in pure functions and one suspend orchestrator behind the existing
`MusicSource` seam (`OnThisDate.kt`), the same split `Catalog.kt` already uses for
`mergeArtists`/`pickRandomShow` — `OnThisDateTest.kt` covers the date matching, the range
batching, the Relisten budget split, and the fan-out (including one artist's fetch failing
without sinking the others) with a fake source and no network call.

Out of scope for this pass, left for #22 (the next Couch Tour stop, a related "shows relevant
to favorited artists" query): no shared helper yet between the two, but `showsOnDate`'s shape
— dispatch on backend through `MusicSource`, bound the request count, cache once-a-day — is
worth reusing if #22 needs the same thing.

### D163 — app version is threaded from the release tag on Android, hand-bumped on macOS

#43. Both `BuildConfig.VERSION_NAME` and `MARKETING_VERSION` were static placeholders,
disconnected from the `release_tag` (e.g. `v0.23`) that `build-debug-apk.yml` already used to
name the GitHub Release and its APK asset — displaying either would have shown the same
string forever.

- **Android**: `app/build.gradle.kts`'s `versionName` now reads `-PversionName`
  (`project.findProperty("versionName") as? String ?: "1.0"`), and
  `build-debug-apk.yml`'s "Assemble debug APK" step passes `-PversionName="$RELEASE_TAG"`
  whenever `release_tag` is set, alongside (not instead of) `-PsideInstall=true` when both are
  set — a beta side-install cut with a release tag gets both. Plain pushes and dispatches
  without a tag keep the placeholder. `buildFeatures.buildConfig = true` had to be turned on
  explicitly — AGP 8 disables `BuildConfig` generation by default. Surfaced as a small,
  `onSurfaceVariant`-at-60%-alpha, centered caption (`"Couch Tour ${BuildConfig.VERSION_NAME}"`)
  as the literal last item in `HomeScreen`'s `LazyColumn`, after the Sync section — the most
  out-of-the-way spot without a dedicated Settings screen, which doesn't exist yet.
- **macOS**: no release-tag pipeline exists yet (no macOS CI at all — see CLAUDE.md), so this
  stays manual: `MARKETING_VERSION` in `macos/project.yml` gets bumped by hand alongside each
  notable build, same as before, just now actually looked at. `SyncView` — the closest thing
  to a settings screen today — reads it back at runtime via
  `Bundle.main.infoDictionary?["CFBundleShortVersionString"]` rather than hardcoding a second
  copy of the string in Swift, so a hand-bump to `project.yml` is the only place that needs
  touching. Shown as a `.caption`/`.secondary`, centered `Section` at the bottom of the form.
  Building a whole Settings scene or an "About" panel just for this (both currently
  unscheduled — see ROADMAP.md and #25) was ruled out as more than the issue asked for.
### D164 — the tour is derived from the latest show, not asked for; the unplayed half stays live, not cached

"Next Couch Tour stop" (#22), the last item in the personal-library cluster, sitting on top of
favorite artists (#14) and reusing `showsOnDate`'s shape as D162 flagged: dispatch on backend
through `MusicSource`, bound the request count, cache once a day (`NextStop.kt`). It's a
sibling of `OnThisDate.kt`, not an extension of it — the two problems (anniversary matching,
tour derivation) share no step past the fan-out skeleton, so a shared helper would be an
abstraction bought for two callers with nothing in common.

Neither backend has a "current tour" concept. `PeriodRef`s are years, not tours, so the only
way to find one is to fetch an artist's most recent shows and read `ShowSummary.tourName` off
them — the current tour is whichever one the latest show belongs to. An artist whose latest
show carries no tour name (older or single-show periods often don't) simply doesn't
participate, the same opt-out `showsOnAnniversary` gives a malformed date.

Two periods are fetched per artist, not one: a tour crossing New Year — latest show in
January, the rest of the run the previous December — would otherwise expose only its tail,
and since this picks the *oldest* show in the tour, a missing older half doesn't degrade the
answer, it makes it wrong. `recentPeriods` sorts on `PeriodRef.label` rather than `id` — id is
the year itself on phish.in but an opaque uuid on Relisten (`RelistenYear.toPeriodRef`), while
label is a plain year string on both, including the synthetic `POPULAR_PERIOD_ID` ("Popular"),
which drops out for not parsing as one.

Unlike D162's cache, the "unplayed" half of the answer is deliberately *not* baked into the
daily one: it depends on the `progress` table, which changes the instant a show finishes,
while the catalog fetch only changes once a day. So `NextStop.load` caches `currentTours`'
network result exactly like `OnThisDate.load` does, and `oldestUnplayed` runs fresh on every
recomposition against `ProgressDao.finishedKeys()` — a `Flow`, so finishing a show updates the
row without a screen reload. No migration: a new `@Query` doesn't touch the schema hash,
`version` stays 8.

Matching "have I played this?" across backends needed one small addition to `Queue.kt`:
`recordingShowKey(artistSlug, date)`, a Relisten recording key with its tape id dropped. A
favorited-artist catalog fetch never resolves to a specific tape, so the match has to be "any
tape of that night", not the exact key `ProgressDao` stores against — `showId`/`playedShowIds`
reparse a stored key back down to that identity rather than fuzzy-matching key prefixes.

The request bound is per-backend like D162's Relisten cap, not shared: `MAX_TOUR_ARTISTS` (3)
participate per backend, each costing 1 `periods()` + 2 `shows()` calls — worst case about
twelve requests, cheaper than D162's nineteen since there's no year budget to split.

Rendered as a `SectionHeader` + `RowItem` on Home, between "History" and "On this date" —
not the button the issue's title names. `SurpriseMeButton` is a button because its answer is
computed on tap and is meant to be a surprise; here the answer is already fetched, and hiding
the tour/date/venue behind a tap throws away exactly the context that makes it worth tapping.
Silent when there's nothing to show, same intent as D162's "absence reads as nothing" — but
genuinely silent this time: it branches on the result directly rather than through `loaded()`,
whose null branch emits a spinner.

Tested at one layer: `NextStopTest.kt` covers period selection, tour derivation (including a
New Year run's December half), the cross-backend played-show match, the oldest-unplayed pick
with its tie-break, the fan-out's per-backend cap and failure isolation, and the cache key —
all against a fake `MusicSource`, no network, following `OnThisDateTest.kt`'s pattern.

Deliberately out of scope: a "tour" is inferred from `tourName` matching alone, with no
recency cutoff — an artist whose last tour was years ago still yields a valid answer, which
reads as a feature (there's always a next stop to catch up on) rather than a bug for this
pass.

### D165 — both backends' "Not Part of a Tour" is a sentinel, not a real tour name

Found on a real device within minutes of D164 shipping: favoriting Grateful Dead surfaced
"1994-02-25 · Grateful Dead — Not Part of a Tour" as the next stop, when the band hasn't
toured since 1995. `currentTourShows` (`NextStop.kt`) took `ShowSummary.tourName` at face
value, but confirmed live against both APIs, neither leaves the field blank or null for a
standalone show — they both send the literal string `"Not Part of a Tour"`. Treated as a real
tour name, every such show across an artist's two most recent fetched periods got lumped
together as if they were one tour, so a defunct or lightly-touring artist's oldest untoured
show would win the cross-artist pick every time and quietly crowd out artists that actually
have a current tour.

Fixed by giving the sentinel the same opt-out `currentTourShows` already gave a blank or null
`tourName` (D164): if the most recent show is tagged "Not Part of a Tour", the artist simply
doesn't participate, same as one with no current tour at all. Covered by a new
`NextStopTest.kt` case built from the exact string both APIs return, confirmed by querying
them directly rather than guessing at the shape.

Mike suggested a richer fix in the same conversation: rather than an inactive artist silently
opting out, offer a picker to choose which past tour or era to track for "catching up" on.
Left for a follow-up rather than folded into this pass — it is a real UI addition (where does
it live, how is "tour" enumerated for a picker), not a bug fix, and deserves its own design
pass. Noted in ROADMAP.md.

### D166 — three small desktop player fixes, pulled forward from #25's batch

#25 ("Desktop UI improvements") is explicitly a batch to split from, not a single unit of
work — ROADMAP.md's build order already named three items in it as small and worth pulling
forward ahead of the rest: the scrubber seek-thrash fix, the `skipToNext` disable asymmetry,
and menu-bar `Commands`. This does those three and leaves the rest of #25 (a real Now Playing
view, artwork, volume control, a Settings scene, desktop search, history grouping, the tape
switcher's own Source-picker rework) for later passes.

- **Scrubber** (`MiniPlayerView.swift`): the `Slider`'s `value` binding called `player.seek`
  on every intermediate value, so dragging issued a continuous stream of seeks at
  `AVPlayer`. Now tracked with a local `@State private var dragPositionMs: Double?` and
  `Slider`'s `onEditingChanged` — the displayed position follows the drag locally, and
  `player.seek(toMs:)` fires exactly once, when the drag ends.
- **`skipToNext` disable** (`MiniPlayerView.swift`): `skipToPrevious` was already disabled at
  `currentIndex == 0`; `skipToNext` had no equivalent at the end of the queue. Added
  `.disabled((player.currentIndex ?? -1) >= player.tracks.count - 1)`, the same bounds check
  mirrored in the new menu commands below.
- **Menu-bar `Commands`** (`CouchTourApp.swift`): a new "Playback" `CommandMenu` — Play/Pause
  (bare Space, matching standard macOS media-app convention), Next Track and Previous Track
  (Cmd+Right/Cmd+Left, modified rather than the bare arrow keys browse already uses for list
  navigation, so the two don't collide). Confirmed live: the menu renders in the correct
  standard position (between View and Window), and Next/Previous correctly start disabled
  with no queue loaded.

**Testing limits worth being honest about.** `macos/CouchTour` has no unit test target — this
is UI code verified by building and running, same as `CLAUDE.md` asks for. I was able to
build, install, and drive the app directly (`osascript`/`System Events`, window-scoped
`screencapture` rather than a full-desktop grab — this machine had several personal windows
open), and confirmed the Commands menu live end to end. I could not get a synthetic click via
`System Events` to trigger `ShowDetailView.TrackRow`'s `.onTapGesture` and actually start
playback — clicks that worked fine for `List` row *navigation* (artist → year → show) didn't
register on this particular tap gesture, a pre-existing interaction that isn't part of this
change. That blocked live verification of the scrubber drag behavior and the `skipToNext`
disable state while a queue is actually loaded; both are covered by code review and the same
bounds-check pattern already proven live in the Commands menu, but a real interactive pass —
starting a show and dragging the scrubber, confirming the button disables on the last
track — is worth five minutes on a real device before calling this done.

Also: `macos/scripts/install.sh` picked the first `find` match under DerivedData rather than
the newest, so with more than one stale `CouchTour-*` folder around (routine — `xcodegen
generate` gives the project a fresh identity on every run, so old DerivedData folders pile up
instead of being reused) it could silently install a build from days earlier. Found while
verifying this change — the install script handed back a binary with none of these edits in
it. Fixed to sort by mtime and take the newest.

One live-testing wrinkle, unrelated to the fix itself and not code to "fix": a fresh ad-hoc
signature (which a clean rebuild produces) needs the Keychain to re-authorize
`SyncSession`'s stored token, which blocks app launch behind a password prompt neither an
agent nor a script should be answering. It resolved itself by relaunching a build whose
signature had already been trusted in an earlier session — noted here in case a similarly
inexplicable hang shows up again.

### D167 — a Now Playing inspector, artwork everywhere, and a volume control (#25, player surface)

The second slice pulled from #25's batch (D166 pulled the first three small items). This one
takes the whole player-surface bullet group: a real Now Playing view, artwork in the app and
the system widget, and a volume control. Browse (#17's Source-picker rework, search, history
grouping, venue/city on drill-in) and a Settings scene are left for later slices — #25 is
explicitly meant to be split, not done in one pass.

**Placement: a trailing `.inspector` panel**, not a separate window or a fifth sidebar
section. A window would need its own artwork/transport duplicated and is one more window to
manage; a sidebar section was rejected because `RootView.swift` deliberately builds a fresh
`NavigationStack` per section (Artists → Periods → Shows → Show state must not leak across
sections), so visiting a "Now Playing" section would reset browse's drill-down every time —
exactly the state loss the issue's "see the rest of the show while browsing elsewhere" is
asking to avoid. The inspector needs a toolbar to toggle it, which also delivers #25's "no
toolbar on the detail pane" item as a side effect.

**No transport controls in the panel.** `MiniPlayerView` is always on screen directly below
the inspector whenever a show is loaded (`RootView.swift`), so duplicating play/pause/skip/
scrub a few points above it would be redundant chrome. The panel is artwork, identity, and the
full track queue; `MiniPlayerView` stays the only transport.

**Queue.** `NowPlayingInspector` reads `player.tracks` — the whole show's track list, which
`Player` already holds independent of how much of it is actually loaded into `AVQueuePlayer`
(only a suffix starting at the current index ever is). Grouped by set with the same
first-appearance-order logic `ShowDetailView` already had; that logic moved out to
`TrackGroups.swift` so both views share it rather than duplicating it. Tapping any row calls
`player.seek(toTrack:)`, which rebuilds the queue from that index — the same mechanism
`ShowDetailView`'s track rows already use.

**Artwork.** `ArtworkView` wraps `AsyncImage` with a `music.note` placeholder — needed because
Relisten show summaries carry no art at all, so the placeholder is the common case there, not
an edge case. Used at 36pt in the mini player and 160pt in the inspector. The system Now
Playing widget needed a separate path: `MPNowPlayingInfoCenter`'s `MPMediaItemArtwork` wants an
`NSImage`, not a URL, and `updateNowPlayingInfo()` rebuilds its whole info dictionary on every
track change — including before an async fetch can possibly have completed — so `Player` now
fetches into a cached `NSImage` (`loadArtwork(for:)`, keyed against the URL that requested it so
a stale load can't land after the show has already changed again) and includes it from that
cache rather than fetching inline. This closes D107, which shipped Now Playing metadata
without artwork specifically because this wasn't built yet.

**Volume.** `Player.volume` (0...1) is app-level, not system volume — a `@Published` property
with `didSet` writing through to `queuePlayer.volume` and `UserDefaults` so it survives a
relaunch; `init` also sets `queuePlayer.volume` directly since `didSet` doesn't fire for an
initializer assignment. `toggleMute()` stashes the pre-mute level rather than just zeroing it,
so unmuting restores where it was. Lives in the mini player, not the inspector, so it's
reachable without opening the panel.

**One change outside the strict scope, flagged deliberately.** `ShowDetailView`'s `TrackRow`
used `.contentShape(Rectangle()).onTapGesture`, which D166 found doesn't reliably respond to a
synthetic click via `System Events` — the exact thing that blocked live verification of
anything needing a loaded queue in that pass, and would have blocked this one too, since none
of this PR's changes are observable without one. Switched to `Button(...).buttonStyle(.plain)`,
mirrored in the new `QueueRow`. Three-line change, keyboard- and VoiceOver-accessible either
way, and plausibly unblocks the automated pass — but stands on its own accessibility merit even
if it turns out not to.

**Testing limits, worth being just as honest about as D166 was.** The build succeeds
(`xcodebuild`) and `swift test` stays green (124/124 — CouchTourKit itself is untouched by this
pass; the 94/94 D166 cited has grown since). Live verification hit the exact hang D166 already
documented and moved on from: `xcodegen generate` gives the project a fresh identity, so
`install.sh`'s Release build carries a fresh ad-hoc signature, and this machine's login keychain
holds a real `sync.deviceToken` from an actual paired device (the live sync verification from
D116-D148) — `SyncSession.init` reads it unconditionally via `AppModel`, at launch, before any
UI appears. `sample` confirmed the main thread parked in `SecItemCopyMatching`, and a
`SecurityAgent` process was running — the OS asking to authorize the new signature against that
real credential. Per D166 and CLAUDE.md, that prompt is never one to answer non-interactively;
the process was killed instead, twice, without touching it. This worktree has no
previously-trusted build of its own to fall back to (D166's was a different worktree, a
different DerivedData path), so nothing in this PR was clicked through live — verification here
is code review only: the diff was read start to finish, the build was confirmed clean, and two
real issues were caught and fixed that way (stale artwork surviving a show switch until the new
fetch resolved, and `.navigationTitle` not being guaranteed to render inside an `.inspector`
panel that isn't itself a `NavigationStack` — both described above). A real interactive pass —
toggling the inspector, playing a show, confirming artwork appears in both the panel and
Control Center's Now Playing widget, dragging the volume slider and hearing it — is worth five
minutes on a real device before calling this fully done, the same ask D166 closed with.

**Also found and fixed while exploring this issue, unrelated to the change itself:** Android's
History screen is not actually grouped by artist, contrary to `HistoryView.swift`'s comment and
two lines in ROADMAP.md. `MainActivity.kt`'s `HistoryScreen` is a flat `LazyColumn` over
`ProgressDao.history()`; the grouping DAO methods added for it (`Progress.artists()`,
`Progress.historyFor()`) are real but referenced only by tests, never by any screen. Corrected
in both places rather than left to mislead whichever slice eventually ports "History grouped by
artist" to macOS.

### D168 — the Source-picker rework lands on macOS, plus two inherited Relisten bugs (#25, browse)

The third slice pulled from #25's batch (D166 did the small player fixes; D167 did the whole
player-surface group). This one takes the show-detail items from #25's browse group: the tape
switcher was still a bare `Picker` labeled "Tape" — #17's rework shipped on Android but never
landed here — and `ShowDetailView` dropped the venue/city on drill-in. Search and history
grouping stay out of this slice; search is its own stack macOS has none of (`MusicSource` has
no `search` at all), confirmed as the next desktop slice rather than folded in here, and history
grouping is a fresh design per D167's correction, not a port.

**Two bugs found while porting, fixed in the same pass.** Confirming the port against Android's
`RecordingRef`/`SourceRow` turned up two places macOS's `RelistenSource` mapping had drifted
from fixes Android already made:

- **Blank taper/lineage rendered empty.** Relisten sends `""` rather than omitting these on
  many sources; `RelistenAPI.swift`'s `toRecordingRef()` did `taper ?? (isSoundboard ? ... :
  ...)`, so a blank taper produced an empty row label and a bare "Lineage:" with nothing after
  it. A blank string satisfies Swift's `??` the same way it satisfies Kotlin's `?:` — neither
  language's nil-coalescing treats `""` as absent. Fixed with a small `Optional<String>.nonBlank`
  helper, mirroring Android's `taper?.takeIf { it.isNotBlank() }` (`Relisten.kt:207-217`).
- **`looksLikeMatrix` never ported.** Android's `RecordingRef` has the computed property since
  #17 shipped; macOS's `RecordingRef` (`Catalog.swift`) had all seven of `RecordingRef`'s stored
  fields but not the derived one. Added verbatim, including the doc comment's framing — a
  substring match on free text, a hint for the UI, not a fact, since Relisten has no structured
  matrix flag.

Both are covered in `CouchTourKitTests` now: `CatalogTests` for `looksLikeMatrix` (taper match,
lineage match, case-insensitivity, negative), `RelistenParsingTests` for the blank-taper and
blank-lineage fallbacks, built directly against `RelistenSource`'s own init rather than a new
JSON fixture — the same pattern `testFlattensSetsInIndexOrderAndDropsTracksWithNoMp3Url` already
uses for a constructed edge case.

**Picker: a `.popover`, not a sheet.** The macOS analogue of Android's `ModalBottomSheet`:
anchored to the row that opened it, dismissed by clicking away, lighter than a modal sheet for
what's fundamentally a picker. `SourceRow` carries what Android's does — label, an "SBD" badge,
a "Matrix?" badge (the `?` stays, marking the heuristic), `★ rating · N reviews` when rated,
`Taper:` suppressed when it would just repeat the label, `Lineage:` when present, current source
checkmarked. No client-side sort — Relisten already returns sources ranked by
`avg_rating_weighted` desc (D79), and the current source is pinned first the same way the old
`Picker` built its list.

**Gate fix.** The old gate opened whenever `hasMultipleSources && (!alternates.isEmpty ||
recording != nil)` — the `recording != nil` arm rendered a one-item picker with nothing to pick
on a single-source show. Aligned to Android's `hasMultipleSources && alternates.isNotEmpty()`.

**Position carry across a source switch — the behavioral half of #17 macOS lacked entirely.**
Before this, picking a different source swapped the displayed track list but left the player on
the old source. `switchSource(to:)` captures the pre-switch queue key, reloads, and — only if
that queue key is still what the player has loaded (i.e. this show's queue is what's actually
playing, not just what's being browsed) — restarts on the new source at the same track index and
position via `player.play(detail:startIndex:resumePositionMs:)`, which already took exactly
these parameters. Index is clamped to the new source's track count and read fresh from the
player right before the call (not snapshotted before the network fetch), since tapers split
tracks differently — the same approximation Android's picker documents, not something to imply
is exact.

**`navigationSubtitle`.** `ShowDetailView` now sets `.navigationSubtitle(show.where_)` alongside
`navigationTitle(show.date)`, restoring the venue/city `ShowsView`'s list row already shows.

**Testing.** `swift test` is the real gate here and it's green — 131/131, up from 124 (D167's
count), with both bug fixes covered by tests that failed against the pre-fix code. `xcodebuild`
succeeds; no new source files, so no `xcodegen generate` was needed. Live verification hit the
same environmental wall D166/D167 already documented and moved past — any code change produces a
fresh ad-hoc signature via `install.sh`, and this machine's login keychain holds a real
`sync.deviceToken` from an actual paired device that `SyncSession.init` reads unconditionally at
launch, parking the main thread behind a Keychain re-authorization prompt no agent should answer.
Unlike D167, the cost of that here is smaller: `looksLikeMatrix` and the blank-string fixes are
pure model logic, fully proven by `swift test` independent of the UI. What's unverified live is
the popover's rendering and the position-carry across a real source switch — flagged in the PR
for a manual pass, same as the prior two entries.

### D169 — desktop search: a sidebar section, porting Android's whole stack (#25, D5 walk-back)

The fourth slice pulled from #25's batch (D166 did the small player fixes, D167 the
player-surface group, D168 the show-detail browse group). This one adds the piece ROADMAP.md
carried as an open question until now — search — settled yes and filed as the next slice.
macOS had none of it: `MusicSource` declared only `artists()`/`periods()`/`shows()`/`show()`,
and the desktop MVP's original scoping comment (`Catalog.swift`, D5) explicitly named search
as out of scope alongside login/likes/playlists. Login/likes/playlists stay out; search is now
in.

**Port, not fresh design.** Android's whole stack (`Catalog.kt`'s `MusicSource.search`/
`searchAll`/`SearchHits`, `Api.kt`'s phish.in search, `Relisten.kt`'s search DTOs and the
`song:`/`venue:`-prefixed `PeriodRef` trick) transferred close to verbatim. The one deliberate
omission: `SearchHits.playlists` isn't ported — the desktop MVP has no `Playlist` model or
playlists screen at all (D5), so there's nothing for a playlist hit to land on.

**Placement: a new sidebar section, confirmed with Mike over two other options** (a toolbar
`.searchable` over the Artists browse pane, and a ⌘F palette sheet). Both lost for the same
reason D167 rejected a Now Playing sidebar section: `RootView.swift` deliberately builds a
fresh `NavigationStack` per section so Artists → Periods → Shows → Show drill-down doesn't
leak. But that constraint cuts the opposite way for search than it did for Now Playing — a
toolbar search field would fight the drill-down (what does typing mean three levels into
1977?) and collide with the existing Now Playing toolbar button, and a sheet would need to
dismiss itself before pushing onto whatever browse stack happens to be visible. A sidebar
section is a destination, not a companion, so it gets its own stack the same way
Artists/History/Sync already do.

**A phish.in track hit opens its show, confirmed with Mike over auto-play-on-arrival.**
`ShowDetailView` already shows the full setlist and the track is one click away; auto-play
would need `ShowDetailView` to accept and act on an optional start-track id, one more moving
part for what search's own hit list already makes reachable. A track with no `show_date` is
dropped from the list entirely rather than pushing a `ShowSummary` that can't load.

**Two encoding traps, the actual reason this needed its own request tests.**
- phish.in's `/search/{term}` puts the term in the *path*, and `PhishInAPI.path(_:)`'s
  `URL.appendPathComponent` treats `/` as a separator — a raw slash in the term would silently
  become an extra path segment and 404. `PhishInAPI.search(_:)` bypasses `path(_:)` for this
  one call and builds `percentEncodedPath` by hand, escaping `/` explicitly. The test asserting
  this had to be written against `URLComponents.percentEncodedPath`, not `RequestTests`'
  existing `pathSegments` helper — that helper splits the already percent-*decoded* `URL.path`,
  so it would pass even if the bug were live.
- Relisten's is the opposite shape on purpose (`?q=` query param, not a path segment) —
  `RelistenAPI.path(_:)` returns a bare `URL` here, unlike `PhishInAPI`'s `URLComponents`, so
  `search(_:)` builds its own `URLComponents` to attach the query item.

**A second `navigationDestination(for: ShowSummary.self)` would have been silently wrong.**
`ShowsView` (reached when a song/venue slice hit pushes it) already declares that destination
for its own list. SwiftUI supports only one `navigationDestination` per data type per
`NavigationStack`, no matter how deep the declaring view sits — a second declaration for the
same type doesn't error, it silently drops one of them. `SearchView`'s own direct show links
(the Shows section, track hits) go through a private `SearchDestination` wrapper with its own
declaration instead of sharing `ShowSummary`'s.

**`MockServer` gained host-scoped `enqueue`.** The fan-out test (`SearchFanOutTests`, port of
`SearchFanOutTest.kt`) needs two backends racing concurrently, and the existing `MockServer` is
one global `URLProtocol` with a single FIFO response queue — two concurrent requests would
drain it in a nondeterministic order. `enqueue(_:forHost:)` (default `nil`, matching any
request) lets a test pin a response to a specific mock host without touching any existing
unscoped call.

**`.searchFocused` needs macOS 15; this project's deployment target is 14** (`project.yml`).
⌘F still switches to the Search section on any supported OS via `appModel.selection` — a
`FocusOnRequest` view modifier gates the actual field-focusing behind
`if #available(macOS 15, *)`, so 14 gets the section switch without the auto-focus rather than
failing to build.

**Fixtures copied verbatim, not trimmed fresh.** `check-fixtures.sh` requires every macOS
fixture to byte-match an Android counterpart, and Android already had all four this needed
(`search.json`, `relisten_search.json`, `relisten_song_shows.json`,
`relisten_venue_shows.json`) — copied as-is rather than re-trimmed from a live response.

**Testing.** `swift test` is 155/155, up from 131 (D168's count) — the model layer
(`SearchHits`'s combinators, both backends' DTOs and mapping, the prefix dispatch, both
encoding traps, the fan-out) is fully proven independent of the UI. `xcodebuild` succeeds;
`xcodegen generate` was needed (`SearchView.swift` is a new source file). Live verification hit
the same wall D166-D168 all documented: this machine's login keychain holds a real
`sync.deviceToken` from a paired device, and `SyncSession.init` reads it unconditionally at
launch — any `install.sh` rebuild gets a fresh ad-hoc signature, parking the main thread in
`SecItemCopyMatching` behind a Keychain re-authorization prompt no agent should answer. Nothing
UI-facing in this PR was clicked through live; verification here is `swift test` plus a full
read of the diff. A manual pass is worth five minutes on a real device before calling this
fully done: type a query and watch grouped hits appear, click a song hit and land on that
song's shows, click a venue hit the same way, click a show hit and a track hit and land on the
right show, confirm ⌘F both switches to Search and (on macOS 15+) focuses the field, and
confirm switching sections away and back resets the query as expected.

## Iteration 33 — a macOS beta build, and its icon mirrors Android's badge (D170)

### D170 — `CouchTourBeta`: a second Xcode target, its own sandbox container, and an amber-pill icon like Android's

Mirrors D137 (Android's `sideInstall`): a new `CouchTourBeta` target in `project.yml`, bundle
id `dev.mike.couchtour.mac.beta`, display name "Couch Tour Beta" — installs alongside the
regular app instead of replacing it (`macos/scripts/install-beta.sh`, parallel to
`install.sh`, building the `CouchTourBeta` scheme into `Couch Tour Beta.app`).

**Data isolation turned out to already be free, then got a belt-and-suspenders seam anyway.**
macOS App Sandbox scopes an app's container by bundle id, so prod and beta land in separate
`~/Library/Containers/<bundle-id>/` trees — confirmed empirically
(`dev.mike.couchtour.mac` vs `...mac.beta`), no code required. Added the seam regardless:
`ProgressStore.defaultURL(appSupportDirName:)` and `SystemKeychain(service:)` now take
overridable parameters (defaults unchanged — the regular app's on-disk path and Keychain
service are untouchable, same "names that look wrong and are not" reasoning CLAUDE.md gives
Android's `phishin.db`), and `AppModel.swift` passes the beta-only values behind a
`SWIFT_ACTIVE_COMPILATION_CONDITIONS: BETA` flag set only on the new target. Sandbox isolation
being real doesn't mean it's wise to be the only thing standing between a build config typo
and silently corrupting the real listening history.

**The icon now mirrors Android's badge, reversing D146's call.** D146 gave macOS a diagonal
ribbon (`AppIcon-Beta.appiconset`) because "Android's inline pill wouldn't read as clearly" on
a non-circle-masked mac icon. At Mike's request this iteration replaced it: an amber
(`#FFB71B`) pill with black bold "BETA" text, composited onto the same base art
(`icon_512x512@2x.png` as the 1024px master, ImageMagick, Lanczos-downsampled to all 10
required sizes) in the same position Android's `ic_launcher_beta_foreground.png` uses —
legible down to 128@2x (256px), same trade-off the ribbon always made at 16/32px.

**Live-paired into a new group, separate from prod.** The existing sync pairing (D138-D143:
Mike's real Pixel 9 Pro XL and his Mac Mini) was left untouched. A second, beta-only group now
links this macOS build, Mike's phone's side-installed beta APK, and the `phishin_test`
emulator — all three paired and a sync round-trip confirmed ("Last synced just now" on the
emulator's Sync screen).

**One live mistake, and what it confirmed about `unlink()`:** an accidental tap hit "Unlink
this device" instead of "Add another device" on the macOS beta app mid-session. `unlink()`
(`Sync.swift`) is local-only — `store.clear()`, no server call — so the server-side device row
was untouched (not revoked), but the local Keychain token was gone for good, with no way to
recover it. Rejoining needed a fresh invite code from a device still in the group (Mike's
phone), the same as pairing for the first time — confirming by accident that a lost local
token is unrecoverable by design (D127: the server never re-mints a token for an existing
device id, or a leaked database row plus a guessed id would be enough to impersonate a
device). The orphaned `Mac Mini` row was revoked from the phone's Devices list afterward.

## Iteration 34 — closing out #25: a Settings scene and History filtered by artist (D171)

### D171 — Sync moves into a real Settings scene; History gets a last-played-ordered artist filter

The last two items in #25's batch, both confirmed with Mike before building. This closes #25 —
every UI gap the issue collected (player surface D166-D167, browse D168, search D169, the beta
build D170 was a separate track) is now done.

**Settings: Sync moves in, the sidebar section goes away.** `CouchTourApp.swift` declares a
`Settings` scene holding the same `SyncView` the sidebar used to show — that's the whole change
macOS needs for ⌘, to work; no `Commands` entry required. Sync is the only settings-like surface
this app has (the one other persisted preference, volume, already lives in the mini player,
where it belongs), so nothing was invented to fill out a General tab — the alternative Mike
turned down. Two things a `Settings` scene gets wrong by default if you don't watch for them:
it does **not** inherit the environment a `WindowGroup` sets up, so a view built assuming
`@EnvironmentObject` access would crash on open rather than fail to compile (`SyncView` already
took `syncSession` and `sync` as plain parameters, so this was a check, not a fix); and
`.navigationTitle` isn't guaranteed to render outside a `NavigationStack` — `SyncView`'s
`.navigationTitle("Sync")` came out for the same reason D167 dropped one from `.inspector`. A
bare `Form` in a Settings window also sizes badly without a `.frame(width:)`.

**History stays flat and newest-first; it gains a filter, not sections or a drill-down.**
Mike's call, and the reasoning holds up: History mostly answers "what did I just play", and
both grouping alternatives cost an extra click or a mode switch to get back to that. The filter
is the same shape `SearchView`'s own artist picker already uses — a menu-style `Picker`, shown
only when there's more than one artist, selection falling back to "everything" (not literally
reset) once the artist it names is no longer present, the identical accident-of-key-reuse
behavior `SearchView.resultsList` documents.

**`ProgressStore.artists()`/`historyFor(artist:)` stayed unused on purpose, despite looking
purpose-built for this.** Both exist and are tested — Android-parity groundwork, same as
`Progress.artists()`/`historyFor()` on the Kotlin side, referenced only by tests there too. But
`artists()` orders alphabetically (`ORDER BY artist`), the opposite of what Mike asked for, and
`historyFor(artist:)` would be an unnecessary second query against a list `HistoryView` already
holds in memory. `history()` is already `updatedAt` descending, so a fresh free function,
`historyArtists(_:)` in `ProgressStore.swift`, gets last-played order for free — first
appearance in an already-sorted list is last-played order, no second query, no sort. Neither
existing method was touched or deleted; CLAUDE.md's guidance against removing pre-existing code
that isn't in the way applies here even though it now looks stranger to leave unused than it did
before this landed.

**No shared filter component between `SearchView` and `HistoryView`.** Search filters
`ArtistRef` keyed on backend+id; History filters plain artist strings pulled straight off
`PlaybackProgress`. Two small, differently-typed filters, not one abstraction pretending
they're the same thing — the speculative generality CLAUDE.md's simplicity section warns off.

**Testing.** `swift test` is 159/159, up from 155 (D169's count) — `historyArtists`'s ordering,
dedup-at-most-recent, blank-artist skip (with the row still surviving `history()` itself), and
empty-history cases are all covered independent of the UI; the Settings scene is pure wiring
with nothing to unit-test, covered by the build instead. `xcodebuild` succeeds on both
`CouchTour` and `CouchTourBeta` (they share these source files); no new source files, so no
`xcodegen generate` was needed. Live verification hit the same wall D166-D170 all documented:
this machine's login keychain holds a real `sync.deviceToken` from a paired device, and
`SyncSession.init` reads it unconditionally at launch — any `install.sh` rebuild gets a fresh
ad-hoc signature, parking the main thread in `SecItemCopyMatching` behind a Keychain
re-authorization prompt no agent should answer. Nothing UI-facing in this PR was clicked
through live; verification here is `swift test` plus a full read of the diff. A manual pass is
worth a few minutes on a real device before calling this fully done: ⌘, opens Settings with
pairing/devices/sync-now/the version string all rendering at a sensible window size; Sync is
gone from the sidebar and nothing else there shifted; History's artist picker appears only with
more than one artist, orders most-recently-played first, narrows the list on selection, and
"All artists" restores everything including any blank-artist row.

## Iteration 35 — Continue Listening going stale on macOS after a background sync (D172)

### D172 — `ContinueListeningView`/`HistoryView` reload on `syncSession.lastSyncedAt`, not just on queue change

Reported by Mike as "sync is not actually working — phone and desktop show as synced but
continue listening entries don't match." The sync protocol itself (push/pull, last-write-wins,
D119-D149) was fine; the bug was downstream, on macOS only.

**Root cause: the two SwiftUI views never re-queried after a sync pull.** Android's
`ProgressDao.inProgress()` returns a Room `Flow`, which re-emits automatically on any write to
the `progress` table — including the writes `SyncSession.syncOnce` makes via `progressDao.put()`
when applying pulled rows. `ContinueListeningView`/`HistoryView` have no such mechanism: they
load `rows` once via `.task` on appear, and again `.onChange(of: player.queueKey)` when local
playback moves to a new queue. `AppModel.syncNow()` — fired on launch, on
`didBecomeActiveNotification`, and every 15 minutes (`RootView.swift`) — writes straight to
`ProgressStore` through `SyncSession.sync`, but nothing told either view to look again. A sync
could complete correctly, `lastSyncedAt` would update, and the on-disk row for a show played on
the other device would be exactly right — while the visible list kept showing whatever was
loaded before that sync ran, sometimes since app launch.

**Fix: both views also reload `.onChange(of: appModel.syncSession.lastSyncedAt)`.**
`SyncSession.lastSyncedAt` is already `@Published` and already updated only on a successful sync
round trip, so this is the cheapest correct signal — no new plumbing, no GRDB
`ValueObservation`/`DatabasePublisher` needed for what's otherwise a two-line fix. A real
`ValueObservation` would be the more "correct" reactive-DB approach (and would also catch local
writes this same gap misses, e.g. a resume from `ShowDetail`'s saved-progress banner not
updating an already-open Continue Listening list), but that's a bigger change than this bug
needs; left for later if staleness shows up somewhere `syncSession.lastSyncedAt` doesn't cover.

**Testing.** `swift test`: 159/159, unchanged — this bug was never reachable from
`CouchTourKit`'s own tests, since `ProgressStore`/`SyncSession` are both correct; it lived
entirely in the app-target views, which the package tests don't touch. `xcodebuild` succeeds.
Not verified live against a real paired device — same Keychain-reauth wall D166-D171 hit; a
manual check is worth doing: play something on the phone, let the Mac's next sync tick (or
foreground it) go by without touching playback locally, and confirm Continue Listening picks up
the new row without switching sidebar sections.

## Iteration 36 — a failing sync used to say nothing about it (D173)

### D173 — `SyncSession.lastError` on both platforms; every previously-silent failure path now sets it

D172 fixed the UI staleness Mike reported, but the underlying report — "sync is not actually
working" — turned out to be a second, real bug once he checked: the phone's Settings showed
"nothing linked" while the Mac still listed the phone as a paired device. The phone's local
token had gone bad (most likely an app reinstall/data clear, or a prior 401 that
auto-`unlink()`ed it) and `sync()` is a documented no-op when unpaired — `guard let token = ...
else { return }` / `val token = store.deviceToken ?: return`. It had been silently doing
nothing on every launch, foreground, 15-minute timer, and debounced push, with zero indication
anywhere. Re-pairing the phone was the actual fix; this decision is about making the *next*
version of this failure visible instead of silent.

**Every sync failure path used to swallow its error.** `AppModel.syncNow()` is `Task { try?
await syncSession.sync(progressStore) }`; Android's periodic worker, debounced push, and "Sync
now" button all wrap `SyncSession.sync(...)` in `runCatching`/`try`/`Result.retry()` with
nothing surfaced to the UI. An auto-unlink on a 401 is even quieter — `unlink()` runs and
`paired` flips to false with no explanation at all for why. None of these paths were bugs
exactly (background sync failing shouldn't crash anything, and `Result.retry()` is the right
WorkManager behavior), but stacked together they meant a device could stop syncing entirely and
never say so — exactly what happened here.

**Fix: `SyncSession` (both platforms) gets a `lastError: String?`/`StateFlow<String?>`,** set
inside `sync()`/`sync(_:)` itself rather than by each caller, so every existing call site — the
periodic job, the debounce, the manual button, an auto-unlink — gets it for free with no changes
to their own error handling:
- A full round trip succeeding clears it.
- The `unauthorized` branch now also sets an explanatory message before calling `unlink()`, so
  the moment a device gets kicked back to "not paired," it says why instead of just changing
  state silently.
- The `gone` branch is unchanged (D126's transparent full-resync retry isn't a user-facing
  failure).
- A catch-all beyond `SyncException` was added on both platforms, because a plain network
  failure (no connectivity, DNS, timeout) was never a `SyncException` and was escaping `sync()`
  entirely uncaught before this — on macOS in particular, `AppModel.syncNow()`'s `try?` meant
  this class of failure left literally no trace anywhere.
- `CancellationException`/`CancellationError` is explicitly re-thrown untouched before the
  catch-all runs, on both platforms — a debounced push superseding an in-flight one cancels the
  coroutine/Task it's running in, and that's routine, not a failure; recording it as
  `lastError` would flash a false "sync failed" banner on every rapid track change.
- `clearError()` lets a deliberate, unrelated state change (the user tapping "unlink"
  themselves) drop a stale message rather than leaving an old "was revoked" explanation on
  screen after an intentional action.

**UI: both Settings/Sync screens show `lastError` unconditionally, not nested inside "if
paired."** The auto-unlink case is exactly why — `paired` flips to false in the same beat the
error is set, so scoping the message to the paired section would erase the explanation at the
one moment it's needed. Android's `SyncScreen` and macOS's `SyncView` both show it as a plain
red-styled `Text` right below the intro copy.

**Testing.** Both platforms already had a `SyncSession`/`SyncTokenStore` test suite
(`SyncSessionTest`/`SyncTokenStoreTest` on Android, `SyncSessionTests`/`SyncTokenStoreTests` on
macOS, both against a real local mock server) — corrected here from an earlier draft of this
entry that claimed Android had none. Four matching cases were added to each (an unauthorized
response sets an error, a server error sets `lastError` and rethrows, a successful sync clears a
prior error, `clearError()` resets it). Android: `testDebugUnitTest` green (JAVA_HOME pointed at
Android Studio's bundled JDK, per CLAUDE.md). macOS: `swift test` 163/163, up from 159;
`xcodebuild` succeeds. Not verified live — same Keychain-reauth wall as D172; the one thing worth
confirming by hand is the scenario that motivated this: unlink a device from the *other* one's
Devices list, then check that the now-unauthorized device's next sync attempt shows the
"unlinked" message instead of just silently going back to "not paired."

## Iteration 37 — the other way a device silently drops out of sync (D174)

### D174 — `SyncTokenStore.recoveredFromReset` surfaces an Android Keystore reset via `lastError`, not just a `Log.w`

Mike asked whether he'd have to re-pair on every future update, and whether that's avoidable.
Answering that required actually finding out how his phone lost its token in the first place —
D173 made *a* silent failure visible, but didn't explain *this* one, since a bad-token 401 and a
locally-wiped token look identical from the server's side (both just present as "unpaired").

**A normal update shouldn't do this.** `applicationId` and `versionCode` (static `1`, bumped
only by suffix for the beta variant, D137) are stable across builds, and the debug signing key
is restored from a repo secret specifically so every CI build signs identically (the comment on
"Restore debug keystore" in `build-debug-apk.yml` documents the exact failure — "App not
installed" — this already fixed once). A same-key, same-package APK installed over itself is an
update, not a reinstall, and Android preserves app data across those. So routine releases are not
the mechanism — but Android's Keystore-backed `EncryptedSharedPreferences` *can* independently go
bad (an OS-level key-material invalidation, unrelated to anything this app does), and
`SyncTokenStore`'s existing fallback for that — inherited from `TokenStore`'s established
pattern, not new here — silently deletes and recreates the prefs file the moment that happens:

```kotlin
private val prefs: SharedPreferences? = open(context) ?: run {
    context.deleteSharedPreferences(SYNC_PREFS)
    open(context)
}
```

That delete-and-recreate is the right recovery — the alternative is a permanently undecryptable
file and a device stuck in memory-only mode forever, worse than losing one token — but until now
it was invisible: a `Log.w("SyncTokenStore", "Encrypted prefs unavailable...")` nobody would
ever see, then a silently empty store. This is very likely the actual mechanism behind "the
phone shows nothing linked": not something tied to updating per se, but a Keystore hiccup that
happened to get noticed after one.

**Fix: `SyncTokenStore.recoveredFromReset` is `true` whenever this fallback fired**, and
`SyncSession.init` checks it and sets `lastError` (D173's mechanism, for free) to an explanation
distinct from the 401 message — this is a local storage event, not a revoked pairing. Doesn't
prevent the underlying Keystore failure (that's outside this app's control) or guarantee it
won't recur, but the next time it happens, Mike sees why on the very next launch instead of
discovering it days or weeks later by noticing Continue Listening had quietly stopped merging.

Deliberately Android-only — macOS stores the token in Keychain via `SystemKeychain`, not an
encrypted-prefs file with this specific delete-and-recreate shape, and Keychain access failures
there manifest as the interactive re-authorization prompt D147/D166-D172 already documented, not
silent data loss.

**Testing.** `testDebugUnitTest` green. No test added for the fallback path itself — `open()` is
private and forcing a real `EncryptedSharedPreferences.create` failure isn't something
Robolectric's Keystore shadow can easily simulate, and neither the pre-existing `SyncTokenStore`
nor `TokenStore` tests exercise that branch either, so this doesn't leave a new gap relative to
established coverage.

## Iteration 38 — a 30+ second Android resume traced to one un-parallelized fetch (D175)

### D175 — `resolveLocalPlaylistTracks` fetches distinct shows/tapes concurrently, not one at a time

Mike reported resuming playback on Android taking 30+ seconds even on good wifi, much slower
than phish.in itself or any other media app. Tracing the resume path (`PlayerViewModel.resume`
→ `playShow`/`playPlaylist`/`playRecording` → `start()` → Media3) showed the ordinary
single-show/playlist/recording resume does exactly one API call before `ExoPlayer.prepare()`,
and a direct timing check against phish.in's own API put a single `show()` fetch under 200ms —
neither explains a 30-second stall on its own.

The actual mechanism was `resolveLocalPlaylistTracks` (`LocalPlaylist.kt`), the function that
rebuilds a local playlist's (D161, #12) queue by refetching every distinct show/tape its tracks
belong to, since neither backend has a fetch-by-id endpoint. It used Kotlin's stdlib
`Iterable<K>.associateWith { suspendingSelector }` — which is a `for` loop that awaits each
call before starting the next, not a fan-out. A "favorites across years" playlist spanning N
distinct shows paid N *sequential* HTTP round trips — at roughly half a second apiece including
TLS and JSON parsing, 30–60 distinct shows reproduces a 30+ second stall purely from
serialization, independent of bandwidth. This is exactly the kind of playlist the "good wifi,
still 30+ seconds" report describes, and it's also why the comparison to phish.in itself and to
other media apps felt so stark — neither has an equivalent cross-show mixtape feature to be slow
at.

**Fix:** both fan-outs (the phish.in per-date fetch and the Relisten per-show-per-tape fetch)
now use `coroutineScope { refs.map { async { ... } }.awaitAll() }` instead of `associateWith`,
so all distinct shows/tapes resolve in parallel and the wall-clock cost is one round trip, not N.
The two backends still run as two sequential *stages* (Relisten needs its artist list resolved
before it can look up shows), but within each stage every distinct show/tape fetches
concurrently.

**Testing.** Added `` `a playlist spanning several shows resolves each show correctly when
fetched concurrently` `` to `LocalPlaylistResolveTest.kt`, using a custom `MockWebServer`
`Dispatcher` that routes by request path rather than enqueue order — proving each show still
resolves to its own track regardless of what order the now-concurrent requests actually arrive
in, which a plain FIFO `enqueue()` sequence couldn't have verified once the fetches stopped
being sequential. `testDebugUnitTest` green, 327 tests.

## Iteration 39 — D175 didn't explain Mike's actual case, so instrument before guessing again (D176)

### D176 — Timing/connection-reuse logging on `PhishInApi`/`RelistenApi`, not a speculative fix

D175 fixed a real bug, but Mike clarified his 30+ second resume wasn't a local (mixtape)
playlist — it was resuming a recent, ordinary play. Re-tracing that path found nothing
provably broken: `PlayerViewModel.resume()` makes exactly one `PhishInApi.show()`/`playlist()`
call for `QueueKind.SHOW`/`PLAYLIST` (two sequential calls for `RECORDING`, needing the artist
list before the tape), `MediaItems.kt`'s `showTrackItems`/`playlistTrackItems`/
`recordingTrackItems` are pure in-memory mappers with no per-track fetches, `PlaybackService`
does nothing blocking in `onCreate`, and a direct `curl` against phish.in's `/shows/{date}`
endpoint returned under 200ms every time.

**The one candidate that fits "30+ seconds, good wifi" and isn't ruled out:** both
`PhishInApi` and `RelistenApi` (`Api.kt`, `Relisten.kt`) hold a process-lifetime `OkHttpClient`
with a 30s read timeout, OkHttp's default connection pool, and no ping/health-check. If the
process sits backgrounded a while (screen off, radio doze, a router/carrier NAT re-mapping an
idle socket) — exactly the moment before someone taps "resume a recent play" — the pooled
keep-alive connection can go silently dead. The next request writes fine but never gets a
response, and nothing notices until the 30s read timeout fires; the retried request then
succeeds in the same ~100-200ms `curl` already showed. That upper bound landing right at
"30+ seconds" is what makes this the leading theory over anything else checked.

Rather than tune timeouts against a guess, add just enough logging to confirm or kill it on
Mike's own device next time it happens: `ApiTiming.kt`'s `TimingEventListener` (attached to
both clients via `eventListenerFactory`) logs each call's connection-acquired timing and
whether the connection was reused or freshly opened, plus elapsed time on completion/failure —
the reused-without-a-fresh-connect signal is the direct proof of a stale pooled socket.
`PlayerViewModel.resume()` also now logs its own elapsed time and, for the first time, logs
failures instead of silently swallowing them via `runCatching` — and `start()` logs when a tap
arrives before `MediaController` has finished connecting (a separate, real gap found during
this pass: that tap is currently dropped with no feedback and no retry).

**Testing.** The `TimingEventListener`'s `Log.d`/`Log.w` calls run inside `ApiRequestTest`,
`RelistenRequestTest`, and `SearchFanOutTest` — none of which use Robolectric, so they hit the
unmocked `android.util.Log` stub, which throws rather than logging. Wrapped only those calls in
`runCatching` (real device logging is unaffected; `Log` never throws there) rather than adding
Robolectric to tests that were deliberately kept lightweight. No new tests — this is
diagnostic-only and changes no resume behavior. `testDebugUnitTest` green, 327 tests
(unchanged).

## Iteration 40 — Space play/pause never fired (#84, D177)

### D177 — bare Space is an `NSEvent` monitor, not a SwiftUI `keyboardShortcut`

D166 wired Play/Pause to `.keyboardShortcut(.space, modifiers: [])` on the Playback
`CommandMenu`, matching Music/Podcasts. That shortcut never actually arrived at the action
once a `List` had focus — which is the app's usual state (sidebar, artists, shows, history).
SwiftUI delivers Command-modified shortcuts fine (⌘→ / ⌘← still work); it does not deliver
bare Space, because AppKit treats that key as "activate the focused control." Mike filed it
as a high-priority bug (#84).

Fix is a local `NSEvent` keyDown monitor on `Player` (`SpacePlaybackHotkey.swift`): swallow
bare Space and call `togglePlayPause()`, except when the first-responder chain is an
`NSTextView`/`NSTextField` (Search, Settings, playlist naming) or an AppKit modal is up
(those still need Space for the default button). Held-Space repeats are swallowed without
toggling, so holding the key doesn't stutter. The menu item stays, without a
`.keyboardShortcut(.space)` — leaving that in place would steal Space from Search even after
the monitor declined to handle it.

**Testing.** `macos/CouchTour` still has no unit test target; this is the same UI-code
constraint D166 documented. Policy is a small pure helper (`isBareSpace` /
`isEditingText` / `shouldHandle`) so a later app-target test can pin it without spinning up
`AVQueuePlayer`. Verified by compiling the app target.

## Iteration 41 — Persistent toggle to skip filler tracks (#49, D178)

### D178 — Filler track detection and playback queue filtering on Android and macOS

Neither phish.in nor Relisten exposes a structured segment-type flag, so filler tracks (intro,
outro, tuning, stage banter, chatter, crowd noise, announcements, encore break) are identified via
keyword and compound title heuristics (`isFillerTrack(title)` in Kotlin and Swift CouchTourKit).
Real music tracks — including Grateful Dead's "Drums" and "Space", Phish's "Divided Sky", "The
Curtain With", "Tweezer Reprise", etc. — are preserved.

**Queue filtering behavior:**
- Off by default (`skipFiller = false`).
- Stored persistently via `SharedPreferences` on Android (`PlaybackSettings.kt`) and `UserDefaults`
  on macOS (`PlaybackSettings.swift`).
- When enabled, filler tracks are omitted during playback queue construction (`MediaItems.kt` /
  `PlayerViewModel.kt` on Android, `Player.swift` / `filterPlaybackTracks` on macOS).
- If starting playback from index 0 and track 0 is filler (e.g. an "Intro" or "Tuning" track),
  playback automatically advances to the first non-filler track.
- If the user explicitly taps a filler track from the show track list, that track is retained at the
  start index so it plays immediately, while subsequent filler tracks in the queue are skipped.
- Browse UI and setlists continue to display all tracks normally.

**UI Controls:**
- Android: Toggle switch under a new "Playback" section on `HomeScreen`.
- macOS: Toggle switch under a new "Playback" tab in `Settings` (⌘,), and under the `Playback`
  menu bar menu.

**Testing:**
- Swift (`CouchTourKitTests`): `FillerTrackTests.swift` testing keyword heuristics and playback
  filtering; 210/210 tests passing.
- macOS app target (`xcodebuild`): Debug and Beta schemes built and verified.
- Android (`FillerTracksTest.kt`): unit tests for title heuristics, queue filtering, and
  preference persistence; 332/332 tests passing.

## Iteration 42 — hardware play/pause still went to Spotify (D179)

### D179 — macOS media keys follow `playbackState`, not whoever is making sound

D107 registered `MPRemoteCommandCenter.shared()` and published `nowPlayingInfo`, and the
Control Center widget's pause button did pause Couch Tour when that widget was showing us.
The keyboard play/pause key (F8 / the dedicated media key) is a different path: macOS
routes it to whichever app last set `MPNowPlayingInfoCenter.playbackState = .playing`.
Spotify sets that even while paused in the background. We never set `playbackState` at
all — on macOS the system does not infer it from `AVPlayer` the way iOS does — so the
key kept going to Spotify while Couch Tour was the thing actually playing. (`MPNowPlayingSession`
looks like the right API for this and is what iOS 16+ uses; it is unavailable on macOS.)

Fix (`Player.swift`): set `playbackState` to `.playing` / `.paused` / `.stopped` next to
the existing metadata, and re-assert `.playing` whenever we start or resume so we take
the slot back if Spotify held it. Remote commands stay on `.shared()`. Space (#84 / D177)
is unchanged — that key never goes through Now Playing.

**Testing.** Same app-target constraint as D166/D177. Verified by compiling and installing
Couch Tour Beta; confirming the media key pauses Couch Tour while Spotify is open in the
background is the live check.

## Iteration 43 — Build version visibility on main UI and Settings (D181)

### D181 — Surfacing build version on macOS and Android main UI and Settings

Previously, build version information was only visible after scrolling to the bottom of the Home screen on Android or under the Sync tab in Settings on macOS.

**1. Main UI Visibility:**
- **Android**: `HomeScreen`'s top title header now displays `BuildConfig.VERSION_NAME` alongside the app title ("Couch Tour").
- **macOS**: `RootView`'s sidebar displays `Bundle.main.appVersionString` (e.g. "Couch Tour 0.1" / "Couch Tour Beta 0.1") in a `.safeAreaInset(edge: .bottom)` footer.

**2. Settings Visibility:**
- **Android**: `SyncScreen` (and the existing `HomeScreen` settings section) includes the centered version footer.
- **macOS**: All tabs in the `Settings` window (`PlaybackSettingsView`, `AccountView`, `SyncView`) consistently include the version footer at the bottom.

**Testing:**
- Swift unit tests (`swift test`): 210/210 passing.
- macOS Xcode targets (`CouchTour` and `CouchTourBeta`): debug builds succeeded.
- Android unit tests (`./gradlew testDebugUnitTest`): 332/332 passing.

## Iteration 44 — Visual indicators for listened tracks and completed shows (D182)

### D182 — Tracking listened tracks and completed shows

Per Issue #83:
- **Track Listened State**: A track is considered "listened to" when playback reaches $\ge 90\%$ of total duration. Track IDs are persisted in `SharedPreferences` via `ListenedTracks.kt` and surfaced across the app as a subtle checkmark (`Icons.Default.Check`) next to the track number or title in `TrackRow`, `RecordingTrackRow`, search track results, local playlists, and liked tracks.
- **Show Completion**: A show is marked `finished = true` in `Progress` when ExoPlayer reaches `STATE_ENDED` or when the last non-filler track in the queue finishes ($\ge 90\%$). Completed shows display a completed checkmark / "DONE" badge across show lists, headers, search results, liked shows, and anniversary cards.

**Testing:**
- Android unit tests (`ListenedTracksTest.kt`, `ProgressDaoTest.kt`, `MigrationTest.kt`, full suite): 334/334 passing.
- macOS unit tests (`swift test`): 210/210 passing.

## Iteration 45 — Desktop personal-library and account parity (#56–#59, D180)

Closing out the personal-library and account parity cluster on macOS ([DESKTOP-PARITY-PLAN.md](DESKTOP-PARITY-PLAN.md)):

### D180 — Cross-backend local playlists on macOS with concurrent track resolution (#59)

- **#59 Local playlists**: Adds `local_playlists` and `local_playlist_tracks` GRDB tables to `phishin.db` via `LocalPlaylistStore(sharing:)`.
- **Queue Key**: Adds `.localPlaylist` (`"local-playlist:<uuid>"`) `QueueKind`, maintaining byte-identical parity with Android.
- **Resolution**: `resolveLocalPlaylistTracks` groups stored track references by distinct show/tape and resolves distinct shows concurrently (D175 parity), skipping unresolvable references cleanly.
- **UI**: Added a "Playlists" sidebar section, playlist detail view (play-from-here, remove, delete), and "Add to Playlist" popover on track rows.

**Testing:**
- `swift test`: 210/210 passing.
- `xcodebuild`: Both `CouchTour` and `CouchTourBeta` debug schemes succeeded.

## Iteration 46 — Like button on the Now Playing surface (#63, D183)

### D183 — Track likes surfaced directly on Now Playing and MiniPlayer

Per Issue #63, liking a track previously required being on a track-row list screen that directly held a `Track` or `PlayableTrack`. `PlayerState` on Android carried no track ID, backend discriminator, or like metadata.

**Android:**
- `MediaItems.kt`: `coreMediaItem` attaches `track_id`, `backend`, `liked`, and `likes_count` into `MediaMetadata.extras` bundles across `mediaItem`, `recordingMediaItem`, and `localPlaylistTrackItems`.
- `PlaybackService.kt`: `Keys` includes `BACKEND`, `TRACK_ID`, `LIKED`, `LIKES_COUNT`, and exposes them in `Keys.ALL` for Cast / session propagation.
- `PlayerViewModel.kt`: `PlayerState` includes `trackId: String?`, `backend: String?`, `likedByUser: Boolean`, `likesCount: Int`, read from current media item metadata in `refresh()`.
- `MainActivity.kt`: `LikeButton` (phish.in account-gated with count) and `LikeTrackButton` (Relisten local like) exposed internally and embedded in `MiniPlayer`.
- `NowPlaying.kt`: `NowPlayingScreen` displays `LikeButton` or `LikeTrackButton` inline with track/show title metadata.

**macOS:**
- `NowPlayingInspector.swift`: Displays `TrackLikeButton` for active `player.currentTrack` in the inspector header.
- `MiniPlayerView.swift`: Displays `TrackLikeButton` directly in the persistent transport bar alongside track/show titles.
- As requested, `macos/CouchTour/Player.swift` was left untouched.

**Testing:**
- Android unit tests (`./gradlew testDebugUnitTest`): 336/336 tests passing (including new like metadata extras tests in `MediaItemsTest.kt`).
- macOS Swift package tests (`swift test`): 210/210 tests passing.
- macOS app build (`xcodebuild`): Build succeeded.

## Iteration 47 — Local playlist editing: rename & manual reorder (#69, D184)

### D184 — Local playlist management (rename and track reordering)

Follow-up to #12 and #59: Local playlists originally shipped with append and remove only.

**Android:**
- `LocalPlaylist.kt`: Added `renamePlaylist`, `updateTrackPosition`, and `reorderTracks` to `LocalPlaylistDao`. `reorderTracks` updates track `position` indices sequentially in a single `@Transaction` and updates `updatedAt`.
- `PlayerViewModel.kt`: Added `renameLocalPlaylist`, `reorderLocalPlaylistTracks`, and `moveLocalPlaylistTrack`.
- `MainActivity.kt`: Added `RenamePlaylistDialog`, a rename button in `LocalPlaylistScreen`'s header, and Move Up (`Icons.Default.KeyboardArrowUp`) / Move Down (`Icons.Default.KeyboardArrowDown`) buttons on each playlist track row.
- `LocalPlaylistDaoTest.kt`: Unit tests for `renamePlaylist` and `reorderTracks`.

**macOS:**
- `LocalPlaylist.swift`: Added `renamePlaylist(id:name:now:)` and `reorderTracks(playlistId:orderedRowIds:now:)` to `LocalPlaylistStore`.
- `LocalPlaylistTests.swift`: Unit tests for `renamePlaylist` and `reorderTracks`.
- `LocalPlaylistView.swift`: Added Rename button in toolbar (`RenamePlaylistSheet`), drag-and-drop `.onMove` reordering, Move Up/Down buttons, and row context menus.

## Iteration 48 — Live refresh for Sync device list (#64, D185)

### D185 — Polling & manual refresh for paired sync device list

Per Issue #64, both `SyncView.swift` (macOS) and `SyncScreen` (Android) previously only fetched `devices()` on initial mount or upon local pairing changes. When another device joined or was revoked elsewhere, the device list did not update until the screen was closed and reopened.

**Android:**
- `MainActivity.kt`: Added a periodic polling `LaunchedEffect(paired)` that triggers a refresh every 5 seconds while paired and viewing `SyncScreen`. Added a manual refresh `IconButton` (`Icons.Default.Refresh`) in the Devices section header.

**macOS:**
- `SyncView.swift`: Switched `.task` to `.task(id: syncSession.paired)` with a 5-second polling loop while paired and active. Added an explicit refresh button (`Image(systemName: "arrow.clockwise")`) in the Devices section header.

**Testing:**
- Android unit tests (`./gradlew testDebugUnitTest`): 338/338 tests passing.
- macOS Swift package tests (`swift test`): 212/212 tests passing.
- macOS app target build (`xcodebuild`): Build succeeded.

## Iteration 49 — Unified Android Auto browse hierarchy (#28, D186)

### D186 — Unified artist catalog on Android Auto

Per Issue #28 and [MULTI-ARTIST-PLAN.md](MULTI-ARTIST-PLAN.md), Android Auto previously separated Phish into a top-level "Years" root while "Artists" only listed Relisten bands.

- `Catalog.kt`: Made `loadArtistsByBackend()` an `internal suspend fun` shared between `MainActivity.kt` and `PlaybackService.kt`.
- `PlaybackService.kt`:
  - `onCreate()` initializes `Favorites.init(this)` so favorite keys are ready for head-unit browsing.
  - `rootChildren()` exposes "Artists" as the single unified catalog root alongside "Continue Listening".
  - `artistsChildren()` loads the merged artist catalog (`loadArtistsByBackend()` + `mergeArtists`), pinning Phish and favorited artists first, followed by Relisten artists sorted by show count.
  - `artistPeriodsChildren`, `artistShowsChildren`, and `recordingChildren` route Phish uniformly through `BrowseNode.Artist("phishin", "phish")` and `PhishInSource`.
  - Legacy `BrowseNode.Years` nodes remain parseable for backwards compatibility with stale or cached head-unit IDs.
- `BrowseTest.kt`: Added tests verifying roundtrip parsing of unified `BrowseNode.Artist("phishin", "phish")` and child nodes.

**Testing:**
- Android unit tests (`./gradlew testDebugUnitTest`): 338/338 tests passing.
- macOS Swift package tests (`swift test`): 212/212 tests passing.
- macOS app target build (`xcodebuild`): Build succeeded.

## Iteration 50 — FLAC Streaming & Post-Show Tour Stop Prompt (#27, #85, D187-D188)

### D187 — FLAC streaming support with Cast MP3 fallback (#27)

Per Issue #27, Relisten / archive.org sources often provide lossless FLAC streams via `flac_url`.

- **Lossless Stream Resolution:**
  - `RelistenSourceTrack` parses `flac_url` (nullable) alongside `mp3_url` in both Kotlin (`Relisten.kt`) and Swift (`RelistenAPI.swift`), propagating it through `PlayableTrack` (`Catalog.kt`, `Catalog.swift`).
  - Local playback on Android (`MediaItems.kt`) configures ExoPlayer `MediaItem` with `flacUrl` and `MimeTypes.AUDIO_FLAC` when present, retaining `mp3_url` in extras.
  - Local and AirPlay playback on macOS (`Player.swift`) passes `flacUrl` to `AVPlayerItem` for AVFoundation streaming.
- **Intelligent Cast Fallback:**
  - Google Cast hardware/receivers may fail on raw FLAC audio streams. In `Cast.kt` (`CastItemConverter`), media items with `flac_url` are transparently rewritten to stream `mp3_url` (`MimeTypes.AUDIO_MPEG`) to the receiver, preserving the lossless FLAC URL in `customData` so it can be restored when playback returns to local.

### D188 — Post-show next tour stop prompt (#85)

Per Issue #85 and the resolved decision in `ROADMAP.md` that playback must stop at the end of a show's encore rather than automatically rolling into an unrequested show, users now receive an interactive prompt to continue to the next consecutive stop on tour.

- **Tour Stop Resolution:**
  - Pure function `resolveNextConsecutiveShow(currentDate:tourName:candidateShows:)` determines the next chronological show in the same tour (or falls back to the next chronological show overall if the tour has completed or is untoured).
  - Suspend/async helper `findNextTourStop(artist:currentDate:tourName:)` fetches surrounding period shows and resolves the next stop (`NextStop.kt`, `NextStop.swift`).
- **Player & Surface Integration:**
  - Android (`PlayerViewModel.kt`): Observes queue completion (`Player.STATE_ENDED`) and resolves the next tour stop into `postShowPrompt: StateFlow<ShowSummary?>`. Rendered as an interactive card in `NowPlayingScreen` (`NowPlaying.kt`) and `MiniPlayer` (`MainActivity.kt`) with "Play" and "Dismiss" actions.
  - macOS (`Player.swift`): On queue draining in `currentItemDidChange()`, queries `findNextTourStop` and updates `@Published var postShowPrompt: ShowSummary?`. Rendered as an interactive banner in `NowPlayingInspector.swift` and `MiniPlayerView.swift`.

### D189 — Audio quality indicators in Now Playing surfaces & Source Selector quality badges (#27 follow-up)

Following up on FLAC streaming support (#27), users need clarity on whether lossless FLAC or progressive MP3 audio is currently playing, as well as the best available audio quality tier for each recording/source in multi-source tape pickers.

- **Data Models:**
  - `RecordingRef` on both Android (`Catalog.kt`) and macOS (`Catalog.swift`) carries `hasFlac: Boolean`/`Bool = false`.
  - `RelistenSource.toRecordingRef()` (`Relisten.kt`, `RelistenAPI.swift`) inspects track metadata across source sets (`sets.any { ... flacUrl.isNotBlank() }`) to populate `hasFlac`.
  - `PlayerState` on Android (`PlayerViewModel.kt`) provides `audioFormat` (`"FLAC"` vs `"MP3"`) and `isFlac: Boolean`.
- **UI Surfaces:**
  - **Now Playing & MiniPlayer**:
    - Android (`NowPlaying.kt`, `MainActivity.kt`): `AudioQualityBadge` renders format badge ("FLAC" in secondary container, "MP3" in surface variant) in `NowPlayingScreen` and `MiniPlayer`.
    - macOS (`NowPlayingInspector.swift`, `MiniPlayerView.swift`): Renders green "FLAC" badge or secondary "MP3" badge in the inspector and mini player bar.
  - **Source / Tape Selectors**:
    - Android (`MainActivity.kt`): `SourceRow` inside `SourcePicker` bottom sheet and `recordingLabel` display a "FLAC" or "MP3" badge alongside "SBD" / "Matrix?".
    - macOS (`ShowDetailView.swift`): `sourceRow` and `SourceRow` inside `SourcePicker` popover display "FLAC" or "MP3" badge next to source labels.

**Testing:**
- Android unit tests (`./gradlew testDebugUnitTest`): 344/344 tests passing.
- macOS Swift package tests (`swift test`): 216/216 tests passing.
- macOS app target build (`xcodebuild`): Build succeeded (`CouchTour`).

## Iteration 51 — Next Couch Tour Stop Tour Picker for Defunct Artists (#68, D190)

### D190 — Next Couch Tour Stop Tour Picker for Defunct Artists (#68)

For defunct or non-touring artists (such as the Grateful Dead), the latest shows in the archive are often marked with the sentinel `"Not Part of a Tour"` rather than belonging to an active touring cycle. Previously, such artists either opted out of the Next Couch Tour Stop shelf entirely or had untoured standalone shows fall back arbitrarily.

To give users full control over which era, tour, or year they want to track on the Next Couch Tour Stop shelf for any artist:

- **Schema Migration v9:**
  - **Android (Room):** `MIGRATION_8_9` in `Progress.kt` adds table `artist_tour_preferences` (`artist_key` TEXT PRIMARY KEY, `tour_name` TEXT, `year` TEXT, `updated_at` INTEGER NOT NULL).
  - **macOS (GRDB):** Migration v9 in `ProgressStore.swift` creates `artist_tour_preferences` table with matching schema and constraints.
- **Preference Storage & DAOs:**
  - `ArtistTourPreferenceEntity` and `ArtistTourPreferenceDao` (`Progress.kt`) provide CRUD operations (`getPreference`, `getAllPreferences`, `setPreference`, `deletePreference`).
  - `ArtistTourPreference` and `ArtistTourPreferenceStore` (`ProgressStore.swift`) provide async persistence via GRDB on macOS.
- **NextStop Resolution Engine:**
  - `NextStop.kt` & `NextStop.swift`: `tourFor` checks for a configured preference. If a specific `tour_name` is selected, all candidate periods are queried to find matching tour shows. If a specific `year` is selected, candidate shows are fetched from that year's period. If no preference is set, the engine falls back to standard `recentPeriods(TOUR_PERIODS)` and `currentTourShows`.
  - Cache key `NextStop.cacheKey` incorporates sorted artist keys and preference mappings (`artist_key:tour_name:year`) so network caches invalidate immediately when preferences change.
- **Interactive Tour Picker UI:**
  - **Android:** `TourPickerDialog` in `MainActivity.kt` provides a searchable dialog allowing users to pick from past tours or individual years for any defunct or favorited artist.
  - **macOS:** `TourPickerSheet.swift` provides a native SwiftUI sheet for selecting tours and years with instant persistence.

## Iteration 52 — Unified Tag Model & Normalization across Phish.in and Relisten (#67, D191)

### D191 — Unified Tag Model & Normalization across Phish.in and Relisten (#67)

Live music archives feature rich metadata descriptors—such as soundboard recordings, matrix sources, guest appearances, and bustouts. However, Phish.in exposes explicit structured tag endpoints while Relisten expresses source properties via boolean flags and taper notes.

- **Unified Domain Model:**
  - `TagRef` and `Tag` domain models (`Catalog.kt`, `Catalog.swift`) represent unified tag descriptors with `name: String`, `description: String?`, `color: String?`, and `priority: Int`.
- **Phish.in Tag Ingestion:**
  - `PhishInAPI` and `Api.kt` ingest native tags from `phish.in/api/v2/tags` and nested show / track metadata, mapping them directly to `TagRef` collections on `ShowSummary`, `ShowDetail`, and `PlayableTrack`.
- **Relisten Synthetic Tag Normalization:**
  - `deriveSyntheticTags(isSoundboard, looksLikeMatrix, hasFlac)` generates standardized synthetic tags across Relisten sources and recordings:
    - `"SBD"` (priority 100): Soundboard recording
    - `"Matrix"` (priority 90): Matrix recording (SBD + AUD)
    - `"FLAC"` (priority 80): Lossless FLAC audio stream
- **Tag Filtering & Discovery Surfaces:**
  - `filterByTag(tagName)` / `filterShowsByTag` / `filterTracksByTag` provide pure, platform-neutral filtering across show and track lists.
  - Interactive horizontal tag filter chip bars integrated into Show listings (`ShowsView.swift`, `MainActivity.kt`), Search surfaces (`SearchView.swift`), and Show detail headers.

## Iteration 53 — Recency-Weighted Momentum & Trending Sort (#21, D192)

### D192 — Recency-Weighted Momentum & Trending Sort across 48h, 7d, 30d time windows (#21)

Relisten tracks multi-window listening velocity and trending metrics for archival recordings, but these signals were previously unused for catalog discovery.

- **Popularity & Momentum Data Model:**
  - `Popularity` model on Android (`Catalog.kt`) and macOS (`Catalog.swift`) encapsulates `momentumScore`, `trendRatio`, `hotScore48h`, `hotScore7d`, and `hotScore30d`.
  - Parsed from Relisten API v2/v3 responses (`Relisten.kt`, `RelistenAPI.swift`) and mapped onto `ShowSummary` and `ShowDetail`.
- **Multi-Window Sorting Modes:**
  - `ShowSortMode` enum provides comprehensive sorting algorithms:
    - `DATE_ASC` ("Date (Oldest)") / `DATE_DESC` ("Date (Newest)")
    - `TOP_RATED` ("Top Rated"): sorted by rating descending, tie-broken by date.
    - `TRENDING_48H` ("Trending (48h)"): sorted by 48-hour velocity (`hotScore48h`), tie-broken by `momentumScore`, `rating`, `date`.
    - `HOT_7D` ("Hot (7d)"): sorted by 7-day velocity (`hotScore7d`), tie-broken by `momentumScore`, `rating`, `date`.
    - `POPULAR_30D` ("Popular (30d)"): sorted by 30-day velocity (`hotScore30d`), tie-broken by `momentumScore`, `rating`, `date`.
    - `MOMENTUM` ("Momentum"): sorted by overall momentum score (`momentumScore`), tie-broken by `hotScore48h`, `rating`, `date`.
- **UI Surfaces:**
  - Sort mode selectors and menus in `MainActivity.kt` and `ShowsView.swift` / `PeriodsView.swift`.
  - Visual momentum indicators (e.g. `⚡ 12.4` and `★ 4.8`) rendered on show rows, cards, and detail headers.

## Iteration 54 — Deterministic Procedural Show Artwork & Cassette Graphics (#62, D193)

### D193 — Deterministic Procedural Show Artwork & Cassette Graphic Placeholders (#62)

Live concert recordings in Relisten and archive.org lack standardized square album artwork, previously falling back to generic grey placeholder icons across browse grids, player views, and notifications.

- **Deterministic Procedural Artwork Generation:**
  - Implemented `Artwork.kt` (Android / Jetpack Compose) and `Artwork.swift` (macOS SwiftPM / SwiftUI) with pure deterministic generators (`ShowArtworkGenerator`, `ArtworkPalette`).
  - Uses 64-bit FNV-1a hashing on seed strings composed of normalized artist names and show dates (`"artist:yyyy-MM-dd"`).
- **Curated & Procedural Palettes:**
  - Curated distinct color schemes for iconic live bands (Grateful Dead psychedelic amber/rose/indigo, Jerry Garcia Band burgundy/gold, Phish aqua/purple, Goose neon teal, Billy Strings bluegrass cedar).
  - 12 procedural fallback palettes derived from vintage concert posters, light shows, and cassette tape aesthetics.
- **Procedural Cassette & Concert Poster Graphics:**
  - Renders vintage cassette J-card tape shells, magnetic spools, textured header stripes, and stylized date/venue typography directly on canvas.
  - Replaces all generic placeholder icons across Browse views (`ShowsView`, `ArtistsView`, `PeriodsView`), Show Details (`ShowDetailView`), History, Continue Listening, MiniPlayer, and Now Playing surfaces.

**Testing:**
- Android unit tests (`./gradlew testDebugUnitTest`): 461/461 tests passing.
- macOS Swift package tests (`swift test`): 346/346 tests passing.
- macOS Xcode project generation (`xcodegen generate`): Verified clean project generation.

## Iteration 55 — macOS Home Screen & Feature Parity with Android (D194)

### D194 — Native macOS Home Dashboard & Discovery Parity

Brought the macOS client to full feature parity with Android's home dashboard:

- **Anniversary Engine (`OnThisDate.swift`):**
  - Added concurrent multi-artist fetching for historical shows matching today's calendar date across favorited Phish and Relisten artists.
  - Implemented phish.in chunked batch querying under the 900-show request limit, with in-memory caching and automatic invalidation.
- **1-Click "Surprise Me" Player (`Catalog.swift`):**
  - Added `pickRandomShow(artists:source:)` for instantaneous random concert discovery and playback.
- **Native macOS Home Screen (`HomeView.swift`):**
  - Header with "Surprise Me" action.
  - Horizontal card carousels for "Continue Listening" and "On This Date".
  - "Next Couch Tour Stop" card resolving unplayed tour shows with `TourPickerSheet` for configuring historical tours.
  - "Favorite Artists" grid with show counts and star toggles.
  - "Library & Explore" navigation shortcuts (Browse Artists, Playlists, History).
  - "Settings & Status" overview with live phish.in connection, skip filler toggle, and device sync status.
- **Root Navigation Integration (`RootView.swift`, `AppModel.swift`):**
  - Added `.home` ("Home", `house` icon) as the default top destination in `SidebarSection`.

## Iteration 56 — macOS Auto-Updates with Sparkle 2.x & GitHub Release Packaging (#60, D195)

### D195 — Sparkle 2.x Auto-Updates, Appcast Channels, and Ad-Hoc Library Validation

Implemented native background auto-updates and manual update checks for distributed macOS binaries (#60).

- **Sparkle 2.x Framework Integration (`Updater.swift`, `project.yml`):**
  - Integrated `Sparkle` 2.6.4 Swift Package into `CouchTour` (Production) and `CouchTourBeta` (Beta) schemes.
  - Wrapped `SPUStandardUpdaterController` in `UpdaterViewModel` (`Updater.swift`) exposing published `canCheckForUpdates` and `automaticallyChecksForUpdates`.
  - Configured appcast channels and public EdDSA signing keys in `Info.plist`:
    - Production Feed: `https://raw.githubusercontent.com/mkny13/couch-tour/main/appcast.xml`
    - Beta Feed: `https://raw.githubusercontent.com/mkny13/couch-tour/main/appcast-beta.xml`
    - `SUEnableAutomaticChecks: true`
- **UI Surfaces:**
  - Added "Check for Updates..." to the macOS application menu (`CommandGroup(after: .appInfo)` in `CouchTourApp.swift`).
  - Added "Software Updates" section with automatic checks toggle in Settings window (`PlaybackSettingsView.swift`).
  - Added "Check for Updates..." action in the sidebar footer (`RootView.swift`).
- **Ad-Hoc Signing & Library Validation:**
  - Set `com.apple.security.cs.disable-library-validation: true` and deep code-signing (`codesign --force --deep --sign -`) in `install.sh` / `install-beta.sh` so ad-hoc signed local builds load embedded `Sparkle.framework` without dyld signature validation crashes.
- **Automated GitHub Release Packaging (`macos-release.yml`):**
  - Added GitHub Actions workflow to build release `.app` bundles, package into `.zip` archives, and upload binaries alongside `appcast.xml` to GitHub Releases.

## Iteration 57 — Desktop Cast & AirPlay Sender Integration on macOS (#10, D196)

### D196 — Google Cast Sender Protocol, Bonjour Discovery, and AirPlay Route Selection (#10)

Brought full remote casting capabilities to Couch Tour's macOS desktop client, mirroring the Android Cast implementation (D58, D62, D64, D68, D81, D187):

- **Portable Google Cast Domain & Protocol Engine (`CouchTourKit`):**
  - `CastModels.swift`: `CastDevice`, `CastKeys`, and `CastMediaStatus` models.
  - `CastItemConverter`: Formats track/show metadata and queue items into Cast receiver media dictionaries, with lossless FLAC-to-MP3 fallback stream URL rewrite while preserving `flac_url` in `customData` (D187).
  - `CastCodec.swift`: Pure Swift encoder/decoder for Google Cast V2 framing (4-byte length prefix + Protobuf packet binary + JSON payloads).
  - `CastPlaybackStateMachine.swift`: Manages connection lifecycle, request sequencing, heartbeat pings/pongs, receiver status negotiation, media transport commands, and track finish events.
- **macOS Discovery & TLS Connection (`macos/CouchTour`):**
  - `CastDiscovery.swift`: Uses Network.framework `NWBrowser` for Bonjour DNS-SD (`_googlecast._tcp.local.`), parsing TXT records (`fn`, `md`, `id`) into a reactive list of available Cast devices.
  - `CastClient.swift`: Manages TLS `NWConnection` to port 8009 with custom SecTrust handling for Cast devices' self-signed certificates, driving the state machine and dispatching playback updates.
  - `AirRoutePicker.swift`: Wraps `AVRoutePickerView` via `NSViewRepresentable` for routing audio to AirPlay receivers, Apple TVs, and HomePods.
- **Player & Transport Integration (`Player.swift`):**
  - Connects/disconnects Cast receiver sessions, transparently routing play/pause, skip next/previous, seeking, and volume.
  - Pauses local `queuePlayer` while casting and keeps `ProgressRecorder` / `SyncSession` synchronized with remote playback ticks.
  - Coming back from the TV lands paused (following D62).
- **UI Surfaces & Menu Controls (`MiniPlayerView.swift`, `NowPlayingInspector.swift`, `CastRoutePicker.swift`, `CouchTourApp.swift`):**
  - Added `CastRoutePickerButton` popover in MiniPlayer and Now Playing inspector with live discovered Cast receivers list and embedded AirPlay picker.
  - Active "Casting to [Device Name]" badge with 1-click disconnect and device switching.
  - Added "Disconnect Cast" action to macOS Playback menu.

**Testing:**
- macOS Swift package tests (`swift test`): 355/355 tests passing (+9 new Cast tests).
- macOS Xcode project generation (`xcodegen generate`) and app target build (`xcodebuild`): Build succeeded (`CouchTour`).

## Iteration 58 — phish.in Sign-In Fix on macOS Clients (D197)

### D197 — Unauthenticated Login Requests, Email Whitespace Sanitization, and Keychain Memory Fallback

Fixed issues preventing phish.in account login on macOS clients:

- **Unauthenticated Login Requests (`PhishInAPI.swift`):**
  - `PhishInAPI.send` and `post` now support an `authenticated: Bool` flag (defaulting to `true`).
  - `PhishInAPI.login` explicitly passes `authenticated: false`, preventing stale or expired `X-Auth-Token` headers from being sent with login credentials, which caused the backend to reject valid logins.
- **Email Whitespace Sanitization (`AccountView.swift`):**
  - Trimmed leading/trailing whitespace and newlines from email inputs before sending, matching Android's `Session.login(email.trim(), password)` behavior.
- **In-Memory Keychain Fallback (`PhishInAuth.swift`):**
  - Added `memoryJwt` and `memoryUsername` cache/fallback to `PhishInTokenStore`, matching Android's `TokenStore` in `Auth.kt`. If Keychain access encounters signature changes or authorization prompts, the active session is reliably retained in memory for the app's lifetime.
- **Interactive Home Screen Status (`HomeView.swift`):**
  - Wrapped "phish.in Account" and "Sync" status cards in `HomeView` with buttons presenting modal sheets for `AccountView` and `SyncView`.

**Testing:**
- macOS Swift package tests (`swift test`): 355/355 tests passing.
- macOS app builds (`xcodebuild`): `CouchTour` and `CouchTourBeta` clean builds verified.

## Iteration 59 — macOS Auto-Update Pipeline Fix: Key Rotation and CI-Automated Appcasts (D198)

### D198 — Sparkle Key Rotation, Automated Signing, and Appcast Generation in CI

Auto-update was silently non-functional since D195 shipped: `appcast.xml`/`appcast-beta.xml` were hand-written once and never updated on later releases, and neither had a `sparkle:edSignature` — Sparkle 2.x refuses any update that doesn't verify against `SUPublicEDKey`, so even a current appcast entry would have been rejected. The original private EdDSA key was never durably saved anywhere and could not be recovered.

- **Key rotation (`macos/project.yml`):** Generated a new EdDSA keypair via Sparkle's `generate_keys` tool; `SUPublicEDKey` updated for both `CouchTour` and `CouchTourBeta` targets. Every existing install (including production betas already in the field) has the old public key baked into its `Info.plist` and can never trust anything signed with the new key — a one-time manual reinstall is required to get back onto the auto-update chain.
- **CI-automated signing and appcast generation (`macos-release.yml`):** After building and uploading the release zip, a new step runs Sparkle's `generate_appcast` tool (bundled with the SPM package under `macos/build/SourcePackages/artifacts/sparkle/Sparkle/bin/`) against a staging directory containing the new zip and the existing appcast file, signing with the private key from the `SPARKLE_PRIVATE_KEY` repo secret (piped via `--ed-key-file -`, never written to disk). A following step checks out `main` and commits the regenerated `appcast.xml`/`appcast-beta.xml` directly, so the feed `SUFeedURL` points at (raw GitHub content on `main`) always reflects the latest signed release without a manual editing step.
- **Build-number bug, the actual root cause (`macos-release.yml`):** `CURRENT_PROJECT_VERSION` (`CFBundleVersion`, exposed to Sparkle as `sparkle:version`) is hardcoded to `"1"` in `project.yml` for every build and was never overridden per-release — only `MARKETING_VERSION` was. Sparkle's update-availability check compares `sparkle:version`, not the human-readable `sparkle:shortVersionString`, so with every release reporting build `"1"`, Sparkle could never have perceived *any* release as newer than what's installed, independent of the signature/appcast problems above. Fixed by overriding `CURRENT_PROJECT_VERSION="$GITHUB_RUN_NUMBER"` at `xcodebuild` time — monotonically increasing across every run of this workflow.
- **Double `-beta` suffix (`macos-release.yml`):** since betas started being tagged `vX.Y-beta` (matching the release tag itself) rather than plain `vX.Y`, the version computation appended `-beta` to a `clean_ver` that already ended in `-beta`, producing `MARKETING_VERSION` values like `0.55-beta-beta`. Fixed by stripping a trailing `-beta` from `clean_ver` before reapplying it.
- **Also fixed in the same pass (`macos-release.yml`):** the workflow's `xcodegen generate` step was producing a `.pbxproj` in `projectFormat: xcode16_0` (xcodegen's new default) that the runner's default Xcode 15.4 couldn't open ("future Xcode project file format (77)") — this had made every prior run of `macos-release.yml` fail before even reaching the signing/versioning problems above. Fixed by explicitly selecting the Xcode 16.2 install already present on the `macos-14` runner image.

**Verification:** dispatched `macos-release.yml` for `v0.55-beta` end-to-end — build succeeded, zip uploaded, `appcast-beta.xml` regenerated with a valid `sparkle:edSignature` and a real incrementing `sparkle:version`, and committed to `main`.

## Iteration 60 — macOS periodic progress tick fires while paused, clobbering a synced-in advance (D199)

### D199 — `Player`'s `AVQueuePlayer` time observer gates `saveProgress` on `isPlaying`, matching Android

Reported live by Mike: played Halley's Comet (Phish, 2026-07-27) on the Mac, then resumed and
continued into the next track on Android the next day. Back at the Mac — left open and paused
on Halley's Comet the whole time, never relaunched — Continue Listening still showed Halley's
Comet even though Sync reported a successful round trip and a "just now" timestamp on the stale
row. D172/D173 (the last two sync-staleness bugs) were both already fixed and correctly in
place — `ContinueListeningView`/`HomeView` do reload on `syncSession.lastSyncedAt`, and a real
sync failure would have surfaced via `lastError`. This was a third, different bug.

**Root cause: `Player.observePlayer()`'s periodic time observer isn't gated on playback state.**
`AVQueuePlayer.addPeriodicTimeObserver` keeps invoking its block on schedule even while
`rate == 0` (paused) — a known AVFoundation behavior, not a bug in Apple's framework. The
observer called `saveProgress(force: false)` unconditionally on every tick, which reaches
`ProgressRecorder.saveTick` and — subject only to its 5s internal throttle, not to whether
anything actually changed — rewrites the local `progress` row for the still-loaded, unmoving
queue with a fresh `updatedAt = now()`. `ProgressRecorder`'s own doc comment already says the
intended rule is "write every 5s *while playing*," but nothing enforced that; the periodic
observer fired regardless.

That stale-but-freshly-timestamped local row is exactly what broke sync: the next round trip's
`changedSince(lastPushWatermark)` saw it as legitimately changed and pushed it to the server,
where last-write-wins by `updatedAt` let it silently overwrite Android's genuinely newer
progress — even though the Mac's own data was actually the *older* state. The sync itself
worked correctly end to end; it just synced the wrong snapshot outward.

**Android already has the right shape for this.** `PlaybackService.kt`'s equivalent tick
explicitly checks `if (active.isPlaying) saveNow()` before writing anything on its 5s loop; this
worktree's job (`desktop-android-parity`) is exactly to find and close gaps like this one where
macOS diverges from an already-correct Android behavior.

**Fix:** wrap the `saveProgress(force: false)` call inside the periodic time observer in
`if self.isPlaying { ... }` ([Player.swift](../macos/CouchTour/Player.swift)) — mirroring
Android's gate. `positionMs` is still updated unconditionally (harmless — it doesn't move while
paused), only the store write and its throttle-tracked `lastSaveTime` are skipped. All other
`saveProgress(force: true)` call sites (play/pause toggle, track change, seek, cast handoff)
are untouched — those are real events and are supposed to write immediately regardless of
`isPlaying`.

**Testing:** `swift test` — 355/355, unaffected (this bug lived entirely in the app-target
`Player.swift`, which the SwiftPM package's own tests don't reach). `xcodebuild` succeeds. Not
independently verified live beyond the build — the actual repro (leave a show loaded-paused
overnight, advance it on another device, come back) takes real elapsed time to confirm; worth a
manual check on the next beta.

## Iteration 61 — macOS Home Screen UX Polish, Batch A (#97, #98, #100, #101, D200)

### D200 — Tour Picker Reload Ordering, Starred-Artist Surprise Me, Continue Listening Split Targets, and a macOS Feedback Launcher

Four independent Home-screen fixes filed from Mike's own testing of macOS beta v0.57-beta
(2026-08-26), worked as Batch A of the macOS UX polish pass (see
[prompts/macos-ux-polish-batches.md](../prompts/macos-ux-polish-batches.md)).

- **#100 — Next Stop shelf stale after saving a tour/year (`HomeView.swift`):**
  `.sheet(item: $tourPickerArtist)` had no `onDismiss:`, so `TourPickerSheet.savePreference()`/
  `clearPreference()` (both take the identical dismiss-without-reload shape) correctly
  invalidated `NextStop`'s cache but nothing re-read it — `reloadDiscovery()` only ran on
  `.task` and on `appModel.favorites.keys` changing, neither of which a tour preference save
  touches. Fixed by adding `onDismiss: { await reloadProgress(); await reloadDiscovery() }`,
  in that order — `reloadDiscovery()` reads `tourPreferences` from state, so it has to run
  *after* `reloadProgress()` has refreshed that state, or it just recomputes the same stale
  answer. **Android already invalidates correctly** — `MainActivity.kt`'s
  `nextStopShows = loadOnce(Triple(today, favoritedArtists.keys, preferencesMap))` is keyed on
  `preferencesMap`, itself `remember`'d off `getAllPreferences().collectAsState()`, a reactive
  Room `Flow` — any DB write recomposes automatically. Left unchanged.
- **#101 — "Surprise Me" pulls from starred artists, not the whole catalog (supersedes D157):**
  Added `surpriseMeArtists(favorited:merged:)` to `Catalog.swift`/`Catalog.kt` — favorited when
  non-empty, falling back to the full merged catalog otherwise. The fallback (rather than
  disabling the button) was the deliberate call for the empty case: a first-run user with
  nothing starred yet would otherwise find Surprise Me permanently dead, which is a worse
  experience than the novelty-grade global draw D157 originally shipped. Both platforms agree.
  `pickRandomShow` itself is unchanged — it already took whatever artist list it was handed.
- **#98 — Continue Listening cards/rows split into two non-overlapping targets:**
  `ResumeCardView` (`HomeView.swift`) and `ProgressRow` (`ContinueListeningView.swift`, also
  used by `HistoryView.swift`) previously had exactly one meaning for the whole surface: resume.
  Split so artwork/title/subtitle open the show page and a separate, much larger Resume button
  (full-width below the text on the Home card; a `play.circle.fill` icon on sidebar/History
  rows) resumes. Avoided the click-swallowing nesting the issue warned about (`Button` nested
  in another `Button`'s or `NavigationLink`'s label loses clicks on macOS) by keeping the two
  targets as *siblings* — the artwork is its own plain-style `Button`, Resume is a second,
  separate `Button`, and the title/subtitle block uses `.onTapGesture` rather than a third
  nested control — never one control inside another's label.
  - Resolving a stored `PlaybackProgress` row to a navigable target needed a lookup, so added
    `resolveNavigationTarget(for:localPlaylistStore:)` to `Resume.swift` rather than inventing a
    second path: it reuses `resolveShowDetail` (the same resolution `resume(_:player:...)`
    already does) for `.show`/`.recording` rows, taking `.summary` off the resulting
    `ShowDetail`. Local playlists are the one case that can't reuse that path as-is —
    `resolveShowDetail`'s `.localPlaylist` branch builds a synthetic `ShowSummary` (backend
    `.phishin`, id `"local:<uuid>"`, date `"<n> tracks"`) meant for `Player.play(detail:)`, not
    for `ShowDetailView`, which re-fetches by `artist.backend`/`date` and would call
    `PhishInAPI.show(date: "<n> tracks")` — a guaranteed-failure request. So
    `resolveNavigationTarget` checks `QueueRef.kind` first: a `.localPlaylist` row resolves via
    a cheap local `LocalPlaylistStore.playlist(id:)` read and navigates to `LocalPlaylistView`
    instead, the same destination `LocalPlaylistsView` already registers.
  - Pushed via `.navigationDestination(item:)` bound to a `@State private var
    resumeNavigationTarget: ResumeNavigationTarget?` (an enum over `.show`/`.localPlaylist`) in
    each of `HomeView`, `ContinueListeningView`, and `HistoryView` — the value isn't known until
    the async resolve on tap completes, so a static `NavigationLink(value:)` (used elsewhere in
    these same files for already-known `ShowSummary`s) doesn't apply; the two destination
    mechanisms coexist without conflict since `ResumeNavigationTarget` is a distinct type from
    `ShowSummary`.
  - `HistoryView.swift`'s `ProgressRow(row:)` call site wasn't in this batch's file list but
    broke the build once `ProgressRow`'s signature changed (Swift reported it as "unable to
    type-check this expression in reasonable time" — a misleading error the compiler tends to
    emit for a mismatched SwiftUI-builder call site rather than a clear "missing arguments").
    Updated it to the same split/resolve pattern rather than leave it broken; History gains the
    same navigate-vs-resume behavior Continue Listening does, which was already the correct
    direction for consistency.
- **#97 — macOS Feedback button:** Quiet `questionmark.bubble` icon button (`FeedbackButton.swift`)
  next to Surprise Me in `HomeView`'s header — deliberately unobtrusive, `.buttonStyle(.borderless)`,
  not competing with Surprise Me's prominent styling. Opens
  `https://github.com/mkny13/couch-tour/issues/new` pre-filled via `NSWorkspace.shared.open`,
  mirroring Android's `FeedbackButton.kt`. The URL/body construction (`feedbackIssueURL(context:)`)
  lives in `CouchTourKit/Feedback.swift` as a pure function over a `FeedbackContext` struct so it's
  unit-testable without touching `Bundle`/`ProcessInfo`/`sysctl` — those live-environment reads stay
  in the app-target `FeedbackButton.swift`, gathering `ProcessInfo.processInfo.operatingSystemVersionString`
  for macOS version, `sysctlbyname("hw.model", ...)` for hardware model, `appModel.selection` for
  current screen, and `#if BETA` for channel. **Uses `Bundle.main.appMarketingVersion` (bare, e.g.
  `"0.57-beta"`), not `appVersionString`** (which already prefixes the display name, e.g. `"Couch
  Tour Beta 0.57-beta"`) for both the title and the body's App Version field — using the latter would
  have reproduced the exact "Couch Tour Couch Tour Beta" duplication this same batch's #98 work
  fixed in the Home footer a few commits ago. Title follows Android's `Feedback (Couch Tour
  <version>)` shape exactly so issues sort together.

**Testing:**
- macOS Swift package tests (`swift test`): 361/361 (+6: 2 for `surpriseMeArtists`, 4 for
  `feedbackIssueURL`).
- Android unit tests (`testDebugUnitTest`): passing, +2 for `surpriseMeArtists`.
- macOS Xcode project generation and both app target builds (`CouchTour`, `CouchTourBeta`):
  succeed.
- Not independently verified with a live click-through of the running app — this environment's
  GUI automation couldn't safely drive the built app without operating on Mike's live desktop
  session (unrelated open windows/other tools) or his real, in-use `progressStore` databases;
  worth a manual pass on the next beta, particularly the two-target hit-testing on #98's cards.

## Iteration 62 — Batch A Manual-Test Follow-Ups: Doubled Back Buttons and a Global Feedback Button (D201)

### D201 — `navigationBarBackButtonHidden` for the Pre-Existing Double Back Button, Feedback Button Moved to `RootView`'s Global Toolbar

Two fixes from Mike's manual test pass on D200's beta (v0.59-beta/v0.60):

- **Doubled back buttons on every drilled-down view (`ShowDetailView`, `PeriodsView`,
  `ShowsView`, `LocalPlaylistView`):** pre-existing since #96 (`BackButtonToolbarItem`,
  `cb824b6`), not introduced by this batch — it just went unnoticed until #98 opened a brand
  new push path (History → Show Detail) that made it visible for the first time. Root cause:
  macOS's `NavigationStack` renders its own automatic back chevron once there's navigation
  history, and `#96`'s custom `BackButtonToolbarItem` (`.navigation` placement, `⌘[` shortcut)
  never suppressed it — a well-documented SwiftUI gap (Apple Developer Forums' "Duplicate back
  buttons" thread describes exactly this). Root screens (Home, Artists, etc.) correctly show
  neither button — there's no navigation history yet — which is why Mike also observed "pages
  like Artists have none": expected, not a second bug. Fixed once, at the source: `.
  navigationBackButton()` (`BackButton.swift`) now chains `.navigationBarBackButtonHidden(true)`
  before adding the custom toolbar item, so every one of the four call sites is fixed without
  touching them individually.
- **Feedback button, moved from `HomeView`'s header to `RootView`'s persistent toolbar:** Mike
  wanted it reachable from every screen, not just Home. `RootView.swift`'s `detail:` pane
  already has a `.toolbar` on the outer `Group` wrapping the per-section `switch` — the same
  toolbar the "Now Playing" button lives in, applied once and persisting across every sidebar
  section rather than being redeclared per-section. Added `FeedbackButton` there as a sibling
  `ToolbarItem`, removed it from `HomeView`'s `headerSection`. `currentScreen: appModel.selection
  ?? .home` is now sourced from `RootView` directly, which if anything makes the "Screen" field
  in the filed issue's body more accurate than before — it now reflects whatever section is
  actually on screen when the button is pressed, not just Home.

**Testing:**
- macOS Swift package tests (`swift test`): 361/361, unaffected — both fixes are app-target-only
  (`BackButton.swift`, `RootView.swift`, `HomeView.swift`).
- macOS Xcode project generation and both app target builds (`CouchTour`, `CouchTourBeta`):
  succeed.
- Not independently verified live — same GUI-automation constraint as D200; worth confirming on
  the next beta that exactly one back chevron shows on drilled-down views and that Feedback is
  reachable and reports the correct current screen from a non-Home section.

