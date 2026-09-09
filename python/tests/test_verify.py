import copy
import unittest
from fractions import Fraction
from pathlib import Path
from regelsuche.verify import VerificationError, load, loads, verify_artifact


class VerificationTest(unittest.TestCase):
    def test_actual_java_export_and_tampering(self):
        artifact = load(Path(__file__).resolve().parents[2] / "docs/examples/linear-solution.json")
        self.assertEqual([Fraction(2), Fraction(1), Fraction(3)], verify_artifact(artifact)["particular"])
        changed = copy.deepcopy(artifact)
        changed["evidence"]["result"]["solution"]["particular"][0] = "99"
        with self.assertRaises(VerificationError):
            verify_artifact(changed)
        with self.assertRaises(VerificationError):
            verify_artifact(artifact, ["x=4"])
        # A server's audit flag or cost claim is not authority for this mathematical check.
        artifact["evidence"]["audit"] = {"status": "UNTRUSTED", "work": -100}
        self.assertEqual("UNIQUE", verify_artifact(artifact)["classification"])

    def test_complete_affine_basis_and_contradictions(self):
        artifact = self.artifact(["a+b=3", "c+d=5"], ["a", "b", "c", "d"],
            {"classification": "UNDERDETERMINED", "variables": ["a", "b", "c", "d"],
             "particular": ["3", "0", "5", "0"], "basis": [["-1", "1", "0", "0"], ["0", "0", "-1", "1"]]})
        self.assertEqual(2, len(verify_artifact(artifact)["basis"]))
        artifact["evidence"]["result"]["solution"]["basis"].pop()
        with self.assertRaises(VerificationError):
            verify_artifact(artifact)
        inconsistent = self.artifact(["x=1", "0=1"], ["x"],
            {"classification": "INCONSISTENT", "variables": ["x"], "basis": [], "contradiction": "1"})
        self.assertEqual("INCONSISTENT", verify_artifact(inconsistent)["classification"])
        inconsistent["evidence"]["request"]["equations"][1] = "0=0"
        with self.assertRaises(VerificationError):
            verify_artifact(inconsistent)

    def test_exact_decimals_and_rejected_envelopes(self):
        artifact = self.artifact(["0.1*x=0.3"], ["x"],
            {"classification": "UNIQUE", "variables": ["x"], "particular": ["3"], "basis": []})
        self.assertEqual([Fraction(3)], verify_artifact(artifact)["particular"])
        for source in ('[]', '{"x":1,"x":2}', '{"x":NaN}', '[' * 2000):
            with self.assertRaises(VerificationError):
                loads(source)
        for equation in ("__import__('os')=x", "1e10000000*x=1", "x*x=1"):
            artifact["evidence"]["request"]["equations"] = [equation]
            artifact["evidence"]["result"]["equations"] = [equation]
            with self.assertRaises(VerificationError):
                verify_artifact(artifact)

    @staticmethod
    def artifact(equations, variables, solution):
        return {"schema": "regelsuche.linear-solution-artifact/v1", "evidence": {
            "request": {"schema": "regelsuche.linear-solve-request/v1", "equations": equations},
            "result": {"status": "SOLVED", "equations": list(equations), "variables": variables, "solution": solution}}}


if __name__ == "__main__":
    unittest.main()
