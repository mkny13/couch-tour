#!/usr/bin/env python3
"""Local UAT checklist board, backed directly by UAT.md.

No dependencies — stdlib only. Run it, click things off, and the markdown file is
rewritten in place so the result lands in git where Claude and Kanban tasks can read it.

    python3 scripts/uat-server.py [--port 4785]

Statuses map to the checkbox character in UAT.md: ' ' pending, 'x' pass, '!' needs work.
A "needs work" item carries a note on the following line, indented under the list item.
"""

import argparse
import json
import re
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
            item = {
                "id": m.group(2),
                "status": STATUS_BY_CHAR[m.group(1)],
                "text": m.group(3),
                "note": note,
            }
            if current is None:
                current = {"title": "Ungrouped", "items": []}
                sections.append(current)
            current["items"].append(item)
    return [s for s in sections if s["items"]]


def update(item_id, status, note):
    """Rewrite one item's status (and note) in UAT.md, leaving everything else byte-identical."""
    lines = UAT_PATH.read_text().splitlines()
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
            if status == "needs-work" and note.strip():
                out.append(f"  > {note.strip()}")
            continue
        out.append(line)
        i += 1
    if not found:
        raise KeyError(item_id)
    UAT_PATH.write_text("\n".join(out) + "\n")


PAGE = """<!doctype html><html><head><meta charset="utf-8">
<title>UAT — Couch Tour</title>
<style>
:root{--bg:#fff;--fg:#1a1a1a;--muted:#666;--line:#e3e3e3;--card:#fafafa;
--pass:#1a7f37;--warn:#bc4c00;--pend:#57606a;--accent:#0969da}
@media(prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--muted:#8b949e;
--line:#30363d;--card:#161b22;--pass:#3fb950;--warn:#d29922;--pend:#8b949e;--accent:#58a6ff}}
*{box-sizing:border-box}
body{margin:0;padding:2rem 1.25rem 4rem;background:var(--bg);color:var(--fg);
font:15px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;max-width:900px;margin-inline:auto}
h1{font-size:1.5rem;margin:0 0 .25rem}
.sub{color:var(--muted);margin:0 0 1.5rem;font-size:.9rem}
.bar{display:flex;gap:1rem;flex-wrap:wrap;padding:.75rem 1rem;background:var(--card);
border:1px solid var(--line);border-radius:8px;margin-bottom:1.5rem;font-size:.875rem}
.bar b{font-variant-numeric:tabular-nums}
h2{font-size:1rem;margin:1.75rem 0 .6rem;padding-bottom:.35rem;border-bottom:1px solid var(--line)}
.item{padding:.7rem .85rem;border:1px solid var(--line);border-radius:8px;margin-bottom:.5rem;
background:var(--card);display:flex;gap:.85rem;align-items:flex-start}
.item.pass{opacity:.6}
.item.needs-work{border-color:var(--warn)}
.btns{display:flex;gap:.25rem;flex-shrink:0}
.btns button{border:1px solid var(--line);background:transparent;color:var(--muted);
border-radius:6px;padding:.25rem .5rem;cursor:pointer;font-size:.8rem;line-height:1.4}
.btns button:hover{border-color:var(--accent);color:var(--accent)}
.btns button[aria-pressed="true"]{font-weight:600}
.item.pass .btns button.p{background:var(--pass);border-color:var(--pass);color:#fff}
.item.needs-work .btns button.w{background:var(--warn);border-color:var(--warn);color:#fff}
.item.pending .btns button.n{background:var(--pend);border-color:var(--pend);color:#fff}
.body{flex:1;min-width:0}
.id{font:11px ui-monospace,SFMono-Regular,Menlo,monospace;color:var(--muted)}
.txt{margin:.1rem 0 0}
.txt code{font:12px ui-monospace,Menlo,monospace;background:rgba(127,127,127,.16);
padding:.1em .35em;border-radius:4px}
textarea{width:100%;margin-top:.5rem;padding:.5rem;border:1px solid var(--warn);border-radius:6px;
background:var(--bg);color:var(--fg);font:inherit;font-size:.875rem;resize:vertical;min-height:3.2rem}
.hint{color:var(--muted);font-size:.8rem;margin-top:.3rem}
.saved{position:fixed;bottom:1rem;right:1rem;background:var(--pass);color:#fff;padding:.5rem .9rem;
border-radius:6px;font-size:.85rem;opacity:0;transition:opacity .2s;pointer-events:none}
.saved.on{opacity:1}
</style></head><body>
<h1>UAT — Couch Tour</h1>
<p class="sub">Writes straight to <code>UAT.md</code>. Commit the file when you're done so Claude and Kanban tasks can see it.</p>
<div class="bar">
  <span><b id="c-pend">0</b> untested</span>
  <span style="color:var(--pass)"><b id="c-pass">0</b> pass</span>
  <span style="color:var(--warn)"><b id="c-warn">0</b> needs work</span>
  <span style="margin-left:auto;color:var(--muted)" id="pct"></span>
</div>
<div id="app"></div>
<div class="saved" id="saved">Saved</div>
<script>
let data=[];
// Element ids here are hyphenated, which never become JS globals -- always look them up.
const $=id=>document.getElementById(id);
const esc=s=>s.replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const md=s=>esc(s).replace(/`([^`]+)`/g,'<code>$1</code>').replace(/\\*\\*([^*]+)\\*\\*/g,'<strong>$1</strong>');

async function load(){data=await (await fetch('/api/items')).json();render();}

function render(){
  const all=data.flatMap(s=>s.items);
  const n=k=>all.filter(i=>i.status===k).length;
  $('c-pend').textContent=n('pending');$('c-pass').textContent=n('pass');
  $('c-warn').textContent=n('needs-work');
  $('pct').textContent=all.length?Math.round(n('pass')/all.length*100)+'% verified':'';
  $('app').innerHTML=data.map(s=>`<h2>${esc(s.title)}</h2>`+s.items.map(it=>`
    <div class="item ${it.status}" data-id="${it.id}">
      <div class="btns">
        <button class="n" aria-pressed="${it.status==='pending'}" onclick="setStatus('${it.id}','pending')" title="Not yet tested">○</button>
        <button class="p" aria-pressed="${it.status==='pass'}" onclick="setStatus('${it.id}','pass')" title="Works">✓</button>
        <button class="w" aria-pressed="${it.status==='needs-work'}" onclick="setStatus('${it.id}','needs-work')" title="Needs work">!</button>
      </div>
      <div class="body">
        <span class="id">${it.id}</span>
        <p class="txt">${md(it.text)}</p>
        ${it.status==='needs-work'?`<textarea placeholder="What went wrong? This is the bug report the next agent reads."
          onblur="setNote('${it.id}',this.value)">${esc(it.note)}</textarea>
          <div class="hint">Saved when you click away.</div>`:''}
      </div>
    </div>`).join('')).join('');
}
function find(id){return data.flatMap(s=>s.items).find(i=>i.id===id);}
async function save(it){
  await fetch('/api/item',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({id:it.id,status:it.status,note:it.note||''})});
  $('saved').classList.add('on');setTimeout(()=>$('saved').classList.remove('on'),900);
}
async function setStatus(id,st){const it=find(id);it.status=st;if(st!=='needs-work')it.note='';render();await save(it);}
async function setNote(id,note){const it=find(id);it.note=note;await save(it);}
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
            update(payload["id"], payload["status"], payload.get("note", ""))
        except KeyError as e:
            return self._send(404, json.dumps({"error": f"unknown item {e}"}), "application/json")
        except Exception as e:  # malformed body, unwritable file
            return self._send(400, json.dumps({"error": str(e)}), "application/json")
        self._send(200, json.dumps({"ok": True}), "application/json")

    def log_message(self, *args):
        pass  # the page is chatty; keep the terminal readable


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=4785)
    ap.add_argument("--no-open", action="store_true")
    args = ap.parse_args()
    if not UAT_PATH.exists():
        raise SystemExit(f"No UAT.md at {UAT_PATH}")
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
