# Roadmap

Not-yet-built features and open questions — as opposed to [DECISIONS.md](DECISIONS.md),
which is a log of choices already made.

## Not in the app yet

Offline downloads, sleep timer, creating or editing playlists, liking things from inside the
app. Search covers shows, tracks, and playlists; songs, venues, and tags are returned by the
API but have no screen.

## Open questions

- Should a show auto-advance into the next show, or stop at the encore?
- Do you want the waveform images (`waveform_image_url`) in the player, or is a plain
  scrubber enough?
- Desktop support (browser, Electron, or a native macOS app — must stay free), with
  playback history and resume synced with mobile.

## Multi-artist follow-ups

Detailed in [MULTI-ARTIST-PLAN.md](MULTI-ARTIST-PLAN.md) (O3–O5):

- FLAC support — Relisten serves `flac_url`; the app is MP3-only today because Cast's MIME
  type is hardcoded and the stock receiver expects progressive MP3.
- A real catalog cache, beyond the single `@Volatile`-cached artist list.
- Approach Relisten's operators about the API use, the same courtesy phish.in's maintainer
  extended, before a store release.
- Unify Android Auto's separate "Artists" and "Years" browse roots
  (`PlaybackService.kt`'s `yearChildren`/`tourChildren`) with the phone's single merged
  artist list (D83) — the car still browses Phish and Relisten as two trees.
- Relisten shows have no artwork (`RelistenShowSummary.toShowSummary` sets no `artUrl`), so
  every non-Phish show falls back to a plain `primaryContainer` background in the player
  (D86) and a placeholder icon everywhere else.
- The Now Playing screen (D86) has no like button — `LikeButton` needs a track id and liked
  state that `PlayerState` doesn't carry today, and only phish.in tracks are likable at all.
