# Error-cached-as-empty audit (#445)

**Date:** 2026-10-02 · **Trigger:** #445, part of #403 · escapes #346, #352; fix #391 (`40141f8`)

Sweep of every store that holds the result of a network fetch, looking for a failed load being
written as an empty/partial result (so the screen stays blank or incomplete until expiry).
Verdicts: `ok — errors not cached`, `ok — fixed in #391`, `BUG` (with issue). BUG sites are
filed, not fixed here. Regression helper: `assertErrorNotCachedAsEmpty` in
`app/src/test/java/dev/mike/couchtour/CacheErrorAssertions.kt` and
`macos/Packages/CouchTourKit/Tests/CouchTourKitTests/CacheErrorAssertions.swift`.

| # | Platform | Site (file:line) | What's cached | Verdict |
|---|---|---|---|---|
| 1 | Android | `Catalog.kt:650,656` `PhishInSource.periodsCache` | phish.in years | ok — errors not cached (API throws, `.also{put}` only runs on success; test added) |
| 2 | Android | `Catalog.kt:651,662` `PhishInSource.showsCache` | shows per period / popular | ok — errors not cached (test added) |
| 3 | Android | `Catalog.kt:652,670` `PhishInSource.showDetailCache` | show detail | ok — errors not cached |
| 4 | Android | `Relisten.kt:471,481` `cachedArtists` | Relisten artist list | ok — errors not cached |
| 5 | Android | `Relisten.kt:477,490` `periodsCache` | artist years | ok — errors not cached |
| 6 | Android | `Relisten.kt:478,496` `showsCache` | shows per period/song/venue | ok — errors not cached |
| 7 | Android | `Relisten.kt:479,510` `showDetailCache` | show detail | ok — errors not cached |
| 8 | Android | `Catalog.kt:503-512` `loadArtistsByBackend` | nothing (uses `runCatching`/`getOrDefault`, but only throws-through when both fail; results come from caches 1/4) | ok — no cache write |
| 9 | Android | `OnThisDate.kt:267,284` `OnThisDate.cached` | On This Date shows | ok — fixed in #391 (`Fetched.complete` + non-empty gate) |
| 10 | Android | `NextStop.kt:228,253` `NextStop.cached` | Next Tour Stops | BUG — partial result cached when one artist fails (#465) |
| 11 | Android | `NextStop.kt:310` `getOrDefault(emptyList())` | next-show lookup, not cached | ok — no cache write |
| 12 | Android | `Waveform.kt:19,87` `LruCache` | waveform heights | ok — null on failure is not stored |
| 13 | Android | `Catalog.kt:611` search `getOrElse { SearchHits(failed=…) }` | not cached; failure surfaced via `failed` | ok — no cache write |
| 14 | macOS | `Catalog.swift:880-896` `PhishInCatalogCache` (periods/shows/showDetail) | phish.in years/shows/detail | ok — errors not cached (`try` before `put`; test added) |
| 15 | macOS | `RelistenAPI.swift:823-886` `cachedArtists`, `periodsCache`, `showsCache`, `showDetailCache` | Relisten artists/years/shows/detail | ok — errors not cached |
| 16 | macOS | `OnThisDate.swift:157,176` `OnThisDate.cached` | On This Date shows | BUG — partial result cached; `try?` at `:113` swallows period failures (#467) |
| 17 | macOS | `NextStop.swift:185,213` `NextStop.cached` | Next Tour Stops | BUG — partial result cached; `try?` in `tourFor` (`:82-111`) swallows period failures (#466) |
| 18 | macOS | `WaveformLoader.swift:11,38` `cache`/`envelopeCache` | waveform envelopes | ok — nil on failure is not stored |
| 19 | macOS | `RelistenAPI.swift:891` search | not cached | ok — no cache write |

Not network-result caches (out of scope): `ProgressStore`/Room `progress`, `PlaybackSettings`, loudness caches (computed locally from audio; failure returns no value).

## Root cause of the remaining BUGs

#391 gated the Home caches on "non-empty", which stops an all-failed load from sticking but not a
*partially* failed one: `currentTours`/`showsOnDate` only throw when every artist fails, so one
failed artist leaves a non-empty list that is then cached for the day. Android's On This Date
fixed this with `Fetched.complete`; the other three sites need the same.

No BUG was fixed in this PR (none is a one-line change).
