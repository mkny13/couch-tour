# TASKS.md

Last updated by: Antigravity, 2026-09-12

## Now

## Status
#144 (macOS Home screen non-functional filter pills & carousel arrows) resolved and merged (PR #170, D230). Cut beta release `v0.82`. Primary checkout working tree clean.

## Next steps
- Check the Cline kanban board (`http://127.0.0.1:3484/phish-in-app`) and `ROADMAP.md` for what's next:
  - #90: Feedback (Couch Tour v0.40-beta) — add search to playlist pages
  - #67: Browse/filter by tag

## Context
- **Scope of this file**: per-card work lives in throwaway git worktrees (`~/.cline/worktrees/<hash>/phish-in-app`), each owned by the Cline kanban board per `CLAUDE.md`'s existing convention. This `TASKS.md` tracks the **primary checkout** only — ad-hoc sessions here, not kanban-card work.
- `AGENTS.md` is a symlink to `CLAUDE.md` in this repo (not a separate file) — the new "Session continuity" section lives directly in `CLAUDE.md`, additive to the existing kanban-card and open-issues protocol.
- This file is also read by the `thread` portfolio scanner (`~/ai-tools/thread.py`, already scanning this repo via `/Volumes/ExtSSD160/scripts/*` in `~/.thread.conf`). Its notes on this repo (`~/ai-tools/NOTES.md`) already correctly identified the current dirty state as legitimate WIP, not abandonment — keeping `## Now` accurate here is what lets that read happen automatically instead of by hand next time.
