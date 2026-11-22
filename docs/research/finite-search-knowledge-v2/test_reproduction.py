import hashlib
import json
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
from copy import deepcopy

from verify_reproduction import compare, expected_matrix, image_receipt, validate

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
V1 = HERE.parent / "finite-search-knowledge-v1"


class ReproductionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.matrix = expected_matrix(ROOT)
        # Synthetic v2 records, independent of the v1 report and the smoke execution.
        cls.report = {"schema": "regelsuche.finite-search-knowledge-comparison/v2",
                      "guidedController": "regelsuche.guided-factor-deepening/v2", "portfolio": "regelsuche.factor-search-portfolio/v1",
                      "regime": "PUBLIC_TARGET_DIRECTED_EXACT_SYNTAX", "workComparability": "NATIVE_CALLS_ONLY_FULL_MECHANICAL_WORK_UNMATCHED",
                      "stretch1000x": "UNMET", "maximumNativeCallsPerArm": 4096, "guidedLookupLimit": 262144,
                      "corpusSha256": json.loads((V1 / "plan.json").read_bytes())["historicalCorpusSha256"], "sourceTables": {}, "rows": []}
        cls.runtime = {"schema": cls.report["schema"] + "/runtime", "samples": []}
        for case, arm in sorted(cls.matrix):
            row = {"caseId": case, "arm": arm, "referenceStatus": "COMPLETE_BOUNDED_CLOSURE", "referenceMinimumDepth": -1,
                   "status": "NO_PATH_WITHIN_DEPTH", "pathLength": -1, "acquisitionCalls": 0, "restorationCalls": 0,
                   "searchCalls": 1, "replayCalls": 0, "generated": 0, "lookupOperations": 0, "cacheHits": 0,
                   "replacements": 0, "totalNativeCalls": 1}
            if arm.startswith("GUIDED_CACHE_"):
                row["status"] += ":COMPLETE"
            if arm.startswith("TABLE_"):
                row["status"], row["searchCalls"], row["lookupOperations"] = "NOT_IN_TABLE", 0, 1
                row["acquisitionCalls"] = int(arm == "TABLE_COLD")
                row["restorationCalls"] = int(arm == "TABLE_RESTORED")
                row["totalNativeCalls"] = row["acquisitionCalls"] + row["restorationCalls"]
                content = {"schema": "regelsuche.finite-factor-knowledge/v1", "acquisition": {"calls": 1, "generated": 0}}
                encoded = json.dumps(content, separators=(",", ":")).encode()
                cls.report["sourceTables"][case] = json.dumps({"content": content, "contentHash": "sha256:" + hashlib.sha256(encoded).hexdigest()})
            cls.report["rows"].append(row)
            cls.runtime["samples"].append({"caseId": case, "arm": arm, "wallNanos": 1, "currentThreadAllocatedBytes": -1})

    def test_rejects_invalid_reference_and_arm_specific_terminals(self):
        for arm, status in (("ONE_SIDED", "FABRICATED"), ("PORTFOLIO_3", "REACHED:COMPLETE"),
                            ("GUIDED_CACHE_1", "NO_PATH_WITHIN_DEPTH"), ("GUIDED_CACHE_128", "WORK_EXHAUSTED:COMPLETE"),
                            ("TABLE_COLD", "NO_COMPLETE_SOURCE_TABLE"), ("TABLE_WARM", "ACQUISITION_INCONCLUSIVE"),
                            ("TABLE_RESTORED", "CONTEXT_MISMATCH")):
            report = deepcopy(self.report)
            row = next(r for r in report["rows"] if r["arm"] == arm)
            row["status"] = status
            with self.subTest(arm=arm, status=status), self.assertRaises(ValueError):
                validate(report, self.runtime, self.matrix)
        report = deepcopy(self.report)
        for row in report["rows"]:
            row["referenceStatus"] = "INVALID"
        with self.assertRaises(ValueError):
            validate(report, self.runtime, self.matrix)

    def test_arm_specific_work_cannot_move_to_an_unexecuted_phase(self):
        for arm, field in (("GUIDED_CACHE_1", "acquisitionCalls"), ("PORTFOLIO_3", "restorationCalls"),
                           ("ONE_SIDED", "lookupOperations"), ("PORTFOLIO_1", "cacheHits"),
                           ("ITERATIVE_DEEPENING", "replacements"), ("TABLE_COLD", "restorationCalls"),
                           ("TABLE_RESTORED", "acquisitionCalls"), ("TABLE_WARM", "searchCalls"),
                           ("TABLE_COLD", "cacheHits"), ("TABLE_RESTORED", "replacements")):
            report = deepcopy(self.report)
            row = next(r for r in report["rows"] if r["arm"] == arm)
            row[field] += 1
            if field.endswith("Calls"):
                if arm.startswith("TABLE_"):
                    row["totalNativeCalls"] += 1
                else:
                    row["searchCalls"] -= 1
            with self.subTest(arm=arm, field=field), self.assertRaises(ValueError):
                validate(report, self.runtime, self.matrix)

    def test_failed_generation_preserves_paid_work_and_unavailable_tables_do_no_work(self):
        report = deepcopy(self.report)
        case = next(iter(report["sourceTables"]))
        del report["sourceTables"][case]
        tables = {r["arm"]: r for r in report["rows"] if r["caseId"] == case and r["arm"].startswith("TABLE_")}
        for arm, row in tables.items():
            row.update(lookupOperations=0, restorationCalls=0)
            if arm == "TABLE_COLD":
                row.update(status="ACQUISITION_INCONCLUSIVE", acquisitionCalls=4, generated=9, totalNativeCalls=4)
            else:
                row.update(status="NO_COMPLETE_SOURCE_TABLE", totalNativeCalls=0)
        validate(report, self.runtime, self.matrix)
        for arm, field in (("TABLE_COLD", "lookupOperations"), ("TABLE_COLD", "replayCalls"),
                           ("TABLE_WARM", "replayCalls"), ("TABLE_WARM", "generated"),
                           ("TABLE_RESTORED", "lookupOperations"), ("TABLE_RESTORED", "restorationCalls")):
            row = tables[arm]
            original = row.copy()
            row[field] = 1
            if field.endswith("Calls"):
                row["totalNativeCalls"] += 1
            with self.subTest(arm=arm, field=field), self.assertRaises(ValueError):
                validate(report, self.runtime, self.matrix)
            row.update(original)

    def test_source_table_failure_rows_cannot_be_attached_to_complete_tables(self):
        report = deepcopy(self.report)
        row = next(r for r in report["rows"] if r["arm"] == "TABLE_WARM")
        row["status"] = "NO_COMPLETE_SOURCE_TABLE"
        with self.assertRaises(ValueError):
            validate(report, self.runtime, self.matrix)

    def test_bounded_reference_and_witness_cannot_exceed_frozen_case_depth(self):
        report = deepcopy(self.report)
        for row in report["rows"]:
            row["referenceMinimumDepth"] = 100
            row["status"] = "REACHED:COMPLETE" if row["arm"].startswith("GUIDED_CACHE_") else "REACHED"
            row["pathLength"] = 100
        with self.assertRaises(ValueError):
            validate(report, self.runtime, self.matrix)

    def test_three_way_check_requires_provenance_and_valid_terminals(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "workflow-source.txt").write_text("a" * 40 + "\n")
            (root / "image.id").write_text("sha256:" + "b" * 64)
            (root / "image-digests.json").write_text('["eclipse-temurin@sha256:3c35c595580ae3342eb34e5832e8e516ded8e767efa4a5fe61b8644d44847b8e"]')
            for name in ("host-1", "host-2", "container"):
                place = root / name
                place.mkdir()
                (place / "source.txt").write_text("6dce1fae5bf84cbb588129993830c41be45a8bda\n3098d9e6b330aa4ef700fa47a7562dc9ce9fa31c\n")
                (place / "comparison.json").write_text(json.dumps(self.report))
                (place / "runtime.json").write_text(json.dumps(self.runtime))
                (place / "build.log").write_text("public build diagnostic\n")
                (place / "run.log").write_text("public runtime diagnostic\n")
            command = [sys.executable, "-B", str(HERE / "verify_reproduction.py"), str(ROOT), str(root)]
            baseline = subprocess.run(command, capture_output=True)
            self.assertEqual(0, baseline.returncode, baseline.stderr)
            for path, bad in (("workflow-source.txt", ""), ("workflow-source.txt", "invalid"),
                              ("host-1/build.log", ""), ("container/run.log", "   \n")):
                f = root / path
                original = f.read_bytes()
                f.write_text(bad)
                with self.subTest(path=path, bad=bad):
                    self.assertNotEqual(0, subprocess.run(command, capture_output=True).returncode)
                f.write_bytes(original)

            # All three copies agree byte for byte yet carry the same false terminal ledger.
            (root / "reproduction.json").unlink()
            forged = deepcopy(self.report)
            for row in forged["rows"]:
                row["referenceStatus"] = "INVALID"
                if not row["arm"].startswith("TABLE_"):
                    row["status"] = "FABRICATED"
            for name in ("host-1", "host-2", "container"):
                (root / name / "comparison.json").write_text(json.dumps(forged))
            self.assertNotEqual(0, subprocess.run(command, capture_output=True).returncode)
            self.assertFalse((root / "reproduction.json").exists())

    def test_exact_matrix_and_observations(self):
        self.assertIsNone(validate(self.report, self.runtime, self.matrix))

    def test_missing_or_duplicated_row_is_rejected(self):
        for mutate in (lambda rows: rows.pop(), lambda rows: rows.__setitem__(1, rows[0])):
            with self.subTest(mutate=mutate):
                report = deepcopy(self.report)
                mutate(report["rows"])
                with self.assertRaises(ValueError):
                    validate(report, self.runtime, self.matrix)

    def test_runtime_and_mechanical_accounting_are_checked(self):
        for target, field, value in (("samples", "wallNanos", -1), ("rows", "totalNativeCalls", 99999),
                                     ("samples", "caseId", "foreign")):
            with self.subTest(field=field):
                report, runtime = deepcopy(self.report), deepcopy(self.runtime)
                (report if target == "rows" else runtime)[target][0][field] = value
                with self.assertRaises(ValueError):
                    validate(report, runtime, self.matrix)

    def test_immutable_image_requires_the_frozen_parent_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            (evidence / "image.id").write_text("sha256:" + "1" * 64)
            (evidence / "image-digests.json").write_text(json.dumps(["eclipse-temurin@sha256:" + "2" * 64]))
            with self.assertRaises(ValueError):
                image_receipt(evidence)

    def test_runtime_is_observation_only_and_source_tables_must_be_retained(self):
        runtime = deepcopy(self.runtime)
        runtime["samples"][0]["wallNanos"] += 5
        validate(self.report, runtime, self.matrix)
        report = deepcopy(self.report)
        report["sourceTables"].pop(next(iter(report["sourceTables"])))
        with self.assertRaises(ValueError):
            validate(report, runtime, self.matrix)

    def test_rejects_unbound_table_or_erased_restoration_cost(self):
        for mode in ("table", "restoration"):
            report = deepcopy(self.report)
            case = next(iter(report["sourceTables"]))
            if mode == "table":
                table = json.loads(report["sourceTables"][case])
                table["contentHash"] = "sha256:" + "0" * 64
                report["sourceTables"][case] = json.dumps(table, separators=(",", ":"))
            else:
                row = next(r for r in report["rows"] if r["caseId"] == case and r["arm"] == "TABLE_RESTORED")
                row["totalNativeCalls"] -= row["restorationCalls"]
                row["restorationCalls"] = 0
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                validate(report, self.runtime, self.matrix)

    def test_equal_json_with_a_different_byte_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            a, b, c = [Path(directory) / name for name in ("a", "b", "c")]
            for path in (a, b, c):
                path.write_bytes(b'{"sample":1}\n')
            compare([a, b, c])
            c.write_bytes(b'{"sample":1} \n')
            with self.assertRaises(ValueError):
                compare([a, b, c])


if __name__ == "__main__":
    unittest.main()
