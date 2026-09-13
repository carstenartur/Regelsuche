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
from unittest import mock

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

    def test_root_catalog_and_nested_directory_symlinks_are_rejected(self) -> None:
        for relative in ("profiles.json", "campaign", "."):
            with self.subTest(relative=relative):
                target = self.root / relative
                outside = self.root.parent / "outside"
                target.rename(outside)
                target.symlink_to(outside, target_is_directory=outside.is_dir())
                try:
                    self.reject()
                finally:
                    target.unlink()
                    outside.rename(target)

    def test_symbolic_ancestor_of_the_requested_root_is_rejected(self) -> None:
        alias = self.root.parent / "alias"
        alias.symlink_to(self.root.parent, target_is_directory=True)
        with self.assertRaises(SystemExit):
            self.verifier.validate(alias / self.root.name)

    def test_consumed_identity_fields_cannot_change_under_the_original_hash(self) -> None:
        mutations = (
            ("evidence-summary.json", "candidateCount", 2),
            ("campaign/production-campaign-manifest.json", "candidateCount", 2),
            ("hidden-rule-release-evidence.json", "generatedValidationExamples", 176),
            ("qualification/candidate-qualification-evidence.json", "pairedUtilityPermille", 999),
            ("qualification/candidate-qualification-run.json", "suiteHash", FORGED_HASH),
        )
        for relative, field, value in mutations:
            with self.subTest(relative=relative, field=field):
                original = read(self.root, relative)
                changed = copy.deepcopy(original)
                self.assertNotEqual(value, changed[field])
                changed[field] = value
                write(self.root, relative, changed)
                try:
                    self.reject()
                finally:
                    write(self.root, relative, original)

    def test_every_root_binding_source_requires_its_existing_schema(self) -> None:
        for relative in ("evidence-summary.json", "hidden-rule-release-evidence.json",
                         "campaign/production-campaign-manifest.json"):
            with self.subTest(relative=relative):
                original = read(self.root, relative)
                changed = copy.deepcopy(original)
                changed["schema"] = "unrecognized-evidence/v999"
                write(self.root, relative, changed)
                try:
                    self.reject()
                finally:
                    write(self.root, relative, original)

    def test_unsupported_no_follow_platform_fails_instead_of_skipping_verification(self) -> None:
        with mock.patch("release_readiness_files.os.supports_dir_fd", set()):
            with self.assertRaisesRegex(SystemExit, "UNSUPPORTED_PLATFORM"):
                self.validate()

    def test_schema_symlink_is_rejected(self) -> None:
        schemas = self.root.parent / "schemas"
        shutil.copytree(self.verifier.SCHEMAS, schemas)
        schema = schemas / self.verifier.SCHEMA_PAIRS[0][0]
        original = schema.read_bytes()
        outside = self.root.parent / "outside-schema.json"
        outside.write_bytes(original)
        schema.unlink()
        schema.symlink_to(outside)
        with mock.patch.object(self.verifier, "SCHEMAS", schemas):
            self.reject()

    def test_opened_root_and_first_read_bytes_remain_owned_after_path_replacement(self) -> None:
        from release_readiness_files import EvidenceFiles
        expected = (self.root / "profiles.json").read_bytes()
        outside = self.root.parent / "outside"
        outside.mkdir()
        (outside / "profiles.json").write_bytes(b"outside-data")
        moved = self.root.parent / "moved-root"
        with EvidenceFiles(self.root) as files:
            self.root.rename(moved)
            self.root.symlink_to(outside, target_is_directory=True)
            try:
                self.assertEqual(expected, files.read_bytes("profiles.json"))
                (moved / "profiles.json").write_bytes(b"changed-after-first-read")
                self.assertEqual(expected, files.read_bytes(Path("profiles.json")))
            finally:
                self.root.unlink()
                moved.rename(self.root)

    def test_reader_refuses_relative_escape_and_nonregular_members(self) -> None:
        from release_readiness_files import EvidenceFiles
        import os
        pipe = self.root / "not-a-file"
        os.mkfifo(pipe)
        with EvidenceFiles(self.root) as files:
            for name in ("../outside.json", str(self.root / "profiles.json"), ".", "not-a-file"):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    files.read_bytes(name)

    def test_campaign_count_still_requires_summary_binding_after_manifest_rehash(self) -> None:
        from release_readiness_identities import campaign_hash
        relative = "campaign/production-campaign-manifest.json"
        manifest = read(self.root, relative)
        manifest["candidateCount"] += 1
        self.assertEqual(manifest["contentHash"], campaign_hash(manifest),
                         "historical campaign v2 hash does not cover the summary counts")
        write(self.root, relative, manifest)
        self.reject()

    def test_each_direct_dependency_hash_is_recomputed_before_root_use(self) -> None:
        mutations = (
            ("evidence-summary.json", "evidenceHash"),
            ("hidden-rule-release-evidence.json", "evidenceHash"),
            ("campaign/production-campaign-manifest.json", "contentHash"),
            ("qualification/candidate-qualification-evidence.json", "contentHash"),
            ("qualification/candidate-qualification-run.json", "contentHash"),
        )
        for relative, field in mutations:
            with self.subTest(relative=relative):
                original = read(self.root, relative)
                forged = copy.deepcopy(original)
                forged[field] = FORGED_HASH
                write(self.root, relative, forged)
                try:
                    with self.assertRaisesRegex(SystemExit, "canonical identity differs"):
                        self.validate()
                finally:
                    write(self.root, relative, original)

    def test_java_unicode_order_blank_filter_and_utf8_replacement_are_preserved(self) -> None:
        from release_readiness_identities import java_hash, java_list, sorted_java_strings
        # Oracle: JDK 25 List.stream().filter(!String.isBlank).distinct().sorted(),
        # List.toString(), and SHA-256 of String.getBytes(StandardCharsets.UTF_8).
        values = ["\u000b", "\u0085", "\u00a0", "\u2007", "\u2000", "\u202f",
                  "z", "\U00010000", "\ue000", "z"]
        self.assertEqual("sha256:f5f38822fd19e7c857d71db71027eb0a412044c2d02ea9bb1d3aacb10010324b",
                         java_hash(java_list(sorted_java_strings(values))))
        self.assertEqual("sha256:c55874dc1cdc737315a4af6031e71eda35060469b02592bcf570b8611f0c22f6",
                         java_hash("\ud800|\udc00|\ud800\udc00|Ä\n"))

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
