# UAT — manual verification owed

Things that can only be confirmed by a human using the app. Automated suites cannot reach any of
this: Android's tests are Robolectric/MockWebServer, and `swift test` covers `CouchTourKit` only —
never the macOS app target (D208). A green build is not a verified feature.

**Check items off in the local board** rather than editing this file by hand:

```bash
python3 scripts/uat-server.py
```

It serves `http://127.0.0.1:4785`, writes straight back to this file, and needs no dependencies.
Committing the result is what makes the outcome visible to Claude and to Kanban tasks.

**Status meanings**

| Mark | Means |
|---|---|
| `- [ ]` | Not yet tested |
| `- [x]` | Works — considered done |
| `- [!]` | **Needs work** — a real bug report. The note underneath is the symptom; whoever picks the feature up next reads it before starting. |

Add new items when finishing a batch. Keep the `uat-NNN` ids stable and never reuse one.

---

## Batch 1 — macOS tag filter, sort, artwork, context menu (v0.63, #67/#21/#62/#115, D206)

Zero click-through happened for this batch — the machine's display session was locked for its
whole run, so nothing below has ever been exercised by anyone.

- [x] `uat-001` **Show sort control** (macOS) — Artist → a year → shows. Change the toolbar sort to "Top Rated", then "Trending (48h)". List reorders each time, with no spinner and no refetch.
- [x] `uat-002` **Sort survives nothing it shouldn't** (macOS) — With a non-default sort applied, navigate into a show and back. Confirm what happens to the sort is sane (either preserved or reset — just not a crash or an empty list).
- [!] `uat-003` **Tag filter on a show list** (macOS) — Same screen. The tag picker appears only when the shows actually carry tags; picking one narrows the list; picking "All" restores it.
  > give me some examples to try. clicking the one I found, "partial" in the phish list has no different effect than clicking anywhere else on that item
- [x] `uat-004` **Tag filter on search** (macOS) — Search something broad ("dark star"). Tag picker narrows results. Then change the query so the selected tag no longer exists in the results — it must fall back to "All", not show an empty list.
- [!] `uat-005` **Procedural artwork** (macOS) — Find a Relisten (non-Phish) show, which has no artwork. Home cards and the player show a coloured gradient with the artist's monogram, not a grey music-note box. The same show always gets the same colours.
  > not really a bug but note in all situations, it's always properly displayed as "moe." with lowercase and period. This is th eonly band with this rule
- [!] `uat-006` **Artwork date badge** (macOS) — On the large artwork (Now Playing inspector), a date badge appears; on the small mini-player thumbnail it does not.
  > shows but date format must always be YYYY-MM-DD
- [x] `uat-007` **Continue Listening context menu — Home** (macOS) — Right-click a Continue Listening card on Home. Open / Mark Completed / Remove all appear and all three do what they say.
- [x] `uat-008` **Continue Listening context menu — Listening screen** (macOS) — Same three actions on a row in the full Listening screen, behaving identically to the Home shelf.

## Batch 2A — Android list sort and filter (v0.62, #91/#116/#90, D205)

Unit-tested (473 passing) but never run on a device.

- [x] `uat-009` **Artists sort toggle** (Android) — Artists screen. Toggle Popular ↔ A–Z; order changes. Favorites stay in their own pinned section under both, and Phish stays pinned above both.
- [x] `uat-010` **Artists filter field** (Android) — Type a partial band name. List narrows, including matching a favorited artist. Clearing restores the full list; a no-match query shows a sensible empty state, not a blank screen.
- [x] `uat-011` **Search result sort** (Android) — Search, then switch sort between Relevance / Newest / Oldest / Most liked. Under "Most liked", Relisten hits (which have no like count) settle at the end rather than scattering.
- [x] `uat-012` **In-playlist search — phish.in playlist** (Android) — Open a public playlist, use the new search field, confirm it filters that playlist's tracks.
- [!] `uat-013` **In-playlist search — local playlist, and reorder interlock** (Android) — Open a local mixtape, filter it, and confirm drag-to-reorder is disabled while a filter is active. **This is the one worth testing hardest** — reordering a filtered list would silently scramble the real track order.
  > drag to reorder doesn't work,period

## Batch 2B — macOS list sort and filter (#91/#116/#90, D210)

macOS parity for Batch 2A. Unit-tested (400 passing) but, like Batch 1, never clicked — the same
locked display session blocked it, and `osascript` additionally lacked Accessibility permission.

- [ ] `uat-021` **Artists sort toggle** (macOS) — Artists screen. Toggle Popular ↔ A–Z. Order changes, Favorites stay in their own section, and an artist appears in Favorites *or* the main list but not confusingly duplicated.
- [ ] `uat-022` **Artists filter field** (macOS) — Type a partial band name; list narrows, including favorited artists. Clearing restores everything; a no-match query shows a sensible empty state.
- [ ] `uat-023` **Search result sort** (macOS) — Switch between Relevance / Newest / Oldest / Most liked. Under "Most liked", Relisten hits (no like count) settle after phish.in hits rather than scattering.
- [ ] `uat-024` **Local playlist search + reorder interlock** (macOS) — Filter a local playlist and confirm drag-to-reorder is disabled while the filter is active. Same scrambling hazard as `uat-013` on Android — worth testing hard.
- [ ] `uat-025` **Android/macOS agreement** (both) — Sort the same artist list both ways on phone and Mac. Same ordering under the same option; this is a parity batch, so a divergence is the bug.

## Batch 4 — catalog cache (#61, D207)

Behaviour change with no UI, so the only real check is that nothing went stale or stuck.

- [x] `uat-014` **Back-navigation is faster** (Android) — Artist → year → shows → back → into the same year again. The second entry should be visibly quicker and not re-show a loading spinner.
- [x] `uat-015` **Back-navigation is faster** (macOS) — Same walk on the desktop app.
- [ ] `uat-016` **No cross-artist bleed** (either platform) — Open artist A's 1995, go back, open artist B's 1995. B must show B's shows. A cache keyed carelessly would show A's.
- [ ] `uat-017` **Fresh data still arrives** (either platform) — Leave the app open a while, then revisit a previously-viewed list. Nothing should be permanently frozen to a stale copy.

## Pre-existing, carried from ROADMAP's device checklist

Long-standing items that predate this sprint.

- [ ] `uat-018` **Notification audio ducking** (Android, #23/D93) — Play a show, trigger a system notification sound, confirm audio ducks smoothly and recovers.
- [!] `uat-019` **Native share sheet** (Android, #19/D155–D156) — Share a show and a track; confirm the chooser opens and the shared URL resolves in another app.
  > phish.in links are good. relisten ones don't appear to have correctly formatted URLs. They return a relisten page but not the actual show page
- [x] `uat-020` **Reactive background sync** (macOS, D172) — Play something on the phone and confirm the Mac's Continue Listening updates in the background without being touched.

## Sleep / rate change sync resilience (#127, D211)

- [ ] `uat-026` **Mac sleep/wake does not clobber newer remote progress** (both) — Listen to a track on Android to advance it past what was paused on macOS. Let the Mac sleep or change audio outputs. Wake Mac or launch Couch Tour; verify Android's newer track remains current and is not reverted by the Mac.

## Batch 5 — Ledger Design Revamp (Android & macOS)

- [ ] `uat-027` **Ledger Theme & Navigation** (Android) — Bottom nav bar with 4 tabs (Home, Search, Library, Settings), docked mini-player with 2px spec gradient progress bar. Dark and light appearance toggle.
- [ ] `uat-028` **Now Playing Waveform & Controls** (Android) — Waveform scrubber responds to scrubbing/seeking. Dark hero artwork gradient fade vs clean plain white background on light theme. Transport row order: add-to-playlist, previous, play/pause, next, like/heart.
- [ ] `uat-029` **Library Screen with Type Badges** (Android) — 4 filter chips (All, Playlists, Shows, Tracks), sort dropdown, and fixed-width 44dp type badges (LIST, SHOW, TRACK) with aligned text rows.
- [ ] `uat-030` **3-Pane Desktop Layout** (macOS) — Left sidebar (~236px) with navigation, favorites, and sync status; center content; right player rail (392px) with waveform scrubber and up-next queue.
- [ ] `uat-031` **Expanded Now Playing View** (macOS) — Full window expanded player modal with 440px artwork tile, ambient blurred background wash, waveform scrubber, and collapse affordance.
- [ ] `uat-032` **Show Detail Multi-Column & Conic Glow** (macOS) — Breadcrumbs, 160×160 artwork with conic glow blur, stats row, action pills (Resume, Saved, Add to playlist), 2-column setlist layout with compact durations and hairlines, active track highlight bar.
- [ ] `uat-033` **Desktop Library Table** (macOS) — Category filter tabs (All, Playlists, Shows, Tracks), sort chips ("Recently added", "Artist"), fixed-column table (`TYPE`, `NAME`, `ARTIST`, `RATING`, `LENGTH`, `ADDED`, play, dots menu), and "New playlist +" creation sheet.
- [ ] `uat-034` **macOS Light Mode Player Contrast & Search Filters** (macOS) — Expanded Now Playing and Player Rail have dark, high-contrast text (`#20222C`) in Light mode; search filter pills (Sort, Artist, Soundboard, Jam chart) dynamically filter and sort search results.
- [ ] `uat-035` **Show Detail Action Pills & Next Tour Stop Tap** (Android) — ShowHeader and RecordingHeader action pills (Resume, Saved, Add) respond to touch; tapping Next Tour Stop card navigates to show details.
- [ ] `uat-036` **Saved Shows & Library Bookmark Parity** (macOS & Android) — On Show Detail, tapping "Saved" toggles bookmark state for the show (not the artist). Saved shows display bookmark icons on Home "On This Date" cards. On Android Show/Recording header, pills dynamically toggle between Play/Resume and Save/Saved.
- [ ] `uat-037` **Now Playing Tape Lineage & Show Rating** (Android) — Now Playing screen tape header displays dynamic tape lineage (SBD / AUD / taper details) and renders "SHOW RATING ★ 4.6" when a rating is present.


