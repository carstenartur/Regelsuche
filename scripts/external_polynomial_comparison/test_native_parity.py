import unittest
from copy import deepcopy

from external_polynomial_comparison.native_parity import project_row, project_sequence


def row():
    return {'profile': 'SYMPY_PORTFOLIO', 'case': 'test', 'source': 'x+x',
            'bindingWallNanos': 2, 'requestWallNanos': 3,
            'judgment': {'verificationWallNanos': 4, 'endToEndNanos': 7,
                         'valid': True, 'verdict': 'EXACT'},
            'response': {'nativeWallNanos': 1, 'nativeCpuNanos': 1,
                         'candidates': [{'wallNanos': 1, 'output': '2*x', 'cost': 1}],
                         'totalWork': 5, 'modelHash': 'a', 'proof': {'wallNanos': 1}}}


def sequence():
    return {'complete': True, 'wallNanos': 20, 'controllerCpuNanos': 2,
            'profile': 'NATIVE_LEARNED', 'repetition': 0,
            'operations': [{'op': 'initialize', 'requestWallNanos': 2,
                            'response': {'nativeWallNanos': 1, 'nativeCpuNanos': 1,
                                         'trainingWork': 3, 'modelHash': 'a'}}],
            'workers': [{'pid': 2, 'peakRssBytes': 100, 'exitCode': 0,
                         'termination': 'GRACEFUL_EOF',
                         'cpu': {'userNanos': 3, 'systemNanos': 2, 'totalNanos': 5,
                                 'scope': 'PROCESS'}}]}


class NativeParityTest(unittest.TestCase):
    def test_measurements_are_masked_without_mutating_raw(self):
        first = row()
        second = deepcopy(first)
        second.update(bindingWallNanos=20, requestWallNanos=30)
        second['judgment'].update(verificationWallNanos=40, endToEndNanos=70)
        second['response'].update(nativeWallNanos=10, nativeCpuNanos=10)
        second['response']['candidates'][0]['wallNanos'] = 10
        self.assertEqual(project_row(first), project_row(second))
        self.assertEqual(first, row())

    def test_mathematical_and_unknown_fields_are_not_masked(self):
        for keys, value in [(('judgment', 'valid'), False),
                            (('response', 'totalWork'), 6),
                            (('response', 'modelHash'), 'b'),
                            (('response', 'proof', 'wallNanos'), 2),
                            (('response', 'candidates', 0, 'output'), 'x')]:
            changed = row()
            field = changed
            for key in keys[:-1]:
                field = field[key]
            field[keys[-1]] = value
            self.assertNotEqual(project_row(row()), project_row(changed))

    def test_missing_null_and_invalid_measurements(self):
        changed = row()
        del changed['response']['nativeCpuNanos']
        self.assertNotEqual(project_row(row()), project_row(changed))
        changed['response']['nativeCpuNanos'] = None
        self.assertNotEqual(project_row(row()), project_row(changed))
        for value in (-1, True, '1'):
            changed['response']['nativeCpuNanos'] = value
            with self.assertRaises(ValueError):
                project_row(changed)

    def test_sequence_preserves_setup_and_receipt_semantics(self):
        changed = sequence()
        changed.update(wallNanos=40, controllerCpuNanos=4)
        changed['workers'][0].update(pid=3, peakRssBytes=200)
        changed['workers'][0]['cpu'].update(userNanos=6, systemNanos=4, totalNanos=10)
        changed['operations'][0]['requestWallNanos'] = 4
        self.assertEqual(project_sequence(sequence()), project_sequence(changed))
        changed['operations'][0]['response']['trainingWork'] = 4
        self.assertNotEqual(project_sequence(sequence()), project_sequence(changed))
        changed['complete'] = False
        with self.assertRaises(ValueError):
            project_sequence(changed)


if __name__ == '__main__':
    unittest.main()
