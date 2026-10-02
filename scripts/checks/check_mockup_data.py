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


def _kt_preview_end(lines, i):
    """Index of the last line of the Kotlin preview function starting at annotation line i.

    Handles block bodies and expression bodies (`fun X() =` with the body on later lines)
    without swallowing the declarations that follow.
    """
    depth, seen_fun, eq_seen, block, j = 0, False, False, False, i
    while j < len(lines):
        line = re.sub(r'"(\\.|[^"\\])*"', '""', lines[j])
        m = re.search(r"\bfun\b", line)
        if m and not seen_fun:
            seen_fun = True
            tail = line[m.end():]
        else:
            tail = line if seen_fun else ""
        if seen_fun and not eq_seen:
            d = depth
            for ch in tail:
                if ch == "{" and d == 0:
                    block = True
                    break  # block body; brace counting below finds its end
                if ch in "([{":
                    d += 1
                elif ch in ")]}":
                    d -= 1
                elif ch == "=" and d == 0:
                    eq_seen = True
                    break
        depth += sum(line.count(c) for c in "([{") - sum(line.count(c) for c in ")]}")
        if seen_fun and depth <= 0:
            if not eq_seen:
                if block:
                    return j
            else:
                nxt = next((l.strip() for l in lines[j + 1:] if l.strip()), "")
                cont = re.search(r"(=|[-+*/%&|,.]|\?:)\s*$", line.rstrip()) or \
                    re.match(r"(\?\.|\.|\?:|&&|\|\||[-+*/%]\s)", nxt)
                if not cont:
                    return j
        j += 1
    return len(lines) - 1


def preview_lines(lines, ext):
    """Return indexes of lines inside preview code."""
    skip = set()
    start = KT_PREVIEW if ext == ".kt" else SWIFT_PREVIEW
    i = 0
    while i < len(lines):
        if not start.search(lines[i]):
            i += 1
            continue
        if ext == ".kt":
            j = _kt_preview_end(lines, i)
            skip.update(range(i, j + 1))
            i = j + 1
            continue
        depth, opened, j = 0, False, i
        while j < len(lines):
            skip.add(j)
            depth += lines[j].count("{") - lines[j].count("}")
            if "{" in lines[j]:
                opened = True
            if opened and depth <= 0:
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
