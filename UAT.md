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

- [ ] `uat-001` **Show sort control** (macOS) — Artist → a year → shows. Change the toolbar sort to "Top Rated", then "Trending (48h)". List reorders each time, with no spinner and no refetch.
- [ ] `uat-002` **Sort survives nothing it shouldn't** (macOS) — With a non-default sort applied, navigate into a show and back. Confirm what happens to the sort is sane (either preserved or reset — just not a crash or an empty list).
- [ ] `uat-003` **Tag filter on a show list** (macOS) — Same screen. The tag picker appears only when the shows actually carry tags; picking one narrows the list; picking "All" restores it.
- [ ] `uat-004` **Tag filter on search** (macOS) — Search something broad ("dark star"). Tag picker narrows results. Then change the query so the selected tag no longer exists in the results — it must fall back to "All", not show an empty list.
- [ ] `uat-005` **Procedural artwork** (macOS) — Find a Relisten (non-Phish) show, which has no artwork. Home cards and the player show a coloured gradient with the artist's monogram, not a grey music-note box. The same show always gets the same colours.
- [ ] `uat-006` **Artwork date badge** (macOS) — On the large artwork (Now Playing inspector), a date badge appears; on the small mini-player thumbnail it does not.
- [ ] `uat-007` **Continue Listening context menu — Home** (macOS) — Right-click a Continue Listening card on Home. Open / Mark Completed / Remove all appear and all three do what they say.
- [ ] `uat-008` **Continue Listening context menu — Listening screen** (macOS) — Same three actions on a row in the full Listening screen, behaving identically to the Home shelf.

## Batch 2A — Android list sort and filter (v0.62, #91/#116/#90, D205)

Unit-tested (473 passing) but never run on a device.

- [ ] `uat-009` **Artists sort toggle** (Android) — Artists screen. Toggle Popular ↔ A–Z; order changes. Favorites stay in their own pinned section under both, and Phish stays pinned above both.
- [ ] `uat-010` **Artists filter field** (Android) — Type a partial band name. List narrows, including matching a favorited artist. Clearing restores the full list; a no-match query shows a sensible empty state, not a blank screen.
- [ ] `uat-011` **Search result sort** (Android) — Search, then switch sort between Relevance / Newest / Oldest / Most liked. Under "Most liked", Relisten hits (which have no like count) settle at the end rather than scattering.
- [ ] `uat-012` **In-playlist search — phish.in playlist** (Android) — Open a public playlist, use the new search field, confirm it filters that playlist's tracks.
- [ ] `uat-013` **In-playlist search — local playlist, and reorder interlock** (Android) — Open a local mixtape, filter it, and confirm drag-to-reorder is disabled while a filter is active. **This is the one worth testing hardest** — reordering a filtered list would silently scramble the real track order.

## Batch 4 — catalog cache (#61, D207)

Behaviour change with no UI, so the only real check is that nothing went stale or stuck.

- [ ] `uat-014` **Back-navigation is faster** (Android) — Artist → year → shows → back → into the same year again. The second entry should be visibly quicker and not re-show a loading spinner.
- [ ] `uat-015` **Back-navigation is faster** (macOS) — Same walk on the desktop app.
- [ ] `uat-016` **No cross-artist bleed** (either platform) — Open artist A's 1995, go back, open artist B's 1995. B must show B's shows. A cache keyed carelessly would show A's.
- [ ] `uat-017` **Fresh data still arrives** (either platform) — Leave the app open a while, then revisit a previously-viewed list. Nothing should be permanently frozen to a stale copy.

## Pre-existing, carried from ROADMAP's device checklist

Long-standing items that predate this sprint.

- [ ] `uat-018` **Notification audio ducking** (Android, #23/D93) — Play a show, trigger a system notification sound, confirm audio ducks smoothly and recovers.
- [ ] `uat-019` **Native share sheet** (Android, #19/D155–D156) — Share a show and a track; confirm the chooser opens and the shared URL resolves in another app.
- [ ] `uat-020` **Reactive background sync** (macOS, D172) — Play something on the phone and confirm the Mac's Continue Listening updates in the background without being touched.
