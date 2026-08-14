# Roadmap

Not-yet-built features and open questions — as opposed to [DECISIONS.md](DECISIONS.md),
which is a log of choices already made.

## Not in the app yet

Offline downloads, sleep timer, creating or editing playlists, liking things from inside the
app. Search covers shows, tracks, playlists, songs, and venues; tags are returned by the API
but have no screen.

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
