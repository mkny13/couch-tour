# Android Dead Code & Deprecated API Audit

## 1. Unused Android Composables & Views

### Overview
The Android client is 100% Jetpack Compose for its UI layer. There are no legacy XML view layouts (`res/layout/` is entirely absent). However, as features have evolved (specifically the Ledger redesign, modern compact components, and centralized artwork helpers), several composables and UI helpers were superseded and left uncalled in the codebase.

### Unused Composables
The following 7 `@Composable` components are completely unreferenced across the codebase and can be safely removed:

1. **`SurpriseMeButton`** (`app/src/main/java/dev/mike/couchtour/MainActivity.kt:981`)
   - **Type:** `@Composable private fun SurpriseMeButton(artists: List<ArtistRef>, nav: NavHostController)`
   - **Status:** Dead code.
   - **Reason:** Originally a full-width action button on the Home screen (#20). It was superseded by `SurpriseMeChip` (`MainActivity.kt:728`), which renders as a compact chip in the Home screen header. `SurpriseMeButton` is no longer referenced anywhere in the app.

2. **`AnniversaryCard`** (`app/src/main/java/dev/mike/couchtour/MainActivity.kt:3612`)
   - **Type:** `@Composable private fun AnniversaryCard(show: ShowSummary, nav: NavHostController)`
   - **Status:** Dead code.
   - **Reason:** A 132dp card designed for the pre-Ledger horizontal scrolling row for "On this date" shows (#13). It was superseded by `OnThisDateLedgerRow` (`MainActivity.kt:904`) as part of the Ledger design system migration. It remains referenced only in descriptive KDoc comments.

3. **`ResumeCard`** (`app/src/main/java/dev/mike/couchtour/MainActivity.kt:3640`)
   - **Type:** `@OptIn(ExperimentalFoundationApi::class) @Composable private fun ResumeCard(progress: Progress, vm: PlayerViewModel, nav: NavHostController)`
   - **Status:** Dead code.
   - **Reason:** A 132dp card designed for the pre-Ledger horizontal scrolling "Continue listening" row. It was superseded by `InProgressLedgerRow` (`MainActivity.kt:774`). It is never invoked.

4. **`ArtworkBox`** (`app/src/main/java/dev/mike/couchtour/NowPlaying.kt:636`)
   - **Type:** `@Composable fun ArtworkBox(...)`
   - **Status:** Dead code.
   - **Reason:** A thin wrapper around `ShowArtwork` with procedural fallback arguments. Callers across the entire app now call `ShowArtwork` directly (`Artwork.kt:475`), rendering `ArtworkBox` completely orphaned.

5. **`AudioQualityBadge`** (`app/src/main/java/dev/mike/couchtour/NowPlaying.kt:708`)
   - **Type:** `@Composable internal fun AudioQualityBadge(format: String, isFlac: Boolean, modifier: Modifier = Modifier)`
   - **Status:** Dead code.
   - **Reason:** Designed to render formatted audio quality chips. During player UI iteration, the FLAC indicator in `NowPlayingScreen` was implemented directly inline (`NowPlaying.kt:302-320`) as a `Box` with border and text rather than using this component.

6. **`ProgressBarOverlay`** (`app/src/main/java/dev/mike/couchtour/DesignComponents.kt:311`)
   - **Type:** `@Composable fun ProgressBarOverlay(progress: Float, modifier: Modifier = Modifier, height: Dp = 2.dp, useGradient: Boolean = true)`
   - **Status:** Dead code.
   - **Reason:** A 2dp progress bar overlay created during the Ledger design handoff for rows and the mini-player. `MiniPlayer` (`MainActivity.kt:4007-4015`) and `InProgressLedgerRow` both implemented their progress bar geometry directly inline, leaving `ProgressBarOverlay` unused.

7. **`LedgerToggle`** (`app/src/main/java/dev/mike/couchtour/DesignComponents.kt:399`)
   - **Type:** `@Composable fun LedgerToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier)`
   - **Status:** Dead code.
   - **Reason:** A 40x22 custom toggle switch from the Ledger design specs. In practice, `SettingsScreen.kt` (`SettingsToggleRow:411-420`) adopted the standard Material 3 `Switch` with custom palette colors (`SwitchDefaults.colors`), leaving `LedgerToggle` unreferenced.

### Unused Dead Aliases & Companion Members
- **`ShowSortMode.companion`** (`app/src/main/java/dev/mike/couchtour/Catalog.kt:74-79`):
  - `val DATE = DATE_DESC`
  - `val RATING = TOP_RATED`
  - `val TRENDING_7D = HOT_7D`
  - `val TRENDING_30D = POPULAR_30D`
  - These aliases are never referenced; all callers reference the enum values (`DATE_DESC`, `TOP_RATED`, etc.) directly.
- **`RelistenPopularityWindows` aliases** (`app/src/main/java/dev/mike/couchtour/Relisten.kt:78-80`):
  - `val w48h: RelistenPopularityWindow? get() = window48h`
  - `val w7d: RelistenPopularityWindow? get() = window7d`
  - `val w30d: RelistenPopularityWindow? get() = window30d`
  - Never referenced by any production or test code.
- **`ArtistTourPreferenceDao` unused queries** (`app/src/main/java/dev/mike/couchtour/Progress.kt:135-150`):
  - `getPreferenceFlow(artistKey)`
  - `getAllPreferencesSync()`
  - `clearAll()`
  - Declared Room queries that are not consumed by any repository or ViewModel.
- **Test-only functions in production source**:
  - `currentUser()` (`app/src/main/java/dev/mike/couchtour/Api.kt:238`): Only exercised in `ApiRequestTest.kt`. The app manages user authentication directly through stored tokens in `Auth.kt`.
  - `positionAt()` (`app/src/main/java/dev/mike/couchtour/Format.kt:36`): Only exercised in `FormatTest.kt`.
  - `toTag()` (`app/src/main/java/dev/mike/couchtour/Catalog.kt:54`): Only called in `TagTests.kt`.
  - `historyFor()` (`app/src/main/java/dev/mike/couchtour/Progress.kt:91`): Only exercised in `ProgressDaoTest.kt`.

---

## 2. Obsolete Network Adapters

A comprehensive audit of the Android networking layer (`Api.kt`, `Relisten.kt`, `Sync.kt`, `Catalog.kt`, and `CatalogCache.kt`) reveals that no obsolete network adapter files remain in the codebase:
- **`PhishInApi` (`Api.kt`):** Actively used for Phish show lookups, setlists, user authentication, likes (`like`, `unlike`, `likedShows`, `likedTracks`), and remote playlists.
- **`RelistenApi` (`Relisten.kt`):** The primary multi-artist network adapter for Relisten's catalog, supporting artists, years, shows, sources, songs, and venues.
- **`SyncApi` (`Sync.kt`):** The client for Cloudflare Worker + D1 backend sync (`pairStart`, `pairClaim`, `sync`, `devices`, `revokeDevice`).
- **No legacy HTTP libraries:** All network traffic routes cleanly through OkHttp 4.12.0 and `kotlinx.serialization`. No retired v1 endpoints, legacy Apache HTTP clients, or deprecated Volley/Retrofit components exist.

*Recommendation:* No wholesale network adapter removals are required for Android.

---

## 3. Deprecated APIs & Modern Replacements

The following API usages are deprecated or violate modern Android/Compose SDK conventions:

### A. Android Platform & Manifest Deprecations

#### 1. `android:allowBackup="false"`
- **Location:** `app/src/main/AndroidManifest.xml:27`
- **Deprecation:** Deprecated in Android 12 (API 31).
- **Explanation:** In Android 12+, `allowBackup` only controls cloud backups. Device-to-device transfers require explicit configuration.
- **Replacement:** Specify `android:dataExtractionRules="@xml/data_extraction_rules"` referencing an XML resource that configures cloud backups and device-to-device transfer rules separately.

#### 2. Redundant `mipmap-anydpi-v26/` Resource Qualifier
- **Location:** `app/src/main/res/mipmap-anydpi-v26/`
- **Deprecation:** Unnecessary version qualifier (`minSdkVersion` is 26).
- **Replacement:** Move resource files (`ic_launcher.xml` and `ic_launcher_beta.xml`) directly to `res/mipmap-anydpi/` and remove the `-v26` directory.

#### 3. Android Auto Intent Filters
- **Location:** `app/src/main/AndroidManifest.xml:80`
- **Deprecation/Issue:** Missing intent-filter for `android.media.action.MEDIA_PLAY_FROM_SEARCH`.
- **Replacement:** Add the required intent-filter to `PlaybackService` to fully support Android Auto voice search intents.

---

### B. Java-style Static APIs Superseded by Android KTX Extensions

Android KTX extensions provide null-safe, idiomatic Kotlin alternatives that are cleaner and more expressive than static Java calls:

#### 1. `Uri.parse(string)` &rarr; `string.toUri()`
- **Locations:**
  - `app/src/main/java/dev/mike/couchtour/ExternalReleaseHelper.kt:9, 26`
  - `app/src/main/java/dev/mike/couchtour/FeedbackButton.kt:39`
  - `app/src/main/java/dev/mike/couchtour/MainActivity.kt:4388`
  - `app/src/main/java/dev/mike/couchtour/MediaItems.kt:206`
  - `app/src/main/java/dev/mike/couchtour/PlaybackService.kt:416`
- **Replacement:** Replace `Uri.parse(url)` with `import androidx.core.net.toUri` and `url.toUri()`.

#### 2. `SharedPreferences.edit()` &rarr; `prefs.edit { ... }`
- **Locations:**
  - `app/src/main/java/dev/mike/couchtour/Favorites.kt:34`
  - `app/src/main/java/dev/mike/couchtour/LikedTracks.kt:36`
  - `app/src/main/java/dev/mike/couchtour/PlaybackSettings.kt:62, 73, 80`
  - `app/src/main/java/dev/mike/couchtour/SavedShows.kt:33`
  - `app/src/main/java/dev/mike/couchtour/Sync.kt:279, 287, 296, 305`
  - `app/src/main/java/dev/mike/couchtour/ThemeSettings.kt:45`
- **Replacement:** Replace `prefs.edit().putString(...).apply()` with the KTX block:
  ```kotlin
  prefs.edit {
      putString(KEY, value)
  }
  ```

#### 3. `Bitmap` Factory & Pixel Accessors &rarr; KTX Extensions
- **Locations:**
  - `app/src/main/java/dev/mike/couchtour/Qr.kt:46` (`Bitmap.createBitmap(...)`)
  - `app/src/main/java/dev/mike/couchtour/Qr.kt:49` (`bitmap.setPixel(...)`)
  - `app/src/main/java/dev/mike/couchtour/Waveform.kt:27, 44, 55` (`bitmap.getPixel(...)`)
- **Replacement:**
  - Use `createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)` from `androidx.core.graphics`.
  - Use `bitmap[x, y] = color` and `bitmap[x, y]` operator overloads.

---

### C. Media3 UnstableApi & CameraX Opt-In Handling

Media3 and CameraX mark non-final or internal APIs with `@UnstableApi` and `@ExperimentalGetImage`. Without proper annotations, Android Lint reports error-level `UnsafeOptInUsageError` failures during `app:lintDebug`:

#### 1. Media3 UnstableApi Calls
- **Locations:**
  - `app/src/main/java/dev/mike/couchtour/Cast.kt:150` (`MediaItemConverter`)
  - `app/src/main/java/dev/mike/couchtour/PlaybackService.kt:97, 191, 192, 195, 264-277, 606-607` (`RemoteCastPlayer`, `PreloadConfiguration`, `SessionAvailabilityListener`)
- **Replacement:** Add `@OptIn(androidx.media3.common.util.UnstableApi::class)` to the enclosing methods/classes, or configure lint opt-in in `lint.xml`.

#### 2. CameraX ExperimentalGetImage
- **Location:** `app/src/main/java/dev/mike/couchtour/Qr.kt:118` (`proxy.image`)
- **Replacement:** Add `@OptIn(androidx.camera.core.ExperimentalGetImage::class)` on the image analysis callback in `Qr.kt`.

---

### D. Compose Parameter Ordering Conventions

According to Compose API design guidelines, `modifier: Modifier = Modifier` should be the first optional parameter in any Composable function so callers can easily specify modifiers positionally or with standard trailing lambdas:
- **`ShowArtwork`** (`app/src/main/java/dev/mike/couchtour/Artwork.kt:400, 481`): `modifier` is defined after other optional parameters with defaults (`show: ShowSummary? = null`, etc.).
- **`WaveformScrubber`** (`app/src/main/java/dev/mike/couchtour/DesignComponents.kt:187`): `modifier` follows optional parameters.
- **Replacement:** Place `modifier: Modifier = Modifier` before the other optional parameters.

---

## 4. Removal & Refactor Plan

**Estimated Total Effort:** Medium (~3-4 hours across 3 independent tasks)

### Phase 1: Dead Code Removal (Low Risk, High Navigability Value)
- **Scope:**
  - Delete unused composables from `MainActivity.kt`: `SurpriseMeButton`, `AnniversaryCard`, `ResumeCard`.
  - Delete unused composables from `NowPlaying.kt`: `ArtworkBox`, `AudioQualityBadge`.
  - Delete unused composables from `DesignComponents.kt`: `ProgressBarOverlay`, `LedgerToggle`.
  - Delete dead companion aliases in `Catalog.kt` (`ShowSortMode.companion`) and `Relisten.kt` (`RelistenPopularityWindows`).
  - Clean up dead KDoc references that point to deleted cards.
- **Risk:** Zero. All removed components have 0 callers in production and tests.
- **Estimated Time:** 1 hour.

### Phase 2: Deprecated APIs & KTX Modernization (Low Risk)
- **Scope:**
  - Replace `Uri.parse(...)` with `.toUri()`.
  - Replace `prefs.edit()...apply()` with `prefs.edit { ... }`.
  - Replace `Bitmap.createBitmap` and pixel getters/setters in `Qr.kt` and `Waveform.kt` with KTX operator overloads.
  - Reorder `modifier` parameters to be the first optional parameter in `Artwork.kt` and `DesignComponents.kt`.
- **Risk:** Very low. Behavioral parity is preserved.
- **Estimated Time:** 1 hour.

### Phase 3: Manifest & Opt-In Upgrades (Low Risk)
- **Scope:**
  - Add `@OptIn(UnstableApi::class)` and `@OptIn(ExperimentalGetImage::class)` to suppress lint errors.
  - Move `res/mipmap-anydpi-v26/` files into `res/mipmap-anydpi/`.
  - Add `data_extraction_rules.xml` and update `AndroidManifest.xml` to replace deprecated `android:allowBackup="false"`.
  - Add `MEDIA_PLAY_FROM_SEARCH` intent-filter to `PlaybackService` for Android Auto search readiness.
- **Risk:** Low.
- **Estimated Time:** 1-1.5 hours.

---

## 5. Verification

After performing the removals and upgrades in follow-up issues, verify the changes with:
1. **Android Unit & Robolectric Tests:**
   ```bash
   JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest
   ```
2. **macOS Cross-Platform Verification:**
   ```bash
   swift test --package-path macos/Packages/CouchTourKit
   ```
3. **Android Lint Report:**
   ```bash
   JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew app:lintDebug
   ```
   (Verify that `UseKtx`, `ModifierParameter`, and `UnsafeOptInUsageError` warnings/errors are resolved).
