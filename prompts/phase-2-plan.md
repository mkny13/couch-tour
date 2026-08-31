# Phase 2 — Planning Prompt

This is a **planning-only** prompt for [ROADMAP.md](../ROADMAP.md)'s Phase 2 ("Audio
Fidelity, Discovery & Media Power Features") table. Do not write any application code or open
a PR against `app/`, `macos/`, or `sync/` while running this. The deliverable is a plan Mike
reviews and approves — implementation is a separate, later pass, the same two-step shape
Phase 2 Batch 4 used (C1 produced a design the direction got chosen from; C2 was the
implementation prompt written only after that).

## Why plan first here

Phase 2 isn't one cohesive feature like Batch 4's sidebar removal — it's nine mostly-unrelated
items that happen to be scheduled together. The batching, sequencing, and worktree-parallelism
decisions actually matter (get them wrong and two batches fight over the same file, or a
"quick" item turns out to gate three others), and they're exactly the kind of judgment call
that should be made once, reviewed, and then executed against — not improvised mid-batch.

## Step 0 — audit before you plan anything

**Check whether some of this is already done before scoping it as new work.** This repo has a
recurring pattern where a PR merges code that closes out an issue's scope, but the issue itself
stays open on GitHub until someone explicitly verifies and closes it (#59 and, it looks like,
#10 and #27 may be in exactly this state right now). Don't take the open/closed status on
GitHub at face value:

```bash
gh issue view <n> --repo mkny13/couch-tour
git log --all --oneline --grep="#<n>"
gh pr list --repo mkny13/couch-tour --state merged --search "<n> in:title"
```

For each issue below, determine: **not started**, **partially done** (some scope shipped, some
remains — say exactly what's left), or **looks done, just needs verification + issue closeout**
(no new engineering work, just confirm and close). This changes the plan a lot — a batch of
"verify and close #10, #27" is nearly free and can run first regardless of dependencies.

## Scope — the nine Phase 2 issues

Read each with `gh issue view <n>` rather than re-deriving from the ROADMAP table description;
the issue bodies carry the real detail.

| Issue | Feature | Platforms (per ROADMAP) |
|---|---|---|
| [#65](https://github.com/mkny13/couch-tour/issues/65) | Offline downloads | Android, macOS |
| [#18](https://github.com/mkny13/couch-tour/issues/18) | Source & show volume leveling | Android, macOS |
| [#27](https://github.com/mkny13/couch-tour/issues/27) | FLAC streaming support | Android, macOS |
| [#68](https://github.com/mkny13/couch-tour/issues/68) | "Next Stop" tour picker for defunct artists | Android, macOS |
| [#67](https://github.com/mkny13/couch-tour/issues/67) | Browse & filter by tag | Android, macOS |
| [#21](https://github.com/mkny13/couch-tour/issues/21) | Trending & momentum browse | Android, macOS |
| [#91](https://github.com/mkny13/couch-tour/issues/91) | Sortable search results | Android, macOS |
| [#61](https://github.com/mkny13/couch-tour/issues/61) | Multi-level catalog cache | Android, macOS |
| [#62](https://github.com/mkny13/couch-tour/issues/62) | Relisten show artwork | Android, macOS |

`#85` (post-show Next Stop prompt) and `#60` (Sparkle auto-updates) were also in this phase's
original table but look shipped already — confirm via Step 0 and drop them from the plan if so,
noting it so ROADMAP.md's table can be corrected.

**Also check the open issue queue for anything filed since the table was last written** — at
minimum `gh issue list --repo mkny13/couch-tour --state open`. A few Feedback-filed items
(#90, #115, #116 as of this writing) aren't in the Phase 2 table at all. Decide whether any
belong folded into this phase (small, related to an item above) versus left for a separate
pass, and say which and why.

## What the plan needs to cover

1. **Real dependencies vs. false ones.** Some of these plausibly touch the same code on both
   platforms even though they look unrelated on the surface — e.g. #61 (catalog cache) sits
   underneath `Catalog.kt` / `CouchTourKit`'s catalog model that #67, #21, #62, and #91 all
   read from; #18 and #27 both touch the playback/audio pipeline. Read the actual current code
   (not just the issue descriptions) before declaring something independent.
2. **Batching for parallel worktrees**, in the style Batch 4 used: which items can run in
   fully separate worktrees with no file overlap, which need to be sequenced because one's
   output is the other's input, and which touch the same files closely enough that they should
   just be one batch rather than two batches fighting over a merge.
3. **Per-batch scope**, at the level of detail Batch A/B/C's prompts modeled — specific
   files/functions where you already know them, what's out of scope, what to verify by hand
   vs. what's unit-testable. You don't have to write the final batch prompts in this pass; a
   plan that's specific enough to turn into those prompts without re-research is the bar.
4. **Platform sequencing within an item** — several of these are "Android, macOS" pairs where
   doing both at once probably isn't the right call (recent history here has been Android
   first, then a macOS parity pass referencing the Android decision, e.g. #17 → #37 then
   folded into #25). Say which order makes sense per item and why, or say "these are small
   enough to do simultaneously" if that's genuinely true.

## Output

Write the plan to `prompts/phase-2-batches.md` (new file, sibling to this one) and present it
to Mike for review — don't start implementation work or open any code PRs in this pass. Once
he picks a direction, the next session turns the approved plan into the actual batch prompts,
the same way Batch C1's chosen direction became Batch C2's prompt in
[macos-ux-polish-batches.md](macos-ux-polish-batches.md).
