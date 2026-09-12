# TASKS.md

Last updated by: Claude (ai-workflow-setup Phase 3), 2026-09-12

## Now
- [ ] 148-docs: reconcile `ROADMAP.md`/`UAT.md` wording and macOS screenshots for the already-merged Show Detail Save→Like Replacement (#148, `405b20c`) | next: review the pending diff and commit, or discard if superseded by later work
- [ ] markup-tool: new `scripts/markup-tool.html` screen-annotation tool, currently untracked | next: decide whether to commit it or delete if it was a one-off

## Status
Latest merged work: `405b20c` macOS Save→bookmark replaced with Like + whole-show Add to Playlist (#148, #168). The working tree has real pending changes from that batch's doc/screenshot wrap-up (see `## Now`) — this isn't a dead session, it's trailing bookkeeping that hasn't been committed yet.

## Next steps
- Check the Cline kanban board (`http://127.0.0.1:3484/phish-in-app`) and `ROADMAP.md`'s "Suggested build order" for what's next — those remain the plane of record for backlog and active card work (see `CLAUDE.md` → "Working under the Cline Kanban board" / "Working through open issues").
- Clear the two `## Now` items above (commit or discard) before they go stale.

## Context
- **Scope of this file**: per-card work lives in throwaway git worktrees (`~/.cline/worktrees/<hash>/phish-in-app`), each owned by the Cline kanban board per `CLAUDE.md`'s existing convention. This `TASKS.md` tracks the **primary checkout** only — ad-hoc sessions here, not kanban-card work.
- `AGENTS.md` is a symlink to `CLAUDE.md` in this repo (not a separate file) — the new "Session continuity" section lives directly in `CLAUDE.md`, additive to the existing kanban-card and open-issues protocol.
- This file is also read by the `thread` portfolio scanner (`~/ai-tools/thread.py`, already scanning this repo via `/Volumes/ExtSSD160/scripts/*` in `~/.thread.conf`). Its notes on this repo (`~/ai-tools/NOTES.md`) already correctly identified the current dirty state as legitimate WIP, not abandonment — keeping `## Now` accurate here is what lets that read happen automatically instead of by hand next time.
