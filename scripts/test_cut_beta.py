#!/usr/bin/env python3
"""Tests for scripts/cut-beta.sh failure reporting (#605).

Runs the real script against fake `git` and `gh` binaries on PATH; no network:
    python3 scripts/test_cut_beta.py
"""

import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "cut-beta.sh"

FAKE_GIT = "#!/bin/sh\n[ \"$1\" = tag ] && echo v0.87\nexit 0\n"

# FAIL_WORKFLOW names the workflow whose `gh run watch` should fail.
FAKE_GH = r"""#!/bin/sh
case "$1 $2" in
  "workflow run") exit 0 ;;
  "run list")
    case "$*" in
      *build-debug-apk*) id=111 ;;
      *) id=222 ;;
    esac
    case "$*" in *databaseId*) echo $id ;; *) echo "https://example/runs/$id" ;; esac ;;
  "run watch") [ "$3" = "$FAIL_ID" ] && exit 1; exit 0 ;;
  "run view") echo "step X: boom"; echo "Commit updated appcast failed" ;;
esac
exit 0
"""


class CutBetaTests(unittest.TestCase):
    def run_script(self, fail_id="", *args):
        with tempfile.TemporaryDirectory() as d:
            for name, body in (("git", FAKE_GIT), ("gh", FAKE_GH), ("sleep", "#!/bin/sh\n")):
                p = Path(d, name)
                p.write_text(body)
                p.chmod(p.stat().st_mode | stat.S_IEXEC)
            env = {**os.environ, "PATH": f"{d}:{os.environ['PATH']}", "FAIL_ID": fail_id}
            return subprocess.run(["bash", str(SCRIPT), *args, "notes"], env=env,
                                  capture_output=True, text=True, timeout=60)

    def test_success_exits_zero(self):
        r = self.run_script()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("shipped on Android and macOS", r.stdout)

    def test_mac_failure_is_loud_and_nonzero(self):
        r = self.run_script("222")
        self.assertEqual(r.returncode, 1)
        self.assertIn("MAC BUILD FAILED for v0.88", r.stderr)
        self.assertIn("Commit updated appcast failed", r.stderr)
        self.assertNotIn("ANDROID BUILD FAILED", r.stderr)

    def test_android_failure_is_loud_and_nonzero(self):
        r = self.run_script("111")
        self.assertEqual(r.returncode, 1)
        self.assertIn("ANDROID BUILD FAILED", r.stderr)

    def test_no_watch_skips_waiting(self):
        r = self.run_script("222", "--no-watch")
        self.assertEqual(r.returncode, 0)
        self.assertNotIn("FAILED", r.stderr)


if __name__ == "__main__":
    unittest.main()
