"""Accounting and independent selected-path checks for the registered v2 run."""
from copy import deepcopy
import hashlib
import importlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


def typed(node):
    return json.dumps({'schema': 'regelsuche.typed-move-expression/v1', 'expression': node}, separators=(',', ':'))


def variable(name):
    return {'type': 'variable', 'name': name}


def binary(operator, left, right):
    return {'type': 'binary', 'operator': operator, 'left': left, 'right': right}


def candidate():
    a, b = variable('a'), variable('b')
    source = typed(binary('ADD', binary('MUL', binary('ADD', a, b), binary('SUB', a, b)), binary('MUL', b, b)))
    target = typed(binary('MUL', a, a))
    move = {'sourceKind': 'LEARNED', 'assumptions': [], 'primitiveExpansion': [],
            'transformation': {'assumptions': [], 'primitiveRuleIds': ['checked-schema:test'], 'transformedExpression': target}}
    receipt = {'accepted': True, 'work': 2, 'receipts': ['checked-schema:test'], 'reason': 'CHECKED_SCHEMA'}
    start = {'expression': source, 'assumptions': [], 'searchDepth': 0, 'primitiveDepth': 0}
    end = {'expression': target, 'assumptions': [], 'searchDepth': 1, 'primitiveDepth': 0}
    search = {'reached': False, 'metrics': {'primitiveWork': 1, 'searchWork': 1, 'verificationWork': 1},
              'totalWork': 3, 'events': [{'decision': 'ENQUEUED', 'source': start, 'target': end, 'move': move, 'verification': receipt}]}
    return {'status': 'CANDIDATE', 'profile': 'LEARNED_SCHEMA', 'input': '(a+b)*(a-b)+b*b', 'output': 'a*a',
            'typedInput': source, 'typedOutput': target, 'inputCost': 5, 'outputCost': 1,
            'modelHash': setup()['modelHash'], 'target': '', 'targetReached': False, 'search': json.dumps(search),
            'totalWork': 6, 'selectionWork': 1, 'selectedReplayWork': 2, 'internalWorkWithinBudget': True,
            'witness': [{'source': source, 'target': target, 'move': move, 'verification': receipt}],
            'nativeWallNanos': 10, 'nativeCpuNanos': 8, 'nativeAllocatedBytes': 16}


def setup(profile='LEARNED_SCHEMA', restored=False):
    model = json.dumps({'profile': profile, 'schemas': []})
    return {'status': 'INITIALIZED', 'profile': profile, 'model': model,
            'modelHash': 'sha256:' + hashlib.sha256(model.encode()).hexdigest(),
            ('restoreWork' if restored else 'trainingWork'): 0 if profile == 'BASE' else 7,
            'nativeWallNanos': 10, 'nativeCpuNanos': 8, 'nativeAllocatedBytes': 16}


class SchemaDriverTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        try:
            cls.driver = importlib.import_module('external_polynomial_comparison.run_schema')
        except ModuleNotFoundError as error:
            if error.name != 'external_polynomial_comparison.run_schema':
                raise
            cls.driver = None

    def setUp(self):
        self.assertIsNotNone(self.driver, 'the v2 sequence driver is not implemented')

    def test_missing_training_or_restore_work_is_rejected(self):
        for restored, key in [(False, 'trainingWork'), (True, 'restoreWork')]:
            response = setup(restored=restored)
            del response[key]
            with self.subTest(key=key), self.assertRaises((ValueError, KeyError)):
                self.driver.check_setup('LEARNED_SCHEMA', response, restored=restored)
        with self.assertRaises(ValueError):
            self.driver.check_setup('BASE', dict(setup('BASE'), trainingWork=1))

    def test_model_bytes_hash_and_restored_model_must_match(self):
        response = setup()
        self.assertEqual(response['modelHash'], self.driver.check_setup('LEARNED_SCHEMA', response))
        restored = setup(restored=True)
        self.assertEqual(response['modelHash'], self.driver.check_setup('LEARNED_SCHEMA', restored, restored=True, expected_model=response['model']))
        for bad in [dict(response, model=response['model'] + ' '), dict(response, profile='BASE'),
                    dict(response, modelHash=response['modelHash'][7:])]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                self.driver.check_setup('LEARNED_SCHEMA', bad)
        with self.assertRaises(ValueError):
            self.driver.check_setup('LEARNED_SCHEMA', restored, restored=True, expected_model='{}')

    def test_exact_schema_step_needs_no_primitive_expansion_but_has_independent_math(self):
        response = candidate()
        counts = self.driver.audit_response(response['input'], response)
        self.assertEqual({'selectedEdges': 1, 'learnedEdges': 1, 'primitiveEdges': 0}, counts)

    def test_java_receipt_cannot_authorize_a_false_step(self):
        response = candidate()
        step = response['witness'][0]
        step['target'] = typed(variable('b'))
        step['move']['transformation']['transformedExpression'] = step['target']
        search = json.loads(response['search'])
        search['events'][0].update(move=deepcopy(step['move']))
        search['events'][0]['target']['expression'] = step['target']
        response['search'] = json.dumps(search)
        with self.assertRaises(ValueError):
            self.driver.audit_response(response['input'], response)

    def test_unsearched_better_surface_cannot_replace_the_actual_typed_incumbent(self):
        response = candidate()
        response.update(typedOutput=response['typedInput'], witness=[], selectedReplayWork=0, totalWork=1,
            search=json.dumps({'reached': False, 'metrics': {'primitiveWork': 0, 'searchWork': 0, 'verificationWork': 0},
                               'totalWork': 0, 'events': []}))
        # Both printed polynomials are correct, but the selected search state is
        # unchanged: printing a*a cannot manufacture four saved operations.
        with self.assertRaises(ValueError):
            self.driver.audit_response(response['input'], response)

    def test_equivalent_input_restructure_is_not_the_requested_start_state(self):
        response = candidate()
        a, b = variable('a'), variable('b')
        replacement = typed(binary('ADD', binary('MUL', b, b),
            binary('MUL', binary('ADD', a, b), binary('SUB', a, b))))
        response['typedInput'] = replacement
        response['witness'][0]['source'] = replacement
        search = json.loads(response['search'])
        search['events'][0]['source']['expression'] = replacement
        response['search'] = json.dumps(search)
        with self.assertRaises(ValueError):
            self.driver.audit_response(response['input'], response)

    def test_output_binding_preserves_formatter_association_and_power_precedence(self):
        a, b, c = variable('a'), variable('b'), variable('c')
        def unchanged(source, node, output):
            response = candidate()
            document = typed(node)
            response.update(input=source, output=output, typedInput=document, typedOutput=document,
                inputCost=2, outputCost=2, witness=[], selectedReplayWork=0, totalWork=1,
                search=json.dumps({'reached': False, 'metrics': {'primitiveWork': 0, 'searchWork': 0, 'verificationWork': 0},
                                   'totalWork': 0, 'events': []}))
            return response
        for source, node, output in [
            ('a+(b+c)', binary('ADD', a, binary('ADD', b, c)), 'a + b + c'),
            ('a+(b-c)', binary('ADD', a, binary('SUB', b, c)), 'a + b - c'),
            ('a*(b*c)', binary('MUL', a, binary('MUL', b, c)), 'a * b * c'),
            ('(a^2)^3', binary('POW', binary('POW', a, {'type': 'number', 'value': '2'}), {'type': 'number', 'value': '3'}), '(a ^ 2) ^ 3'),
        ]:
            response = unchanged(source, node, output)
            with self.subTest(source=source):
                self.driver.audit_response(source, response)
                if source == 'a+(b+c)':
                    with self.assertRaises(ValueError):
                        self.driver.audit_response(source, dict(response, output='b+a+c'))

    def test_endpoint_equivalence_does_not_hide_disconnected_or_unadmitted_paths(self):
        for mutation in ['disconnected', 'unadmitted', 'missing', 'premise', 'replay']:
            response = candidate()
            if mutation == 'disconnected':
                response['witness'][0]['source'] = response['typedOutput']
            elif mutation == 'unadmitted':
                search = json.loads(response['search'])
                search['events'] = []
                response['search'] = json.dumps(search)
            elif mutation == 'missing':
                response['witness'] = []
            elif mutation == 'premise':
                response['witness'][0]['move']['assumptions'] = ['a != 0']
            else:
                response['selectedReplayWork'] = 0
                response['totalWork'] = 4
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.driver.audit_response(response['input'], response)

    def test_query_binding_rejects_model_switch_target_and_hidden_budget_overrun(self):
        response = candidate()
        self.driver.check_response('LEARNED_SCHEMA', response['input'], response, setup()['modelHash'], 6)
        for key, value in [('modelHash', 'wrong'), ('profile', 'BASE'), ('target', 'a*a'),
                           ('input', 'b*b'), ('totalWork', 5), ('internalWorkWithinBudget', False)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.driver.check_response('LEARNED_SCHEMA', response['input'], dict(response, **{key: value}), setup()['modelHash'], 6)
        overrun = dict(response, internalWorkWithinBudget=False)
        self.driver.check_response('LEARNED_SCHEMA', response['input'], overrun, setup()['modelHash'], 5)

    def test_actual_sequence_is_not_a_scaled_single_request_and_pays_both_processes(self):
        # The worker implements a minimal source-only BASE protocol; the controller
        # under test still starts, persists, shuts down, restores, queries and reaps.
        program = '''import hashlib,json,os,sys
model = '{"profile":"BASE","schemas":[]}'
h = 'sha256:' + hashlib.sha256(model.encode()).hexdigest()
print(json.dumps({'status':'READY','pid':os.getpid()}),flush=True)
for line in sys.stdin:
 r=json.loads(line)
 if r['op'] in ('initialize','restore'):
  assert r['op'] != 'restore' or r['model'] == model
  out={'status':'INITIALIZED','profile':'BASE','model':model,'modelHash':h,'nativeWallNanos':1,'nativeCpuNanos':1,'nativeAllocatedBytes':1}
  out['trainingWork' if r['op']=='initialize' else 'restoreWork']=0
 else:
  assert r['op']=='run'
  tree=json.dumps({'schema':'regelsuche.typed-move-expression/v1','expression':{'type':'variable','name':r['source']}})
  out={'status':'CANDIDATE','profile':'BASE','input':r['source'],'output':r['source'],'inputCost':0,'outputCost':0,'typedInput':tree,'typedOutput':tree,'modelHash':h,'target':'','targetReached':False,'totalWork':0,'selectionWork':0,'selectedReplayWork':0,'internalWorkWithinBudget':True,'search':json.dumps({'reached':False,'metrics':{'primitiveWork':0,'searchWork':0,'verificationWork':0},'totalWork':0,'events':[]}),'witness':[],'nativeWallNanos':1,'nativeCpuNanos':1,'nativeAllocatedBytes':1}
 print(json.dumps(out),flush=True)
'''
        protocol = {'workBudget': 20, 'queryTimeoutSeconds': 2, 'setupTimeoutSeconds': 5,
                    'cases': [{'id': 'a', 'source': 'a'}, {'id': 'b', 'source': 'b'}]}
        with tempfile.TemporaryDirectory() as directory:
            sequence = self.driver.run_sequence(protocol, 'BASE', 3, 0, [sys.executable, '-u', '-c', program], Path(directory))
            self.assertEqual(['a', 'b', 'a'], [r['case'] for r in sequence['rows']])
            self.assertEqual(3, sequence['accounting']['attemptedQueries'])
            self.assertEqual(3, sequence['accounting']['eligibleQueries'])
            self.assertTrue(sequence['accounting']['complete'])
            self.assertEqual(2, len(sequence['processes']))
            self.assertEqual(2, len({p['pid'] for p in sequence['processes']}))
            self.assertTrue(all(p['termination'] == 'GRACEFUL_EOF' and p['exitCode'] == 0 for p in sequence['processes']))
            phases = sequence['phases']
            for key in ['trainingStartup', 'training', 'modelPersistence', 'trainingShutdown', 'queryStartup', 'modelLoad', 'restore', 'queryShutdown']:
                self.assertGreater(phases[key]['wallNanos'], 0, key)
            self.assertGreaterEqual(sequence['accounting']['lifecycleWallNanos'], sum(p['wallNanos'] for p in phases.values()) + sum(r['judgment']['endToEndNanos'] for r in sequence['rows']))
            self.assertGreater(sequence['accounting']['wholeWorkerCpu']['totalNanos'], 0)
            self.assertGreater(sequence['accounting']['peakWorkerRssBytes'], 0)
            self.assertEqual(5, sequence['accounting']['reportedNativeMethodAllocatedBytes'])
            for missing in ['training', 'restore']:
                broken = deepcopy(sequence)
                del broken['phases'][missing]
                with self.subTest(missing=missing), self.assertRaises(ValueError):
                    self.driver.account_sequence(protocol, broken)
            broken = deepcopy(sequence)
            broken['rows'].pop()
            with self.assertRaises(ValueError):
                self.driver.account_sequence(protocol, broken)
            broken = deepcopy(sequence)
            broken['processes'][0]['cpu'] = None
            self.assertFalse(self.driver.account_sequence(protocol, broken)['complete'])
            for phase, field in [('training', 'trainingWork'), ('restore', 'restoreWork')]:
                broken = deepcopy(sequence)
                del broken['phases'][phase]['response'][field]
                with self.subTest(field=field), self.assertRaises((ValueError, KeyError)):
                    self.driver.account_sequence(protocol, broken)
            for phase in ['training', 'modelPersistence', 'modelLoad', 'restore']:
                broken = deepcopy(sequence)
                broken['phases'][phase]['wallNanos'] = 0
                with self.subTest(phase=phase), self.assertRaises(ValueError):
                    self.driver.account_sequence(protocol, broken)
            broken = deepcopy(sequence)
            broken['phases']['modelLoad']['status'] = 'BLOCKED'
            self.assertFalse(self.driver.account_sequence(protocol, broken)['complete'])
            broken = deepcopy(sequence)
            broken['processes'][1]['pid'] = broken['processes'][0]['pid']
            with self.assertRaises(ValueError):
                self.driver.account_sequence(protocol, broken)

    def test_timeout_retains_real_cpu_receipt_and_never_claims_graceful_completion(self):
        command = [sys.executable, '-u', '-c', 'import sys; print(\'{"status":"READY"}\',flush=True); sys.stdin.readline();\nwhile True: sum(i*i for i in range(1000))']
        with tempfile.TemporaryDirectory() as directory:
            worker = self.driver.AccountedSession(command, Path(directory) / 'stderr.txt', 5)
            response, _ = worker.request({'op': 'run'}, .1)
            self.assertEqual('TIMEOUT', response['status'])
            receipt = deepcopy(worker.receipt)
            self.assertGreater(receipt['cpu']['totalNanos'], 0)
            self.assertGreater(receipt['peakRssBytes'], 0)
            self.assertEqual('FORCED', receipt['termination'])
            worker.close()
            self.assertEqual(receipt, worker.receipt)

    def test_failed_initialization_retains_all_requested_rows_and_startup_receipt(self):
        program = '''import json,sys
print('{"status":"READY"}',flush=True)
for line in sys.stdin:
 print('{"status":"ERROR","error":"deliberate initialization failure"}',flush=True)
'''
        protocol = {'workBudget': 20, 'queryTimeoutSeconds': 1, 'setupTimeoutSeconds': 5,
                    'cases': [{'id': 'a', 'source': 'a'}]}
        with tempfile.TemporaryDirectory() as directory:
            sequence = self.driver.run_sequence(protocol, 'BASE', 3, 0, [sys.executable, '-u', '-c', program], Path(directory))
            self.assertEqual(3, len(sequence['rows']))
            self.assertTrue(all(r['judgment']['verdict'] == 'BLOCKED_WORKER_UNAVAILABLE' for r in sequence['rows']))
            self.assertEqual('ERROR', sequence['phases']['training']['response']['status'])
            self.assertEqual(1, len(sequence['processes']))
            self.assertGreater(sequence['processes'][0]['cpu']['totalNanos'], 0)
            self.assertIsNone(sequence['accounting']['wholeWorkerCpu'])
            self.assertFalse(sequence['accounting']['complete'])

    def test_quality_disagreements_and_missing_receipts_never_produce_speed_ratios(self):
        protocol = {'profiles': ['BASE', 'LEARNED_SCHEMA', 'LEARNED_SELECTED'],
                    'sequenceLengths': [1], 'repetitions': 1}
        sequences = []
        for profile, cost, wall in [('BASE', 2, 200), ('LEARNED_SCHEMA', 1, 100), ('LEARNED_SELECTED', 2, 50)]:
            sequences.append({'profile': profile, 'length': 1, 'repetition': 0,
                'rows': [{'index': 0, 'case': 'a', 'judgment': {'valid': True, 'outputCost': cost}}],
                'accounting': {'complete': True, 'lifecycleWallNanos': wall, 'totalLogicalWork': 7,
                               'wholeWorkerCpu': {'totalNanos': wall}}})
        compared = self.driver.paired_comparisons(protocol, sequences)
        schema = compared['LEARNED_SCHEMA_versus_BASE']['pairs'][0]
        self.assertEqual('BETTER', schema['rows'][0]['outcome'])
        self.assertIsNone(schema['fullLifecycleWallSpeedup'])
        selected = compared['LEARNED_SELECTED_versus_BASE']['pairs'][0]
        self.assertEqual(4, selected['fullLifecycleWallSpeedup'])
        sequences[-1]['accounting']['complete'] = False
        compared = self.driver.paired_comparisons(protocol, sequences)
        self.assertIsNone(compared['LEARNED_SELECTED_versus_BASE']['pairs'][0]['fullLifecycleWallSpeedup'])

    def test_protocol_and_sequence_matrix_cannot_be_relaxed_after_results(self):
        path = Path('config/benchmarks/learned-schema-efficiency-v2.json')
        protocol = self.driver.load_protocol(path)
        self.assertEqual(36, len(list(self.driver.schedule(protocol))))
        with tempfile.TemporaryDirectory() as directory:
            altered = Path(directory) / 'protocol.json'
            changed = deepcopy(protocol)
            changed['queryTimeoutSeconds'] += 1
            altered.write_text(json.dumps(changed))
            with self.assertRaises(ValueError):
                self.driver.load_protocol(altered)
        with self.assertRaises(ValueError):
            self.driver.require_sequences(protocol, [])

    def test_snapshotted_runtime_is_bound_to_bytes_not_a_mutable_classpath(self):
        self.assertTrue(hasattr(self.driver, 'snapshot_runtime'), 'runtime byte snapshot missing')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            classes = root / 'classes'
            classes.mkdir()
            (classes / 'Example.class').write_bytes(b'original bytecode')
            dependency = root / 'dependency.jar'
            dependency.write_bytes(b'original dependency')
            output = root / 'output'
            output.mkdir()
            receipt = self.driver.snapshot_runtime(os.pathsep.join([str(classes), str(dependency)]), output)
            classpath = receipt['classpathEntries']
            self.assertEqual(2, len(classpath))
            retained = output / classpath[0] / 'Example.class'
            self.assertEqual(b'original bytecode', retained.read_bytes())
            (classes / 'Example.class').write_bytes(b'changed build after snapshot')
            dependency.write_bytes(b'changed dependency after snapshot')
            self.driver.verify_runtime(output, receipt)
            retained.write_bytes(b'tampered retained runtime')
            with self.assertRaises(ValueError):
                self.driver.verify_runtime(output, receipt)

    def test_source_provenance_rejects_dirty_and_untracked_implementation(self):
        self.assertTrue(hasattr(self.driver, 'source_provenance'), 'source provenance gate missing')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.run(['git', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', *args],
                                      cwd=root, check=True, capture_output=True)
            git('init', '-q')
            source = root / 'Example.java'
            source.write_text('class Example {}')
            git('add', 'Example.java')
            git('commit', '-qm', 'initial source')
            original = self.driver.source_provenance(root)
            source.write_text('class Changed {}')
            with self.assertRaises(ValueError):
                self.driver.source_provenance(root)
            source.write_text('class Example {}')
            for name in ['Untracked.java', 'runner.py', 'build.gradle', 'config/protocol.json']:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('untracked source')
                with self.subTest(name=name), self.assertRaises(ValueError):
                    self.driver.source_provenance(root)
                path.unlink()
            (root / 'build').mkdir()
            (root / 'build' / 'generated.java').write_text('allowed generated build output')
            self.assertEqual(original, self.driver.source_provenance(root))
            source.write_text('class Changed {}')
            git('add', 'Example.java')
            git('commit', '-qm', 'changed source')
            changed = self.driver.source_provenance(root)
            self.assertNotEqual(original['sourceRevision'], changed['sourceRevision'])
            self.assertNotEqual(original['sourceTreeHash'], changed['sourceTreeHash'])


if __name__ == '__main__':
    unittest.main()
