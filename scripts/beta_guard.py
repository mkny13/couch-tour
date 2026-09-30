#!/usr/bin/env python3
"""Scheduled CI guard to flag un-promoted betas (#260).

Checks the gap between the latest pre-release and production release daily.
When the gap exceeds BETA_GUARD_MAX_AGE_DAYS (default 7), flags it in GitHub issues
by creating or commenting on an open issue with marker title 'Beta promotion overdue:'.
"""

import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

REPO_SLUG = os.environ.get("GITHUB_REPOSITORY", "mkny13/couch-tour")
DEFAULT_MAX_AGE_DAYS = 7
MARKER_PREFIX = "Beta promotion overdue:"


class GhError(RuntimeError):
    """A gh CLI call failed."""


def run_gh(args: list[str]) -> str:
    """Run the local gh CLI; stdout on success, GhError carrying stderr on failure."""
    try:
        r = subprocess.run(["gh", *args], capture_output=True, text=True, timeout=60)
    except OSError as e:
        raise GhError(f"gh not runnable: {e}") from e
    except subprocess.TimeoutExpired as e:
        raise GhError(f"gh timed out: {' '.join(args)}") from e
    if r.returncode != 0:
        raise GhError((r.stderr or r.stdout).strip() or f"gh exited {r.returncode}")
    return r.stdout


def parse_iso_datetime(val: str) -> datetime:
    """Parse an ISO 8601 timestamp string into a timezone-aware UTC datetime."""
    if val.endswith("Z"):
        val = val[:-1] + "+00:00"
    dt = datetime.fromisoformat(val)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def get_release_date_str(release: dict) -> str:
    """Return published_at, falling back to created_at."""
    return release.get("published_at") or release.get("created_at") or ""


def get_release_datetime(release: dict) -> datetime:
    """Return timezone-aware datetime for release, falling back to minimum datetime."""
    ts = get_release_date_str(release)
    if not ts:
        return datetime.min.replace(tzinfo=timezone.utc)
    return parse_iso_datetime(ts)


def parse_releases(raw_json) -> list[dict]:
    """Parse releases JSON (single array, multiple concatenated arrays, or list).

    Filters out draft releases and sorts descending by release date.
    """
    if isinstance(raw_json, list):
        items = [dict(x) for x in raw_json]
    elif isinstance(raw_json, str):
        raw_json = raw_json.strip()
        if not raw_json:
            return []
        items = []
        decoder = json.JSONDecoder()
        idx = 0
        length = len(raw_json)
        while idx < length:
            while idx < length and raw_json[idx].isspace():
                idx += 1
            if idx >= length:
                break
            obj, idx = decoder.raw_decode(raw_json, idx)
            if isinstance(obj, list):
                items.extend(obj)
            elif isinstance(obj, dict):
                items.append(obj)
    else:
        return []

    # Ignore draft releases
    published = [r for r in items if not r.get("draft", False)]
    # Sort descending by date (most recent first)
    published.sort(key=get_release_datetime, reverse=True)
    return published


def latest_prerelease(releases: list[dict]) -> dict | None:
    """Return the most recent pre-release, or None."""
    for r in releases:
        if r.get("prerelease", False):
            return r
    return None


def latest_production(releases: list[dict]) -> dict | None:
    """Return the most recent production release (prerelease is False), or None."""
    for r in releases:
        if not r.get("prerelease", False):
            return r
    return None


def gap_days(beta: dict | None, prod: dict | None, now=None) -> int:
    """Calculate the staleness gap in days.

    If either release is None or the beta is not newer than production,
    returns 0. Otherwise returns the number of full days since the beta.
    """
    if beta is None or prod is None:
        return 0

    beta_dt = get_release_datetime(beta)
    prod_dt = get_release_datetime(prod)
    if beta_dt <= prod_dt:
        return 0

    if now is None:
        now_dt = datetime.now(timezone.utc)
    elif isinstance(now, str):
        now_dt = parse_iso_datetime(now)
    elif now.tzinfo is None:
        now_dt = now.replace(tzinfo=timezone.utc)
    else:
        now_dt = now

    delta = now_dt - beta_dt
    return max(0, delta.days)


def get_max_age_days() -> int:
    """Return max age days threshold from env or default."""
    val = os.environ.get("BETA_GUARD_MAX_AGE_DAYS")
    if val is not None:
        try:
            return int(val)
        except ValueError:
            pass
    return DEFAULT_MAX_AGE_DAYS


def should_flag(beta: dict | None, prod: dict | None, now=None, max_age_days=None) -> bool:
    """Return True if the unpromoted beta gap strictly exceeds max_age_days."""
    if beta is None or prod is None:
        return False
    if max_age_days is None:
        max_age_days = get_max_age_days()
    gap = gap_days(beta, prod, now)
    return gap > max_age_days


def issue_title(beta: dict, prod: dict) -> str:
    """Generate title for the overdue promotion issue."""
    beta_tag = beta.get("tag_name", "unknown")
    return f"{MARKER_PREFIX} {beta_tag}"


def render_body(beta: dict, prod: dict, gap: int) -> str:
    """Render the initial issue markdown body."""
    beta_tag = beta.get("tag_name", "unknown")
    prod_tag = prod.get("tag_name", "unknown")
    beta_date = get_release_date_str(beta)
    prod_date = get_release_date_str(prod)
    return (
        "## Problem\n\n"
        f"**{prod_tag}** ({prod_date}) is the last production release. "
        f"The latest pre-release is **{beta_tag}** ({beta_date}), "
        f"which has been sitting un-promoted for {gap} days.\n\n"
        "CLAUDE.md: \"Promoting beta to production: Never automatic; requires explicit owner confirmation.\"\n\n"
        "## Goal\n\n"
        "Promote the latest confirmed-good beta to production by running `scripts/promote-beta.sh`:\n\n"
        "```bash\n"
        f"scripts/promote-beta.sh {beta_tag} <next-tag> \"Production release <next-tag>: <summary of changes>\"\n"
        "```\n\n"
        "---\n"
        "Filed automatically by `.github/workflows/beta-guard.yml`."
    )


def render_comment_body(beta: dict, prod: dict, gap: int) -> str:
    """Render the comment body for recurring alert on existing issue."""
    beta_tag = beta.get("tag_name", "unknown")
    prod_tag = prod.get("tag_name", "unknown")
    return (
        f"Beta promotion is still overdue: **{beta_tag}** has been un-promoted for {gap} days "
        f"(latest production release: **{prod_tag}**).\n\n"
        "---\n"
        "Updated automatically by `.github/workflows/beta-guard.yml`."
    )


def parse_existing_issue(raw_json) -> int | None:
    """Parse gh issue list JSON and find an open issue with the marker title prefix."""
    if isinstance(raw_json, list):
        data = raw_json
    elif isinstance(raw_json, str):
        raw_json = raw_json.strip()
        if not raw_json:
            return None
        data = json.loads(raw_json)
    else:
        return None

    for issue in data:
        title = issue.get("title", "")
        if title.startswith(MARKER_PREFIX):
            return int(issue["number"])
    return None


def write_step_summary(text: str) -> None:
    """Append one line to $GITHUB_STEP_SUMMARY if set."""
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        try:
            with open(summary_path, "a", encoding="utf-8") as f:
                f.write(text.rstrip() + "\n")
        except OSError as e:
            print(f"Warning: could not write to GITHUB_STEP_SUMMARY: {e}", file=sys.stderr)


def create_issue(title: str, body: str) -> int:
    """Create a new issue with labels type:chore and p2. Return issue number."""
    out = run_gh([
        "issue", "create",
        "--repo", REPO_SLUG,
        "--title", title,
        "--body", body,
        "--label", "type:chore",
        "--label", "p2",
    ])
    url = out.strip().splitlines()[-1] if out.strip() else ""
    if m := re.search(r"/issues/(\d+)\s*$", url):
        return int(m.group(1))
    raise GhError(f"could not parse created issue url: {url!r}")


def comment_on_issue(number: int, body: str) -> None:
    """Comment on an existing issue."""
    run_gh([
        "issue", "comment", str(number),
        "--repo", REPO_SLUG,
        "--body", body,
    ])


def find_existing_issue() -> int | None:
    """Search for open issues with the marker prefix."""
    out = run_gh([
        "issue", "list",
        "--repo", REPO_SLUG,
        "--state", "open",
        "--search", f'"{MARKER_PREFIX}" in:title',
        "--json", "number,title",
    ])
    return parse_existing_issue(out)


def run_guard(now=None, max_age_days=None) -> dict:
    """Run the guard check and return execution result summary dict."""
    if max_age_days is None:
        max_age_days = get_max_age_days()

    raw_releases = run_gh(["api", "--paginate", f"repos/{REPO_SLUG}/releases"])
    releases = parse_releases(raw_releases)
    beta = latest_prerelease(releases)
    prod = latest_production(releases)

    gap = gap_days(beta, prod, now=now)
    flag = should_flag(beta, prod, now=now, max_age_days=max_age_days)

    if not flag:
        msg = (
            f"Beta promotion guard: no flag needed. "
            f"Latest beta: {beta.get('tag_name') if beta else None}, "
            f"latest prod: {prod.get('tag_name') if prod else None}, "
            f"gap: {gap} days (threshold: {max_age_days} days)."
        )
        print(msg)
        write_step_summary(msg)
        return {
            "flagged": False,
            "gap": gap,
            "beta": beta.get("tag_name") if beta else None,
            "prod": prod.get("tag_name") if prod else None,
            "action": "none",
        }

    # Flag needed: upsert issue
    existing_id = find_existing_issue()
    if existing_id is not None:
        body = render_comment_body(beta, prod, gap)
        comment_on_issue(existing_id, body)
        msg = (
            f"Beta promotion guard: gap is {gap} days (> {max_age_days} days). "
            f"Commented on existing issue #{existing_id}."
        )
        print(msg)
        write_step_summary(msg)
        return {
            "flagged": True,
            "gap": gap,
            "beta": beta.get("tag_name") if beta else None,
            "prod": prod.get("tag_name") if prod else None,
            "action": "comment",
            "issue": existing_id,
        }
    else:
        title = issue_title(beta, prod)
        body = render_body(beta, prod, gap)
        new_id = create_issue(title, body)
        msg = (
            f"Beta promotion guard: gap is {gap} days (> {max_age_days} days). "
            f"Created issue #{new_id}: {title}."
        )
        print(msg)
        write_step_summary(msg)
        return {
            "flagged": True,
            "gap": gap,
            "beta": beta.get("tag_name") if beta else None,
            "prod": prod.get("tag_name") if prod else None,
            "action": "create",
            "issue": new_id,
        }


def main() -> int:
    try:
        run_guard()
    except Exception as e:
        print(f"Error in beta_guard: {e}", file=sys.stderr)
        write_step_summary(f"Beta promotion guard encountered an error: {e}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
