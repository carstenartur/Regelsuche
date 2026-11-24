import unittest
from copy import deepcopy

from external_polynomial_comparison.typed_parity import project_metadata, project_row


def row():
    return {'profile': 'SYMPY_FACTOR', 'transportWallNanos': 1, 'bindingWallNanos': 2,
            'requestWallNanos': 3, 'judgment': {'endToEndNanos': 4,
            'verificationWallNanos': 1, 'valid': True}, 'response': {
                'nativeWallNanos': 1, 'nativeCpuNanos': 2, 'output': 'x',
                'candidates': [{'wallNanos': 1, 'output': 'x'}], 'totalWork': 3}}


def metadata():
    return {'controllerCpuScope': 'scope', 'controllerProcessCpuNanos': 1,
            'freezeCommit': 'frozen', 'platform': 'linux', 'protocolHash': 'protocol',
            'python': '3.12', 'revision': 'revision', 'split': 'public',
            'setup': {'BASE': {'startupNanos': 2, 'trainingRequestNanos': 3,
                               'freezePersistenceNanos': 4, 'processCpu': {'userNanos': 1,
                               'systemNanos': 2, 'totalNanos': 3, 'scope': 'PROCESS'},
                               'training': {'nativeWallNanos': 1, 'nativeCpuNanos': 2,
                                            'trainingWork': 0, 'modelHash': 'a'}}},
            'warmups': [{'profile': 'SYMPY_FACTOR', 'requestWallNanos': 3,
                         'response': row()['response']}]}


class TypedParityTest(unittest.TestCase):
    def test_measurements_and_environment(self):
        first = metadata()
        other = deepcopy(first)
        other.update(platform='other', python='3.10', controllerProcessCpuNanos=100)
        other['setup']['BASE']['training']['nativeCpuNanos'] = 100
        other['setup']['BASE']['processCpu']['totalNanos'] = 100
        other['warmups'][0]['response']['candidates'][0]['wallNanos'] = 100
        self.assertEqual(project_metadata(first), project_metadata(other))
        self.assertEqual(first, metadata())

    def test_metadata_semantics_and_unknown_fields(self):
        for key in ('controllerCpuScope', 'freezeCommit', 'protocolHash', 'revision', 'split'):
            other = metadata()
            other[key] = 'changed'
            self.assertNotEqual(project_metadata(metadata()), project_metadata(other))
        other = metadata()
        other['setup']['BASE']['training']['modelHash'] = 'b'
        self.assertNotEqual(project_metadata(metadata()), project_metadata(other))
        other['unexpected'] = 1
        with self.assertRaises(ValueError):
            project_metadata(other)

    def test_rows_preserve_math_work_and_unknown_measurements(self):
        first = row()
        other = deepcopy(first)
        other['transportWallNanos'] = 100
        other['response']['candidates'][0]['wallNanos'] = 100
        self.assertEqual(project_row(first), project_row(other))
        for key, value in [('output', 'y'), ('totalWork', 4), ('newNanos', 1)]:
            changed = deepcopy(other)
            changed['response'][key] = value
            self.assertNotEqual(project_row(first), project_row(changed))
        self.assertEqual(first, row())

    def test_measurement_type_and_presence(self):
        for value in (-1, True, '1'):
            other = row()
            other['requestWallNanos'] = value
            with self.assertRaises(ValueError):
                project_row(other)
        other = row()
        del other['requestWallNanos']
        self.assertNotEqual(project_row(row()), project_row(other))
        other['requestWallNanos'] = None
        self.assertNotEqual(project_row(row()), project_row(other))


if __name__ == '__main__':
    unittest.main()
