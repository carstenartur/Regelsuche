import json
import hashlib
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from external_polynomial_comparison import run_native as native
from external_polynomial_comparison.run import canonical, judge, summarize
from external_polynomial_comparison.run_schema import snapshot_runtime, _file_inventory, CPU_SCOPE


class NativeComparisonTest(unittest.TestCase):
    def test_response_binding_rejects_wrong_source_model_and_hidden_costs(self):
        response = dict(status='CANDIDATE', profile='NATIVE_BASE', input='x+0', output='x', target='',
                        targetReached=False, modelHash='bound', search=json.dumps({'totalWork': 7}),
                        selectionWork=3, selectedReplayWork=2, totalWork=12, internalWorkWithinBudget=True)
        native.check_response('NATIVE_BASE', 'x+0', response, 'bound', 12)
        for key, value in [('input', 'y'), ('modelHash', 'wrong'), ('totalWork', 8),
                           ('internalWorkWithinBudget', False), ('target', 'x'), ('targetReached', True)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                native.check_response('NATIVE_BASE', 'x+0', dict(response, **{key: value}), 'bound', 12)

    def test_quality_requires_every_case_and_does_not_cancel_a_loss_with_a_win(self):
        protocol = dict(cases=[{'id': 'a', 'source': 'x+0', 'cohort': 'HISTORICAL'},
                               {'id': 'b', 'source': 'y+0', 'cohort': 'WITHHELD_PUBLIC_VARIANT'}],
                        profiles=['NATIVE_BASE', 'NATIVE_LEARNED', 'SYMPY_PORTFOLIO'], repetitions=1)
        rows = []
        for case in protocol['cases']:
            for profile in protocol['profiles']:
                output = case['source'] if profile == 'NATIVE_LEARNED' and case['id'] == 'b' else case['source'][0]
                response = dict(status='CANDIDATE', output=output)
                rows.append(dict(case=case['id'], profile=profile, repetition=0, source=case['source'],
                                 response=response, judgment=judge(case['source'], response, 0, 10**9)))
        report = native.quality(protocol, rows)
        self.assertTrue(report['NATIVE_BASE']['accepted'])
        self.assertFalse(report['NATIVE_LEARNED']['accepted'])
        self.assertEqual(1, report['NATIVE_LEARNED']['worse'])
        with self.assertRaises(ValueError):
            native.quality(protocol, rows[:-1])

    def test_short_incorrect_expression_fails_the_common_exact_judge(self):
        result = judge('(x+1)*(x-1)', dict(status='CANDIDATE', output='0'), 0, 10**9)
        self.assertFalse(result['valid'])
        self.assertEqual('INVALID_IDENTITY', result['verdict'])

    def fixture(self, directory):
        """Small explicit verifier fixture; not a mathematical/performance measurement."""
        output = Path(directory)
        protocol = dict(cases=[dict(id='a', source='x', cohort='HISTORICAL'),
                               dict(id='b', source='y', cohort='HISTORICAL')],
                        profiles=['SYMPY_PORTFOLIO'], repetitions=1, sympyVersion='1.14.0', queryTimeoutSeconds=1)
        dependency = output / 'fixture-dependency.txt'
        dependency.write_text('Verifier fixture, not an executable runtime')
        runtime = snapshot_runtime(str(dependency), output)
        rows = []
        for case in protocol['cases']:
            response = dict(status='CANDIDATE', profile='SYMPY_PORTFOLIO', output=case['source'])
            rows.append(dict(case=case['id'], source=case['source'], profile='SYMPY_PORTFOLIO', repetition=0,
                             response=response, requestWallNanos=0, judgment=judge(case['source'], response, 0, 10**9)))
        receipt = dict(pid=123, exitCode=0, termination='GRACEFUL_EOF', receiptError=None, peakRssBytes=1024,
                       cpu=dict(userNanos=2, systemNanos=1, totalNanos=3, scope=CPU_SCOPE),
                       rssScope='WAIT4_PROCESS_PEAK_RESIDENT_SET;MAX_ACROSS_SEQUENTIAL_WORKERS')
        metadata = dict(runtimeBytesHash=runtime['runtimeBytesHash'], sequences=[dict(profile='SYMPY_PORTFOLIO', repetition=0,
            ready=dict(status='READY', sympy='1.14.0'), operations=[], workers=[receipt], complete=True,
            wallNanos=10**9, controllerCpuNanos=1)])
        for name, value in [('protocol.json', protocol), ('rows.json', rows), ('metadata.json', metadata),
                            ('summary.json', summarize(protocol, rows)), ('quality.json', native.quality(protocol, rows))]:
            (output / name).write_bytes(canonical(value))
        self.refresh_manifest(output)
        return protocol, rows, metadata

    @staticmethod
    def refresh_manifest(output):
        inventory = _file_inventory(output)
        inventory.pop('manifest.json', None)
        (output / 'manifest.json').write_bytes(canonical(inventory))

    def test_offline_verifier_rejects_case_relabeling_with_a_recomputed_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            protocol, rows, _ = self.fixture(output)
            with patch.object(native, 'load_protocol', return_value=protocol):
                native.verify(output)
                rows[0]['case'], rows[1]['case'] = rows[1]['case'], rows[0]['case']
                (output / 'rows.json').write_bytes(canonical(rows))
                self.refresh_manifest(output)
                with self.assertRaises(ValueError):
                    native.verify(output)

    def test_offline_verifier_rejects_missing_receipts_bad_cpu_negative_time_and_wrong_sympy(self):
        mutations = [lambda m: m['sequences'][0].update(workers=[]),
                     lambda m: m['sequences'][0].update(wallNanos=-1),
                     lambda m: m['sequences'][0]['workers'][0]['cpu'].update(totalNanos=1),
                     lambda m: m['sequences'][0]['ready'].update(sympy='WRONG-VERSION'),
                     lambda m: m['sequences'].append(m['sequences'][0])]
        for mutate in mutations:
            with self.subTest(mutation=mutations.index(mutate)), tempfile.TemporaryDirectory() as directory:
                output = Path(directory)
                protocol, _, metadata = self.fixture(output)
                mutate(metadata)
                (output / 'metadata.json').write_bytes(canonical(metadata))
                self.refresh_manifest(output)
                with patch.object(native, 'load_protocol', return_value=protocol), self.assertRaises(ValueError):
                    native.verify(output)

    def test_initial_ready_timeout_retains_its_actual_process_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            protocol = dict(cases=[dict(id='a', source='x')], setupTimeoutSeconds=.08, queryTimeoutSeconds=1)
            command = [sys.executable, '-u', '-c', 'import time; time.sleep(10)']
            sequence, rows = native._sequence(protocol, 'NATIVE_BASE', 0, command, Path(directory))
            self.assertFalse(sequence['complete'])
            self.assertEqual(1, len(sequence['workers']))
            receipt = sequence['workers'][0]
            self.assertGreater(receipt['pid'], 0)
            self.assertIsNotNone(receipt['cpu'])
            self.assertNotEqual(0, receipt['exitCode'])
            self.assertEqual('BLOCKED_WORKER_UNAVAILABLE', rows[0]['response']['status'])

    def test_unavailable_measurements_are_explicitly_incomplete(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            protocol, _, metadata = self.fixture(output)
            receipt = metadata['sequences'][0]['workers'][0]
            receipt.update(cpu=None, peakRssBytes=None, receiptError='wait4 unavailable')
            (output / 'metadata.json').write_bytes(canonical(metadata))
            self.refresh_manifest(output)
            with patch.object(native, 'load_protocol', return_value=protocol):
                result = native.verify(output)
            self.assertFalse(result['resources']['complete'])

    def test_consumer_ready_timeout_does_not_repeat_the_producer_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            protocol = dict(cases=[dict(id='a', source='x')], setupTimeoutSeconds=.3, queryTimeoutSeconds=1)
            protocol.update(dict.fromkeys(native.SETTINGS, 1))
            model = '{}'
            acquired = dict(status='ACQUIRED', profile='NATIVE_LEARNED', settings={k: protocol[k] for k in native.SETTINGS},
                model=model, modelHash='sha256:' + hashlib.sha256(model.encode()).hexdigest(),
                trainingWork=1, restorationWork=0, schemas=1, nativeWallNanos=1, nativeCpuNanos=0)
            marker = str(Path(directory) / 'started')
            script = ('import sys,time,pathlib\n'
                      f'p=pathlib.Path({marker!r})\n'
                      'if p.exists(): time.sleep(10)\n'
                      'p.write_text("started")\n'
                      'print(\'{"status":"READY"}\',flush=True)\n'
                      'sys.stdin.readline()\n'
                      f'print({json.dumps(acquired)!r},flush=True)\n'
                      'sys.stdin.read()\n')
            sequence, _ = native._sequence(protocol, 'NATIVE_LEARNED', 0,
                [sys.executable, '-u', '-c', script], Path(directory))
            self.assertFalse(sequence['complete'])
            self.assertEqual(2, len(sequence['workers']))
            self.assertEqual(2, len({receipt['pid'] for receipt in sequence['workers']}))
            self.assertEqual(0, sequence['workers'][0]['exitCode'])
            self.assertNotEqual(0, sequence['workers'][1]['exitCode'])
