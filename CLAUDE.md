# Couch Tour

An unofficial native Android client for [phish.in](https://phish.in), the open-source live
Phish archive. Kotlin, Jetpack Compose, Media3, Room. See [README.md](README.md) for what
the app does and [DECISIONS.md](DECISIONS.md) for why it does it that way.

## Building

**There is no Java on `PATH`.** Every Gradle invocation needs the JDK bundled with Android
Studio, or it fails with "Unable to locate a Java Runtime":

```
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest
```

The whole suite is local — Robolectric and MockWebServer, no device or emulator. It runs in
well under a minute, so run it after any change.

## Names that look wrong and are not

The app was renamed from "Phish.in for Android" to "Couch Tour" in `c2b99e2`. The rename was
deliberately scoped to user-visible identity. Do not "finish" it — each of these is load
bearing:

- **`"phishin.db"`** and **`"phishin_auth"`** are on-disk names. Renaming either orphans the
  listening history and login of every install that already exists.
- **`PhishInApi`** is the client for phish.in's API. It is named for the service on the other
  end of the socket, not for this app.
- **`PhishInDb`** anchors the Room schema export directory,
  `app/schemas/dev.mike.couchtour.PhishInDb/`, which the migration tests read by path.

References to phish.in in comments, docs, and API URLs are correct and should stay. The
attribution in the README is required framing, not a leftover disclaimer.

## Room migrations

The `progress` table *is* the feature — it holds listening position and history, which is the
thing the app exists to never lose. Migrations are always written out properly; a destructive
migration is never the right answer here. Add a `MIGRATION_n_n+1`, register it in
`addMigrations(...)`, bump `version`, commit the generated schema JSON, and cover it in
`MigrationTest.kt`.

## Project conventions

- **Log decisions in [DECISIONS.md](DECISIONS.md).** It is organised by iteration with `Dnn`
  identifiers. When a decision reverses an earlier one, add a new entry marking the old one
  superseded rather than editing history.
- **Comments explain why, not what.** The existing code is deliberately literate about
  tradeoffs and surprises — match that when adding to it.
- **The README states a unit-test count.** It goes stale; update it when adding or removing
  tests.

## Publishing constraints

Being prepared for a Google Play release. Two constraints come from outside the code:

- The phish.in maintainer permitted the API use but asked that this not be branded as
  official. Nothing in the store listing, app title, or icon may imply a first-party
  relationship.
- Keep the band's name out of the store title — it is their trademark. Descriptive use in the
  listing body is fine.

Built-in Last.fm scrobbling was removed before release to shrink the privacy surface
(`c8d2308`). Scrobbling still works via the official Last.fm app, which reads the
MediaSession metadata directly — keep that metadata correct.

Licensed [PolyForm Noncommercial 1.0.0](LICENSE): source available, not OSI open source.
