# Client security audit: Android and macOS (#806, part 3 of #801)

Previous passes: #222 (#247/#248), #477 (2026-10-02, base `c314a1e2`). Audited at `main` HEAD as of 2026-11-13 (#806). Scope: the Android and macOS clients; `sync/`, `scripts/` and CI workflows are covered by sibling sub-issues (e.g. #805).

**Outcome:**
- **Finding 1 (allowBackup regression — fixed, D355):** `AndroidManifest.xml` had dropped `android:allowBackup="false"` in commit `decc3ccbf` (#494) when adding `android:dataExtractionRules` and `fullBackupContent="false"`. Because `allowBackup` defaults to true, `ApplicationInfo.FLAG_ALLOW_BACKUP` remained set on API < 31 or tools checking the flag. Restored `android:allowBackup="false"` alongside `dataExtractionRules` (re-affirming D27, D28, D355).
- **Finding 2 (Manifest security gate added):** Added Robolectric test suite `ManifestSecurityTest.kt` asserting:
  1. `FLAG_ALLOW_BACKUP` is unset (fails if `android:allowBackup="false"` is removed).
  2. `FLAG_USES_CLEARTEXT_TRAFFIC` is unset.
  3. Exported app components match exactly the three permitted entry points (`MainActivity`, `TvMainActivity`, `PlaybackService`).
  4. Source manifest declares exactly those three components with expected export flags.
  5. `data_extraction_rules.xml` excludes all 5 storage domains (`root`, `file`, `database`, `sharedpref`, `external`) for both cloud-backup and device-transfer.
  6. Intent filters and query extra isolation for `MEDIA_PLAY_FROM_SEARCH` are verified.
- **MEDIA_PLAY_FROM_SEARCH surface review:** Hypothesized as an extra on `MainActivity`, but verified on `PlaybackService` (`AndroidManifest.xml:88`). `MainActivity` does not declare or process search intents; `PlaybackService`'s `LibrarySessionCallback` does not implement `onSearch`/`onPlayFromSearch`. No query extra (`SearchManager.QUERY`) is persisted or reaches network/file sinks. Verified with tests.
- **Dependencies:** All dependencies updated and scanned via GitHub Advisories and OSV (all 0 CVEs / known vulnerabilities).

---

## Android

| Check | Result | Evidence |
|---|---|---|
| `INTERNET` | Justified | OkHttp clients in `PhishInApi`, `SyncApi` (`Sync.kt`), `YouTubeApi` (`YouTube.kt`); Media3 streaming |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Justified | `PlaybackService` declares `foregroundServiceType="mediaPlayback"` in manifest (`AndroidManifest.xml:84`) |
| `POST_NOTIFICATIONS` | Justified | Runtime-requested on API 33+ in `MainActivity.onCreate` (`MainActivity.kt:108-109`) so media playback notification displays |
| `CAMERA` | Justified, runtime-only on pairing | Declared with `required="false"`. Referenced only for QR pairing scan in `Qr.kt:78,87` (`checkSelfPermission`, `RequestPermission`). No other call sites |
| Exported components | Exactly three permitted | Merged manifest and source manifest contain exactly three app components: `MainActivity`, `TvMainActivity`, `PlaybackService`. Enforced by `ManifestSecurityTest` |
| `MainActivity` (exported, MAIN/LAUNCHER) | OK | Only processes `open_now_playing` (boolean UI flip) and `syncBaseUrl` (string, strictly gated on `BuildConfig.DEBUG` via `SyncApi.maybeApplyBaseUrlOverride`, D323 / #480). Does not declare `MEDIA_PLAY_FROM_SEARCH` |
| `TvMainActivity` (exported, LEANBACK_LAUNCHER) | OK | Android TV launcher entry only (`AndroidManifest.xml:52-62`). Reads no intent extras |
| `PlaybackService` (exported, MediaLibraryService) | OK | Required for Android Auto, system media controls, and Assistant controllers. `onGetSession` (`PlaybackService.kt:563`) exposes only public catalog browse and playback, no auth tokens |
| `MEDIA_PLAY_FROM_SEARCH` | OK | Declared on `PlaybackService` (`AndroidManifest.xml:88`), not `MainActivity`. `LibrarySessionCallback` (`PlaybackService.kt:584-610`) implements only `onGetLibraryRoot` and `onGetChildren`. `onSearch` and `onPlayFromSearch` are not implemented. `SearchManager.QUERY` is never read, persisted, or passed to network or filesystem. Verified in `ManifestSecurityTest` |
| No other component types | OK | No broadcast receivers, content providers, or FileProvider declared in `app/src/main/AndroidManifest.xml`. Library receivers (WorkManager, ProfileInstaller) are protected by system permissions (`BIND_JOB_SERVICE`, `DUMP`) |
| `allowBackup` | **Fixed (D355)** | Restored `android:allowBackup="false"` to `<application>` in `AndroidManifest.xml:26` alongside `android:dataExtractionRules="@xml/data_extraction_rules"` and `android:fullBackupContent="false"` (re-affirming D27, D28, D355). `ManifestSecurityTest` asserts `FLAG_ALLOW_BACKUP == 0` and fails with `AssertionError: Expected FLAG_ALLOW_BACKUP to be unset in ApplicationInfo, but it was set` if removed |
| Data extraction rules | Full exclusion | `app/src/main/res/xml/data_extraction_rules.xml` excludes all 5 domains (`root`, `file`, `database`, `sharedpref`, `external`) from both `<cloud-backup>` and `<device-transfer>`. Keystore-bound tokens cannot be decrypted across device restores; DB syncs from backend |
| Cleartext / network security config | None | No `usesCleartextTraffic`, `targetSdk = 36`, cleartext disabled by default. `ManifestSecurityTest` asserts `FLAG_USES_CLEARTEXT_TRAFFIC == 0` |
| Token storage | OK | `TokenStore` (`Auth.kt:29`) and `SyncTokenStore` (`Sync.kt:319`) use `EncryptedSharedPreferences`. On hardware Keystore invalidation or error, they wipe and re-initialize or fall back to in-memory session only; plaintext preferences are never written |
| Tokens in `Log.*` | None | All 24 `Log.*` call sites across `app/src/main/` reviewed (`MainActivity.kt:137`, `HeuristicMatches.kt:25`, `CuratedMatches.kt:16`, `ApiTiming.kt:37,56,68,80`, `Cast.kt:93,94`, `SearchScreen.kt:58`, `DiagnosticsLog.kt:154,173`, `Sync.kt:358,719,827`, `Auth.kt:34,39,43`, `PlayerViewModel.kt:143,412,461,485,591,593`). They log routes, timing, exception classes, and show counts. None log tokens, passwords, credentials, or queries |
| Diagnostics log | OK | `DiagnosticsLog.kt:75-86` redacts keys containing `token`, `secret`, `password`, `auth`, `cookie`, `key`, `pairing`, `code` (`isRedactedKey`). Tested by `DiagnosticsLogTest` |
| Feedback attachments | OK | `FeedbackDiagnosticsTest`: `DiagnosticsLog.exportText` attached only when user explicitly keeps diagnostics enabled; diagnostics export is sanitized and bounded |
| Secrets in repo | None | Keystores and `local.properties` gitignored. No private keys, AWS/GCP service accounts, or personal access tokens committed. `BuildConfig` exposes only `SYNC_BASE_URL` (public endpoint) |

---

## macOS

| Check | Result | Evidence |
|---|---|---|
| `com.apple.security.app-sandbox` | Justified | Both targets (`CouchTour`, `CouchTourBeta`) in `macos/project.yml:60,113`; D104/#345 |
| `com.apple.security.network.client` | Justified | Outbound HTTPS to phish.in, Relisten, Cloudflare sync worker; Google Cast client sockets. No `network.server` entitlement |
| `temporary-exception.files.home-relative-path.read-only` | Justified | Read-only access to legacy plist and Application Support paths for one-time unsandboxed migration (`Migration.swift:70,76`) |
| `temporary-exception.mach-lookup.global-name` | Justified (added since `c314a1e2`) | `$(PRODUCT_BUNDLE_IDENTIFIER)-spks` and `$(PRODUCT_BUNDLE_IDENTIFIER)-spki` in `macos/project.yml:63-65,116-118`. Required for Sparkle 2 sandboxed auto-update service communication |
| `com.apple.security.cs.disable-library-validation` | Kept | Embedded Sparkle framework with local ad-hoc signing (`macos/scripts/install.sh`) requires this; removing breaks local development installs |
| ATS | OK | No `NSAppTransportSecurity` or `NSAllowsArbitraryLoads` in `macos/project.yml` or sources |
| Keychain | OK | `Keychain.swift:42-43`: `kSecAttrSynchronizable = false`, `kSecAttrAccessibleAfterFirstUnlock` |
| Tokens in `NSLog`/`print` | None | All 9 `NSLog`/`print` calls reviewed (`AppModel.swift:129`, `CastClient.swift:103,121`, `Migration.swift:70,76`, `ProgressRecorder.swift:75,90`, `CouchTourUITests.swift:137,333`). Log socket/migration error types only; no credentials |
| Diagnostics log | OK | `DiagnosticsLog.swift` redacts keys matching token/secret/password/auth/pairing keywords |
| Feedback | OK | Prefilled GitHub issue URL without tokens (`Feedback.swift`, `FeedbackButton.swift`) |
| Sparkle feeds | HTTPS, signed | `SUFeedURL` points to `https://raw.githubusercontent.com/mkny13/couch-tour/appcast/appcast[-beta].xml`. `SUPublicEDKey` set (`yWmYH/MYjiHVgfRTWDz1QQH39t+upIPeK1Dkh8eqcbA=`). `SUEnableInstallerLauncherService: true` set |

---

## Dependencies

Scanned against GitHub Advisories (`gh api /advisories`) and OSV (`api.osv.dev`):

| Platform | Dependency | Version | Vulnerabilities Found | Status |
|---|---|---|---|---|
| Android | `com.squareup.okhttp3:okhttp` | 5.5.1 | 0 (prior CVE-2021-0341 / CVE-2016-2402 patched in 4.9.2 / 3.1.2) | Clear |
| Android | `androidx.media3:media3-exoplayer` | 1.11.1 | 0 | Clear |
| Android | `androidx.media3:media3-session` | 1.11.1 | 0 | Clear |
| Android | `androidx.media3:media3-common` | 1.11.1 | 0 | Clear |
| Android | `androidx.security:security-crypto` | 1.1.0 | 0 (bumped from alpha06 to stable 1.1.0) | Clear |
| Android | `androidx.room:room-runtime` | 2.8.5 | 0 | Clear |
| Android | `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.12.0 | 0 | Clear |
| Android | `io.coil-kt:coil-compose` | 2.7.0 | 0 | Clear |
| Android | `com.google.zxing:core` | 3.5.4 | 0 | Clear |
| Android | `androidx.work:work-runtime-ktx` | 2.12.0 | 0 | Clear |
| Android | `com.github.TeamNewPipe:NewPipeExtractor` | v0.26.5 | 0 | Clear |
| macOS | `groue/GRDB.swift` | 7.12.0 | 0 | Clear |
| macOS | `sparkle-project/Sparkle` | 2.10.0 | 0 | Clear |

---

## Hard-coded URLs (both platforms)

- Plaintext `http://`: Only XML namespace definitions (`xmlns:android="http://schemas.android.com/apk/res/android"`, `xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle"`). No plaintext HTTP endpoints in source code.
- HTTPS hosts: `phish.in`, `api.relisten.net`, `relisten.net`, Cloudflare sync Worker (`couch-tour-sync[-staging].mkastellec.workers.dev`), GitHub (`raw.githubusercontent.com`, `github.com`), Google APIs (`www.googleapis.com`, `www.youtube.com`), `archive.org`, and outbound streaming service links (`open.spotify.com`, `tidal.com`). All hosts correspond to documented integrations or user-initiated links.

---

## Not covered

1. **Backend / Worker / CI Scripts**: Handled in sibling issues under #801 (e.g. #805 for developer scripts, deployment token scopes, subshell execution).
2. **macOS library validation in CI vs local development**: `com.apple.security.cs.disable-library-validation` remains required for local ad-hoc signed installs with embedded Sparkle. Evaluating whether CI builds can sign Sparkle with a developer ID and re-enable library validation requires signed binary distribution testing.
