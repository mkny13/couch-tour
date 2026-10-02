#!/usr/bin/env python3
"""Compares the structure (keys and JSON types, never values) of re-recorded contract fixtures
against the committed ones, and reports drift as one GitHub issue (#443, D320).

    shape_check.py --committed DIR --fresh DIR [--report-issue]

Exit 0: no drift. 1: drift (reported on stdout, and in the issue with --report-issue).
2: the issue could not be filed. An endpoint missing from --fresh was a fetch failure
(record.py --skip-failed), which is an outage rather than drift, so it is noted and skipped.
"""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

REPO_SLUG = os.environ.get("GITHUB_REPOSITORY", "mkny13/couch-tour")
ISSUE_TITLE = "Contract drift: upstream API shape changed"
ISSUE_LABELS = ["mahler", "type:bug"]
NULL = "null"


class GhError(RuntimeError):
    """A gh CLI call failed."""


def run_gh(args):
    """Run the gh CLI; stdout on success, GhError carrying stderr on failure."""
    try:
        r = subprocess.run(["gh", *args], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise GhError(f"gh failed: {e}") from e
    if r.returncode != 0:
        raise GhError((r.stderr or r.stdout).strip() or f"gh exited {r.returncode}")
    return r.stdout


def type_name(value):
    if value is None:
        return NULL
    if isinstance(value, bool):  # before number: bool is an int in Python
        return "bool"
    if isinstance(value, (int, float)):
        return "number"
    if isinstance(value, str):
        return "string"
    return "array" if isinstance(value, list) else "object"


def shape(value, path="$", out=None):
    """Flattens JSON to {path: set of type names}. Array elements merge into `path[]`, so
    trimming and reordering don't register as changes."""
    out = {} if out is None else out
    out.setdefault(path, set()).add(type_name(value))
    if isinstance(value, dict):
        for k, v in value.items():
            shape(v, f"{path}.{k}", out)
    elif isinstance(value, list):
        for v in value:
            shape(v, f"{path}[]", out)
    return out


def _types(types):
    return "|".join(sorted(types))


def diff(old, new):
    """Readable drift lines between two shapes: `+ path (type)`, `- path`, `~ path: a -> b`.

    Null is ignored on both sides: whether a field happens to be null in a 3-element sample is
    a value difference, not a shape change. Array element presence (`path[]`) and anything
    under a container the other side never observed are skipped for the same reason.
    """
    lines = []
    for path in sorted(old.keys() | new.keys()):
        if path.endswith("[]"):
            if path not in old or path not in new:
                continue
        elif path not in old or path not in new:
            parent = path.rsplit(".", 1)[0]
            if parent not in old or parent not in new:
                continue
            if path in new:
                lines.append(f"+ {path} ({_types(new[path])})")
            else:
                lines.append(f"- {path}")
            continue
        a, b = old[path] - {NULL}, new[path] - {NULL}
        if a and b and a != b:
            lines.append(f"~ {path}: {_types(a)} -> {_types(b)}")
    return lines


def compare_dirs(committed, fresh):
    """Returns (drift {endpoint: lines}, skipped [endpoint])."""
    drift, skipped = {}, []
    for f in sorted(Path(committed).glob("contract_*.json")):
        other = Path(fresh) / f.name
        if not other.exists():
            skipped.append(f.name)
            continue
        lines = diff(shape(json.loads(f.read_text("utf-8"))), shape(json.loads(other.read_text("utf-8"))))
        if lines:
            drift[f.name] = lines
    return drift, skipped


def report(drift, skipped):
    out = ["Upstream phish.in / Relisten responses no longer match the committed contract fixtures "
           "(keys and types only; `+` added, `-` removed, `~` retyped).", ""]
    for name, lines in drift.items():
        out += [f"### `{name}`", "```diff", *lines, "```", ""]
    if skipped:
        out.append("Skipped (fetch failed, not drift): " + ", ".join(f"`{s}`" for s in skipped))
        out.append("")
    out.append("Fix: `scripts/contracts/record.sh`, update the DTOs the decode tests flag, run the "
               "Android and CouchTourKit tests (D319, D320).")
    return "\n".join(out)


def upsert_issue(body, gh=run_gh):
    """Comments on the one open drift issue if there is one, else creates it."""
    found = json.loads(gh(["issue", "list", "-R", REPO_SLUG, "--state", "open", "--label", "mahler",
                           "--search", f'"{ISSUE_TITLE}" in:title', "--json", "number,title"]) or "[]")
    existing = [i["number"] for i in found if i["title"] == ISSUE_TITLE]
    if existing:
        gh(["issue", "comment", str(existing[0]), "-R", REPO_SLUG, "--body", "<!-- mahler:agent -->\n" + body])
        return "comment"
    args = ["issue", "create", "-R", REPO_SLUG, "--title", ISSUE_TITLE, "--body", body]
    for label in ISSUE_LABELS:
        args += ["--label", label]
    gh(args)
    return "create"


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--committed", required=True, type=Path)
    p.add_argument("--fresh", required=True, type=Path)
    p.add_argument("--report-issue", action="store_true")
    args = p.parse_args()
    drift, skipped = compare_dirs(args.committed, args.fresh)
    for s in skipped:
        print(f"skipped {s}: fetch failed, not drift", file=sys.stderr)
    if not drift:
        print("No contract drift.")
        return 0
    text = report(drift, skipped)
    print(text)
    if args.report_issue:
        try:
            print(f"Issue {upsert_issue(text)}d.", file=sys.stderr)
        except GhError as e:
            print(f"Could not file drift issue: {e}", file=sys.stderr)
            return 2
    return 1


if __name__ == "__main__":
    sys.exit(main())
