"""Contract tests, not execution or tuning of the external development corpus."""
import hashlib
import json
from pathlib import Path
import tempfile
import sys
import unittest
from external_polynomial_comparison import run_typed as typed

ROOT = Path(__file__).resolve().parents[2]

class TypedRunnerTest(unittest.TestCase):
    def protocol(self):
        return typed.load_protocol(ROOT / 'config/benchmarks/typed-external-polynomial-v1.json')

    def setup(self, profile='BASE'):
        model = json.dumps({'profile': profile})
        return {'status': 'INITIALIZED', 'profile': profile, 'model': model,
                'modelHash': 'sha256:' + hashlib.sha256(model.encode()).hexdigest(),
                'settings': {k: self.protocol()[k] for k in typed.SETTING_KEYS},
                'trainingWork': 0, 'trainingWorkComponents': {'formation': 0, 'historySearch': 0, 'historyMemory': 0, 'policyTrials': 0},
                'learnedPrograms': 0}

    def candidate(self):
        return {'status': 'CANDIDATE', 'profile': 'BASE', 'target': '', 'targetReached': False,
                'modelHash': self.setup()['modelHash'], 'selectionWork': 3, 'selectedReplayWork': 2,
                'totalWork': 12, 'internalWorkWithinBudget': True,
                'search': json.dumps({'reached': False, 'totalWork': 7})}

    def test_protocol_bytes_are_frozen(self):
        self.assertEqual(9, len(self.protocol()['profiles']))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'protocol.json'
            path.write_text(json.dumps(self.protocol()))
            with self.assertRaises(ValueError): typed.load_protocol(path)

    def test_setup_accepts_bound_model_and_rejects_hidden_training_cost(self):
        setup = self.setup()
        self.assertEqual(setup['modelHash'], typed.check_setup(self.protocol(), 'BASE', setup))
        for field, value in [('trainingWork', 1), ('modelHash', 'wrong'), ('profile', 'EXPERT')]:
            bad = dict(setup, **{field: value})
            with self.assertRaises(ValueError): typed.check_setup(self.protocol(), 'BASE', bad)
        bad = dict(setup, trainingWork=1, trainingWorkComponents=dict(setup['trainingWorkComponents'], formation=1))
        with self.assertRaises(ValueError): typed.check_setup(self.protocol(), 'BASE', bad)

    def test_java_prefixed_digest_contract_and_model_byte_integrity(self):
        setup = self.setup()
        setup['modelHash'] = 'sha256:' + hashlib.sha256(setup['model'].encode('utf-8')).hexdigest()
        self.assertEqual(setup['modelHash'], typed.check_setup(self.protocol(), 'BASE', setup))
        with self.assertRaises(ValueError):
            typed.check_setup(self.protocol(), 'BASE', dict(setup, model=setup['model'] + ' '))
        with self.assertRaises(ValueError):
            typed.check_setup(self.protocol(), 'BASE', dict(setup, modelHash=setup['modelHash'][7:]))

    def test_query_binding_rejects_targets_profile_switch_and_unbilled_replay(self):
        candidate = self.candidate()
        typed.check_response('BASE', candidate, self.setup()['modelHash'], 20)
        with self.assertRaises(ValueError):
            typed.check_response('BASE', dict(candidate, modelHash=None), None, 20)
        for key, value in [('target', 'x'), ('targetReached', True), ('modelHash', 'wrong'),
                           ('profile', 'EXPERT'), ('totalWork', 7), ('selectionWork', -1)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                typed.check_response('BASE', dict(candidate, **{key: value}), self.setup()['modelHash'], 20)

    def test_overruns_stay_diagnostics_but_cannot_claim_budget_success(self):
        response = dict(self.candidate(), totalWork=22, selectionWork=13, internalWorkWithinBudget=False)
        typed.check_response('BASE', response, self.setup()['modelHash'], 20)
        with self.assertRaises(ValueError):
            typed.check_response('BASE', dict(response, internalWorkWithinBudget=True), self.setup()['modelHash'], 20)

    def test_lifecycle_pays_startup_training_warmup_and_failed_queries(self):
        protocol = dict(self.protocol(), profiles=['BASE'])
        setup = self.setup()
        metadata = {'setup': {'BASE': {'startupNanos': 10, 'trainingRequestNanos': 20, 'training': setup}},
                    'warmups': [{'profile': 'BASE', 'requestWallNanos': 30}]}
        rows = [{'profile': 'BASE', 'response': {'status': 'ERROR'},
                 'judgment': {'endToEndNanos': 50, 'valid': False}}]
        paid = typed.lifecycle(protocol, metadata, rows)['BASE']
        self.assertEqual(110, paid['lifecycleWallNanos'])
        self.assertEqual(0, paid['eligibleQueries'])
        self.assertEqual(1, paid['attemptedQueries'])
        self.assertFalse(paid['completeNativeCpu'])

    def test_real_worker_cpu_is_retained_on_timeout_and_close_is_idempotent(self):
        self.assertTrue(hasattr(typed, 'MeasuredSession'), 'whole-worker CPU is not measured')
        with tempfile.TemporaryDirectory() as directory:
            command = [sys.executable, '-u', '-c', 'import sys; print(\'{"status":"READY"}\'); sys.stdin.readline();\nwhile True: sum(i*i for i in range(1000))']
            worker = typed.MeasuredSession(command, Path(directory) / 'worker.stderr', 5)
            response, elapsed = worker.request({'op': 'run'}, .1)
            self.assertEqual('TIMEOUT', response['status'])
            self.assertGreater(worker.process_cpu['totalNanos'], 0)
            self.assertEqual(worker.process_cpu['totalNanos'], worker.process_cpu['userNanos'] + worker.process_cpu['systemNanos'])
            retained = dict(worker.process_cpu)
            worker.close()
            self.assertEqual(retained, worker.process_cpu)
            self.assertEqual('BLOCKED_WORKER_UNAVAILABLE', worker.request({}, 1)[0]['status'])

if __name__ == '__main__': unittest.main()
