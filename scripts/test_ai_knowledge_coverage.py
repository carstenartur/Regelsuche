"""Regression controls for omitted executable capability boundaries (#984)."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "knowledge_verifier", Path(__file__).with_name("verify-ai-knowledge-artifacts.py"))
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)

PREFIX = "de.regelsuche.search.reachability."
TYPES = [PREFIX + name for name in (
    "SharedUnifiedRulePreparationCoordinator",
    "OccurrenceAwareSharedRulePreparationCoordinator", "OccurrencePreparationReplay")]
TESTS = [PREFIX + name for name in (
    "SharedUnifiedRulePreparationCoordinatorTest",
    "OccurrenceAwareSharedRulePreparationCoordinatorTest",
    "OccurrencePreparationConcreteVerifierTest")]


class CapabilityCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in VERIFIER.REQUIRED_FILES:
            self.write(name, {} if name.endswith(".json") else "review context")
        self.write("evidence.json", {"evidence": [{"id": "retained-evidence"}]})
        self.write("classes.json", {"classes": [
            {"class": name, "methodFacts": [{"signature": "analyze()"}]}
            for name in TYPES]})
        self.write("tests.json", {"tests": [{"testClass": name} for name in TESTS]})
        self.write("capabilities.json", {"capabilities": [{
            "id": "rewrite-search", "matchedTypes": TYPES, "matchedTests": TESTS}]})
        self.write("context-packs/index.json", {"contextPacks": [{
            "id": "rewrite-search", "file": "context-packs/rewrite-search.json"}]})
        self.write("context-packs/rewrite-search.json", {
            "id": "rewrite-search", "types": TYPES, "tests": TESTS})
        self.write("complexity.json", {
            "codeComplexity": {"maxMethodCognitiveComplexity": 4},
            "aiCostDrivers": {"tokenCostDrivers": []}, "aiContextDebt": 10,
            "contextFootprint": {
                "schemaVersion": 3, "measurementStatus": "MEASURED",
                "method": VERIFIER.EXPECTED_CONTEXT_FOOTPRINT_METHOD,
                "capabilityCount": 1, "capabilitySampleCount": 1,
                "unresolvedCapabilityTypeReferences": 0,
                "unresolvedCapabilityModuleReferences": 0,
                "unresolvedCapabilityPackageReferences": 0,
                "capabilityWorkingSetSources": {"typeOrPackage": 1},
                "normalizedContextDebt": 10}})

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value if isinstance(value, str) else json.dumps(value), encoding="utf-8")

    def read(self, name):
        return json.loads((self.root / name).read_text(encoding="utf-8"))

    def errors(self):
        return VERIFIER.verify(self.root)[0]

    def test_complete_extracted_and_assigned_boundaries_pass(self):
        self.assertEqual([], self.errors())

    def test_nonempty_capability_cannot_omit_occurrence_coordinator(self):
        doc = self.read("capabilities.json")
        doc["capabilities"][0]["matchedTypes"].remove(TYPES[1])
        self.write("capabilities.json", doc)
        errors = self.errors()
        self.assertTrue(any("CAPABILITY_MEMBERSHIP_MISSING" in error and TYPES[1] in error for error in errors), errors)

    def test_nonempty_context_cannot_omit_concrete_replay_boundary(self):
        doc = self.read("context-packs/rewrite-search.json")
        doc["types"].remove(TYPES[2])
        self.write("context-packs/rewrite-search.json", doc)
        errors = self.errors()
        self.assertTrue(any("CONTEXT_MEMBERSHIP_MISSING" in error and TYPES[2] in error for error in errors), errors)

    def test_assigned_but_unextracted_type_is_distinct_failure(self):
        doc = self.read("classes.json")
        doc["classes"] = [entry for entry in doc["classes"] if entry["class"] != TYPES[0]]
        self.write("classes.json", doc)
        errors = self.errors()
        self.assertTrue(any("BOUNDARY_NOT_EXTRACTED" in error and TYPES[0] in error for error in errors), errors)
        self.assertFalse(any("MEMBERSHIP_MISSING" in error for error in errors))

    def test_replay_regression_tests_must_be_assigned_to_context(self):
        doc = self.read("context-packs/rewrite-search.json")
        doc["tests"].remove(TESTS[2])
        self.write("context-packs/rewrite-search.json", doc)
        errors = self.errors()
        self.assertTrue(any("CONTEXT_MEMBERSHIP_MISSING" in error and TESTS[2] in error for error in errors), errors)

    def test_missing_pack_is_not_hidden_by_nonempty_index(self):
        (self.root / "context-packs/rewrite-search.json").unlink()
        self.assertTrue(self.errors())

    def test_duplicate_capabilities_cannot_shadow_membership(self):
        doc = self.read("capabilities.json")
        doc["capabilities"].append({"id": "rewrite-search", "matchedTypes": [], "matchedTests": []})
        self.write("capabilities.json", doc)
        self.assertTrue(self.errors())

    def test_context_identity_must_match_capability(self):
        doc = self.read("context-packs/rewrite-search.json")
        doc["id"] = "another-capability"
        self.write("context-packs/rewrite-search.json", doc)
        self.assertTrue(self.errors())


if __name__ == "__main__":
    unittest.main()
