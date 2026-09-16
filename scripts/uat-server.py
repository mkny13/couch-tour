#!/usr/bin/env python3
"""Local UAT checklist board, backed directly by UAT.md.

No dependencies — stdlib only. Run it, click things off, and the markdown file is
rewritten in place so the result lands in git where Claude and Kanban tasks can read it.

    python3 scripts/uat-server.py [--port 4785]

Statuses map to the checkbox character in UAT.md: ' ' pending, 'x' pass, '!' needs work.
A "needs work" item carries a note on the following line, indented under the list item.

Marking an item [!] with a note also files a GitHub bug (type:bug p1) through the local gh
CLI, in the shape spec'd by mkny13/mahler#292: the first [!] opens the issue, a re-mark
comments on the still-open issue instead of duplicating it, and a re-mark after that issue
was closed opens a new one (a regression, not the same report). The number is remembered on
the note line as a trailing '(→ #N)' marker and survives a later pass as a marker-only line,
so a fresh [!] always finds its old issue. Passing or clearing never touches the filed
issue — closing it stays a human/Mahler decision through the normal pipeline.
"""

import argparse
import json
import re
import subprocess
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
UAT_PATH = REPO_ROOT / "UAT.md"

ITEM_RE = re.compile(r"^- \[([ x!])\] `(uat-\d+)` (.*)$")
NOTE_RE = re.compile(r"^  > (.*)$")
SECTION_RE = re.compile(r"^## (.*)$")

STATUS_BY_CHAR = {" ": "pending", "x": "pass", "!": "needs-work"}
CHAR_BY_STATUS = {v: k for k, v in STATUS_BY_CHAR.items()}

# The bug target is this repo, by slug rather than by cwd: the server can be started
# from anywhere and gh only auto-detects the repo from the working directory.
REPO_SLUG = "mkny13/couch-tour"
SOURCE_LINK = f"https://github.com/{REPO_SLUG}/blob/main/UAT.md"

# The trailing '(→ #N)' that pins a filed issue to an item's note line. The arrow makes
# an accidental collision with note prose practically impossible.
MARKER_RE = re.compile(r"\s*\(→ #(\d+)\)\s*")
BOLD_RE = re.compile(r"\*\*(.+?)\*\*")
PLATFORM_RE = re.compile(r"\((Android|macOS|both|sync)\)", re.IGNORECASE)

BUG_FOOTER = (
    "Filed automatically from the in-app UAT check. "
    "Passing this item again does not close this issue."
)


class GhError(RuntimeError):
    """A gh CLI call failed; the UAT.md write still went through, so this is a warning."""


def split_marker(note):
    """Split a note's '(→ #N)' filed-issue marker off, returning (clean note, issue no)."""
    m = MARKER_RE.search(note)
    if not m:
        return note, None
    return (note[: m.start()] + note[m.end():]).strip(), int(m.group(1))


def item_title(text):
    """Human title for the filed bug: the item's bolded name, or a trimmed prefix of it."""
    if m := BOLD_RE.search(text):
        return m.group(1)
    # Unbolded items: most descriptions introduce their body with ' — '.
    return text.split(" — ")[0][:80].rstrip()


def area(text, section):
    """The bug's area label: the item's platform when it names one, else its section."""
    m = PLATFORM_RE.search(text)
    if not m:
        return section
    g = m.group(1).lower()
    return {"macos": "macOS", "android": "Android"}.get(g, g.capitalize())


def bug_body(item_id, title, area, note):
    """The filed issue body — the shape contract from mkny13/mahler#292, verbatim."""
    return (
        f"**UAT item:** `{item_id}` — {title}\n"
        f"**Area:** {area}\n"
        f"**Source:** {SOURCE_LINK}\n"
        "\n"
        f"{note}\n"
        "\n"
        "---\n"
        f"{BUG_FOOTER}"
    )


def comment_body(note):
    return (
        "Re-failed in the UAT board. New note:\n"
        "\n"
        f"{note}\n"
        "\n"
        "---\n"
        f"{BUG_FOOTER}"
    )


def run_gh(args):
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


def create_issue(title, body):
    """Create the issue and return its number (gh prints the new issue's URL)."""
    out = run_gh([
        "issue", "create", "--repo", REPO_SLUG,
        "--title", title, "--body", body,
        "--label", "type:bug", "--label", "p1",
    ])
    url = out.strip().splitlines()[-1] if out.strip() else ""
    if m := re.search(r"/issues/(\d+)\s*$", url):
        return int(m.group(1))
    raise GhError(f"could not parse created issue url: {url!r}")


def issue_state(number):
    out = run_gh(["issue", "view", str(number), "--repo", REPO_SLUG, "--json", "state"])
    try:
        return json.loads(out)["state"]
    except (KeyError, ValueError) as e:
        raise GhError(f"could not read state of #{number}: {e}") from e


def comment_on_issue(number, body):
    run_gh(["issue", "comment", str(number), "--repo", REPO_SLUG, "--body", body])


def file_bug_report(item_id, title, area, note, linked_issue):
    """File or update the GitHub bug for one [!] mark, and return the issue number.

    Dedup rule (mahler#292): an open linked issue gets the new note as a comment rather
    than a second issue; a closed one means the bug came back after a fix, so a fresh
    issue is opened instead of reopening the old report.
    """
    if linked_issue is not None:
        if issue_state(linked_issue).lower() == "open":
            comment_on_issue(linked_issue, body=comment_body(note))
            return linked_issue
    return create_issue(title=f"UAT fail: {title} ({item_id})", body=bug_body(item_id, title, area, note))


def parse(text):
    """Return a list of sections, each with its items, in document order."""
    sections, current = [], None
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if m := SECTION_RE.match(line):
            current = {"title": m.group(1), "items": []}
            sections.append(current)
            continue
        if m := ITEM_RE.match(line):
            note = ""
            if i + 1 < len(lines) and (n := NOTE_RE.match(lines[i + 1])):
                note = n.group(1)
            note, issue = split_marker(note)
            item = {
                "id": m.group(2),
                "status": STATUS_BY_CHAR[m.group(1)],
                "text": m.group(3),
                "note": note,
                "issue": issue,
            }
            if current is None:
                current = {"title": "Ungrouped", "items": []}
                sections.append(current)
            current["items"].append(item)
    return [s for s in sections if s["items"]]


def update(item_id, status, note):
    """Rewrite one item's status (and note) in UAT.md, leaving everything else byte-identical.

    A [!] with a note also files a GitHub bug (see file_bug_report) and pins its number
    onto the note line as a trailing '(→ #N)'. The marker survives a later pass as a
    marker-only note line, so a fresh [!] still finds its old issue; a [!] re-mark with
    an unchanged note files nothing (every textarea blur would otherwise spam the issue
    with duplicate comments).

    Returns a result dict for the API response. A gh failure degrades to a warning —
    the UAT.md write is the source of truth and must succeed regardless.
    """
    lines = UAT_PATH.read_text().splitlines()
    text, existing_note, section, located = "", "", "", False
    for idx, line in enumerate(lines):
        if m := SECTION_RE.match(line):
            section = m.group(1)
        elif (m := ITEM_RE.match(line)) and m.group(2) == item_id:
            text = m.group(3)
            if idx + 1 < len(lines) and (n := NOTE_RE.match(lines[idx + 1])):
                existing_note = n.group(1)
            located = True
            break
    if not located:
        raise KeyError(item_id)
    _, existing_issue = split_marker(existing_note)

    note = note.strip()
    clean, incoming_issue = split_marker(note)
    existing_clean = split_marker(existing_note)[0]
    issue_no, warning = existing_issue, None

    if status == "needs-work" and clean:
        if incoming_issue is not None:
            # The payload itself carries the marker: already on record.
            issue_no = incoming_issue
        elif clean == existing_clean and existing_issue is not None:
            # Same note as already on disk: nothing new to report.
            pass
        else:
            try:
                issue_no = file_bug_report(
                    item_id, item_title(text), area(text, section), clean, existing_issue
                )
            except (GhError, OSError) as e:
                warning = f"saved to UAT.md, but filing the GitHub bug failed: {e}"

    out, i, found = [], 0, False
    while i < len(lines):
        line = lines[i]
        m = ITEM_RE.match(line)
        if m and m.group(2) == item_id:
            found = True
            out.append(f"- [{CHAR_BY_STATUS[status]}] `{item_id}` {m.group(3)}")
            i += 1
            # Drop any existing note line; it is re-added below only if still relevant.
            if i < len(lines) and NOTE_RE.match(lines[i]):
                i += 1
            if status == "needs-work" and clean:
                marker = f" (→ #{issue_no})" if issue_no is not None else ""
                out.append(f"  > {clean}{marker}")
            elif issue_no is not None:
                # The note is gone but the filed issue isn't — keep the linkage alive
                # so re-failing finds it instead of opening a duplicate.
                out.append(f"  > (→ #{issue_no})")
            continue
        out.append(line)
        i += 1
    if not found:
        raise KeyError(item_id)
    UAT_PATH.write_text("\n".join(out) + "\n")
    return {"ok": True, "issue": issue_no, "warning": warning}


PAGE = """<!doctype html><html><head><meta charset="utf-8">
<title>UAT — Couch Tour</title>
<style>
:root{
  --bg: #161826; --fg: #e2e8f0; --muted: #94a3b8; --line: #232532; --card: #1c1e2c;
  --card-hover: #212435; --pass: #10b981; --warn: #f59e0b; --pend: #64748b;
  --accent: #9184d9; --accent-light: #b5abfc; --input-bg: #12141f;
}
@media(prefers-color-scheme: light){
  :root{
    --bg: #f8fafc; --fg: #0f172a; --muted: #64748b; --line: #e2e8f0; --card: #ffffff;
    --card-hover: #f1f5f9; --pass: #059669; --warn: #d97706; --pend: #94a3b8;
    --accent: #6f62c7; --accent-light: #5d5294; --input-bg: #ffffff;
  }
}
* { box-sizing: border-box; }
body {
  margin: 0; padding: 2rem 1.5rem 5rem; background: var(--bg); color: var(--fg);
  font: 15px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  max-width: 960px; margin-inline: auto;
}
.header { display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: 0.5rem; margin-bottom: 0.5rem; }
h1 { font-size: 1.6rem; font-weight: 700; margin: 0; letter-spacing: -0.02em; display: flex; align-items: center; gap: 0.6rem; }
.badge { font-size: 0.75rem; font-weight: 600; padding: 0.2rem 0.55rem; border-radius: 999px; background: rgba(145,132,217,0.18); color: var(--accent-light); border: 1px solid rgba(145,132,217,0.3); }
.sub { color: var(--muted); margin: 0 0 1.25rem; font-size: 0.9rem; }

.controls {
  display: flex; gap: 0.75rem; flex-wrap: wrap; align-items: center; margin-bottom: 1.25rem;
}
.filter-tabs {
  display: inline-flex; background: var(--card); border: 1px solid var(--line); border-radius: 8px; padding: 3px; gap: 2px;
}
.tab {
  background: transparent; border: none; color: var(--muted); padding: 0.4rem 0.85rem;
  border-radius: 6px; cursor: pointer; font-size: 0.825rem; font-weight: 500; transition: all 0.15s ease;
}
.tab:hover { color: var(--fg); background: rgba(255,255,255,0.04); }
.tab.active { background: var(--accent); color: #ffffff; font-weight: 600; }
.tab .count { font-size: 0.75rem; opacity: 0.85; margin-left: 0.25rem; }

.search-box {
  flex: 1; min-width: 200px;
}
.search-input {
  width: 100%; padding: 0.45rem 0.85rem; border: 1px solid var(--line); border-radius: 8px;
  background: var(--input-bg); color: var(--fg); font: inherit; font-size: 0.875rem; outline: none;
}
.search-input:focus { border-color: var(--accent); }

.bar {
  display: flex; gap: 1.25rem; flex-wrap: wrap; align-items: center; padding: 0.85rem 1.1rem;
  background: var(--card); border: 1px solid var(--line); border-radius: 10px; margin-bottom: 1.75rem;
  font-size: 0.875rem;
}
.bar b { font-variant-numeric: tabular-nums; font-weight: 600; }
.stat-pill { display: inline-flex; align-items: center; gap: 0.4rem; }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }

.section-head {
  display: flex; align-items: center; justify-content: space-between; margin: 2rem 0 0.75rem;
  padding-bottom: 0.4rem; border-bottom: 1px solid var(--line);
}
h2 { font-size: 1.05rem; font-weight: 600; margin: 0; color: var(--fg); }
.sec-badge { font-size: 0.75rem; color: var(--muted); font-variant-numeric: tabular-nums; }

.item {
  padding: 0.85rem 1rem; border: 1px solid var(--line); border-radius: 10px; margin-bottom: 0.6rem;
  background: var(--card); display: flex; gap: 1rem; align-items: flex-start; transition: border-color 0.15s, background 0.15s;
}
.item:hover { background: var(--card-hover); }
.item.pass { opacity: 0.65; }
.item.needs-work { border-color: var(--warn); background: rgba(245,158,11,0.05); }
.item.pending { border-left: 3px solid var(--pend); }
.item.needs-work { border-left: 3px solid var(--warn); }
.item.pass { border-left: 3px solid var(--pass); }

.btns { display: flex; gap: 0.35rem; flex-shrink: 0; margin-top: 2px; }
.btns button {
  border: 1px solid var(--line); background: transparent; color: var(--muted);
  border-radius: 6px; width: 28px; height: 28px; display: inline-flex; align-items: center;
  justify-content: center; cursor: pointer; font-size: 0.85rem; transition: all 0.15s;
}
.btns button:hover { border-color: var(--accent); color: var(--accent); }
.item.pass .btns button.p { background: var(--pass); border-color: var(--pass); color: #fff; }
.item.needs-work .btns button.w { background: var(--warn); border-color: var(--warn); color: #fff; }
.item.pending .btns button.n { background: var(--pend); border-color: var(--pend); color: #fff; }

.body { flex: 1; min-width: 0; }
.id {
  font: 11px ui-monospace, SFMono-Regular, Menlo, monospace; color: var(--accent-light);
  font-weight: 600; padding: 0.1rem 0.35rem; border-radius: 4px; background: rgba(145,132,217,0.12);
}
a.id { text-decoration: none; }
a.id:hover { text-decoration: underline; }
.id.issue { background: rgba(16,185,129,0.15); color: var(--pass); }
.txt { margin: 0.3rem 0 0; line-height: 1.5; }
.txt strong { color: var(--fg); font-weight: 600; }
.txt code {
  font: 12px ui-monospace, Menlo, monospace; background: rgba(127,127,127,0.18);
  padding: 0.1em 0.35em; border-radius: 4px; color: var(--accent-light);
}
textarea {
  width: 100%; margin-top: 0.6rem; padding: 0.6rem; border: 1px solid var(--warn); border-radius: 8px;
  background: var(--input-bg); color: var(--fg); font: inherit; font-size: 0.875rem; resize: vertical; min-height: 3.5rem;
}
.hint { color: var(--muted); font-size: 0.775rem; margin-top: 0.3rem; }
.empty-state { text-align: center; padding: 3rem 1rem; color: var(--muted); }

.saved {
  position: fixed; bottom: 1.5rem; right: 1.5rem; background: var(--pass); color: #fff;
  padding: 0.6rem 1.1rem; border-radius: 8px; font-size: 0.875rem; font-weight: 600;
  box-shadow: 0 4px 12px rgba(0,0,0,0.3); opacity: 0; transform: translateY(8px);
  transition: opacity 0.2s, transform 0.2s; pointer-events: none; z-index: 99;
}
.saved.on { opacity: 1; transform: translateY(0); }
</style>
</head>
<body>
<div class="header">
  <h1>UAT Checklist <span class="badge">Couch Tour</span></h1>
</div>
<p class="sub">Backed directly by <code>UAT.md</code>. Status changes write immediately so agents and tasks can see results.</p>

<div class="controls">
  <div class="filter-tabs">
    <button class="tab active" data-filter="open" onclick="setFilter('open')">Open Tasks <span class="count" id="c-open-pill">0</span></button>
    <button class="tab" data-filter="all" onclick="setFilter('all')">All <span class="count" id="c-all-pill">0</span></button>
    <button class="tab" data-filter="pending" onclick="setFilter('pending')">Untested <span class="count" id="c-pend-pill">0</span></button>
    <button class="tab" data-filter="needs-work" onclick="setFilter('needs-work')">Needs Work <span class="count" id="c-warn-pill">0</span></button>
    <button class="tab" data-filter="pass" onclick="setFilter('pass')">Passed <span class="count" id="c-pass-pill">0</span></button>
  </div>
  <div class="search-box">
    <input id="q" class="search-input" type="search" placeholder="Search tasks by ID or keyword..." oninput="onSearch(this.value)" />
  </div>
</div>

<div class="bar">
  <span class="stat-pill"><span class="dot" style="background:var(--pend)"></span> <b id="c-pend">0</b> untested</span>
  <span class="stat-pill" style="color:var(--warn)"><span class="dot" style="background:var(--warn)"></span> <b id="c-warn">0</b> needs work</span>
  <span class="stat-pill" style="color:var(--pass)"><span class="dot" style="background:var(--pass)"></span> <b id="c-pass">0</b> pass</span>
  <span style="margin-left:auto;color:var(--muted)" id="pct"></span>
</div>

<div id="app"></div>
<div class="saved" id="saved">✓ Saved to UAT.md</div>
<script>
let data = [];
let currentFilter = 'open';
let currentSearch = '';

const $ = id => document.getElementById(id);
const esc = s => s.replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const md = s => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\\*\\*([^*]+)\\*\\*/g, '<strong>$1</strong>');

async function load() {
  data = await (await fetch('/api/items')).json();
  render();
}

function setFilter(flt) {
  currentFilter = flt;
  document.querySelectorAll('.filter-tabs .tab').forEach(t => {
    t.classList.toggle('active', t.dataset.filter === flt);
  });
  render();
}

function onSearch(val) {
  currentSearch = val.toLowerCase().trim();
  render();
}

function render() {
  const all = data.flatMap(s => s.items);
  const n = k => all.filter(i => i.status === k).length;
  const nOpen = all.filter(i => i.status === 'pending' || i.status === 'needs-work').length;

  $('c-pend').textContent = n('pending');
  $('c-pend-pill').textContent = n('pending');
  $('c-pass').textContent = n('pass');
  $('c-pass-pill').textContent = n('pass');
  $('c-warn').textContent = n('needs-work');
  $('c-warn-pill').textContent = n('needs-work');
  $('c-all-pill').textContent = all.length;
  $('c-open-pill').textContent = nOpen;

  const pct = all.length ? Math.round(n('pass') / all.length * 100) : 0;
  $('pct').textContent = `${pct}% verified (${n('pass')}/${all.length})`;

  let html = '';
  let renderedCount = 0;

  for (const s of data) {
    const items = s.items.filter(it => {
      if (currentFilter === 'open') {
        if (it.status !== 'pending' && it.status !== 'needs-work') return false;
      } else if (currentFilter !== 'all') {
        if (it.status !== currentFilter) return false;
      }
      if (currentSearch) {
        const hay = (it.id + ' ' + it.text + ' ' + (it.note || '')).toLowerCase();
        if (!hay.includes(currentSearch)) return false;
      }
      return true;
    });

    if (items.length === 0) continue;
    renderedCount += items.length;

    html += `<div class="section-head">
      <h2>${esc(s.title)}</h2>
      <span class="sec-badge">${items.length} item${items.length === 1 ? '' : 's'}</span>
    </div>`;

    html += items.map(it => `
      <div class="item ${it.status}" data-id="${it.id}">
        <div class="btns">
          <button class="n" aria-pressed="${it.status==='pending'}" onclick="setStatus('${it.id}','pending')" title="Mark as Untested">○</button>
          <button class="p" aria-pressed="${it.status==='pass'}" onclick="setStatus('${it.id}','pass')" title="Mark as Passed">✓</button>
          <button class="w" aria-pressed="${it.status==='needs-work'}" onclick="setStatus('${it.id}','needs-work')" title="Mark as Needs Work">!</button>
        </div>
        <div class="body">
          <span class="id">${it.id}</span>${it.issue ? ` <a class="id issue" href="https://github.com/mkny13/couch-tour/issues/${it.issue}" target="_blank" rel="noopener" title="GitHub bug filed from this item">→ #${it.issue}</a>` : ''}
          <div class="txt">${md(it.text)}</div>
          ${it.status === 'needs-work' ? `
            <textarea placeholder="What went wrong? This is the bug report the next agent reads."
              onblur="setNote('${it.id}', this.value)">${esc(it.note)}</textarea>
            <div class="hint">Saved when you click away.</div>
          ` : ''}
        </div>
      </div>
    `).join('');
  }

  if (renderedCount === 0) {
    html = `<div class="empty-state">
      <h3 style="margin-top:0">No tasks match your current filter</h3>
      <p>Try selecting a different filter tab or clearing your search.</p>
    </div>`;
  }

  $('app').innerHTML = html;
}

function find(id) {
  return data.flatMap(s => s.items).find(i => i.id === id);
}

let savedTimer;
async function save(it) {
  const r = await fetch('/api/item', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({id: it.id, status: it.status, note: it.note || ''})
  });
  const j = await r.json();
  if (j.issue) it.issue = j.issue;
  render();
  const t = $('saved');
  t.textContent = j.warning ? '⚠ ' + j.warning
    : '✓ Saved to UAT.md' + (j.issue ? ` — filed as #${j.issue}` : '');
  t.style.background = j.warning ? 'var(--warn)' : '';
  t.classList.add('on');
  clearTimeout(savedTimer);
  savedTimer = setTimeout(() => t.classList.remove('on'), j.warning ? 6000 : 1200);
}

async function setStatus(id, st) {
  const it = find(id);
  it.status = st;
  if (st !== 'needs-work') it.note = '';
  render();
  await save(it);
}

async function setNote(id, note) {
  const it = find(id);
  it.note = note;
  await save(it);
}

load();
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype):
        payload = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path == "/":
            self._send(200, PAGE, "text/html; charset=utf-8")
        elif self.path == "/api/items":
            self._send(200, json.dumps(parse(UAT_PATH.read_text())), "application/json")
        else:
            self._send(404, "not found", "text/plain")

    def do_POST(self):
        if self.path != "/api/item":
            return self._send(404, "not found", "text/plain")
        try:
            payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            result = update(payload["id"], payload["status"], payload.get("note", ""))
        except KeyError as e:
            return self._send(404, json.dumps({"error": f"unknown item {e}"}), "application/json")
        except Exception as e:  # malformed body, unwritable file
            return self._send(400, json.dumps({"error": str(e)}), "application/json")
        self._send(200, json.dumps(result), "application/json")

    def log_message(self, *args):
        pass  # the page is chatty; keep the terminal readable


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=4785)
    ap.add_argument("--no-open", action="store_true")
    ap.add_argument("--export", type=str, nargs="?", const="uat.html", help="Export standalone HTML file")
    args = ap.parse_args()
    if not UAT_PATH.exists():
        raise SystemExit(f"No UAT.md at {UAT_PATH}")

    if args.export:
        out_path = Path(args.export)
        if not out_path.is_absolute():
            out_path = REPO_ROOT / out_path
        items_json = json.dumps(parse(UAT_PATH.read_text()))
        standalone = PAGE.replace("data = await (await fetch('/api/items')).json();", f"data = {items_json};")
        out_path.write_text(standalone)
        print(f"Exported standalone UAT board to {out_path}")
        return

    url = f"http://127.0.0.1:{args.port}"
    n = sum(len(s["items"]) for s in parse(UAT_PATH.read_text()))
    print(f"UAT board: {url}  ({n} items from {UAT_PATH.relative_to(REPO_ROOT)})")
    print("Ctrl-C to stop. Commit UAT.md when you're done.")
    if not args.no_open:
        webbrowser.open(url)
    try:
        HTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")


if __name__ == "__main__":
    main()

