import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import shape_check as sc  # noqa: E402


def d(old, new):
    return sc.diff(sc.shape(old), sc.shape(new))


class DiffTest(unittest.TestCase):
    def test_same_shape_different_values(self):
        self.assertEqual(d({"a": 1, "b": ["x"]}, {"a": 2, "b": ["y", "z"]}), [])

    def test_added_key(self):
        self.assertEqual(d({"a": 1}, {"a": 1, "b": "s"}), ["+ $.b (string)"])

    def test_added_nested_key_in_array(self):
        self.assertEqual(d({"t": [{"a": 1}]}, {"t": [{"a": 1, "n": True}]}), ["+ $.t[].n (bool)"])

    def test_removed_key(self):
        self.assertEqual(d({"a": 1, "b": 2}, {"a": 1}), ["- $.b"])

    def test_retyped_key(self):
        self.assertEqual(d({"a": "1"}, {"a": 1}), ["~ $.a: string -> number"])

    def test_nullable_is_not_drift(self):
        self.assertEqual(d({"a": "x"}, {"a": None}), [])
        self.assertEqual(d({"a": None}, {"a": "x"}), [])

    def test_array_length_and_emptiness_is_not_drift(self):
        self.assertEqual(d({"t": [{"a": 1}, {"a": 2}, {"a": 3}]}, {"t": [{"a": 1}]}), [])
        self.assertEqual(d({"t": []}, {"t": [{"a": 1}]}), [])
        self.assertEqual(d({"t": [{"a": 1}]}, {"t": []}), [])

    def test_element_type_change(self):
        self.assertEqual(d({"t": [1]}, {"t": ["x"]}), ["~ $.t[]: number -> string"])


class DirsTest(unittest.TestCase):
    def test_skips_missing_fresh_and_reports_drift(self):
        with tempfile.TemporaryDirectory() as c, tempfile.TemporaryDirectory() as f:
            for n in ("contract_a.json", "contract_b.json", "contract_c.json"):
                Path(c, n).write_text(json.dumps({"k": 1}))
            Path(f, "contract_a.json").write_text(json.dumps({"k": 2}))
            Path(f, "contract_b.json").write_text(json.dumps({"k": 1, "new": "x"}))
            drift, skipped = sc.compare_dirs(c, f)
        self.assertEqual(drift, {"contract_b.json": ["+ $.new (string)"]})
        self.assertEqual(skipped, ["contract_c.json"])
        text = sc.report(drift, skipped)
        self.assertIn("contract_b.json", text)
        self.assertIn("+ $.new (string)", text)
        self.assertIn("contract_c.json", text)


class UpsertTest(unittest.TestCase):
    def run_upsert(self, listing):
        calls = []

        def gh(args):
            calls.append(args)
            return json.dumps(listing) if args[1] == "list" else ""

        return sc.upsert_issue("body", gh), calls

    def test_creates_when_none_open(self):
        action, calls = self.run_upsert([])
        self.assertEqual(action, "create")
        self.assertEqual(calls[-1][:2], ["issue", "create"])
        self.assertIn("mahler", calls[-1])

    def test_comments_on_existing(self):
        action, calls = self.run_upsert([{"number": 7, "title": sc.ISSUE_TITLE},
                                         {"number": 8, "title": "Contract drift: other"}])
        self.assertEqual(action, "comment")
        self.assertEqual(calls[-1][:3], ["issue", "comment", "7"])
        self.assertTrue(calls[-1][-1].startswith("<!-- mahler:agent -->"))


if __name__ == "__main__":
    unittest.main()
