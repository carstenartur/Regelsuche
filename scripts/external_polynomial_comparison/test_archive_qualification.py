import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from external_polynomial_comparison.qualify_archives import (
    require_native_order, require_source, require_typed_outcomes, require_typed_setup)
from external_polynomial_comparison.run_typed import FREEZE_COMMIT, PROTOCOL_HASH


class ArchiveSourceTest(unittest.TestCase):
    def test_sources_and_typed_protocol_must_match_the_admitted_checkout(self):
        revision, tree = 'a' * 40, 'b' * 40
        with TemporaryDirectory() as temporary:
            typed, native = (Path(temporary) / name for name in ('typed', 'native'))
            typed.mkdir()
            native.mkdir()
            typed_metadata = {'revision': revision, 'freezeCommit': FREEZE_COMMIT, 'protocolHash': PROTOCOL_HASH}
            native_metadata = {'sourceRevision': revision, 'sourceTreeHash': 'git:' + tree}
            for directory, metadata in ((typed, typed_metadata), (native, native_metadata)):
                (directory / 'metadata.json').write_text(json.dumps(metadata))
            require_source(typed, native, revision, tree)
            for directory, metadata in ((typed, typed_metadata), (native, native_metadata)):
                for key in metadata:
                    with self.subTest(key=key):
                        path = directory / 'metadata.json'
                        path.write_text(json.dumps({**metadata, key: 'changed'}))
                        with self.assertRaisesRegex(ValueError, 'source|protocol'):
                            require_source(typed, native, revision, tree)
                        path.write_text(json.dumps(metadata))


class TypedSetupTest(unittest.TestCase):
    def setUp(self):
        self.protocol = {'profiles': ['BASE', 'SYMPY_CANCEL'], 'workBudget': 10, 'sympyVersion': '1.14.0'}
        self.models = {'BASE.model.json'}
        model_hash = 'sha256:' + 'a' * 64
        self.metadata = {'setup': {
            'BASE': {'ready': {'status': 'READY'}, 'training': {'modelHash': model_hash}},
            'SYMPY_CANCEL': {'ready': {'status': 'READY', 'sympy': '1.14.0'}}},
            'warmups': [
                {'profile': 'BASE', 'response': {'status': 'CANDIDATE', 'profile': 'BASE', 'modelHash': model_hash,
                 'target': '', 'targetReached': False, 'search': '{"reached":false,"totalWork":4}', 'selectionWork': 0,
                 'selectedReplayWork': 0, 'totalWork': 4, 'internalWorkWithinBudget': True}},
                {'profile': 'SYMPY_CANCEL', 'response': {'status': 'CANDIDATE', 'profile': 'SYMPY_CANCEL'}}]}

    def verify(self):
        require_typed_setup(self.protocol, self.metadata, self.models)

    def test_model_count_alone_cannot_cover_missing_profiles_or_warmups(self):
        self.verify()
        self.models = {'other.model.json'}
        with self.assertRaisesRegex(ValueError, 'model'):
            self.verify()
        self.models = {'BASE.model.json'}
        self.metadata['warmups'][1] = self.metadata['warmups'][0]
        with self.assertRaisesRegex(ValueError, 'warmup'):
            self.verify()
        del self.metadata['setup']['BASE']
        with self.assertRaisesRegex(ValueError, 'setup'):
            self.verify()

    def test_sympy_version_and_worker_readiness_are_bound_to_each_profile(self):
        self.metadata['setup']['SYMPY_CANCEL']['ready']['sympy'] = 'wrong-version'
        with self.assertRaisesRegex(ValueError, 'version'):
            self.verify()
        self.metadata['setup']['SYMPY_CANCEL']['ready']['sympy'] = '1.14.0'
        self.metadata['setup']['BASE']['ready']['status'] = 'NOT_READY'
        with self.assertRaisesRegex(ValueError, 'ready'):
            self.verify()

    def test_warmup_uses_the_trained_model_and_registered_work_budget(self):
        response = self.metadata['warmups'][0]['response']
        original_hash = response['modelHash']
        response['modelHash'] = 'sha256:' + '0' * 64
        with self.assertRaisesRegex(ValueError, 'model'):
            self.verify()
        response['modelHash'] = original_hash
        response['internalWorkWithinBudget'] = False
        with self.assertRaisesRegex(ValueError, 'overrun'):
            self.verify()
        response['status'] = 'ERROR'
        with self.assertRaisesRegex(ValueError, 'warmup'):
            self.verify()


class RegisteredOrderTest(unittest.TestCase):
    def setUp(self):
        self.protocol = {'profiles': ['A', 'B'], 'cases': [{'id': 'x'}, {'id': 'y'}], 'repetitions': 2}

    @staticmethod
    def rows(order):
        return [dict(repetition=rep, case=case, profile=profile) for rep, case, profile in order]

    def test_typed_order_and_producer_acceptance_apply_to_the_whole_matrix(self):
        rows = self.rows([(0, 'x', 'A'), (0, 'x', 'B'), (0, 'y', 'B'), (0, 'y', 'A'),
                          (1, 'x', 'B'), (1, 'x', 'A'), (1, 'y', 'A'), (1, 'y', 'B')])
        summary = {profile: {'errors': 0, 'invalid': 0} for profile in self.protocol['profiles']}
        require_typed_outcomes(self.protocol, rows, summary)
        for field in ('errors', 'invalid'):
            summary['B'][field] = 1
            with self.assertRaisesRegex(ValueError, 'errors or invalid'):
                require_typed_outcomes(self.protocol, rows, summary)
            summary['B'][field] = 0
        rows[4], rows[5] = rows[5], rows[4]
        with self.assertRaisesRegex(ValueError, 'row order'):
            require_typed_outcomes(self.protocol, rows, summary)

    def test_native_sequence_and_case_order_are_independently_checked(self):
        rows = self.rows([(0, 'x', 'A'), (0, 'y', 'A'), (0, 'x', 'B'), (0, 'y', 'B'),
                          (1, 'x', 'B'), (1, 'y', 'B'), (1, 'x', 'A'), (1, 'y', 'A')])
        sequences = [dict(repetition=rep, profile=profile) for rep, profile in
                     [(0, 'A'), (0, 'B'), (1, 'B'), (1, 'A')]]
        require_native_order(self.protocol, rows, sequences)
        with self.assertRaisesRegex(ValueError, 'sequence order'):
            require_native_order(self.protocol, rows, sequences[::-1])
        rows[0], rows[1] = rows[1], rows[0]
        with self.assertRaisesRegex(ValueError, 'row order'):
            require_native_order(self.protocol, rows, sequences)
