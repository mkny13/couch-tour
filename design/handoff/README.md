# Handoff: Couch Tour — Android & macOS (dark + light)

## Overview
Couch Tour is a jam-band bootleg/archive listening app (Phish, Grateful Dead, Goose, etc.) for streaming taper recordings from sources like phish.in and etree. This package covers two platform designs: an Android phone app and a macOS desktop app, each with dark and light appearance variants.

## About the Design Files
The files in this bundle are **HTML design references** — high-fidelity prototypes showing exact layout, color, type, and copy. They are not production code. The task is to recreate these designs in the target codebase's existing environment (e.g. Jetpack Compose/Kotlin for Android, SwiftUI/AppKit for macOS) using its established patterns — or, if no environment exists yet, to choose the most appropriate native framework and implement there. Do not literally embed this HTML in the app.

## Fidelity
**High-fidelity.** Colors, type sizes, spacing, icons, and copy in the HTML are final for this round of design. Recreate pixel-accurate layouts; treat exact hex values and spacing below as source of truth.

## Files
- `Couch Tour Android.dc.html` — Android phone screens (mobile, 393×852 frames), dark + light
- `Couch Tour macOS.dc.html` — macOS desktop screens (1440×900 windows), dark + light
- `artist-abbreviations.js` — reference data/logic for abbreviating artist names in narrow columns (see below)
- `screenshots/` — static PNG captures of every screen for quick reference:
  - `android-all-screens.png` — all 10 Android screens (5 screens × dark/light) in one strip
  - `macos-2a-home-dark.png` / `-light.png`
  - `macos-2b-search-dark.png` / `-light.png`
  - `macos-2c-showdetail-dark.png` / `-light.png`
  - `macos-2d-nowplaying-dark.png` / `-light.png`
  - `macos-2e-library-dark.png` / `-light.png`

Open either HTML file directly in a browser to view all screens side by side (each screen is labeled).

## Design Tokens

### Colors — Dark (base)
- Background (app frame): `#161826`
- Background (elevated/player panel): `#12141f`
- Surface (cards, rows-hover, mini player): `#1c1e2c`
- Border/hairline: `#232532` (list dividers), `#292b31` (panel borders), `#3f424d` (control outlines)
- Text primary: `#e9e9ed` / `#f3f5fe` (headline weight)
- Text secondary: `#cfd3e5`, `#b2b6ca`
- Text muted/labels: `#9397ab`, `#75798c`
- Accent (purple, brand/selection): `#9184d9` (base), `#b5abfc` (bright/icon), `#d2cefd` (light tint text)
- Accent gradient stops used in hairlines/rules: blue `#5b8cff` → purple `#9184d9` → pink `#f06bb0` → amber `#f2a93b`
- Rating/amber accent: `#f2a93b`
- Cover-art placeholder gradient: `linear-gradient(160deg, #D97706, #991B1B 55%, #1E1B4B)` (amber → red → indigo), plus a blurred conic-gradient glow behind it using the four accent hues above

### Colors — Light (mirrored mapping)
- Background (app frame): `#ffffff`
- Background (elevated): `#f7f7fb`
- Surface (cards, rows): `#f0f1f7`
- Border/hairline: `#e4e7f5`, `#d7dae8`
- Text primary: `#20222c`
- Text secondary: `#3f424d`, `#5a5e70`
- Text muted/labels: `#767a8c`
- Accent (purple): `#6f62c7` (base), `#5d5294` (text/icon on light — deepened for contrast)
- Rating/amber accent: `#a06615` (deepened amber for contrast on white)
- Same gradient hues, used at lower opacity for washes/highlights on light surfaces
- Note: in the **Now Playing** screen, the light variant drops the full-bleed colored artwork hero used in dark mode (mixing dark cover art + light chrome tested as low-contrast/ugly) — light Now Playing uses a plain white background with the cover art either omitted (Android) or shown as a separate bounded tile beside the text (macOS), never as a full-bleed tinted backdrop behind text.

### Typography
- Single family throughout (system UI / Inter-style sans), weights 400 (body) and 500 (medium — titles, emphasis).
- Scale used: 30px (Now Playing artist/date), 24px (show detail title, macOS section titles), 22px (screen headers), 20px, 16–17px, 15px (row titles/body), 14px, 13px, 12px, 11px (eyebrow labels — always uppercase with `letter-spacing: 0.1–0.16em`), 10px (chips/badges).
- Letter-spacing: tight (-0.01 to -0.02em) on large display type; wide (+0.08 to +0.16em) on small uppercase labels.

### Spacing / Shape
- Radii: 28px (phone frame corners), 12px (macOS window corners), 12px/10px (cards), 8px (chips/rows), 4px (small badges), pill/50% (buttons, avatars, play buttons).
- Android phone frame: 393×852 (iPhone/Android-standard mobile viewport), safe-area status bar 30px.
- macOS window: 1440×900, traffic-light controls top-left (12px circles, `#ff5f57` / `#febc2e` / `#28c840`), 3-pane layout on Home/Search/Library (sidebar ~236px, main content flexible, player rail 392px fixed).
- Shadows: dark `0 0 0 1px #3f424d, 0 24px 60px rgba(0,0,0,.6)` (phone) / `0 30px 80px rgba(0,0,0,.65)` (macOS); light `0 0 0 1px #d7dae8, 0 24px 60px rgba(20,22,35,.16–.18)`.

### Icons
Simple 24px stroke-based line icons (custom SVG symbol sprite defined inline at the top of each file: play/pause/next/prev/shuffle/heart/bookmark/playlist-add/search/house/stack/sliders/sync/dots/caret/expand/close). Recreate with your platform's icon set (e.g. Material Symbols on Android, SF Symbols on macOS) matching the same stroke weight and meaning — exact glyphs need not match pixel-for-pixel.

## Screens — Android (phone, 393×852)

Each screen exists in dark and light. All screens share:
- Status bar (time left, signal/wifi/battery right)
- Bottom mini-player + 4-tab bar (Home / Search / Library / Settings) on Home, Library, Settings

1. **Home** — Date header + "Surprise me" shuffle chip. "In Progress" section: horizontal list of in-progress shows/tracks with artist, date, track name, thin per-row progress bar, and a play button; header links to History. "Next Tour Stop" card: row of favorited-artist filter chips (selected chip highlighted), then the next upcoming show with rating and a play button. "On This Date" section: year and artist shown with equal type weight on one line, venue below, rating or duration trailing.
2. **Now Playing** — Full-screen player. Dark: full-bleed cover-art gradient hero behind title text (with a bottom fade to the page background). Light: plain white background, no hero art. Shows artist/date title, venue, tape source + FLAC badge, show rating. Below: current track name, set/track position, jam-chart tag + duration chip, an expandable "jam chart note" card. Waveform-style scrubber (two SVG waveform sprites, unplayed + played, played one clipped to progress %). Transport row: **add-to-playlist (left) → previous → play/pause (center, filled) → next → like/heart with count (right)**.
3. **Show Detail** — Back caret + breadcrumb ("Artist / Year") + overflow menu. Header row: title/venue/rating/tape info on the left, a square cover-art tile on the right. Pill button row: Resume / Saved / Add. Tracklist grouped by set, with set duration in the section header, a highlighted "now playing" row, and jam-chart tags on notable tracks.
4. **Library** — Search field, filter chip row (All/Playlists/Shows/Tracks with counts), sort control + "New playlist" action. Rows show a fixed-width type badge (LIST / SHOW / TRACK, colors purple/amber/neutral respectively) so titles align to a common left edge, then title/subtitle, then a trailing play button, rating, or duration depending on row type.
5. **Settings** — Grouped list under section labels (PLAYBACK, DOWNLOADS & STORAGE, SYNC, ACCOUNT, ABOUT) — toggle rows, value rows with chevron, a sync-status row with a live indicator dot.

## Screens — macOS (desktop, 1440×900)

Three-pane app: fixed left sidebar (nav + favorite-artist list + sync status), flexible main content, fixed-width right player rail. Each screen exists in dark and light.

1. **Home (2A)** — Same content model as Android Home, laid out in the wider 3-pane frame; player rail on the right shows the full Now Playing controls at a smaller footprint with a soft ambient color wash behind it in dark mode (wash removed in light mode per latest revision — plain background there now).
2. **Search (2B)** — Tab row (All / Playlists / Shows / Tracks) with a gradient underline on the active tab, results list.
3. **Show Detail (2C)** — Same content as Android Show Detail, wide layout, large cover art tile, tracklist grouped by set.
4. **Now Playing expanded (2D)** — Full window "expanded" player: draggable-window chrome, ambient blurred color wash across the whole background, large square cover-art tile (with the same conic-gradient glow treatment) beside the title/tape/rating block, transport controls, waveform scrubber. Has a "Collapse" affordance to return to the mini rail.
5. **Library (2E)** — Same row model as Android Library (type badge + title/subtitle + trailing value), full-width table-like layout with additional columns (artist, rating, duration, "added" date) since desktop has room.

## Interactions & Behavior
- Progress bars on in-progress rows reflect % played (thin 2px bar under each row/card).
- Waveform scrubber: two stacked SVG sprites (`#wave-np` full track, `#wave-pl` played portion), the played one clipped via a width-percentage wrapper — recreate as a real seekable scrubber bound to playback position.
- Favorite-artist chips filter the "Next Tour Stop" card to shows from selected artists/tours (multi-select chip row).
- Jam-chart note card in Now Playing has a dismiss (×) control.
- "Resume" pill on Show Detail resumes an in-progress listen; "Saved"/"Add" are bookmark and add-to-playlist toggles.
- Sync row shows last-synced time and a pulsing status dot; "Sync now" is a manual trigger.
- Type badges (LIST/SHOW/TRACK) are fixed-width so row text aligns consistently regardless of badge label length.

## Design Rationale Notes (from design review)
- Now Playing's light mode deliberately does **not** use the full-bleed tinted artwork background from dark mode — testing showed dark title text over the colorful gradient (and a dark scrim over it) both read poorly; the simplest fix (drop the art, use plain background) was chosen.
- IN PROGRESS header and "N of 12" count both link through to full History.
- Track/show rows favor equal visual weight between date/year and artist name (not one subordinate to the other).

## Assets
No external image assets — cover art is represented as a placeholder gradient tile (`linear-gradient(160deg, #D97706, #991B1B 55%, #1E1B4B)` + blurred conic glow) everywhere; real album/show artwork should replace these tiles in the shipped app. All icons are inline SVG (see `<symbol>` definitions at the top of each HTML file) — swap for native platform icon equivalents.

## artist-abbreviations.js
Reference logic (not used directly by the HTML mocks, but informs the "Library"/row-truncation design intent): shows the full artist name when it fits a column, otherwise substitutes a known shorthand (e.g. "Grateful Dead" → "GD", "Gov't Mule" → "mule"), sourced from etree.org taper-file naming conventions; falls back to ellipsis truncation for unlisted artists. Port this table and the `artistLabel(name, fits)` function as-is into whichever narrow-column contexts need it (e.g. Android Home's date-first rows).
