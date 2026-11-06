import copy
import hashlib
import json
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from external_polynomial_comparison.historical_judge import audit_task
from external_polynomial_comparison import historical_sympy_worker as competitor


RECURRENCE = dict(kind='INVARIANT', recurrence=dict(linear=[['0', '1'], ['1', '1']],
    offset=['0', '0'], initial=['0', '1'], degree=2, workBudget=100000))


class HistoricalJudgeTest(unittest.TestCase):
    def test_recurrence_checks_universal_step_base_and_source_binding(self):
        law = dict(polynomial='x0^2+x0*x1-x1^2', evolution='ALTERNATING', initialValue='-1')
        response = dict(status='RESULT', task=RECURRENCE, solved=True, withinBudget=True, law=law)
        self.assertTrue(audit_task(RECURRENCE, response)['solved'])
        for changed in [dict(law, initialValue='1'), dict(law, evolution='FIXED'),
                        dict(law, polynomial='0'), dict(law, polynomial='x0^2')]:
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                audit_task(RECURRENCE, dict(response, law=changed))
        changed = copy.deepcopy(RECURRENCE)
        changed['recurrence']['linear'][1][1] = '2'
        with self.assertRaises(ValueError):
            audit_task(changed, response)

    def test_identity_is_insufficient_without_the_requested_output_shape(self):
        task = dict(kind='TWO_SQUARES', source='(a^2+b^2)*(c^2+d^2)')
        good = '(a*c+b*d)^2+(b*c-a*d)^2'
        self.assertTrue(audit_task(task, dict(status='RESULT', task=task, solved=True, withinBudget=True, output=good))['solved'])
        for output in [task['source'], good+'+1', '0^2+('+task['source']+')^2']:
            with self.subTest(output=output), self.assertRaises(ValueError):
                audit_task(task, dict(status='RESULT', task=task, solved=True, withinBudget=True, output=output))

    def test_constant_factors_and_out_of_domain_sources_do_not_become_success(self):
        task = dict(kind='FACTOR', source='x^4+4*y^4')
        for output in ['1*(x^4+4*y^4)', '(x^2-2*x*y+2*y^2)*(x^2+2*x*y+2*y^2)+0']:
            with self.subTest(output=output), self.assertRaises(ValueError):
                audit_task(task, dict(status='RESULT', task=task, solved=True, withinBudget=True, output=output))
        bad = dict(kind='FACTOR', source='1/x')
        with self.assertRaises(ValueError):
            audit_task(bad, dict(status='RESULT', task=bad, solved=True, withinBudget=True, output='1/x'))

    def test_budget_failure_and_inconclusive_are_not_solved_or_false_proofs(self):
        task = dict(kind='FACTOR', source='x^4+4*y^4+1')
        result = audit_task(task, dict(status='RESULT', task=task, solved=False, withinBudget=True, outcome='INCONCLUSIVE'))
        self.assertFalse(result['solved'])
        with self.assertRaises(ValueError):
            audit_task(task, dict(status='RESULT', task=task, solved=True, withinBudget=False, output='(x+1)*(x-1)'))

    def test_native_selected_path_cannot_be_rebound_or_disconnected(self):
        task = dict(kind='FACTOR', source='x^2-y^2')
        output = '(x-y)*(x+y)'
        response = dict(status='RESULT', task=task, solved=True, withinBudget=True, output=output,
                        normalizedInput=task['source'], witness=[dict(source=task['source'], target=output)])
        self.assertTrue(audit_task(task, response)['solved'])
        changed = copy.deepcopy(response); changed['witness'][0]['source'] = 'u^2-v^2'
        with self.assertRaises(ValueError): audit_task(task, changed)
        changed = copy.deepcopy(response); changed['witness'] = []
        with self.assertRaises(ValueError): audit_task(task, changed)


class HistoricalSympyTest(unittest.TestCase):
    def test_general_procedures_form_all_three_kinds_without_targets(self):
        tasks = [RECURRENCE, dict(kind='FACTOR', source='(u+1)^4+4*v^4'),
                 dict(kind='TWO_SQUARES', source='((a+1)^2+b^2)*(c^2+d^2)')]
        for task in tasks:
            with self.subTest(task=task):
                response = competitor.evaluate(task)
                self.assertTrue(audit_task(task, response)['solved'])

    def test_two_square_formation_handles_units_and_rational_scalars(self):
        for source in ['((2*a)^2+(3*b)^2)*(c^2+d^2)', '((a/3)^2+b^2)*(c^2+d^2)']:
            task = dict(kind='TWO_SQUARES', source=source)
            with self.subTest(source=source):
                self.assertTrue(audit_task(task, competitor.evaluate(task))['solved'])

    def test_missing_law_and_indefinite_form_remain_inconclusive(self):
        task = copy.deepcopy(RECURRENCE)
        task['recurrence'].update(linear=[['2']], offset=['0'], initial=['1'])
        for value in [task, dict(kind='TWO_SQUARES', source='(a^2+b^2)*(c^2-d^2)')]:
            with self.subTest(task=value):
                self.assertFalse(audit_task(value, competitor.evaluate(value))['solved'])


class HistoricalRunnerTest(unittest.TestCase):
    def setUp(self):
        from external_polynomial_comparison import run_historical
        self.runner = run_historical

    def fixture(self):
        from external_polynomial_comparison.run import canonical
        from external_polynomial_comparison.run_schema import CPU_SCOPE
        prelude = dict(expressionBlocks=[], recurrenceBlocks=[], recurrenceFamilyValidation=[])
        p = dict(cases=[dict(id='first', role='CLASSICAL', task=RECURRENCE),
                        dict(id='second', role='PUBLIC_VARIANT', task=RECURRENCE)],
                 profiles=['SYMPY'], repetitions=1, lengths=[2], prelude=prelude,
                 sympyVersion='1.14.0', queryTimeoutSeconds=30, setupTimeoutSeconds=.08)
        response = dict(status='RESULT', task=RECURRENCE, profile='SYMPY', generationId='STATELESS',
                        withinBudget=True, solved=True,
                        law=dict(polynomial='x0^2+x0*x1-x1^2', evolution='ALTERNATING', initialValue='-1'))
        rows = [dict(index=i, case=c['id'], task=c['task'], response=copy.deepcopy(response),
                     judgment=audit_task(c['task'], response), requestWallNanos=1, auditWallNanos=1)
                for i, c in enumerate(p['cases'])]
        initial = dict(status='INITIALIZED', profile='SYMPY', generationId='STATELESS',
                       preludeHash='sha256:'+hashlib.sha256(canonical(prelude)).hexdigest())
        operations = [dict(name='startup-query', status='COMPLETE', wallNanos=1, pid=123,
                           ready=dict(status='READY', sympy='1.14.0')),
                      dict(name='initialize', status='COMPLETE', wallNanos=1, request=dict(
                          op='initialize', profile='SYMPY', prelude=prelude, directory='/fixture'), response=initial),
                      dict(name='shutdown-query', status='COMPLETE', wallNanos=1)]
        receipt = dict(stage='query', pid=123, exitCode=0, termination='GRACEFUL_EOF', receiptError=None,
            peakRssBytes=1024, rssScope='WAIT4_PROCESS_PEAK_RESIDENT_SET;MAX_ACROSS_SEQUENTIAL_WORKERS',
            cpu=dict(userNanos=2, systemNanos=1, totalNanos=3, scope=CPU_SCOPE))
        seq = dict(profile='SYMPY', length=2, repetition=0, operations=operations, processes=[receipt],
                   errors=[], rows=rows, wallNanos=20, controllerCpuNanos=1)
        return p, seq

    def test_frozen_protocol_has_complete_rotated_experiment_matrix(self):
        path = Path(__file__).resolve().parents[2]/'config/benchmarks/historical-sympy-v1.json'
        p = self.runner.load_protocol(path)
        schedule = list(self.runner.schedule(p))
        self.assertEqual(27, len(set(schedule)))
        self.assertEqual(819, sum(length for _, length, _ in schedule))
        with tempfile.TemporaryDirectory() as directory:
            changed = Path(directory)/'changed.json'; changed.write_bytes(path.read_bytes()+b' ')
            with self.assertRaises(ValueError): self.runner.load_protocol(changed)

    def test_case_relabeling_and_missing_rows_are_rejected(self):
        p, seq = self.fixture()
        self.runner.validate_rows(p, seq)
        seq['rows'][0]['case'], seq['rows'][1]['case'] = seq['rows'][1]['case'], seq['rows'][0]['case']
        with self.assertRaises(ValueError): self.runner.validate_rows(p, seq)
        seq['rows'].pop()
        with self.assertRaises(ValueError): self.runner.validate_rows(p, seq)

    def test_complete_lifecycle_cannot_lose_receipts_or_corrupt_measurements(self):
        p, seq = self.fixture()
        self.assertTrue(self.runner.account_sequence(p, seq)['complete'])
        mutations = [lambda q:q.update(processes=[]), lambda q:q.update(wallNanos=-1),
            lambda q:q['processes'][0]['cpu'].update(totalNanos=1),
            lambda q:q['operations'][0]['ready'].update(sympy='WRONG'),
            lambda q:q['processes'].append(q['processes'][0]),
            lambda q:q['processes'][0].update(stage='producer')]
        for mutate in mutations:
            changed = copy.deepcopy(seq); mutate(changed)
            with self.subTest(mutation=mutations.index(mutate)), self.assertRaises(ValueError):
                self.runner.account_sequence(p, changed)

    def test_ready_failure_keeps_owned_process_costs_and_all_task_rows(self):
        p, _ = self.fixture()
        with tempfile.TemporaryDirectory() as directory:
            command = [sys.executable, '-u', '-c', 'import time;time.sleep(10)']
            seq = self.runner.run_sequence(p, 'NATIVE_BASE', 2, 0, command, Path(directory))
            self.assertEqual(2, len(seq['rows']))
            self.assertEqual(1, len(seq['processes']))
            self.assertIsNotNone(seq['processes'][0]['cpu'])
            self.assertNotEqual(0, seq['processes'][0]['exitCode'])
            self.assertFalse(seq['accounting']['complete'])

    def test_unavailable_resources_and_unequal_quality_withhold_performance_ratio(self):
        p, seq = self.fixture()
        seq['accounting'] = self.runner.account_sequence(p, seq)
        same = copy.deepcopy(seq)
        self.assertTrue(self.runner.pair(seq, same)['qualityMatched'])
        same['rows'][1]['judgment'].update(solved=False)
        self.assertIsNone(self.runner.pair(seq, same)['wallRatioNativeToSympy'])
        seq['processes'][0].update(cpu=None, peakRssBytes=None, receiptError='wait4 unavailable')
        seq['accounting'] = self.runner.account_sequence(p, seq)
        self.assertFalse(seq['accounting']['complete'])
        self.assertIsNone(self.runner.pair(seq, seq)['wallRatioNativeToSympy'])

    def test_metadata_cannot_claim_a_foreign_runtime(self):
        metadata = dict(protocolHash=self.runner.PROTOCOL_HASH, pythonRuntimeHash='python', runtimeBytesHash='java')
        self.runner.verify_metadata(metadata, {'runtimeBytesHash':'python'}, {'runtimeBytesHash':'java'})
        with self.assertRaises(ValueError):
            self.runner.verify_metadata(dict(metadata, runtimeBytesHash='other'), {'runtimeBytesHash':'python'}, {'runtimeBytesHash':'java'})

    def test_retained_python_runtime_has_readable_bytecode_and_stays_immutable(self):
        from external_polynomial_comparison.run_schema import _file_inventory
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            receipt = self.runner.snapshot_python(output)
            self.assertTrue(any(name.endswith('.pyc') for name in receipt['files']),
                            'normal installed bytecode caches must not be denied to the CAS')
            code = 'import sys;sys.path.insert(0,'+repr(str(output/'python-runtime'))+');import sympy;print(sympy.__version__)'
            subprocess.run([sys.executable,'-I','-B','-c',code], check=True, capture_output=True, timeout=30)
            self.assertEqual(receipt['files'], _file_inventory(output/'python-runtime'))

    def test_consumer_ready_failure_retains_both_distinct_process_receipts(self):
        # Deliberately fake setup bytes test OS ownership, never mathematical/performance evidence.
        from external_polynomial_comparison.run import canonical
        p, _ = self.fixture(); p.update(setupTimeoutSeconds=.3, nativeStructuralBudget=[24,12,1000000,128,2000000])
        model_hash = 'sha256:'+hashlib.sha256(b'{}').hexdigest()
        saved = dict(status='SAVED', profile='NATIVE_LEARNED', generationId=model_hash, snapshotHash=model_hash,
            preludeHash='sha256:'+hashlib.sha256(canonical(p['prelude'])).hexdigest(), operations=[],
            structuralBudget=p['nativeStructuralBudget'], acquisitionApiWork=7, restorationApiWork=0,
            schemaCount=1, recurrenceCount=0, recurrenceFamilyCount=0)
        with tempfile.TemporaryDirectory() as directory:
            marker = str(Path(directory)/'producer-started')
            script = ('import json,sys,time,pathlib\n'
                f'marker=pathlib.Path({marker!r})\n'
                'if marker.exists(): time.sleep(10)\n'
                'marker.write_text("started")\n'
                'print(\'{"status":"READY"}\',flush=True)\n'
                'request=json.loads(sys.stdin.readline())\n'
                'snapshot=pathlib.Path(request["directory"])/"model.json"\n'
                'snapshot.write_text("{}")\n'
                f'response={saved!r}\n'
                'response["snapshot"]=str(snapshot)\n'
                'print(json.dumps(response),flush=True)\n'
                'for line in sys.stdin: pass\n')
            seq = self.runner.run_sequence(p, 'NATIVE_LEARNED', 2, 0,
                [sys.executable,'-u','-c',script], Path(directory))
            self.assertEqual(2, len(seq['processes']))
            self.assertEqual(['producer','query'], [r['stage'] for r in seq['processes']])
            self.assertEqual(2, len({r['pid'] for r in seq['processes']}))
            self.assertTrue(all(r['cpu'] is not None for r in seq['processes']))
            self.assertFalse(seq['accounting']['complete'])


if __name__ == '__main__':
    unittest.main()
