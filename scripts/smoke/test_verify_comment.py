#!/usr/bin/env python3
"""Tests for scripts/smoke/verify-comment.sh (#404).

Stdlib unittest, no network: `gh` is replaced by a stub that records its argv.
    python3 scripts/smoke/test_verify_comment.py
"""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "verify-comment.sh"


class VerifyCommentTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.log = Path(self.tmp.name) / "gh.log"
        stub = Path(self.tmp.name) / "gh"
        stub.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$@" > "$GH_LOG"\n')
        stub.chmod(0o755)
        self.env = dict(os.environ, CCTV_VERIFY_GH=str(stub), GH_LOG=str(self.log))

    def run_script(self, *args):
        return subprocess.run([str(SCRIPT), *args], env=self.env, capture_output=True, text=True)

    def test_dry_run_prints_body_and_does_not_call_gh(self):
        r = self.run_script("--dry-run", "#346", "v0.87", "smoke-reports/v0.87.md: PASS search-artist-hit")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("Issue: #346", r.stdout)
        self.assertIn("<!-- mahler:verified -->\nVerified in beta `v0.87`:\n- Evidence: `smoke-reports/v0.87.md: PASS search-artist-hit`", r.stdout)
        self.assertFalse(self.log.exists())

    def test_posts_via_gh_issue_comment(self):
        r = self.run_script("346", "v0.87-beta", "owner", "UAT", "ok")
        self.assertEqual(r.returncode, 0, r.stderr)
        argv = self.log.read_text().split("\n")
        self.assertEqual(argv[:5], ["issue", "comment", "346", "-R", "mkny13/couch-tour"])
        self.assertEqual(argv[5], "--body")
        self.assertIn("- Evidence: `owner UAT ok`", "\n".join(argv[6:]))

    def test_bare_and_hash_issue_numbers_normalize(self):
        for form in ("346", "#346"):
            r = self.run_script("--dry-run", form, "v0.87", "x")
            self.assertIn("Issue: #346", r.stdout)

    def test_rejects_bad_issue(self):
        r = self.run_script("--dry-run", "abc", "v0.87", "x")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("issue must be a number", r.stderr)

    def test_rejects_bad_tag(self):
        for tag in ("0.87", "latest", "v1"):
            r = self.run_script("--dry-run", "1", tag, "x")
            self.assertNotEqual(r.returncode, 0, tag)
            self.assertIn("tag must look like", r.stderr)

    def test_requires_evidence(self):
        r = self.run_script("1", "v0.87")
        self.assertNotEqual(r.returncode, 0)
        r = self.run_script("1", "v0.87", "  ")
        self.assertNotEqual(r.returncode, 0)
        self.assertFalse(self.log.exists())

    def test_backticks_in_evidence_cannot_break_the_code_span(self):
        r = self.run_script("--dry-run", "1", "v0.87", "a`b")
        self.assertIn("- Evidence: `a'b`", r.stdout)


if __name__ == "__main__":
    unittest.main()
