import copy
import hashlib
import json
import unittest

from external_polynomial_comparison.schema_parity import semantic_model, project_sequence


def fixture(time):
    selection = {'schema': 'regelsuche.typed-source-policy-selection/v3-accounting', 'selected': 'ALL',
                 'trials': [{'observations': [{'task': 'a', 'totalWork': 7, 'wallNanos': time,
                                              'cpuNanos': time, 'allocatedBytes': time}]}]}
    model = {'schema': 'regelsuche.checked-schema-comparison-profile/v2', 'profile': 'LEARNED_SELECTED',
             'selectionEvidence': json.dumps(selection), 'trainingWork': 7, 'selected': 'ALL'}
    raw = json.dumps(model)
    digest = 'sha256:' + hashlib.sha256(raw.encode()).hexdigest()
    response = {'model': raw, 'modelHash': digest, 'nativeWallNanos': time}
    return {'profile': 'LEARNED_SELECTED', 'errors': [], 'command': ['java', str(time)], 'wallNanos': time,
            'phases': {'training': {'response': response}, 'restore': {'response': copy.deepcopy(response)},
                       'modelPersistence': {'modelHash': digest}, 'modelLoad': {'modelHash': digest}},
            'processes': [{'pid': time, 'peakRssBytes': time, 'cpu': {'totalNanos': time}, 'exitCode': 0}],
            'rows': [{'case': 'a', 'judgment': {'valid': True, 'endToEndNanos': time},
                      'response': {'modelHash': digest, 'totalWork': 3, 'nativeAllocatedBytes': time,
                                   'witness': [{'wallNanos': 5}]}}],
            'accounting': {'complete': True, 'wholeWorkerCpu': {'totalNanos': time}, 'totalLogicalWork': 10}}


class SchemaParityTest(unittest.TestCase):
    def test_runtime_only_difference(self):
        left, right = fixture(10), fixture(20)
        before = copy.deepcopy(left)
        self.assertEqual(project_sequence(left), project_sequence(right))
        self.assertEqual(left, before)

    def test_semantic_differences_remain(self):
        left = fixture(10)
        for key, value in [('totalWork', 4), ('witness', [{'wallNanos': 6}])]:
            with self.subTest(key=key):
                right = fixture(20)
                right['rows'][0]['response'][key] = value
                self.assertNotEqual(project_sequence(left), project_sequence(right))
        right = fixture(20)
        right['rows'][0]['judgment']['valid'] = False
        self.assertNotEqual(project_sequence(left), project_sequence(right))
        right = fixture(20)
        right['rows'].append(copy.deepcopy(right['rows'][0]))
        self.assertNotEqual(project_sequence(left), project_sequence(right))

    def test_model_selection_and_work_are_semantic(self):
        raw = fixture(10)['phases']['training']['response']
        expected, _ = semantic_model(raw['model'], raw['modelHash'], 'LEARNED_SELECTED')
        for key, value in [('selected', 'BASE'), ('trainingWork', 8)]:
            with self.subTest(key=key):
                changed = json.loads(raw['model'])
                changed[key] = value
                encoded = json.dumps(changed)
                digest = 'sha256:' + hashlib.sha256(encoded.encode()).hexdigest()
                actual, _ = semantic_model(encoded, digest, 'LEARNED_SELECTED')
                self.assertNotEqual(expected, actual)

    def test_wrong_model_binding_and_incomplete_sequence_rejected(self):
        for part in ('training', 'restore', 'modelPersistence', 'modelLoad', 'query'):
            with self.subTest(part=part):
                sequence = fixture(10)
                if part in ('training', 'restore'):
                    target = sequence['phases'][part]['response']
                elif part == 'query':
                    target = sequence['rows'][0]['response']
                else:
                    target = sequence['phases'][part]
                target['modelHash'] = 'sha256:' + '0' * 64
                with self.assertRaises(ValueError):
                    project_sequence(sequence)
        sequence = fixture(10)
        sequence['accounting']['complete'] = False
        with self.assertRaises(ValueError):
            project_sequence(sequence)


if __name__ == '__main__':
    unittest.main()
