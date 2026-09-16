#!/usr/bin/env python3
"""Tests for scripts/uat-server.py's GitHub bug-filing path (#258).

Stdlib unittest, no dependencies — matching the server script's own no-dependency rule:

    python3 scripts/test_uat_server.py

All tests run against a temp UAT.md with a fake gh layer; nothing here touches the real
UAT.md or GitHub.
"""

import json
import importlib.util
import sys
import tempfile
import threading
import unittest
from http.server import HTTPServer
from pathlib import Path
from unittest import mock

# The server script's filename has a hyphen, so it needs an explicit loader rather than
# a plain import.
_spec = importlib.util.spec_from_file_location(
    "uat_server", Path(__file__).resolve().parent / "uat-server.py"
)
uat_server = importlib.util.module_from_spec(_spec)
sys.modules["uat_server"] = uat_server
_spec.loader.exec_module(uat_server)

SAMPLE = """# UAT — manual verification owed

## Batch 1 — test batch

- [ ] `uat-901` **Sample item** (macOS) — Do the thing.
- [ ] `uat-902` No bold here — plain item text
- [ ] `uat-903` **Other item** (both) — Another thing.
"""


class UatServerTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.NamedTemporaryFile("w", suffix=".md", delete=False)
        self.tmp.write(SAMPLE)
        self.tmp.close()
        p = mock.patch.object(uat_server, "UAT_PATH", Path(self.tmp.name))
        p.start()
        self.addCleanup(p.stop)

    def read(self):
        return Path(self.tmp.name).read_text()


class FilingTests(UatServerTestCase):
    def setUp(self):
        super().setUp()
        self.gh = mock.Mock(return_value=42)
        self.state = mock.Mock(return_value="OPEN")  # gh reports state uppercase
        self.comment = mock.Mock()
        for name, fn in [("create_issue", self.gh), ("issue_state", self.state),
                         ("comment_on_issue", self.comment)]:
            p = mock.patch.object(uat_server, name, fn)
            p.start()
            self.addCleanup(p.stop)

    def test_first_fail_opens_issue_with_spec_shape(self):
        result = uat_server.update("uat-901", "needs-work", "it broke on launch")
        self.gh.assert_called_once()
        kwargs = self.gh.call_args.kwargs
        self.assertEqual(kwargs["title"], "UAT fail: Sample item (uat-901)")
        body = kwargs["body"]
        self.assertIn("**UAT item:** `uat-901` — Sample item", body)
        self.assertIn("**Area:** macOS", body)
        self.assertIn(f"**Source:** {uat_server.SOURCE_LINK}", body)
        self.assertIn("it broke on launch", body)
        self.assertIn(uat_server.BUG_FOOTER, body)
        self.assertEqual(result, {"ok": True, "issue": 42, "warning": None})
        self.assertIn("- [!] `uat-901` **Sample item** (macOS) — Do the thing.", self.read())
        self.assertIn("  > it broke on launch (→ #42)", self.read())
        self.state.assert_not_called()
        self.comment.assert_not_called()

    def test_remark_on_open_issue_comments_instead_of_duplicating(self):
        uat_server.update("uat-901", "needs-work", "it broke on launch")
        uat_server.update("uat-901", "needs-work", "still broken, worse now")
        self.gh.assert_called_once()  # only the first mark created an issue
        self.comment.assert_called_once_with(42, body=mock.ANY)
        comment_body = self.comment.call_args.kwargs["body"]
        self.assertIn("still broken, worse now", comment_body)
        self.assertIn(uat_server.BUG_FOOTER, comment_body)
        self.assertIn("  > still broken, worse now (→ #42)", self.read())

    def test_unchanged_note_files_nothing(self):
        uat_server.update("uat-901", "needs-work", "it broke on launch")
        uat_server.update("uat-901", "needs-work", "it broke on launch")
        self.gh.assert_called_once()
        self.comment.assert_not_called()
        self.assertIn("  > it broke on launch (→ #42)", self.read())

    def test_remark_after_close_opens_new_issue_as_regression(self):
        uat_server.update("uat-901", "needs-work", "it broke on launch")
        self.state.return_value = "closed"
        self.gh.return_value = 43
        uat_server.update("uat-901", "needs-work", "regressed after the fix")
        self.assertEqual(self.gh.call_count, 2)
        self.comment.assert_not_called()
        self.assertIn("  > regressed after the fix (→ #43)", self.read())

    def test_pass_preserves_linkage_as_marker_only_line(self):
        uat_server.update("uat-901", "needs-work", "it broke on launch")
        uat_server.update("uat-901", "pass", "")
        self.assertIn("  > (→ #42)", self.read())
        self.assertEqual(self.comment.call_count, 0)  # pass never touches the issue
        # And a fresh fail finds the old issue again rather than duplicating.
        uat_server.update("uat-901", "needs-work", "came back")
        self.gh.assert_called_once()
        self.comment.assert_called_once_with(42, body=mock.ANY)

    def test_needs_work_without_note_files_nothing_but_keeps_marker(self):
        uat_server.update("uat-901", "needs-work", "it broke on launch")
        uat_server.update("uat-901", "needs-work", "")
        self.gh.assert_called_once()
        self.assertIn("  > (→ #42)", self.read())

    def test_gh_failure_degrades_to_warning_and_still_saves(self):
        self.gh.side_effect = uat_server.GhError("gh: offline")
        result = uat_server.update("uat-901", "needs-work", "it broke on launch")
        self.assertIn("gh: offline", result["warning"])
        self.assertIsNone(result["issue"])
        self.assertIn("  > it broke on launch\n", self.read())  # no phantom marker

    def test_platform_and_section_fallback_in_body(self):
        uat_server.update("uat-902", "needs-work", "plain item broke")
        title = self.gh.call_args.kwargs["title"]
        self.assertEqual(title, "UAT fail: No bold here (uat-902)")
        self.assertIn("**Area:** Batch 1 — test batch", self.gh.call_args.kwargs["body"])
        uat_server.update("uat-903", "needs-work", "both item broke")
        self.assertIn("**Area:** Both", self.gh.call_args.kwargs["body"])



class ParsingTests(UatServerTestCase):
    def test_parse_exposes_issue_and_strips_marker(self):
        items = {i["id"]: i for s in uat_server.parse(self.read()) for i in s["items"]}
        self.assertIsNone(items["uat-901"]["issue"])
        uat_server.UAT_PATH.write_text(
            self.read().replace(
                "- [ ] `uat-901` **Sample item** (macOS) — Do the thing.\n",
                "- [!] `uat-901` **Sample item** (macOS) — Do the thing.\n"
                "  > it broke (→ #7)\n",
            )
        )
        items = {i["id"]: i for s in uat_server.parse(self.read()) for i in s["items"]}
        self.assertEqual(items["uat-901"]["issue"], 7)
        self.assertEqual(items["uat-901"]["note"], "it broke")

    def test_split_marker(self):
        self.assertEqual(uat_server.split_marker("plain"), ("plain", None))
        self.assertEqual(uat_server.split_marker("note (→ #12)"), ("note", 12))
        self.assertEqual(uat_server.split_marker("(→ #12)"), ("", 12))

    def test_item_title(self):
        self.assertEqual(uat_server.item_title("**Sample item** (macOS) — Do the thing."),
                         "Sample item")
        long_text = "No bold here — " + "x" * 120
        self.assertEqual(len(uat_server.item_title(long_text)), 12)  # cut at ' — '

    def test_area(self):
        self.assertEqual(uat_server.area("**X** (macOS) — y", "S"), "macOS")
        self.assertEqual(uat_server.area("**X** (Android) — y", "S"), "Android")
        self.assertEqual(uat_server.area("**X** — y", "Section title"), "Section title")


class CreateIssueTests(UatServerTestCase):
    def test_create_issue_builds_gh_command_and_parses_url(self):
        fake = mock.Mock(return_value="https://github.com/mkny13/couch-tour/issues/57\n")
        with mock.patch.object(uat_server, "run_gh", fake):
            n = uat_server.create_issue("T", "B")
        self.assertEqual(n, 57)
        args = fake.call_args.args[0]
        self.assertEqual(args[:3], ["issue", "create", "--repo"])
        self.assertIn(uat_server.REPO_SLUG, args)
        labels = [args[i + 1] for i, a in enumerate(args) if a == "--label"]
        self.assertEqual(labels, ["type:bug", "p1"])

    def test_issue_state_parses_json(self):
        fake = mock.Mock(return_value='{"state":"OPEN"}')
        with mock.patch.object(uat_server, "run_gh", fake):
            self.assertEqual(uat_server.issue_state(57), "OPEN")


class HttpTests(UatServerTestCase):
    def test_api_item_relays_filing_result(self):
        with mock.patch.object(uat_server, "create_issue", mock.Mock(return_value=42)), \
             mock.patch.object(uat_server, "issue_state", mock.Mock(return_value="OPEN")), \
             mock.patch.object(uat_server, "comment_on_issue", mock.Mock()):
            server = HTTPServer(("127.0.0.1", 0), uat_server.Handler)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            self.addCleanup(server.shutdown)
            import urllib.request
            req = urllib.request.Request(
                f"http://127.0.0.1:{server.server_address[1]}/api/item",
                data=json.dumps({"id": "uat-901", "status": "needs-work",
                                 "note": "it broke on launch"}).encode(),
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(req) as r:
                body = json.loads(r.read())
            self.assertEqual(body, {"ok": True, "issue": 42, "warning": None})

    def test_api_unknown_item_is_404(self):
        server = HTTPServer(("127.0.0.1", 0), uat_server.Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        import urllib.request
        import urllib.error
        req = urllib.request.Request(
            f"http://127.0.0.1:{server.server_address[1]}/api/item",
            data=json.dumps({"id": "uat-999", "status": "pass", "note": ""}).encode(),
            headers={"Content-Type": "application/json"},
        )
        with self.assertRaises(urllib.error.HTTPError) as cm:
            urllib.request.urlopen(req)
        self.assertEqual(cm.exception.code, 404)


if __name__ == "__main__":
    unittest.main(verbosity=2)