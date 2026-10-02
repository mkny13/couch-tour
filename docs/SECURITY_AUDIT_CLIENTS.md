# Client security audit: Android and macOS (#477, part 3 of #473)

Previous pass: #222 (#247/#248). Audited at `main` as of 2026-10-02. Scope: the two clients only; `sync/`, `scripts/` and CI workflows are covered by sibling sub-issues.

**Outcome:** no permission or entitlement removed (all are used), no code change needed to pass any "Done when" check. One real finding filed as **#480** (exported `MainActivity` persists a `syncBaseUrl` intent extra). No dependency has a known advisory.

## Android

| Check | Result | Evidence |
|---|---|---|
| `INTERNET` | Justified | OkHttp clients in `PhishInApi`, `SyncApi` (`Sync.kt`), `YouTubeApi` (`YouTube.kt`); Media3 streaming |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Justified | `PlaybackService` declares `foregroundServiceType="mediaPlayback"` (manifest) |
| `POST_NOTIFICATIONS` | Justified | Runtime-requested on API 33+ in `MainActivity.onCreate` (`MainActivity.kt:184`) so the media notification shows |
| `CAMERA` | Justified, runtime-only on pairing | Only `Manifest.permission.CAMERA` references are in `Qr.kt:78,87` (the scan composable: `checkSelfPermission`, then `RequestPermission` launcher). No other call site. `uses-feature camera` is `required="false"` |
| Exported components | See rows below | `grep exported= AndroidManifest.xml` shows three |
| `MainActivity` (exported, MAIN/LAUNCHER) | **Finding #480 — fixed** | Previously persisted `syncBaseUrl` (string) as the sync base URL in every build type. Now gated on `BuildConfig.DEBUG` via `SyncApi.maybeApplyBaseUrlOverride`: debug/beta builds still accept intent overrides; release builds ignore the extra entirely, leaving the compiled-in `SYNC_BASE_URL` unchanged and persisting nothing. See D323. `open_now_playing` (boolean, only flips UI to Now Playing: harmless) is unchanged. |
| `TvMainActivity` (exported, LEANBACK_LAUNCHER) | OK | Launcher entry only; `grep getStringExtra\|getExtra` finds extras only in `MainActivity.kt` |
| `PlaybackService` (exported, MediaLibraryService) | OK, accepted | Required for system/Android Auto/Assistant media controllers. `onGetSession` returns the session to any controller (`PlaybackService.kt:501`), the media3 default; the session exposes only playback and browse of public catalog data, no tokens |
| No other component types | OK | No receivers, providers or FileProvider (`grep registerReceiver\|FileProvider\|ContentProvider`) |
| `allowBackup` | Holds | `android:allowBackup="false"` (manifest, D27) |
| Cleartext / network security config | None | No `usesCleartextTraffic`, no `networkSecurityConfig` (`grep` over `app/src`); `targetSdk = 36` so cleartext is off by default |
| Token storage | OK | `TokenStore` (`Auth.kt`), `SyncTokenStore` (`Sync.kt`) use EncryptedSharedPreferences; on Keystore failure they fall back to in-memory, never plaintext prefs |
| Tokens in `Log.*` | None | All 25 `Log.*` calls reviewed (`grep 'Log\.[diwev]('`). They log tags, exception class/message, route paths, timings. `ApiTiming` logs `url.encodedPath` only (no query, no headers). `Auth.kt:34-43` and `Sync.kt:299` log exceptions about the keystore, not the values |
| Diagnostics log (#344) | OK | `DiagnosticsLog.log` redacts values for keys matching token/secret/password/auth-style names (`isRedactedKey`, `redactValue`); call sites log route names, library keys, sync error codes (`syncErrorCode`), not credentials |
| Feedback attachments | OK | Feedback attaches `DiagnosticsLog.exportText` (the redacted log) only when the user leaves "include diagnostics" on |
| Secrets in repo | None | Release keystore path/passwords come from gitignored `local.properties` (`app/build.gradle.kts`); `*.jks`, `*.keystore`, `local.properties` are in `.gitignore`; no key patterns (`AIza…`, `ghp_…`, private-key PEM) in any tracked file. `BuildConfig` carries only `SYNC_BASE_URL` (a public URL). `YouTubeApi.apiKey` is `null` unless wired by the owner; nothing is committed |
| Dependencies | No known advisories | OSV query (api.osv.dev, Maven) for okhttp 4.12.0, security-crypto 1.1.0-alpha06, kotlinx-serialization 1.7.3, zxing 3.5.3, media3 1.10.1, coil 2.7.0, room 2.6.1, NewPipeExtractor v0.26.5, ML Kit barcode 17.3.0, WorkManager 2.11.2: all returned zero vulnerabilities. Nothing bumped. `security-crypto` is an alpha (long-lived, deprecated upstream): not a CVE, tracked informally |

## macOS

| Check | Result | Evidence |
|---|---|---|
| `com.apple.security.app-sandbox` | Justified | Both targets; D104/#345 |
| `com.apple.security.network.client` | Justified | Outbound HTTPS to phish.in/Relisten/sync worker; Cast sender connection in `CastClient.swift`. No `NWListener`/server sockets (`grep NWListener\|bind(\|listen(`), so `network.server` is correctly absent. Bonjour browsing (`NWBrowser`, `CastDiscovery.swift`) needs no extra entitlement |
| `temporary-exception.files.home-relative-path.read-only` | Justified | Read only by `UnsandboxedMigration` (`Migration.swift`, called from `AppModel.swift:76`) to merge the old unsandboxed prefs and DB; paths are exactly the per-target plist and Application Support dir. Read-only, narrowly scoped. Removal candidate once the migration is retired |
| `com.apple.security.cs.disable-library-validation` | Kept, needs human check to remove | Sparkle is embedded in both targets and local installs are ad-hoc signed (`macos/scripts/install.sh:67,70`), where library validation rejects a framework with a different/no team ID. Not removed because it can't be verified without launching the signed app (no UI automation in this run). Follow-up: try removing for the CI-signed build only |
| ATS | OK | No `NSAppTransportSecurity` or `NSAllowsArbitraryLoads` in `macos/project.yml` or sources |
| Keychain | OK | `Keychain.swift`: `kSecAttrSynchronizable = false`, `kSecAttrAccessibleAfterFirstUnlock` (lines 42-43) |
| Tokens in `NSLog`/`print` | None | All 7 `NSLog`/`print` calls (`AppModel.swift:128`, `CastClient.swift:98,116`, `Migration.swift:70,76`, `ProgressRecorder.swift:75,90`) log errors from prefs/DB/Cast sockets, no credentials |
| Diagnostics log | OK | `DiagnosticsLog.swift` redacts keys matching token/secret/password/passwd/auth… (`isRedactedKey`, applied to fields and marks) |
| Feedback | OK | `Feedback.swift` builds a prefilled GitHub new-issue URL; no token involved (`FeedbackButton.swift:6`) |
| Sparkle feeds | HTTPS, signed | `SUFeedURL` is `https://raw.githubusercontent.com/...` for both targets; `SUPublicEDKey` set; every current `<enclosure>` in `appcast.xml`/`appcast-beta.xml` has `sparkle:edSignature`. Two legacy entries (v0.51 in `appcast.xml`, v0.53 in `appcast-beta.xml`) have none; Sparkle with an EdDSA key rejects unsigned updates, so they are inert, not a hole |
| Dependencies | No known advisories | `Package.resolved`: GRDB 6.29.3; Sparkle 2.10.0 (`project.yml`). OSV (SwiftURL) returned zero vulnerabilities for both |

## Hard-coded URLs (both platforms)

`grep http://` over `app/src/main`, `macos/CouchTour`, `CouchTourKit/Sources`, appcasts, `project.yml`: only XML namespace URIs (`xmlns:android`, `xmlns:sparkle`, `purl.org`). No plaintext endpoints.

`grep -oE https://host` hosts: `phish.in`, `api.relisten.net`, `relisten.net`, the sync worker (`couch-tour-sync[-staging].mkastellec.workers.dev`), `raw.githubusercontent.com` / `github.com` (Sparkle feed, release zips, feedback issues, repo links), `www.googleapis.com` (YouTube Data API, key-gated), `www.youtube.com`, `archive.org`, and outbound deep links to `open.spotify.com` / `tidal.com`. `invalid.local` / `invalid` appear only as unreachable placeholder hosts. Everything is a stated integration or a user-opened link. No Last.fm client code exists (D-publishing rule: no built-in scrobbler).

## Other observations (no change made)

- Tracked scratch files in the repo root (`build_output.log`, `stderr.txt`, `stdout.txt`, `test_output.log`, `fix.patch`, `scratch.kt`, `test.js`, `test.swift`, `test_script.swift`, `patch_test.sh`, `report.md`, `issue.txt`) were scanned for secrets and contain none. They are clutter and `issue.txt` is stale, but removing them is outside this audit.
- If the camera permission or any entitlement is later changed, QR pairing and playback should be re-checked on the beta (phone and Mac). Nothing was removed here, so no human check is required for this PR.
