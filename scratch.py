import re

with open('CLAUDE.md', 'r') as f:
    content = f.read()

# Replace Kanban board section
old_kanban = """## Working under the Cline Kanban board

The Kanban board (`http://127.0.0.1:3484`, one project per repo) is the primary development
plane. It creates a **worktree per task** under `~/.cline/worktrees/<hash>/phish-in-app` and
starts `claude --permission-mode auto` inside it. These rules take precedence over the generic
worktree advice above whenever a `~/.cline/worktrees/` path is your working directory.

**The board owns the worktree; you own the branch.**

- **Never** run `git worktree add` or `git worktree remove` — not on your own worktree, not on
  another task's. The board created yours and will clean it up. Removing it mid-task strands the
  card and hides your work (this happened once already; see D208).
- Your worktree starts on a **detached HEAD**. That is normal, not a problem to fix — but do not
  commit and stop there, because a detached-HEAD commit is unreachable from any branch and
  invisible to everyone. `git checkout -b <descriptive-branch>` first.
- **Own git end-to-end**: branch, commit, push, open a PR, wait for CI
  ([scripts/ci-wait.sh](scripts/ci-wait.sh)), self-review the diff, merge with
  `--delete-branch`. This is the same autonomous loop as "Working through open issues" above.
  Leave the board's own **Automatically → Make commit / Make PR** setting **off** — with it on,
  you and the board both try to open a PR for the same work.
- **End your final message with a status line** so the card's summary states the outcome
  unambiguously — the board's column does not track whether you merged:
  `STATUS: MERGED #124` / `STATUS: PR-OPEN #124` / `STATUS: BLOCKED <one-line reason>`.

**Decision IDs are a shared mutable resource.** Parallel tasks each branch from `main` and each
compute "the next `Dnnn`" from what they see there, so two tasks routinely pick the same number —
this is a real collision git cannot detect, because both sides are appends to different regions
of `DECISIONS.md` (D208 records how this bit us). So:

- Write the `DECISIONS.md` entry as your **last** commit, and immediately before writing it run
  `git fetch origin && git show origin/main:DECISIONS.md | grep -c '^### D'` to allocate the
  number against `main` *as it is at merge time*, not as it was when you branched.
- If you still collide at merge, **renumber your own entry** (the later-merging one) and never
  edit the other batch's content."""

new_mahler = """## Working under Mahler

Mahler runs you as a background agent in a dedicated worktree under `.mahler-worktrees/`. These rules take precedence over the generic worktree advice above whenever a `.mahler-worktrees/` path is your working directory.

**Mahler owns the worktree and branch.**

- **Never** run `git worktree add` or `git worktree remove` — not on your own worktree, not on
  another task's. Mahler created yours and will clean it up. Removing it mid-task strands the
  issue and hides your work (this happened once already; see D208).
- **Stay in your worktree.** Never run `git reset` or `git checkout` outside it, to avoid
  wiping other sessions' work. You are already on your branch, so do not `git checkout -b`.
- **Your job ends at the push.** When you are done, commit, push your branch, and end with
  `STATUS: DONE <one-line summary>`. Do not open a PR, watch CI, merge, or comment on the
  issue — Mahler's conductor does that, in code, after you end.
- **End your final message with a status line** so Mahler knows the outcome:
  `STATUS: DONE <summary>` / `STATUS: NEEDS-YOU <question> [OPTIONS: ...]` / `STATUS: BLOCKED <reason>` / `STATUS: YIELDED <handoff>`.

**Decision IDs are a shared mutable resource.** Parallel tasks each branch from `main` and each
compute "the next `Dnnn`" from what they see there, so two tasks routinely pick the same number —
this is a real collision git cannot detect, because both sides are appends to different regions
of `DECISIONS.md` (D208 records how this bit us). So:

- **Use `mahler next-id`.** For shared sequential IDs, run `mahler next-id <project> <prefix>`
  (e.g., `mahler next-id couch-tour D`) to allocate the number and avoid collisions. Do not
  manually grep `DECISIONS.md` against main."""

content = content.replace(old_kanban, new_mahler)

# Remove "Working through open issues" entirely
old_open_issues = """## Working through open issues

Mike drives the backlog by asking for "the next issue" or "the next batch," works from the
prompts given for each one, and can't review code himself — so the merge/release loop is
autonomous by default, not a proposal he approves each time:

- **Merge PRs without asking first**, once CI is green and a self-review of the diff (read the
  actual changes, not just the description) turns up nothing that looks wrong — matches the
  issue, touches only what it should, has real tests, doesn't contradict something documented
  elsewhere (this file, DECISIONS.md). Only hold a PR for Mike when something in the diff looks
  genuinely risky or ambiguous, not merely "could be nicer." [scripts/ci-wait.sh](scripts/ci-wait.sh)
  `<pr-number>` replaces the manual poll-then-diagnose loop — it blocks until checks finish and,
  on a failure, pulls the failing job's log tail in the same call.
- **Clean up your own worktree and branch immediately after your PR merges — but only a
  worktree you created yourself.** If you are running under the Kanban board, the board owns
  the worktree and removing it corrupts the card; see "Working under the Cline Kanban board"
  below, which takes precedence over this bullet. Otherwise: `git worktree
  remove` on the worktree you were using (if any) and delete the branch, both locally and on
  the remote (`gh pr merge --delete-branch` handles the remote side in one step). This is what
  actually keeps `.claude/worktrees/` and the branch list usable; skipping it is exactly how 4
  stale worktrees and 20+ merged-but-undeleted branches piled up before a 2026-08-20 cleanup
  pass caught them. A worktree directory's name is only ever accurate at the moment it's
  created — the same folder gets reused for unrelated later branches (its name then means
  nothing), so don't rely on it to identify what's inside; `git -C <path> branch --show-current`
  is the source of truth. If you're mid-task and about to run out of room to finish (context
  limit, told to stop), commit and push whatever's done rather than leaving it uncommitted in
  the worktree — an open PR (even a rough one) is far more likely to get picked up than a diff
  nobody knows to look for.
- **Cut a beta release at the end of each batch of work**, not just when asked: `workflow_dispatch`
  on `build-debug-apk.yml` with `side_install: true` and `prerelease: true` (see "Cutting a
  beta release" above), tag bumped by one (`v0.NN`), release notes summarizing what merged.
  This is how Mike gets a build onto his own devices to test — treat it as part of finishing
  the batch, not a separate ask. **Stop at the beta.** Promoting one to production (see
  "Promoting a beta to production" above) needs Mike to have actually tested it — that's the
  entire point of a separate beta channel — so it only happens when he explicitly confirms one,
  never automatically at the end of a batch alongside the beta.
- **Multiple worktrees can run in parallel without active coordination.** Don't have one
  agent's prompt tell it to "coordinate" with another running elsewhere — that spends tokens
  re-deriving context for no real benefit. Instead, when handing out a batch for a second
  worktree, name the specific file(s)/function(s) the other one is already working in and tell
  the new one to avoid those; git resolves everything else at merge time. If a real conflict
  does show up, it gets resolved then, not preemptively negotiated between agents.
- **ROADMAP.md's "Suggested build order"** is the standing prioritization — pick the next
  unclaimed item from there rather than re-deriving priority from scratch each time, and update
  it (plus the Feature ideas issue-number cross-references) when the picture changes enough to
  matter.

"""

content = content.replace(old_open_issues, "")

# Replace "Cline Kanban board" reference in Session continuity section
content = content.replace('Working under the Cline Kanban board', 'Working under Mahler')

with open('CLAUDE.md', 'w') as f:
    f.write(content)

print("Replacement done.")
