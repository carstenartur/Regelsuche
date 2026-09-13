#!/usr/bin/env python3
"""Challenge root/matrix bindings using private copies of a real retained run."""

from __future__ import annotations

import argparse
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
REPOSITORY = Path(__file__).resolve().parents[1]
RUN = "release-readiness-run.json"
MATRIX = "release-readiness-report.json"
FORGED_HASH = "sha256:" + "0" * 64


def digest(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def read(root: Path, relative: str) -> dict:
    return json.loads((root / relative).read_text(encoding="utf-8"))


def write(root: Path, relative: str, value: dict) -> None:
    (root / relative).write_text(
        json.dumps(value, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )


def rehashed_run(value: dict) -> str:
    # Independent control producer, checked against the retained Java-generated hash.
    fields = (
        ("profileCatalog", "profileCatalogHash"),
        ("evidence", "evidenceHash"),
        ("hiddenRuleEvidence", "hiddenRuleEvidenceHash"),
        ("qualificationEvidence", "qualificationEvidenceHash"),
        ("matrix", "matrixHash"),
        ("campaign", "campaignManifestHash"),
    )
    material = value["schema"] + "".join(
        "\n" + name + "=" + value[field] for name, field in fields
    )
    return digest(material.encode("utf-8"))


def rehashed_matrix(value: dict) -> str:
    # Java List<String>.toString and boolean text, not JSON serialization.
    profiles = []
    for profile in sorted(value["profiles"], key=lambda item: item["profile"]):
        checks = [
            item["code"] + "|passed=" + str(item["passed"]).lower()
            + "|actual=" + item["actual"] + "|required=" + item["required"]
            for item in sorted(profile["checks"], key=lambda item: item["code"])
        ]
        profiles.append(
            profile["profile"] + "|" + profile["status"] + "|"
            + str(profile["authorizesAutonomyClaim"]).lower() + "|["
            + ", ".join(checks) + "]|[" + ", ".join(sorted(profile["blockers"])) + "]"
        )
    material = (
        value["schema"] + "\nevidence=" + value["evidenceHash"]
        + "\nhiddenRuleEvidence=" + value["hiddenRuleEvidenceHash"]
        + "\nprofiles=[" + ", ".join(profiles) + "]"
        + "\nautonomy=" + value["autonomousCampaignStatus"]
    )
    return digest(material.encode("utf-8"))


class ReleaseReadinessBindingsTest(unittest.TestCase):
    retained_root: Path

    @classmethod
    def setUpClass(cls) -> None:
        path = REPOSITORY / "scripts/verify-release-readiness-evidence.py"
        spec = importlib.util.spec_from_file_location("release_readiness_verifier", path)
        assert spec is not None and spec.loader is not None
        cls.verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.verifier)
        cls.verifier.SCHEMAS = REPOSITORY / "docs/schemas"
        cls.original_bytes = {
            path.relative_to(cls.retained_root): path.read_bytes()
            for path in cls.retained_root.rglob("*") if path.is_file()
        }
        assert cls.original_bytes, "the controls require real generated evidence"

    @classmethod
    def tearDownClass(cls) -> None:
        after = {
            path.relative_to(cls.retained_root): path.read_bytes()
            for path in cls.retained_root.rglob("*") if path.is_file()
        }
        assert after == cls.original_bytes, "controls changed the retained input"

    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory(prefix="release-binding-control-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "evidence"
        shutil.copytree(self.retained_root, self.root)

    def validate(self) -> None:
        with contextlib.redirect_stdout(io.StringIO()):
            self.verifier.validate(self.root)

    def reject(self) -> None:
        with self.assertRaises(SystemExit, msg="verifier accepted manipulated retained evidence"):
            self.validate()

    def test_valid_retained_java_hashes(self) -> None:
        run, matrix = read(self.root, RUN), read(self.root, MATRIX)
        self.assertEqual(run["contentHash"], rehashed_run(run))
        self.assertEqual(matrix["contentHash"], rehashed_matrix(matrix))
        self.validate()

    def test_original_zero_matrix_hash_reproducer(self) -> None:
        run = read(self.root, RUN)
        run["matrixHash"] = FORGED_HASH
        write(self.root, RUN, run)
        self.reject()

    def test_each_root_reference_even_with_fully_rehashed_root(self) -> None:
        original = read(self.root, RUN)
        for field in (
            "campaignManifestHash", "profileCatalogHash", "evidenceHash",
            "hiddenRuleEvidenceHash", "qualificationEvidenceHash", "matrixHash",
        ):
            with self.subTest(field=field):
                forged = copy.deepcopy(original)
                forged[field] = FORGED_HASH
                forged["contentHash"] = rehashed_run(forged)
                write(self.root, RUN, forged)
                self.reject()

    def test_root_content_hash_is_verified(self) -> None:
        run = read(self.root, RUN)
        run["contentHash"] = FORGED_HASH
        write(self.root, RUN, run)
        self.reject()

    def test_catalog_original_bytes_are_bound(self) -> None:
        path = self.root / "profiles.json"
        path.write_bytes(path.read_bytes() + b"\n")
        self.reject()

    def test_campaign_summary_references_retained_campaign(self) -> None:
        evidence = read(self.root, "evidence-summary.json")
        evidence["campaignManifestHash"] = FORGED_HASH
        write(self.root, "evidence-summary.json", evidence)
        self.reject()

    def test_qualification_cross_bindings(self) -> None:
        mutations = (
            ("candidate-qualification-evidence.json", "campaignManifestHash"),
            ("candidate-qualification-run.json", "campaignManifestHash"),
            ("candidate-qualification-run.json", "qualificationEvidenceHash"),
        )
        for name, field in mutations:
            with self.subTest(name=name, field=field):
                relative = "qualification/" + name
                original = read(self.root, relative)
                changed = copy.deepcopy(original)
                changed[field] = FORGED_HASH
                write(self.root, relative, changed)
                self.reject()
                write(self.root, relative, original)

    def test_matrix_evidence_cross_binding_survives_full_rehash(self) -> None:
        matrix = read(self.root, MATRIX)
        matrix["evidenceHash"] = FORGED_HASH
        self.write_rehashed_matrix(matrix)
        self.reject()

    def test_matrix_actual_is_hashed_even_if_status_is_unchanged(self) -> None:
        matrix = read(self.root, MATRIX)
        matrix["profiles"][0]["checks"][0]["actual"] += "; altered observation"
        write(self.root, MATRIX, matrix)
        self.reject()

    def test_matrix_claim_matches_bound_catalog(self) -> None:
        matrix = read(self.root, MATRIX)
        matrix["profiles"][0]["claim"] = "a different unsupported claim"
        write(self.root, MATRIX, matrix)
        self.reject()

    def write_rehashed_matrix(self, matrix: dict) -> None:
        matrix["contentHash"] = rehashed_matrix(matrix)
        write(self.root, MATRIX, matrix)
        run = read(self.root, RUN)
        run["matrixHash"] = matrix["contentHash"]
        run["contentHash"] = rehashed_run(run)
        write(self.root, RUN, run)

    def test_duplicate_profile_cannot_hide_missing_profile_after_full_rehash(self) -> None:
        matrix = read(self.root, MATRIX)
        replacement = next(p for p in matrix["profiles"] if p["profile"] == "AUTONOMOUS_CAMPAIGN")
        matrix["profiles"] = [
            copy.deepcopy(replacement) if p["profile"] == "SEARCH_REPRODUCIBILITY" else p
            for p in matrix["profiles"]
        ]
        self.write_rehashed_matrix(matrix)
        self.reject()

    def test_profile_authority_is_derived_even_after_full_rehash(self) -> None:
        matrix = read(self.root, MATRIX)
        search = next(p for p in matrix["profiles"] if p["profile"] == "SEARCH_REPRODUCIBILITY")
        search["authorizesAutonomyClaim"] = True
        self.write_rehashed_matrix(matrix)
        self.reject()

    def test_profile_status_and_blockers_must_agree_after_full_rehash(self) -> None:
        matrix = read(self.root, MATRIX)
        search = next(p for p in matrix["profiles"] if p["profile"] == "SEARCH_REPRODUCIBILITY")
        search["blockers"] = ["INCONSISTENT_BLOCKER"]
        self.write_rehashed_matrix(matrix)
        self.reject()

    def test_profile_and_check_order_use_java_canonical_order(self) -> None:
        matrix = read(self.root, MATRIX)
        matrix["profiles"].reverse()
        for profile in matrix["profiles"]:
            profile["checks"].reverse()
            profile["blockers"].reverse()
        write(self.root, MATRIX, matrix)
        self.validate()

    def test_duplicate_json_fields_are_rejected(self) -> None:
        path = self.root / RUN
        text = path.read_text(encoding="utf-8")
        path.write_text('{"matrixHash":"' + FORGED_HASH + '",' + text[1:], encoding="utf-8")
        self.reject()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True, help="real generated qualified release evidence")
    args, remaining = parser.parse_known_args()
    ReleaseReadinessBindingsTest.retained_root = args.root.resolve()
    unittest.main(argv=[sys.argv[0], *remaining])


if __name__ == "__main__":
    main()
