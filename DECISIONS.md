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

See [ROADMAP.md](ROADMAP.md) for what's not built yet and the open questions about what's
next.
