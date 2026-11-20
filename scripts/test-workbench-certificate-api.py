#!/usr/bin/env python3
"""Check that the Workbench schema accepts only complete certificate receipts."""

import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator


API = Path(__file__).resolve().parents[1] / "app/src/main/resources/web/openapi/openapi.json"
VERIFIED = {
    "hall": {
        "status": "VERIFIED", "leftCount": 4, "rightCount": 4,
        "edges": [[0, 0], [1, 0], [2, 0], [3, 1], [3, 2], [3, 3]],
        "matching": [[0, 0], [3, 1]], "witness": [0, 1, 2], "neighbors": [0],
        "countDeficit": 0, "hallDeficit": 2, "searchWork": 79, "verificationWork": 29,
    },
    "montgomery": {
        "status": "VERIFIED", "modulus": 3329, "radixBits": 16, "inverse": 62209,
        "productCount": 4, "residueBits": 16, "accumulatorBits": 32,
        "observation": "CANONICAL_RESIDUE", "sourceReductions": 4, "targetReductions": 1,
        "leftRange": [-100, 100], "rightRange": [-200, 200], "sourceOutputRange": [-6656, 6656],
        "targetAccumulatorRange": [-80000, 80000], "targetOutputRange": [-1665, 1665],
        "planningWork": 395, "verificationWork": 220,
    },
    "pair-reduction": {
        "status": "VERIFIED", "evaluatedRows": 100000, "value": "8333333332500000000",
        "firstValue": -50000, "lastValue": 49999, "baselinePairs": "4999950000",
        "prefixRows": 64, "prefixPairs": 2016, "prefixReducedValue": "1397760",
        "prefixDirectValue": "1397760", "evaluationWork": 500007,
    },
}
UNAVAILABLE = {"status": "UNAVAILABLE", "reason": "WORK_LIMIT"}
PARTIAL = {"hall": {"hallDeficit": 2}, "montgomery": {"targetReductions": 1},
           "pair-reduction": {"evaluatedRows": 100000}}


class CertificateDemoApiSchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        api = json.loads(API.read_text(encoding="utf-8"))
        cls.validators = {}
        for name in VERIFIED:
            operation = api["paths"][f"/api/certificate-demos/{name}"]["get"]
            schema = operation["responses"]["200"]["content"]["application/json"]["schema"]
            Draft202012Validator.check_schema(schema)
            cls.validators[name] = Draft202012Validator({**schema, "components": api["components"]})

    def test_accepts_complete_success_and_unavailable_receipts(self):
        for name, validator in self.validators.items():
            for receipt in (VERIFIED[name], UNAVAILABLE):
                with self.subTest(demo=name, status=receipt["status"]):
                    validator.validate(receipt)

    def test_requires_every_success_field(self):
        for name, validator in self.validators.items():
            for field in VERIFIED[name]:
                with self.subTest(demo=name, missing=field):
                    incomplete = dict(VERIFIED[name])
                    del incomplete[field]
                    self.assertFalse(validator.is_valid(incomplete))

    def test_unavailable_requires_a_reason_and_excludes_partial_results(self):
        for name, validator in self.validators.items():
            for receipt in ({"status": "UNAVAILABLE"}, {**UNAVAILABLE, "reason": ""},
                            {**UNAVAILABLE, **PARTIAL[name]}):
                with self.subTest(demo=name, receipt=receipt):
                    self.assertFalse(validator.is_valid(receipt))


if __name__ == "__main__":
    unittest.main()
