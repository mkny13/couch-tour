#!/usr/bin/env python3
"""Tests for scripts/beta_guard.py (#260).

Stdlib unittest, no network, no authenticated gh calls:
    python3 scripts/test_beta_guard.py
"""

import json
import os
import tempfile
import unittest
from datetime import datetime, timezone, timedelta
from pathlib import Path
from unittest import mock

import beta_guard


class BetaGuardParsingTests(unittest.TestCase):
    def test_parse_releases_date_ordering(self):
        raw = [
            {"tag_name": "v0.80", "prerelease": True, "published_at": "2026-09-11T12:00:00Z"},
            {"tag_name": "v0.82", "prerelease": True, "published_at": "2026-09-12T12:00:00Z"},
            {"tag_name": "v0.81", "prerelease": True, "published_at": "2026-09-11T18:00:00Z"},
        ]
        releases = beta_guard.parse_releases(raw)
        tags = [r["tag_name"] for r in releases]
        self.assertEqual(tags, ["v0.82", "v0.81", "v0.80"])

    def test_parse_releases_ignores_drafts(self):
        raw = [
            {"tag_name": "v0.90", "draft": True, "prerelease": True, "published_at": "2026-09-30T12:00:00Z"},
            {"tag_name": "v0.86", "draft": False, "prerelease": True, "published_at": "2026-09-22T12:00:00Z"},
        ]
        releases = beta_guard.parse_releases(raw)
        self.assertEqual(len(releases), 1)
        self.assertEqual(releases[0]["tag_name"], "v0.86")

    def test_parse_releases_published_at_fallback_to_created_at(self):
        raw = [
            {"tag_name": "v0.85", "draft": False, "prerelease": True, "published_at": None, "created_at": "2026-09-21T10:00:00Z"},
            {"tag_name": "v0.84", "draft": False, "prerelease": True, "published_at": "2026-09-20T10:00:00Z", "created_at": "2026-09-19T10:00:00Z"},
        ]
        releases = beta_guard.parse_releases(raw)
        self.assertEqual(releases[0]["tag_name"], "v0.85")
        dt = beta_guard.get_release_datetime(releases[0])
        self.assertEqual(dt, datetime(2026, 9, 21, 10, 0, 0, tzinfo=timezone.utc))

    def test_parse_releases_concatenated_json(self):
        # gh api --paginate outputs multiple top-level JSON arrays concatenated
        raw_json_str = (
            '[{"tag_name": "v0.86", "draft": false, "prerelease": true, "published_at": "2026-09-22T00:00:00Z"}]\n'
            '[{"tag_name": "v0.83", "draft": false, "prerelease": false, "published_at": "2026-09-15T00:00:00Z"}]'
        )
        releases = beta_guard.parse_releases(raw_json_str)
        self.assertEqual(len(releases), 2)
        self.assertEqual(releases[0]["tag_name"], "v0.86")
        self.assertEqual(releases[1]["tag_name"], "v0.83")

    def test_latest_prerelease_and_production(self):
        releases = [
            {"tag_name": "v0.86", "prerelease": True, "published_at": "2026-09-22T00:00:00Z"},
            {"tag_name": "v0.85", "prerelease": True, "published_at": "2026-09-21T00:00:00Z"},
            {"tag_name": "v0.83", "prerelease": False, "published_at": "2026-09-15T00:00:00Z"},
        ]
        beta = beta_guard.latest_prerelease(releases)
        prod = beta_guard.latest_production(releases)
        self.assertIsNotNone(beta)
        self.assertEqual(beta["tag_name"], "v0.86")
        self.assertIsNotNone(prod)
        self.assertEqual(prod["tag_name"], "v0.83")


class BetaGuardLogicTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 9, 30, 12, 0, 0, tzinfo=timezone.utc)
        self.prod_release = {
            "tag_name": "v0.83",
            "prerelease": False,
            "published_at": "2026-09-15T12:00:00Z",
        }

    def test_8_day_gap_flags(self):
        beta_8_days_old = {
            "tag_name": "v0.86",
            "prerelease": True,
            "published_at": "2026-09-22T12:00:00Z",  # 8 days before 2026-09-30
        }
        gap = beta_guard.gap_days(beta_8_days_old, self.prod_release, now=self.now)
        self.assertEqual(gap, 8)
        self.assertTrue(beta_guard.should_flag(beta_8_days_old, self.prod_release, now=self.now, max_age_days=7))

    def test_exactly_7_day_gap_does_not_flag(self):
        beta_7_days_old = {
            "tag_name": "v0.86",
            "prerelease": True,
            "published_at": "2026-09-23T12:00:00Z",  # exactly 7 days before 2026-09-30
        }
        gap = beta_guard.gap_days(beta_7_days_old, self.prod_release, now=self.now)
        self.assertEqual(gap, 7)
        self.assertFalse(beta_guard.should_flag(beta_7_days_old, self.prod_release, now=self.now, max_age_days=7))

    def test_no_prerelease_does_not_flag(self):
        self.assertFalse(beta_guard.should_flag(None, self.prod_release, now=self.now))
        self.assertEqual(beta_guard.gap_days(None, self.prod_release, now=self.now), 0)

    def test_no_production_release_does_not_flag(self):
        beta = {
            "tag_name": "v0.01",
            "prerelease": True,
            "published_at": "2026-09-01T00:00:00Z",
        }
        self.assertFalse(beta_guard.should_flag(beta, None, now=self.now))
        self.assertEqual(beta_guard.gap_days(beta, None, now=self.now), 0)

    def test_production_newer_than_beta_does_not_flag(self):
        # Beta older than production means all betas are promoted
        beta = {
            "tag_name": "v0.82",
            "prerelease": True,
            "published_at": "2026-09-12T12:00:00Z",
        }
        prod = {
            "tag_name": "v0.83",
            "prerelease": False,
            "published_at": "2026-09-15T12:00:00Z",
        }
        gap = beta_guard.gap_days(beta, prod, now=self.now)
        self.assertEqual(gap, 0)
        self.assertFalse(beta_guard.should_flag(beta, prod, now=self.now, max_age_days=7))

    def test_env_var_max_age_days(self):
        beta_4_days_old = {
            "tag_name": "v0.86",
            "prerelease": True,
            "published_at": "2026-09-26T12:00:00Z",  # 4 days before 2026-09-30
        }
        beta_3_days_old = {
            "tag_name": "v0.86",
            "prerelease": True,
            "published_at": "2026-09-27T12:00:00Z",  # 3 days before 2026-09-30
        }
        with mock.patch.dict(os.environ, {"BETA_GUARD_MAX_AGE_DAYS": "3"}):
            self.assertEqual(beta_guard.get_max_age_days(), 3)
            # 4 days > 3 days -> flags
            self.assertTrue(beta_guard.should_flag(beta_4_days_old, self.prod_release, now=self.now))
            # 3 days == 3 days -> does not flag
            self.assertFalse(beta_guard.should_flag(beta_3_days_old, self.prod_release, now=self.now))


class BetaGuardIssueTests(unittest.TestCase):
    def test_issue_title(self):
        beta = {"tag_name": "v0.86"}
        prod = {"tag_name": "v0.83"}
        title = beta_guard.issue_title(beta, prod)
        self.assertTrue(title.startswith("Beta promotion overdue:"))
        self.assertIn("v0.86", title)

    def test_parse_existing_issue_found(self):
        raw = [
            {"number": 100, "title": "Random bug"},
            {"number": 265, "title": "Beta promotion overdue: v0.86"},
        ]
        self.assertEqual(beta_guard.parse_existing_issue(raw), 265)
        self.assertEqual(beta_guard.parse_existing_issue(json.dumps(raw)), 265)

    def test_parse_existing_issue_not_found(self):
        raw = [
            {"number": 100, "title": "Random bug"},
            {"number": 200, "title": "Another bug"},
        ]
        self.assertIsNone(beta_guard.parse_existing_issue(raw))
        self.assertIsNone(beta_guard.parse_existing_issue("[]"))

    def test_render_body_and_comment_body(self):
        beta = {"tag_name": "v0.86", "published_at": "2026-09-22T04:25:45Z"}
        prod = {"tag_name": "v0.83", "published_at": "2026-09-15T22:34:46Z"}
        body = beta_guard.render_body(beta, prod, 8)
        self.assertIn("v0.86", body)
        self.assertIn("v0.83", body)
        self.assertIn("8 days", body)
        self.assertIn("scripts/promote-beta.sh v0.86", body)
        self.assertIn(".github/workflows/beta-guard.yml", body)

        comment = beta_guard.render_comment_body(beta, prod, 9)
        self.assertIn("v0.86", comment)
        self.assertIn("v0.83", comment)
        self.assertIn("9 days", comment)


class BetaGuardWorkflowExecutionTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 9, 30, 12, 0, 0, tzinfo=timezone.utc)
        self.stale_releases_json = json.dumps([
            {"tag_name": "v0.86", "draft": False, "prerelease": True, "published_at": "2026-09-22T12:00:00Z"},
            {"tag_name": "v0.83", "draft": False, "prerelease": False, "published_at": "2026-09-15T12:00:00Z"},
        ])

    def test_running_script_twice_with_stale_state_comments_instead_of_duplicating(self):
        # Run 1: No open issue exists -> Creates issue #500
        run_gh_mock = mock.Mock()

        def gh_side_effect(args):
            cmd = args[0]
            subcmd = args[1] if len(args) > 1 else ""
            if cmd == "api":
                return self.stale_releases_json
            if cmd == "issue" and subcmd == "list":
                # First run: no existing issue found
                return "[]"
            if cmd == "issue" and subcmd == "create":
                # Returns created issue url with number 500
                return "https://github.com/mkny13/couch-tour/issues/500\n"
            return ""

        run_gh_mock.side_effect = gh_side_effect

        with mock.patch.object(beta_guard, "run_gh", run_gh_mock):
            res1 = beta_guard.run_guard(now=self.now, max_age_days=7)

        self.assertTrue(res1["flagged"])
        self.assertEqual(res1["action"], "create")
        self.assertEqual(res1["issue"], 500)

        # Verify create_issue called with labels type:chore and p2, and NO mahler:* label
        create_calls = [c for c in run_gh_mock.call_args_list if c[0][0][0:2] == ["issue", "create"]]
        self.assertEqual(len(create_calls), 1)
        create_args = create_calls[0][0][0]
        self.assertIn("--label", create_args)
        self.assertIn("type:chore", create_args)
        self.assertIn("p2", create_args)
        for arg in create_args:
            self.assertFalse(arg.startswith("mahler:"), f"Forbidden mahler label found: {arg}")

        # Run 2: Issue #500 already exists -> Comments on #500, does NOT create another issue
        run_gh_mock.reset_mock()

        def gh_side_effect_run2(args):
            cmd = args[0]
            subcmd = args[1] if len(args) > 1 else ""
            if cmd == "api":
                return self.stale_releases_json
            if cmd == "issue" and subcmd == "list":
                return json.dumps([{"number": 500, "title": "Beta promotion overdue: v0.86"}])
            if cmd == "issue" and subcmd == "comment":
                return ""
            return ""

        run_gh_mock.side_effect = gh_side_effect_run2

        with mock.patch.object(beta_guard, "run_gh", run_gh_mock):
            res2 = beta_guard.run_guard(now=self.now, max_age_days=7)

        self.assertTrue(res2["flagged"])
        self.assertEqual(res2["action"], "comment")
        self.assertEqual(res2["issue"], 500)

        comment_calls = [c for c in run_gh_mock.call_args_list if c[0][0][0:2] == ["issue", "comment"]]
        self.assertEqual(len(comment_calls), 1)
        self.assertEqual(comment_calls[0][0][0][2], "500")

        # Confirm no create call occurred on the second run
        create_calls_run2 = [c for c in run_gh_mock.call_args_list if c[0][0][0:2] == ["issue", "create"]]
        self.assertEqual(len(create_calls_run2), 0)

    def test_main_exits_zero_even_on_exception(self):
        with mock.patch.object(beta_guard, "run_gh", side_effect=beta_guard.GhError("API error")):
            rc = beta_guard.main()
            self.assertEqual(rc, 0)

    def test_step_summary_written_when_env_present(self):
        with tempfile.NamedTemporaryFile("w+", delete=False) as tf:
            summary_path = tf.name
        try:
            with mock.patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": summary_path}):
                beta_guard.write_step_summary("Test summary line")
                content = Path(summary_path).read_text()
                self.assertIn("Test summary line", content)
        finally:
            if os.path.exists(summary_path):
                os.remove(summary_path)


if __name__ == "__main__":
    unittest.main()
