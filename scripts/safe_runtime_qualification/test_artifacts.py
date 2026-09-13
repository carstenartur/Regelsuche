"""Mutations of real CLI recordings, after the public case manifest was frozen."""
from copy import deepcopy
import json
from pathlib import Path
import unittest

import protocol

HERE = Path(__file__).parent
MANIFEST = HERE.parents[1] / 'config/qualification/safe-runtime-public-v1.json'


class RuntimeArtifactTest(unittest.TestCase):
    def setUp(self):
        self.case = json.loads(MANIFEST.read_text())['cases'][1]
        self.raw = (HERE / 'testdata/native-safe.json').read_bytes()
        self.artifact = json.loads(self.raw)
        self.identity = self.artifact['evidence']['implementation']

    def validate(self, raw=None, profile='SAFE_PREPARATION_V4'):
        self.assertTrue(hasattr(protocol, 'validate_artifact'), 'raw export authority is missing')
        return protocol.validate_artifact(self.raw if raw is None else raw,
                                          self.case, profile, self.identity)

    def mutated(self, change):
        value = deepcopy(self.artifact)
        change(value['evidence'])
        value['contentHash'] = protocol.content_hash(protocol.canonical(value['evidence']))
        return protocol.canonical(value) + b'\n'

    def test_real_exports_bind_canonical_bytes_and_paired_input(self):
        self.assertEqual('SUCCESS', self.validate()['status'])
        direct = (HERE / 'testdata/native-direct.json').read_bytes()
        self.assertEqual('NO_MATCH', self.validate(direct, 'DIRECT_V1')['status'])

    def test_duplicate_keys_and_noncanonical_or_false_hash_are_rejected(self):
        for raw in (self.raw.replace(b'"schema":', b'"schema":"duplicate","schema":', 1),
                    b' ' + self.raw, self.raw.replace(b'"chargedUnits":112', b'"chargedUnits":113')):
            with self.subTest(raw=raw[:80]), self.assertRaises(ValueError):
                self.validate(raw)

    def test_rehashed_source_assumptions_selection_and_each_budget_are_rejected(self):
        changes = [('source', 'x'), ('assumptions', ['x != 0']), ('ruleIds', []),
                   ('preparationRuleIds', ['ast_square_difference_factor']), ('includeSymPy', True),
                   ('maxWorkUnits', 199999), ('maxPrimitiveRewrites', 3), ('maxTheoryWorkUnits', 199999)]
        for key, value in changes:
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'request'):
                self.validate(self.mutated(lambda e: e['request'].__setitem__(key, value)))
        for key in self.case['commonRequest']['preparationBudget']:
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'request'):
                self.validate(self.mutated(lambda e: e['request']['preparationBudget'].__setitem__(key, 0)))

    def test_rehashed_inventory_and_implementation_substitution_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'inventory'):
            self.validate(self.mutated(lambda e: e['inventory']['visible'].pop()))
        with self.assertRaisesRegex(ValueError, 'implementation'):
            self.validate(self.mutated(lambda e: e['implementation'].__setitem__('contentHash', 'sha256:' + 'a'*64)))

    def test_rehashed_recorded_execution_cannot_erase_work_or_lineage(self):
        for key, value in [('primitiveRuleIds', ['ast_square_difference_factor']),
                           ('retainedAssumptions', ['x != 0']), ('expression', 'x'),
                           ('executionWork', {'primitiveRewrites': 1, 'exactTheorySteps': 0, 'exactTheoryWorkUnits': 0})]:
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'candidate|execution'):
                self.validate(self.mutated(lambda e: e['outcomes'][0]['candidate'].__setitem__(key, value)))

    def test_direct_cannot_relabel_prepared_authority(self):
        value = deepcopy(self.artifact)
        value['evidence']['request']['profile'] = 'DIRECT_V1'
        value['contentHash'] = protocol.content_hash(protocol.canonical(value['evidence']))
        with self.assertRaisesRegex(ValueError, 'DIRECT|coordinator'):
            self.validate(protocol.canonical(value) + b'\n', 'DIRECT_V1')

    def test_v4_successor_and_work_authorities_cannot_drift_under_the_same_contract(self):
        for field in ('successorIdentityRevision', 'contextualWork'):
            def change(evidence):
                if field == 'contextualWork':
                    evidence['authority'][field]['receipt']['revision'] = 'unknown/v99'
                else:
                    evidence['authority'][field] = 'unknown/v99'
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'successor|contextual work'):
                self.validate(self.mutated(change))


if __name__ == '__main__':
    unittest.main()
