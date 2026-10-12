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
  "workflow run")
    # DISPATCH_URL=1: print the new run's URL like current gh does.
    if [ "$DISPATCH_URL" = "1" ]; then
      case "$*" in
        *build-debug-apk*) echo "https://github.com/o/r/actions/runs/901" ;;
        *) echo "https://github.com/o/r/actions/runs/902" ;;
      esac
    fi
    exit 0 ;;
  "run list")
    case "$*" in
      *build-debug-apk*)
        if [ "$EMPTY_ANDROID" = "1" ] || [ "$EMPTY_BOTH" = "1" ]; then
          id=""
        else
          id=111
        fi ;;
      *)
        if [ "$EMPTY_MACOS" = "1" ] || [ "$EMPTY_BOTH" = "1" ]; then
          id=""
        else
          id=222
        fi ;;
    esac
    case "$*" in *databaseId*)
      echo $id ;;
    *)
      if [ -n "$id" ]; then
        echo "https://example/runs/$id"
      else
        echo ""
      fi ;;
    esac ;;
  "run watch")
    [ -z "$FAIL_ID" ] && exit 0
    [ "$3" = "$FAIL_ID" ] && exit 1
    exit 0 ;;
  "run view") echo "step X: boom"; echo "Commit updated appcast failed" ;;
esac
exit 0
"""


class CutBetaTests(unittest.TestCase):
    def run_script(self, fail_id="", *args, dispatch_url=False):
        with tempfile.TemporaryDirectory() as d:
            for name, body in (("git", FAKE_GIT), ("gh", FAKE_GH), ("sleep", "#!/bin/sh\n")):
                p = Path(d, name)
                p.write_text(body)
                p.chmod(p.stat().st_mode | stat.S_IEXEC)
            env = {**os.environ, "PATH": f"{d}:{os.environ['PATH']}", "FAIL_ID": fail_id}
            if dispatch_url:
                env["DISPATCH_URL"] = "1"
            if fail_id == "empty_android":
                env["EMPTY_ANDROID"] = "1"
            if fail_id == "empty_macos":
                env["EMPTY_MACOS"] = "1"
            if fail_id == "empty_both":
                env["EMPTY_ANDROID"] = "1"
                env["EMPTY_MACOS"] = "1"
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

    def test_android_id_empty_unverified_branch(self):
        r = self.run_script("empty_android")
        self.assertEqual(r.returncode, 1)
        self.assertIn("ANDROID BUILD UNVERIFIED", r.stderr)
        self.assertNotIn("MAC BUILD FAILED", r.stderr)

    def test_macos_id_empty_unverified_branch(self):
        r = self.run_script("empty_macos")
        self.assertEqual(r.returncode, 1)
        self.assertIn("MAC BUILD UNVERIFIED", r.stderr)
        self.assertNotIn("ANDROID BUILD FAILED", r.stderr)

    def test_both_ids_empty_unverified_branch(self):
        r = self.run_script("empty_both")
        self.assertEqual(r.returncode, 1)
        self.assertIn("ANDROID BUILD UNVERIFIED", r.stderr)
        self.assertIn("MAC BUILD UNVERIFIED", r.stderr)

    def test_no_watch_with_missing_id(self):
        r = self.run_script("empty_android", "--no-watch")
        self.assertEqual(r.returncode, 0)
        self.assertIn("ANDROID BUILD UNVERIFIED", r.stderr)

    def test_dispatch_url_beats_newer_run_in_list(self):
        # `gh run list` would return 111/222 (someone else's newer run); the printed URLs win.
        r = self.run_script("902", dispatch_url=True)
        self.assertEqual(r.returncode, 1)
        self.assertIn("MAC BUILD FAILED", r.stderr)
        self.assertIn("actions/runs/901", r.stdout)
        self.assertIn("actions/runs/902", r.stdout)
        self.assertNotIn("runs/111", r.stdout)

    def test_dispatch_url_success(self):
        r = self.run_script(dispatch_url=True)
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_no_dispatch_url_falls_back_to_run_list(self):
        r = self.run_script()
        self.assertIn("https://example/runs/111", r.stdout)
        self.assertIn("https://example/runs/222", r.stdout)


if __name__ == "__main__":
    unittest.main()
