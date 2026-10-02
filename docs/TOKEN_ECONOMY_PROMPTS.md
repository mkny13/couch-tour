# Token economy: standing agent context (Part 1 of #502)

Agents are told to read `CLAUDE.md` and skim `DECISIONS.md`, `ROADMAP.md` and `README.md`. This pass shrinks that standing context without losing any decision history. Tokens are a rough bytes/4.

## Before and after

| File | Before (bytes / lines / ~tokens) | After (bytes / lines / ~tokens) |
|---|---|---|
| `DECISIONS.md` | 451,454 / 5,233 / ~112,900 | 84,156 / 518 / ~21,000 |
| `docs/decisions/DECISIONS-ARCHIVE.md` | (did not exist) | 367,833 / 4,721 / ~92,000 (read on demand) |
| `CLAUDE.md` (`AGENTS.md` symlinks to it) | 7,009 / 101 / ~1,750 | 7,116 / 101 / ~1,780 |
| `ROADMAP.md` | 26,205 / 243 / ~6,550 | 26,217 / 243 / ~6,550 |
| `README.md` | 15,324 / 268 / ~3,830 | unchanged |
| `UAT.md` | 41,746 / 282 / ~10,440 | unchanged |
| `prompts/*.md` (4 files) | 1,607 total / ~400 | moved to `docs/plans/` (no longer at the root) |

Standing context an agent loads by default drops by about 91k tokens.

## What moved where

- **DECISIONS.md → archive.** Everything from `## Iteration 2` through D266 (D5-era Iteration 2 up to the entry before `### D269`) moved verbatim and in order into `docs/decisions/DECISIONS-ARCHIVE.md`. `DECISIONS.md` keeps its header, `## Architecture`, `## Scope`, `## Data quirks`, and D269-D326. It opens with a pointer to the archive. No entry text was edited. Checked by comparing the sorted D-number headings (`^#+ Dnn` / `^**Dnn`) before and after: identical across the two files, each once.
- **prompts/ → docs/plans/** (`git mv`, not deleted). The four files are tiny stubs, so deletion would have saved little, and `ROADMAP.md` links to two of them; those links were updated.
- **CLAUDE.md.** Only the DECISIONS convention line changed, to mention the archive. No redundancy worth removing was found: each rule is stated once, and the previous pass (#208) had already trimmed it.
- **ROADMAP.md.** It has no archive section, so no items were moved. Only the two `prompts/` links changed.

## Next pass

- `DECISIONS.md` still has D269+ at 84 KB. Later passes can roll the cutoff forward as new decisions land.
- `UAT.md` (42 KB) and `ROADMAP.md` (26 KB): the "Completed Milestones" section of `ROADMAP.md` is ~90 lines of shipped work and could go to an archive section.
- Agent prompts should point to `CLAUDE.md` and say to grep `DECISIONS.md` rather than skim it.
