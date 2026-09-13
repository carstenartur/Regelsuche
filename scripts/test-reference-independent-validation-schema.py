#!/usr/bin/env python3
"""Schema rejection controls over the complete real Java companion output."""

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "companion_schema", ROOT / "scripts/verify-reference-independent-validation-schema.py")
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class CompanionSchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = json.loads((ROOT / "docs/schemas/regelsuche-reference-independent-candidate-validation-v1.schema.json").read_text())
        cls.document = json.loads((ROOT / "regelsuche-discovery/build/reports/reference-independent-candidate-validation/test/reference-independent-candidate-validation.json").read_text())

    def rehashed(self, document):
        canonical = json.dumps(document["content"], ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        document["contentHash"] = "sha256:" + hashlib.sha256(canonical.encode()).hexdigest()
        return document

    def test_complete_real_matrix_obeys_exchange_schema(self):
        CHECKER.check_document(self.schema, self.document)

    def test_reference_labels_are_forbidden_even_when_rehashed(self):
        mutated = copy.deepcopy(self.document)
        mutated["content"]["rows"][0]["outcomes"][0]["referenceMatched"] = True
        with self.assertRaises(ValueError):
            CHECKER.check_document(self.schema, self.rehashed(mutated))

    def test_formal_proof_claim_is_forbidden_even_when_rehashed(self):
        mutated = copy.deepcopy(self.document)
        mutated["content"]["rows"][0]["outcomes"][0]["formalProofStatus"] = "PROVED"
        with self.assertRaises(ValueError):
            CHECKER.check_document(self.schema, self.rehashed(mutated))

    def test_zero_deadline_is_forbidden_even_when_rehashed(self):
        mutated = copy.deepcopy(self.document)
        mutated["content"]["budget"]["timeoutMillis"] = 0
        with self.assertRaises(ValueError):
            CHECKER.check_document(self.schema, self.rehashed(mutated))

    def test_payload_hash_mismatch_is_rejected(self):
        mutated = copy.deepcopy(self.document)
        mutated["contentHash"] = "sha256:" + "0" * 64
        with self.assertRaises(ValueError):
            CHECKER.check_document(self.schema, mutated)


if __name__ == "__main__":
    unittest.main()
