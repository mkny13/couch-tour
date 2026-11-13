# Security audit: developer scripts and CI workflows (#805, part of #801)

Previous passes: #221, #476 (part of #473, base `c314a1e2`).
This re-audit checks all developer scripts and GitHub Actions workflows against changes landed since `c314a1e2` (~7,000 lines across `scripts/`, `.github/`, and `macos/scripts/`).

## Method and limits
- Read every script and workflow call site.
- Grepped and statically analyzed for `shell=True`, `eval`, `curl | sh`, `pull_request_target`, unquoted expansions in subshells/CLI calls, and GitHub Actions expression interpolation (`${{ github.event.* }}`, `${{ inputs.* }}`, `${{ github.head_ref }}`) inside `run:` blocks.
- `shellcheck` (version 0.11.0) was available and executed across all shell scripts in the repository. Disambiguated syntax ambiguities flagged by shellcheck (e.g. `$var[` array indexing in `promote-beta.sh` and `eval` expansions in `report.sh`), quoted unquoted expansions, and safe-guarded empty array expansions under `set -u` in `weekly-smoke.sh`.
- Dependabot configuration in `.github/dependabot.yml` was audited.
- Added a permanent mechanical gate `scripts/checks/check-workflow-injection.sh` (wired into `.github/workflows/checks.yml`) to prevent regression of workflow injection vulnerabilities.

## Findings fixed in this PR
| # | Finding | Fix | Gate |
|---|---|---|---|
| 1 | `weekly-smoke.yml` interpolated `${{ inputs.tag }}` and `${{ inputs.dry_run }}` directly into a `run:` bash script block. An input containing quotes or shell metacharacters would allow arbitrary command execution. | Passed `INPUT_TAG: ${{ inputs.tag }}` and `INPUT_DRY_RUN: ${{ inputs.dry_run }}` through step-level `env:` and referenced `$INPUT_TAG` / `$INPUT_DRY_RUN` in bash. | `scripts/checks/check-workflow-injection.sh`, tested by `scripts/checks/test_check_workflow_injection.sh` |
| 2 | `appcast-check.yml` interpolated `${{ github.repository }}` into a `run:` python CLI argument. | Passed `GITHUB_REPOSITORY: ${{ github.repository }}` through step-level `env:` and referenced `"$GITHUB_REPOSITORY"`. | `scripts/checks/check-workflow-injection.sh` |
| 3 | Automated gate missing for workflow script injection. | Implemented `scripts/checks/check-workflow-injection.sh` and its self-test `test_check_workflow_injection.sh`, and wired it into `checks.yml` alongside `workflow-permissions`. | `checks.yml` job `workflow-injection` |
| 4 | Shell array syntax ambiguity and quoting: `promote-beta.sh` used `$fail_id[` in a regex (SC1087); `report.sh` used `eval "$keys[@]"` (SC1087); `sync-roundtrip.sh` had unquoted `exit $rc`; `weekly-smoke.sh` used unquoted command substitution for flags which under `set -u` risked unbound variable errors. | Disambiguated `${fail_id}` and `${keys}`, quoted `exit "$rc"`, and used bash-safe `${extra_args[@]+"${extra_args[@]}"}` array expansion. | `shellcheck`, `scripts/test_promote_beta.sh`, `scripts/smoke/test-report.sh`, `scripts/smoke/test_weekly_smoke.sh` |
| 5 | Deploy token scope in `sync-deploy.yml`: #476 noted a residual where `CLOUDFLARE_API_TOKEN` was job-level `env:`. | Confirmed token is strictly scoped to step-level `env:` only on the four wrangler steps (`Migrate staging schema`, `Deploy to staging`, `Migrate production schema`, `Deploy to production`). Steps for checkout, node setup, tests, schema diff, and smoke tests have no access to the token. | Code inspection, `test.yml` / CI |

## Python scripts
All Python scripts invoke external commands using list-form arguments (`subprocess.run(["gh", ...])` or `sys.argv`), with no `shell=True` or `os.system`. Values derived from user requests, issues, or tags are passed as parameter values (e.g. `--title`, `--body`, `--tag`) rather than raw commands, and numeric IDs are strictly cast to `int`.

| File | Result | Evidence |
|---|---|---|
| `scripts/uat-server.py` | Pass | Token (`X-UAT-Token` constant-time `secrets.compare_digest`) and `Host` check (`Allowed: 127.0.0.1, localhost`) protect all POST requests before parsing bodies or paths. `subprocess.run(["gh", ...])` uses list form with no `shell=True`. Issue numbers are cast via `int(number)`. Covered by `scripts/test_uat_server.py`. |
| `scripts/beta_guard.py` | Pass | List-form `subprocess.run(["gh", ...], timeout=60)`. Issue search and creation use constant prefixes/labels. Covered by `scripts/test_beta_guard.py`. |
| `scripts/contracts/shape_check.py` | Pass | List-form `gh` CLI invocation; issue number parsed as int from GitHub API JSON; titles and labels are constant. Covered by `scripts/contracts/test_shape_check.py`. |
| `scripts/contracts/record.py` | Pass | No subprocess or OS command execution. Writes fixture files directly via Python file APIs. |
| `scripts/smoke/test_verify_comment.py` | Pass | List-form execution of `verify-comment.sh` in isolated test environments. |
| `scripts/checks/check_appcast.py` | Pass | List-form execution of `gh api`, `gh release download`, and `openssl pkeyutl`. Tag and asset names parsed strictly via `/releases/download/([^/]+)/([^/]+)$` regex. Covered by `scripts/checks/test_check_appcast.py`. |
| `scripts/checks/check_mockup_data.py` | Pass | Pure static file scanner; no subprocess or OS command execution. Covered by `scripts/checks/test_check_mockup_data.py`. |
| `scripts/generate_heuristic_matches.py` | Pass | Reads environment variables (`SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`) and queries Spotify API via `urllib.request`. No subprocess execution. |

## Shell scripts
`shellcheck` (version 0.11.0) was run across all scripts. No `eval` on untrusted external input; no `curl | sh`. All script tests pass.

| File | Result | Evidence |
|---|---|---|
| `scripts/smoke/run-mac.sh` | Pass | No `eval` on untrusted input; all dynamic journey dispatches use internal function names (`mac_run_*`). Pass parameters and AX queries are properly quoted. |
| `scripts/smoke/run-smoke.sh` | Pass | Zero shellcheck warnings. Issue creation and comments use list/variable-quoted `gh` and `mahler` calls (`--title "$title" --body "$body"`). Deduplication uses structured regex markers (`smoke-journey:<id>`). Covered by `scripts/smoke/test-run-smoke.sh`. |
| `scripts/smoke/report.sh` | Pass | Disambiguated `${keys}` and `${vals}` in lookup helper. Markdown output sanitizes pipes and newlines (`cell()`). Covered by `scripts/smoke/test-report.sh`. |
| `scripts/smoke/verify-comment.sh` | Pass | Zero shellcheck warnings. Strict numeric regex validation on issue (`^[0-9]+$`) and tag (`^v[0-9]+(\.[0-9]+)+(-[A-Za-z0-9.]+)?$`). Quotes all `gh issue comment` arguments. Covered by `scripts/smoke/test_verify_comment.py`. |
| `scripts/smoke/weekly-smoke.sh` | Pass | Flag passing hardened against `set -u` empty array expansion (`${extra_args[@]+"${extra_args[@]}"}`). Desktop gate invocation is wrapped safely. Covered by `scripts/smoke/test_weekly_smoke.sh`. |
| `scripts/smoke/sync-roundtrip.sh` | Pass | Quoted `exit "$rc"`. Staging reset script called with `--yes` and verified. Covered by `scripts/smoke/test_sync_roundtrip.sh`. |
| `scripts/promote-beta.sh` | Pass | Fixed regex syntax ambiguity `${fail_id}[[:space:]]*-` (SC1087). Strict verification of report headers, tag matching, and unwaived failures. Covered by `scripts/test_promote_beta.sh`. |
| `scripts/publish-appcast.sh` | Pass | Isolated disposable git worktree for appcast updates; validates branch is not `main`. `before_push` hook is an internal test seam. Covered by `scripts/test_publish_appcast.sh`. |
| `scripts/smoke-nudge.sh` | Pass | Zero shellcheck warnings. Searches and creates/comments on issues with quoted `$title` and `$body`. Covered by `scripts/test_smoke_nudge.sh`. |
| `scripts/smoke/lib.sh` | Pass | Logging, timeout constants, and helper functions with clean variable quoting. |
| `scripts/smoke/run-android.sh` | Pass | Commands to `adb` use quoted arguments and parameter substitutions. |
| `macos/scripts/install.sh` | Pass | Zero shellcheck warnings. Validates and signs local application bundle. |
| `scripts/smoke-sync-reset.sh` | Pass | Refuses to execute against production database name or UUID; requires explicit confirmation. Covered by `scripts/test_smoke_sync_reset.sh`. |
| `scripts/cut-beta.sh` | Pass | Validates branch, executes workflow dispatch via quoted `gh workflow run`. Covered by `scripts/test_cut_beta.py`. |
| `scripts/contracts/record.sh` | Pass | Parameterized test-recording helper script with quoted arguments. |
| `scripts/sync-schema-changed.sh` | Pass | Inspects git diff for `sync/schema.sql`. Covered by `scripts/test_sync_schema_changed.sh`. |
| `macos/scripts/install-beta.sh` | Pass | Zero shellcheck warnings. Downloads and installs beta dmg/zip. |
| `scripts/checks/check-workflow-permissions.sh` | Pass | Audits workflow permissions; covered by `test_check_workflow_permissions.sh`. |
| `scripts/checks/check-workflow-injection.sh` | Pass | Audits workflow `run:` blocks for untrusted `${{ ... }}` expressions; covered by `test_check_workflow_injection.sh`. |
| `scripts/checks/check-mockup-data.sh` | Pass | Python wrapper; covered by `test_check_mockup_data.sh`. |
| `scripts/checks/check-xcuitest-count.sh` | Pass | Covered by `test_check_xcuitest_count.sh`. |
| `scripts/checks/check-sparkle-sandbox-installer.sh` | Pass | Covered by `test_check_sparkle_sandbox_installer.sh`. |

## Workflows
All 10 workflows define top-level `permissions:` (verified by `scripts/checks/check-workflow-permissions.sh`).
None uses `pull_request_target`.
No workflow interpolates `${{ github.event.* }}`, `${{ inputs.* }}`, or `${{ github.head_ref }}` in `run:` blocks (verified by `scripts/checks/check-workflow-injection.sh`).
Secrets are scoped strictly to the individual steps that require them.

| Workflow | Top-level `permissions` | Result | Evidence / Notes |
|---|---|---|---|
| `appcast-check.yml` | `contents: write` | Pass | Needed to push appcast updates to `appcast` branch. `${{ github.repository }}` passed via step `env:`. `GH_TOKEN` scoped to repair step. |
| `beta-guard.yml` | `contents: read`, `issues: write` | Pass | Needed for daily beta gap check to create/comment on reminder issue. `GH_TOKEN` scoped to run step. |
| `build-debug-apk.yml` | `contents: write` | Pass | Needed to create/upload release assets. `DEBUG_KEYSTORE_BASE64` and `GH_TOKEN` scoped to specific restore/publish steps; keystore sha256 printed without secret value. |
| `checks.yml` | `contents: read` | Pass | Runs static checks and gates: `mockup-data`, `workflow-permissions`, `workflow-injection`, `hook-paths`, `xcuitest-count`, `appcast`. No secrets used. |
| `contract-check.yml` | `contents: read`, `issues: write` | Pass | Needed to open drift issue on schema changes. `GH_TOKEN` scoped to check step. |
| `macos-release.yml` | `contents: write` | Pass | Needed for release upload. `MACOS_SIGNING_*`, `SPARKLE_PRIVATE_KEY`, and `GH_TOKEN` scoped strictly to signing and upload steps. |
| `macos-tests.yml` | `contents: read` | Pass | Read-only test execution. Caches SPM dependencies with `runner.os` and package hash keys. No secrets used. |
| `sync-deploy.yml` | `contents: read` | Pass | `CLOUDFLARE_API_TOKEN` scoped strictly to step `env:` in the four wrangler steps (`Migrate staging schema`, `Deploy to staging`, `Migrate production schema`, `Deploy to production`). Other steps have no access. |
| `test.yml` | `contents: read` | Pass | Read-only Android test suite and static guards. No secrets used. |
| `weekly-smoke.yml` | `contents: read`, `issues: write` | Pass | Needed to file rolling smoke-due issue when latest beta is unsmoked. `${{ inputs.tag }}` and `${{ inputs.dry_run }}` scoped to step `env:`. `GH_TOKEN` scoped to step. |

## Dependency scan & Actions
- Dependabot is configured in `.github/dependabot.yml` covering:
  - `gradle` (root `/`, weekly, minor/patch group)
  - `github-actions` (root `/`, weekly, minor/patch group)
  - `npm` (`/sync`, weekly, minor/patch group)
  - `swift` (`/macos/Packages/CouchTourKit`, weekly, minor/patch group)
- Third-party Actions:
  - All actions in `.github/workflows/` are either first-party GitHub actions (`actions/checkout@v7`, `actions/setup-python@v7`, `actions/setup-java@v6`, `actions/setup-node@v7`, `actions/upload-artifact@v7`, `actions/cache@v6`) or official `gradle/actions/setup-gradle@v6`.
  - All actions are pinned to major version tags and updated regularly by Dependabot.

## Not covered
- Client native applications (Android Kotlin/Media3/Room and macOS Swift/CouchTourKit) - covered in client surface security audits (e.g. #801 Part 1 and sibling audit issues).
- Cloudflare Worker backend application code in `sync/src` (endpoints, database schema, and session pairing auth) - covered in sync security audits.
- Hardware-dependent GUI automated driving (macOS display accessibility tree and Android physical device/AVD UI interactions) - covered by local smoke tests and gated desktop runner.
