"""Public benchmark replay and final qualification controls."""
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

    def test_retains_one_post_handoff_qualification_with_all_lane_outcomes(self):
        registry = json.loads((COMPOSER.PUBLIC / "sources.json").read_bytes())
        del registry["compositionSha256"]
        raw = {key: COMPOSER.read_source(COMPOSER.ROOT / item["path"])
               for key, item in registry.items()}
        track = COMPOSER.compose(raw, registry)["tracks"]["F"]
        self.assertIn("finalQualification", track, "Track F needs the post-handoff receipt")
        qualification = track["finalQualification"]
        self.assertEqual(1, qualification["qualificationExecutions"])
        self.assertEqual([1, 0, 1, 2, 1, 2],
                         [lane["qualifiedLineages"] for lane in qualification["lanes"]])

    def test_qualification_cannot_be_transferred_to_an_unfinished_lineage(self):
        registry = json.loads((COMPOSER.PUBLIC / "sources.json").read_bytes())
        allocation = registry["stages-measured"]
        qualification = registry["stages-qualification"]
        stage = json.loads(COMPOSER.read_source(COMPOSER.ROOT / allocation["path"]))
        result = json.loads(COMPOSER.read_source(COMPOSER.ROOT / qualification["path"]))
        unfinished = next(lineage for lane in result["lanes"] for lineage in lane["lineages"]
                          if lineage["allocationStatus"] != "COMPLETED")
        unfinished["qualificationStatus"] = "QUALIFIED"
        with self.assertRaisesRegex(ValueError, "unfinished lineage"):
            COMPOSER.final_qualification(result, stage, allocation["sha256"])


if __name__ == "__main__":
    unittest.main()
