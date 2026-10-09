"""Tests the report boundary: work differences may be isolated, forged proof data may not."""
import copy
import unittest
from run import semantics, differences, zero_codec


class ProtocolTest(unittest.TestCase):
    def fixture(self):
        return {'outcome': 'TARGET_REACHED', 'reached': True, 'totalWork': 17, 'metrics': {'searchWork': 8},
                'witness': [{'source': {'expression': 'x', 'previousRule': 'a'},
                             'move': {'generationCost': 3, 'verificationCost': -1, 'applicationCost': 2,
                                      'provenance': {'evidenceHash': 'original', 'sourceExpression': 'x'}},
                             'verification': {'accepted': True, 'receipts': ['original'], 'work': 6}}],
                'events': [], 'reachedStates': [{'expression': 'x', 'assumptions': ['x != 0']}],
                'stateAssessments': []}

    def test_accounting_difference_is_not_a_mathematical_difference(self):
        first = self.fixture(); second = copy.deepcopy(first)
        second['totalWork'] = 20000; second['metrics']['searchWork'] = 10000
        second['witness'][0]['move']['generationCost'] = 400
        second['witness'][0]['verification']['work'] = 800
        self.assertEqual(semantics(first), semantics(second))
        self.assertNotEqual(first, second)

    def test_source_hash_receipts_and_continuation_cannot_be_hidden(self):
        for path, value in [(['witness', 0, 'move', 'provenance', 'evidenceHash'], 'forged'),
                            (['witness', 0, 'move', 'provenance', 'sourceExpression'], 'y'),
                            (['witness', 0, 'verification', 'accepted'], False),
                            (['witness', 0, 'source', 'previousRule'], 'b'),
                            (['witness', 0, 'move', 'applicationCost'], 99),
                            (['reachedStates', 0, 'assumptions'], [])]:
            original = self.fixture(); changed = copy.deepcopy(original); cursor = changed
            for item in path[:-1]: cursor = cursor[item]
            cursor[path[-1]] = value
            self.assertTrue(differences(semantics(original), semantics(changed)), path)

    def test_missing_projection_or_codec_observation_is_not_success(self):
        self.assertFalse(zero_codec({}))
        self.assertFalse(zero_codec({'nativeInternalCodec': {'EXPRESSION_ENCODE': 1}}))
        self.assertFalse(zero_codec({'nativeInternalCodec': {}}))
        self.assertFalse(zero_codec({'nativeInternalCodec': {'EXPRESSION_ENCODE': 0}}))
        full = dict.fromkeys(('EXPRESSION_ENCODE', 'EXPRESSION_DECODE', 'EXPRESSION_JSON_WRITE', 'EXPRESSION_JSON_READ',
                              'HISTORY_ENCODE', 'HISTORY_DECODE', 'HISTORY_HASH', 'EVIDENCE_JSON_WRITE'), 0)
        self.assertTrue(zero_codec({'nativeInternalCodec': full}))
        for key in full:
            incomplete = dict(full); del incomplete[key]
            self.assertFalse(zero_codec({'nativeInternalCodec': incomplete}))
        self.assertTrue(differences({'witness': []}, {}))

    def test_native_public_inconclusive_is_distinct_from_observed_target(self):
        value = self.fixture(); value['outcome'] = 'INCONCLUSIVE'; value['reached'] = False
        self.assertNotEqual(semantics(self.fixture()), semantics(value))
        self.assertEqual(semantics(self.fixture()), semantics(value, 'TARGET_REACHED'))
        self.assertEqual('INCONCLUSIVE', value['outcome'])


if __name__ == '__main__':
    unittest.main()
