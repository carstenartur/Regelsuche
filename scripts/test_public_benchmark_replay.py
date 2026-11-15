"""Track B's live replay allows only the two recorded matrix-source removals."""
import gzip
import importlib.util
import json
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    "public_benchmark", Path(__file__).with_name("compose-public-benchmark.py"))
COMPOSER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPOSER)


class PublicBenchmarkReplayTest(unittest.TestCase):
    def setUp(self):
        self.frozen = gzip.decompress((COMPOSER.PUBLIC / "hidden-rule-current.json.gz").read_bytes())
        marker = b'"matrix-non-commutative",'
        self.assertEqual(2, self.frozen.count(marker))
        self.replay = self.frozen.replace(marker, b"")

    def test_accepts_only_the_recorded_replay(self):
        COMPOSER.verify_hidden_rule_replay(self.frozen, self.replay)

    def test_rejects_one_additional_field_change(self):
        report = json.loads(self.replay)
        report["profiles"]["best-first"]["summary"]["cases"] += 1
        unexpected = json.dumps(report, separators=(",", ":"), ensure_ascii=False).encode()
        with self.assertRaisesRegex(ValueError, "beyond the two case-002 matrix-source omissions"):
            COMPOSER.verify_hidden_rule_replay(self.frozen, unexpected)

    def test_rejects_the_same_omission_in_the_wrong_case(self):
        report = json.loads(self.frozen)
        for profile in report["profiles"].values():
            case = next(row for row in profile["cases"] if row["opaqueCaseId"] == "case-002")
            other = next(row for row in profile["cases"] if row["opaqueCaseId"] == "case-001")
            case["opaqueCaseId"], other["opaqueCaseId"] = other["opaqueCaseId"], case["opaqueCaseId"]
        wrong_scope = json.dumps(report, separators=(",", ":"), ensure_ascii=False).encode()
        with self.assertRaisesRegex(ValueError, "frozen case-002"):
            COMPOSER.verify_hidden_rule_replay(wrong_scope, self.replay)


if __name__ == "__main__":
    unittest.main()
