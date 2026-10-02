#!/usr/bin/env python3
"""Fail when mockup/placeholder markers appear in shipping code (#444).

Called by check-mockup-data.sh. Preview code is skipped by brace-counting from the
preview marker, because previews legitimately hold sample data.
"""
import os
import re
import sys

ROOT = os.path.abspath(os.environ.get("REPO_ROOT") or os.getcwd())

SCAN = [
    ("app/src/main", ".kt"),
    ("macos/CouchTour", ".swift"),
    ("macos/Packages/CouchTourKit/Sources", ".swift"),
]

# Free-text markers match case-insensitively. The artist names are exact-case string
# literals: case-insensitively, "TAB" would hit every "tab" label in the UI.
TEXT = [r"\blorem\b", r"\bipsum\b", r"TODO: ?real data", r"mock(up)? ?data",
        r"placeholder (text|data|note|copy)", r"fake data", r"dummy data"]
LITERALS = ["Goose", "Grateful Dead", "pgroove", "TAB", "WSP",
            "Widespread Panic", "Perpetual Groove"]
PATTERNS = [(p, re.compile(p, re.I)) for p in TEXT] + \
           [('"%s"' % n, re.compile(re.escape('"%s"' % n))) for n in LITERALS]

KT_PREVIEW = re.compile(r"^\s*@(\w+\.)*Preview\w*\b")
SWIFT_PREVIEW = re.compile(r"#Preview\b|:\s*(\w+,\s*)*PreviewProvider\b")


def fail(msg):
    print(msg)
    sys.exit(1)


def load_allowlist():
    path = os.path.join(ROOT, "scripts/checks/mockup-allowlist.txt")
    entries = set()
    if not os.path.exists(path):
        return entries
    for n, line in enumerate(open(path, encoding="utf-8"), 1):
        raw = line.strip()
        if not raw or raw.startswith("#"):
            continue
        entry, sep, reason = raw.partition("# ")
        if not sep or not reason.strip():
            fail("scripts/checks/mockup-allowlist.txt:%d: entry has no '# reason'" % n)
        file, colon, pat = entry.strip().partition(":")
        if not colon or not pat:
            fail("scripts/checks/mockup-allowlist.txt:%d: expected 'path:pattern  # reason'" % n)
        entries.add((file, pat))
    return entries


def preview_lines(lines, ext):
    """Return indexes of lines inside preview code."""
    skip = set()
    start = KT_PREVIEW if ext == ".kt" else SWIFT_PREVIEW
    i = 0
    while i < len(lines):
        if not start.search(lines[i]):
            i += 1
            continue
        depth, opened, j = 0, False, i
        while j < len(lines):
            skip.add(j)
            depth += lines[j].count("{") - lines[j].count("}")
            if "{" in lines[j]:
                opened = True
            # A Kotlin expression-body preview has no brace; stop after its first
            # non-annotation line rather than swallowing the rest of the file.
            if opened and depth <= 0:
                break
            if not opened and ext == ".kt" and "fun " in lines[j] and "=" in lines[j] \
                    and "{" not in lines[j]:
                break
            j += 1
        i = j + 1
    return skip


def main():
    allow = load_allowlist()
    bad = []
    for base, ext in SCAN:
        for dirpath, dirs, files in os.walk(os.path.join(ROOT, base)):
            dirs[:] = [d for d in dirs if d not in ("Tests", "build")]
            for f in sorted(files):
                if not f.endswith(ext):
                    continue
                full = os.path.join(dirpath, f)
                rel = os.path.relpath(full, ROOT)
                lines = open(full, encoding="utf-8", errors="replace").read().split("\n")
                skip = preview_lines(lines, ext)
                for idx, line in enumerate(lines):
                    if idx in skip:
                        continue
                    for name, rx in PATTERNS:
                        if rx.search(line) and (rel, name) not in allow:
                            bad.append("%s:%d: matched '%s' — remove mockup data or add to "
                                       "scripts/checks/mockup-allowlist.txt with a reason"
                                       % (rel, idx + 1, name))
    if bad:
        fail("\n".join(sorted(set(bad))))
    print("check-mockup-data: ok")


main()
