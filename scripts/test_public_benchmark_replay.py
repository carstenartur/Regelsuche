"""Track B's live replay must reproduce the complete pinned report."""
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
        self.frozen = gzip.decompress(
            (COMPOSER.PUBLIC / (COMPOSER.HIDDEN_RULE_REPLAY + ".json.gz")).read_bytes())

    def test_accepts_only_the_recorded_replay(self):
        COMPOSER.verify_hidden_rule_replay(self.frozen, self.frozen)

    def test_rejects_one_additional_field_change(self):
        report = json.loads(self.frozen)
        report["profiles"]["best-first"]["summary"]["cases"] += 1
        unexpected = json.dumps(report, separators=(",", ":"), ensure_ascii=False).encode()
        with self.assertRaisesRegex(ValueError, "differs from the frozen constant-denominator report"):
            COMPOSER.verify_hidden_rule_replay(self.frozen, unexpected)

    def test_rejects_replaying_the_older_exact_regime(self):
        older = gzip.decompress((COMPOSER.PUBLIC / "hidden-rule-current.json.gz").read_bytes())
        with self.assertRaisesRegex(ValueError, "differs from the frozen constant-denominator report"):
            COMPOSER.verify_hidden_rule_replay(self.frozen, older)


if __name__ == "__main__":
    unittest.main()
