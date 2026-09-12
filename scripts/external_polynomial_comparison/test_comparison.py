"""Independent controls; never execute the registered evaluation corpus in tests."""
import copy
import importlib
import json
import os
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch

try:
    from external_polynomial_comparison import polynomial as p
except ImportError:
    p = None

class PolynomialContractTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(p, 'The common exact polynomial judge is not implemented')

    def test_exact_identity_and_non_identity(self):
        self.assertEqual(p.polynomial('(x+y)*(x-y)+y*y'), p.polynomial('x^2'))
        self.assertNotEqual(p.polynomial('(x+y)*(x-y)+y*(y+1)'), p.polynomial('x^2'))

    def test_rationals_are_exact(self):
        self.assertEqual(p.polynomial('x/3+x/6'), p.polynomial('x/2'))
        self.assertNotEqual(p.polynomial('x/3'), p.polynomial('333*x/1000'))

    def test_undefined_or_unsupported_inputs_are_not_silently_simplified(self):
        for expr in ['x/x', 'x^(-1)', 'ln(x)', '0*(1/0)', 'x^0.5', '__import__("os")', 'True', 'x[0]']:
            with self.subTest(expr=expr), self.assertRaises(p.Unsupported):
                p.polynomial(expr)

    def test_resource_limits_fail_closed(self):
        for expr in ['x^13', 'a+b+c+d+e', '(' * 300 + 'x' + ')' * 300, '9' * 1000]:
            with self.subTest(expr=expr), self.assertRaises(p.Unsupported):
                p.polynomial(expr)

    def test_cost_is_syntax_not_cas_simplification(self):
        self.assertEqual(6, p.operation_cost('(x+y)*(x-y)+y*y+2'))
        self.assertEqual(3, p.operation_cost('x/3+x/6'))
        self.assertEqual(1, p.operation_cost('-5'))
        self.assertEqual(0, p.operation_cost('17'))

    def test_zero_to_zero_and_symbols(self):
        self.assertEqual(p.polynomial('x^0'), p.polynomial('1'))
        self.assertEqual(p.polynomial("0^0"), p.polynomial("1"))


class NativeBaselineTest(unittest.TestCase):
    def worker(self):
        try:
            return importlib.import_module('external_polynomial_comparison.native_worker')
        except ImportError:
            self.fail('The pinned native SymPy worker is not implemented')

    def test_targeted_factor_is_available(self):
        result = self.worker().evaluate('SYMPY_FACTOR', 'x^2-4')
        self.assertEqual('CANDIDATE', result['status'])
        self.assertEqual(p.polynomial('x^2-4'), p.polynomial(result['output']))

    def test_portfolio_retains_every_attempt_and_uses_common_cost(self):
        result = self.worker().evaluate('SYMPY_PORTFOLIO', '(x+y)*(x-y)+y*y+13')
        self.assertEqual(['identity', 'simplify', 'factor', 'cancel', 'horner'], [v['operation'] for v in result['candidates']])
        self.assertEqual(min(v['cost'] for v in result['candidates']), p.operation_cost(result['output']))
        self.assertGreater(result['nativeWallNanos'], 0)
        self.assertGreaterEqual(result['nativeCpuNanos'], 0)

    def test_unknown_profile_and_hidden_target_are_rejected(self):
        worker = self.worker()
        self.assertRaises(ValueError, worker.evaluate, 'UNKNOWN', 'x+1')
        self.assertRaises(ValueError, worker.handle, {'op': 'run', 'profile': 'SYMPY_FACTOR', 'source': 'x', 'target': 'x'})
        self.assertRaises(p.Unsupported, worker.evaluate, 'SYMPY_CANCEL', 'x/x')

class SupervisorContractTest(unittest.TestCase):
    def runner(self):
        try:
            return importlib.import_module('external_polynomial_comparison.run')
        except ImportError:
            self.fail('The complete-matrix comparison supervisor is not implemented')

    def test_protocol_is_bound_to_the_pre_execution_commit(self):
        runner = self.runner()
        path = pathlib.Path('config/benchmarks/external-polynomial-v1.json')
        protocol = runner.load_protocol(path)
        self.assertEqual(27, len(protocol['cases']))
        with tempfile.TemporaryDirectory() as folder:
            other = pathlib.Path(folder, 'protocol.json')
            changed = copy.deepcopy(protocol)
            changed['queryTimeoutSeconds'] = 3
            other.write_text(json.dumps(changed), encoding='utf-8')
            self.assertRaises(ValueError, runner.load_protocol, other)

    def test_incorrect_worse_unchanged_and_timed_out_rows_remain_distinct(self):
        runner = self.runner()
        wrong = runner.judge('x+x', {'status':'CANDIDATE','output':'x'}, 10, 10**9)
        self.assertEqual('INVALID_IDENTITY', wrong['verdict'])
        worse = runner.judge('x', {'status':'CANDIDATE','output':'x+0'}, 10, 10**9)
        self.assertTrue(worse['valid'])
        self.assertFalse(worse['improved'])
        self.assertEqual(-1, worse['savedOperations'])
        unchanged = runner.judge('x', {'status':'CANDIDATE','output':'x'}, 10, 10**9)
        self.assertEqual('UNCHANGED', unchanged['verdict'])
        timeout = runner.judge('x', {'status':'TIMEOUT'}, 10, 10**9)
        self.assertFalse(timeout['valid'])
        self.assertEqual('TIMEOUT', timeout['verdict'])

    def test_common_verification_is_inside_total_request_eligibility(self):
        result = self.runner().judge('x+x', {'status':'CANDIDATE','output':'2*x'}, 10**9+1, 10**9)
        self.assertEqual('DEADLINE_EXCEEDED', result['verdict'])
        self.assertFalse(result['valid'])

    def test_internal_work_overrun_is_diagnostic_and_never_an_eligible_success(self):
        runner = self.runner()
        protocol = {'cases':[{'id':'fixture'}], 'profiles':['LEARNED_RANKED','BASE'], 'repetitions':1}
        rows = []
        for profile, within_budget in [('LEARNED_RANKED', False), ('BASE', True)]:
            response = {'status':'CANDIDATE','profile':profile,'output':'x',
                        'internalWorkWithinBudget':within_budget}
            rows.append({'case':'fixture','profile':profile,'repetition':0,'source':'x+0',
                         'response':response,'requestWallNanos':10,
                         'judgment':runner.judge('x+0', response, 10, 10**9)})
        overrun = rows[0]
        self.assertEqual('INTERNAL_WORK_BUDGET_EXCEEDED', overrun['judgment']['verdict'])
        self.assertFalse(overrun['judgment']['valid'])
        self.assertFalse(overrun['judgment']['improved'])
        self.assertFalse(overrun['judgment']['substantialImprovement'])
        self.assertEqual('x', overrun['response']['output'])
        summary = runner.summarize(protocol, rows)['LEARNED_RANKED']
        self.assertEqual(1, summary['internalWorkOverruns'])
        self.assertEqual(0, summary['valid'])
        self.assertEqual(0, summary['improved'])
        self.assertIsNone(summary['medianValidEndToEndNanos'])
        self.assertEqual({'COMPARATOR_ONLY':1}, runner.paired_comparisons(protocol, rows)['BASE']['counts'])
        runner.verify_row(overrun, 10**9)
        forged = copy.deepcopy(overrun)
        forged['judgment'].update(valid=True, improved=True, substantialImprovement=True, verdict='IMPROVED')
        self.assertRaises(ValueError, runner.verify_row, forged, 10**9)

    def test_duplicate_or_missing_matrix_rows_cannot_verify(self):
        runner = self.runner()
        protocol = {'cases':[{'id':'a'}], 'profiles':['BASE'], 'repetitions':2}
        rows = [{'case':'a','profile':'BASE','repetition':0}, {'case':'a','profile':'BASE','repetition':1}]
        runner.require_matrix(protocol, rows)
        self.assertRaises(ValueError, runner.require_matrix, protocol, rows[:1])
        self.assertRaises(ValueError, runner.require_matrix, protocol, [rows[0], rows[0]])

    def test_process_timeout_kills_worker_and_does_not_invent_empty_success(self):
        import sys
        runner = self.runner()
        with tempfile.TemporaryDirectory() as folder:
            worker = runner.Session([sys.executable, '-u', '-c',
                'import time;print(\'{"status":"READY"}\',flush=True);input();time.sleep(30)'], pathlib.Path(folder)/'stderr.txt', 2)
            result, elapsed = worker.request({'op':'run'}, .05)
            self.assertEqual('TIMEOUT', result['status'])
            self.assertIsNotNone(worker.process.poll())
            self.assertEqual('BLOCKED_WORKER_UNAVAILABLE', worker.request({'op':'run'}, .05)[0]['status'])
            worker.close()

    def test_worker_exit_before_request_retains_error_and_closes_streams(self):
        runner = self.runner()
        with tempfile.TemporaryDirectory() as folder:
            worker = runner.Session([sys.executable, '-u', '-c',
                'print(\'{"status":"READY"}\',flush=True)'], pathlib.Path(folder)/'stderr.txt', 2)
            worker.process.wait(timeout=2)
            try:
                response, elapsed = worker.request({'op':'run'}, 2)
                self.assertEqual('ERROR', response['status'])
                self.assertTrue(response['error'])
                self.assertGreater(elapsed, 0)
                self.assertEqual('BLOCKED_WORKER_UNAVAILABLE', worker.request({'op':'run'}, 2)[0]['status'])
                for stream in (worker.process.stdin, worker.process.stdout, worker.stderr):
                    self.assertTrue(stream.closed)
            finally:
                worker.close()

    @unittest.skipUnless(os.name == 'posix', 'Process-group race is POSIX-specific')
    def test_worker_exit_during_cleanup_preserves_timeout_and_error_rows(self):
        runner = self.runner()
        killpg = os.killpg
        for status, behavior in [('TIMEOUT', 'time.sleep(30)'),
                                 ('ERROR', 'print("malformed-json",flush=True);time.sleep(30)')]:
            with self.subTest(status=status), tempfile.TemporaryDirectory() as folder:
                worker = runner.Session([sys.executable, '-u', '-c',
                    'import time;print(\'{"status":"READY"}\',flush=True);input();'+behavior],
                    pathlib.Path(folder)/'stderr.txt', 2)
                def exit_before_signal(pid, sig):
                    # Reap the real process between poll() and the actual signal,
                    # deterministically exposing the otherwise intermittent race.
                    killpg(pid, sig)
                    worker.process.wait()
                    killpg(pid, sig)
                try:
                    with patch.object(runner.os, 'killpg', side_effect=exit_before_signal):
                        response, elapsed = worker.request({'op':'run'}, .05 if status == 'TIMEOUT' else 2)
                    self.assertEqual(status, response['status'])
                    self.assertGreater(elapsed, 0)
                    self.assertEqual('BLOCKED_WORKER_UNAVAILABLE', worker.request({'op':'run'}, .05)[0]['status'])
                    self.assertTrue(worker.stderr.closed)
                    self.assertTrue(worker.process.stdout.closed)
                finally:
                    worker.close()

class EvidenceIntegrityTest(unittest.TestCase):
    def test_paired_comparison_keeps_losses_failures_and_unsupported(self):
        from external_polynomial_comparison import run
        self.assertTrue(hasattr(run, 'paired_comparisons'), 'Paired comparison is not implemented')
        protocol = {'cases': [{'id': str(i)} for i in range(5)], 'profiles': ['LEARNED_RANKED', 'BASE'], 'repetitions': 1}
        rows = []
        for index, (a, b) in enumerate([(1, 2), (3, 2), (2, 2), (None, 2), (None, None)]):
            for profile, cost in [('LEARNED_RANKED', a), ('BASE', b)]:
                rows.append({'case': str(index), 'profile': profile, 'repetition': 0,
                    'response': {'status': 'SHARED_FRAGMENT_UNSUPPORTED' if index == 4 else 'CANDIDATE' if cost is not None else 'TIMEOUT'},
                    'judgment': {'valid': cost is not None, 'outputCost': cost,
                                 'endToEndNanos': 10 if profile == 'LEARNED_RANKED' else 20}})
        result = run.paired_comparisons(protocol, rows)['BASE']
        self.assertEqual(5, len(result['pairs']))
        self.assertEqual({'BETTER': 1, 'WORSE': 1, 'TIE': 1, 'COMPARATOR_ONLY': 1, 'SHARED_UNSUPPORTED': 1}, result['counts'])
        self.assertEqual([0.5], result.get('equalQualityTimeRatios'))
        self.assertEqual(0.5, result['medianEqualQualityTimeRatio'])
        self.assertEqual(0.5, result['pairs'][2]['equalQualityTimeRatio'])

    def test_forged_source_cost_and_unsupported_outcome_are_rejected(self):
        from external_polynomial_comparison import run
        result = run.judge('x+x', {'status':'CANDIDATE','output':'2*x','inputCost':999}, 0, 10**9)
        self.assertEqual('COST_CONTRACT_MISMATCH', result['verdict'])
        result = run.judge('x', {'status':'SHARED_FRAGMENT_UNSUPPORTED'}, 0, 10**9)
        self.assertEqual('COVERAGE_CONTRACT_MISMATCH', result['verdict'])

    def test_retained_time_sum_cannot_be_rehashed_into_success(self):
        from external_polynomial_comparison import run
        self.assertTrue(hasattr(run, 'verify_row'), 'Retained row verifier is not implemented')
        response = {'status':'CANDIDATE','profile':'BASE','output':'x','inputCost':1,'outputCost':0}
        row = {'source':'x+0','profile':'BASE','response':response,'requestWallNanos':123,
               'judgment':run.judge('x+0', response, 123, 10**9)}
        run.verify_row(row, 10**9)
        row['judgment']['endToEndNanos'] = 0
        self.assertRaises(ValueError, run.verify_row, row, 10**9)

    def test_retained_candidate_is_bound_to_the_requested_profile(self):
        from external_polynomial_comparison import run
        response = {'status':'CANDIDATE','profile':'BASE','output':'x','inputCost':1,'outputCost':0}
        row = {'source':'x+0','profile':'BASE','response':response,'requestWallNanos':123,
               'judgment':run.judge('x+0', response, 123, 10**9)}
        run.verify_row(row, 10**9)
        for replacement in ('LEARNED_RANKED', None):
            with self.subTest(profile=replacement):
                forged = copy.deepcopy(row)
                if replacement is None:
                    del forged['response']['profile']
                else:
                    forged['response']['profile'] = replacement
                with self.assertRaisesRegex(ValueError, 'profile mismatch'):
                    run.verify_row(forged, 10**9)

if __name__ == '__main__':
    unittest.main()
