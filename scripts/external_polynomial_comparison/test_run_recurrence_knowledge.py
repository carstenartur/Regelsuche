"""Independent lifecycle/audit regressions; fixtures are never benchmark evidence."""
import copy
import importlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

MODULE = 'scripts.external_polynomial_comparison.run_recurrence_knowledge'
ROOT = Path(__file__).resolve().parents[2]
PROTOCOL = ROOT / 'config/benchmarks/recurrence-knowledge-lifecycle-v1.json'


def load():
    try:
        return importlib.import_module(MODULE)
    except ModuleNotFoundError:
        return None


def task():
    return {'linear': [['0', '1'], ['1', '4']], 'offset': ['0', '0'],
            'initial': ['2', '3'], 'degree': 2, 'workBudget': 100000}


def common(profile='BASELINE', generation='generation'):
    return {'revision': 'regelsuche.recurrence-knowledge-worker/v1', 'profile': profile,
            'generationId': generation, 'acquisitionWork': 0,
            'acquisitionCosts': dict.fromkeys(('lessons', 'formation', 'preparation', 'validation',
                                              'selection', 'restoration', 'splitChecking', 'total'), 0),
            'sessionSetupWork': 0, 'recurrenceCount': 0, 'recurrenceFamilyCount': 0}


def response(poly='x0^2+4*x0*x1-x1^2', value='19'):
    return dict(common(), status='RESULT', id='probe', task=task(), outcome='VERIFIED',
                solved=True, reused=False, totalWork=123,
                law={'polynomial': poly, 'evolution': 'ALTERNATING', 'initialValue': value})


def complete_sequence(module, protocol, profile='BASELINE', length=1, repetition=0, solved=True):
    costs = common()['acquisitionCosts']
    seq = {'id': f'{profile}.n{length}.r{repetition}', 'profile': profile, 'length': length,
           'repetition': repetition, 'operations': [], 'rows': [], 'processes': [], 'errors': [],
           'wallNanos': 100000, 'controllerCpuNanos': 10}
    previous = 0
    for name in module.operation_plan(protocol, profile):
        op = {'name': name, 'status': 'COMPLETE', 'wallNanos': 1}
        if name.endswith('Startup'):
            op.update(pid=10 if name == 'producerStartup' else 11, ready={'status': 'READY'})
        elif not name.endswith('Shutdown'):
            command = name.split('-')[0]
            r = dict(common(profile), status=module.SUCCESS[command])
            payload = {'command': command}
            if command == 'initialize': payload['profile'] = profile
            elif command == 'learn':
                payload.update(protocol['lessons'][int(name.split('-')[1])], budget=protocol['acquisitionBudget'])
                r.update(work=dict(costs, lessons=7, total=7), reason='NO_NEW_LAW', promoted=False,
                         training=[{'outcome': 'VERIFIED', 'solved': True, 'reused': False, 'totalWork': 3}])
                previous += 7
            elif command == 'generalize':
                payload.update(validation=protocol['familyValidation'], budget=protocol['acquisitionBudget'])
                r.update(work=dict(costs, formation=3, total=3), reason='NO_NEW_LAW', promoted=False, training=[])
                previous += 3
            elif command == 'save':
                payload['directory'] = '/original/snapshots/' + seq['id']
                r.update(snapshot=payload['directory']+'/knowledge.json', snapshotHash='sha256:'+'a'*64)
            elif command == 'restore':
                payload.update(profile=profile, snapshot='/original/snapshots/'+seq['id']+'/knowledge.json')
                r.update(snapshot=payload['snapshot'], snapshotHash='sha256:'+'a'*64, sessionSetupWork=5)
            r.update(acquisitionWork=previous, acquisitionCosts=dict(costs, lessons=min(previous, 14),
                     formation=max(0, previous-14), total=previous))
            op.update(request=payload, response=r, transportWallNanos=0)
        seq['operations'].append(op)
    for i, query in enumerate(protocol['queries'][:length]):
        r = dict(response(), id=query['id'], task=query['task'], profile=profile, reused=profile == 'FAMILY',
                 acquisitionWork=previous, acquisitionCosts=dict(costs, lessons=min(previous, 14),
                 formation=max(0, previous-14), total=previous), sessionSetupWork=0 if profile=='BASELINE' else 5)
        if i != 0 or not solved:
            r.update(outcome='NO_LAW_IN_ANSATZ', solved=False, reused=False, law=None)
        seq['rows'].append({'index': i, 'id': query['id'], 'task': query['task'], 'response': r,
                           'generationId': 'generation', 'independentAudit': module.audit_response(query,r,profile,'generation'),
                           'transportWallNanos': 1, 'auditWallNanos': 1, 'wallNanos': 2})
    for stage in (['query'] if profile=='BASELINE' else ['producer','query']):
        seq['processes'].append({'stage': stage, 'pid': 10 if stage=='producer' else 11,
            'exitCode': 0, 'termination': 'GRACEFUL_EOF', 'peakRssBytes': 1000 if stage=='producer' else 2000,
            'receiptError': None, 'cpu': {'userNanos': 20, 'systemNanos': 10, 'totalNanos': 30, 'scope': module.CPU_SCOPE}})
    return seq


class AuditTests(unittest.TestCase):
    def setUp(self):
        self.m = load()
        self.assertIsNotNone(self.m, 'lifecycle runner must exist')

    def audit(self, result):
        return self.m.audit_response({'id': 'probe', 'task': task()}, result, 'BASELINE', 'generation')

    def test_exact_polynomial_and_distinct_laws(self):
        self.assertTrue(self.audit(response())['valid'])
        self.assertTrue(self.audit(response('2*x0^2+8*x0*x1-2*x1^2', '38'))['valid'])
        self.assertEqual(self.audit(response())['solved'], self.audit(response('2*x0^2+8*x0*x1-2*x1^2', '38'))['solved'])

    def test_initial_source_degree_variable_budget_and_binding_tampering(self):
        mutants = []
        for field, value in [('initialValue', '20'), ('polynomial', 'x0'),
                             ('polynomial', 'x2^2'), ('polynomial', '(x0^2+4*x0*x1-x1^2)^2'),
                             ('polynomial', '1'), ('evolution', 'FIXED')]:
            r = response(); r['law'][field] = value; mutants.append(r)
        for field, value in [('id', 'wrong'), ('profile', 'FAMILY'), ('generationId', 'other'),
                             ('totalWork', 100001), ('totalWork', True), ('solved', False), ('reused', True)]:
            r = response(); r[field] = value; mutants.append(r)
        r = response(); r['task']['initial'] = ['3', '3']; mutants.append(r)
        for r in mutants:
            with self.subTest(r=r):
                self.assertFalse(self.audit(r)['valid'])

    def test_negative_outcomes_are_retained_valid_unsolved(self):
        for outcome in ['NO_LAW_IN_ANSATZ', 'UNSUPPORTED', 'BUDGET_INCONCLUSIVE']:
            r = dict(response(), outcome=outcome, solved=False, law=None)
            self.assertEqual(self.audit(r), {'valid': True, 'solved': False, 'outcome': outcome})
        for r in [dict(response(), status='TIMEOUT'), dict(response(), solved=False),
                  dict(response(), outcome='UNKNOWN'), dict(response(), totalWork=-1)]:
            self.assertFalse(self.audit(r)['valid'])

    def test_task_dimension_boundary_matches_production_transition(self):
        def identity_task(dimension):
            return {'linear': [[str(int(i == j)) for j in range(dimension)] for i in range(dimension)],
                    'offset': ['0'] * dimension, 'initial': ['1'] * dimension,
                    'degree': 2, 'workBudget': 100000}
        self.m.check_task(identity_task(3))
        with self.assertRaisesRegex(ValueError, 'task dimension mismatch'):
            self.m.check_task(identity_task(4))

    def test_simultaneous_affine_substitution(self):
        q = {'id': 'affine', 'task': {'linear': [['0', '1'], ['1', '0']], 'offset': ['1', '-1'],
                                    'initial': ['2', '3'], 'degree': 2, 'workBudget': 100000}}
        r = dict(response('x0+x1', '5'), id='affine', task=q['task'])
        r['law']['evolution'] = 'FIXED'
        self.assertTrue(self.m.audit_response(q, r, 'BASELINE', 'generation')['valid'])


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.m = load(); self.assertIsNotNone(self.m, 'lifecycle runner must exist')

    def test_frozen_protocol_matrix(self):
        p = self.m.load_protocol(PROTOCOL)
        matrix = list(self.m.schedule(p))
        self.assertEqual(len(matrix), 27)
        self.assertEqual(len(set(matrix)), 27)
        self.assertEqual(sum(length for _, length, _ in matrix), 657)
        self.assertEqual([x[0] for x in matrix[:3]], ['BASELINE', 'EXACT_FROZEN', 'FAMILY'])
        self.assertEqual([x[0] for x in matrix[3:6]], ['EXACT_FROZEN', 'FAMILY', 'BASELINE'])
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'protocol.json'; path.write_bytes(PROTOCOL.read_bytes()+b' ')
            with self.assertRaises(ValueError): self.m.load_protocol(path)

    def test_receipt_retained_on_constructor_ready_failure(self):
        with tempfile.TemporaryDirectory() as d:
            receipts = []
            with self.assertRaises(Exception):
                self.m.ReceiptSession([sys.executable, '-u', '-c', 'print("{}", flush=True)'],
                                      Path(d) / 'stderr', 1, receipts, 'query')
            self.assertEqual(len(receipts), 1)
            self.assertIsNotNone(receipts[0]['cpu'])
            self.assertIsNotNone(receipts[0]['peakRssBytes'])

    def test_timeout_retains_receipt_and_planned_rows(self):
        p = self.m.load_protocol(PROTOCOL); p = dict(p, setupTimeoutSeconds=1, queryTimeoutSeconds=.03)
        initialized = dict(common(), status='INITIALIZED')
        fixture = ('import json,time,sys\nprint(json.dumps({"status":"READY"}),flush=True)\n'
                   'for line in sys.stdin:\n payload=json.loads(line)\n'
                   ' if payload["command"] == "initialize": print(' + repr(json.dumps(initialized)) + ',flush=True)\n'
                   ' else: time.sleep(5)')
        with tempfile.TemporaryDirectory() as d:
            seq = self.m.run_sequence(p, 'BASELINE', 8, 0, [sys.executable, '-u', '-c', fixture], Path(d))
            self.assertEqual(len(seq['rows']), 8)
            self.assertEqual(seq['rows'][0]['response']['status'], 'TIMEOUT')
            self.assertEqual(len(seq['processes']), 1)
            self.assertIsNotNone(seq['processes'][0]['cpu'])
            self.assertFalse(seq['accounting']['complete'])
            self.assertTrue(all(not r['independentAudit']['valid'] for r in seq['rows']))
            self.assertIsNone(seq['accounting']['totalLogicalWork'])

    def test_missing_operations_rows_and_receipts_disable_ratios(self):
        p = self.m.load_protocol(PROTOCOL)
        sequences = []
        for profile, length, repetition in self.m.schedule(p):
            sequences.append({'id': f'{profile}.n{length}.r{repetition}', 'profile': profile,
                              'length': length, 'repetition': repetition, 'operations': [], 'rows': [],
                              'processes': [], 'errors': [], 'wallNanos': 1, 'controllerCpuNanos': 1})
        comparisons = self.m.paired_comparisons(p, sequences)
        self.assertEqual(len(comparisons), 18)
        for comparison in comparisons:
            self.assertFalse(comparison['equalAuditedQuality'])
            self.assertIsNone(comparison['fullLifecycleWallSpeedup'])
            self.assertIsNone(comparison['wholeWorkerCpuSpeedup'])

    def test_cost_object_accounting_counts_acquisition_once_and_restoration(self):
        p = self.m.load_protocol(PROTOCOL)
        seq = complete_sequence(self.m, p, 'FAMILY')
        account = self.m.account_sequence(p, seq)
        self.assertTrue(account['complete'], account)
        self.assertEqual(account['acquisitionWork'], 17)
        self.assertEqual(account['restoreWork'], 5)
        self.assertEqual(account['queryWork'], 123)
        self.assertEqual(account['totalLogicalWork'], 145)
        self.assertEqual(account['wholeWorkerCpu']['totalNanos'], 60)
        self.assertEqual(account['peakWorkerRssBytes'], 2000)
        self.assertEqual(account['controllerCpuNanos'], 10)

    def test_equal_valid_distinct_laws_can_be_qualified(self):
        p = self.m.load_protocol(PROTOCOL)
        baseline = complete_sequence(self.m, p)
        learned = complete_sequence(self.m, p, 'EXACT_FROZEN')
        learned['rows'][0]['response']['law'].update(polynomial='2*x0^2+8*x0*x1-2*x1^2', initialValue='38')
        self.assertTrue(self.m.account_sequence(p, learned)['complete'])
        pair = self.m.paired_comparisons(p, [baseline, learned])[0]
        self.assertTrue(pair['equalAuditedQuality'])
        self.assertEqual(pair['fullLifecycleWallSpeedup'], 1)
        self.assertIsNotNone(pair['wholeWorkerCpuSpeedup'])
        self.assertIsNotNone(pair['fullLifecycleCpuSpeedup'])

    def test_registered_reuse_behavior_qualifies_only_the_intended_learned_arm(self):
        p = self.m.load_protocol(PROTOCOL)
        baseline = complete_sequence(self.m, p)
        for profile, unexpected in [('FAMILY', False), ('EXACT_FROZEN', True)]:
            seq = complete_sequence(self.m, p, profile)
            self.assertTrue(self.m.account_sequence(p, seq)['registeredQualityAccepted'])
            row = seq['rows'][0]; row['response']['reused'] = unexpected
            # A correct law remains mathematically valid even if the registered arm was not exercised.
            row['independentAudit'] = self.m.audit_response(p['queries'][0], row['response'], profile, 'generation')
            self.assertTrue(row['independentAudit']['valid'])
            account = self.m.account_sequence(p, seq)
            with self.subTest(profile=profile):
                self.assertTrue(account['complete'])
                self.assertFalse(account['registeredQualityAccepted'])
                pair = next(pair for pair in self.m.paired_comparisons(p, [baseline, seq])
                            if pair['profile'] == profile and pair['length'] == 1 and pair['repetition'] == 0)
                self.assertTrue(pair['equalAuditedQuality'])
                for key in ('fullLifecycleWallSpeedup', 'wholeWorkerCpuSpeedup',
                            'fullLifecycleCpuSpeedup', 'logicalWorkSpeedup'):
                    self.assertIsNone(pair[key])

    def test_all_unsolved_equal_quality_does_not_qualify_development_acceptance(self):
        p = self.m.load_protocol(PROTOCOL)
        a = complete_sequence(self.m, p, solved=False)
        b = complete_sequence(self.m, p, 'EXACT_FROZEN', solved=False)
        pair = self.m.paired_comparisons(p, [a,b])[0]
        self.assertIsNone(pair['fullLifecycleWallSpeedup'])
        self.assertFalse(pair['registeredQualityAccepted'])

    def test_setup_command_generation_and_producer_ledger_tampering(self):
        p = self.m.load_protocol(PROTOCOL)
        mutations = [('save', 'generationId', 'other'), ('learn-1', 'sessionSetupWork', 1)]
        for name, field, value in mutations:
            seq=complete_sequence(self.m,p,'FAMILY')
            next(op for op in seq['operations'] if op['name']==name)['response'][field]=value
            with self.subTest(name=name,field=field):
                self.assertFalse(self.m.account_sequence(p,seq)['complete'])
        seq=complete_sequence(self.m,p,'FAMILY')
        op=next(op for op in seq['operations'] if op['name']=='save')
        op['request']={'command':'generalize','validation':p['familyValidation'],'budget':p['acquisitionBudget']}
        op['response'].update(status='GENERALIZED',work=dict(common()['acquisitionCosts']),training=[],promoted=False,reason='NONE')
        self.assertFalse(self.m.account_sequence(p,seq)['complete'])

    def test_each_complete_protocol_operation_requires_retained_request_response_and_transport(self):
        p = self.m.load_protocol(PROTOCOL)
        baseline = complete_sequence(self.m, p)
        for profile in ('EXACT_FROZEN', 'FAMILY'):
            names = [op['name'] for op in complete_sequence(self.m, p, profile)['operations'] if 'request' in op]
            for name in names:
                for fields in [('request',), ('response',), ('transportWallNanos',),
                               ('request', 'response', 'transportWallNanos')]:
                    seq = complete_sequence(self.m, p, profile)
                    op = next(op for op in seq['operations'] if op['name'] == name)
                    for field in fields:
                        del op[field]
                    with self.subTest(profile=profile, operation=name, missing=fields):
                        account = self.m.account_sequence(p, seq)
                        self.assertFalse(account['complete'])
                        self.assertTrue(account['qualificationErrors'])
                        pair = next(pair for pair in self.m.paired_comparisons(p, [baseline, seq])
                                    if pair['profile'] == profile and pair['length'] == 1 and pair['repetition'] == 0)
                        self.assertIsNone(pair['fullLifecycleWallSpeedup'])

    def test_missing_each_required_component_disables_ratios(self):
        p = self.m.load_protocol(PROTOCOL)
        a = complete_sequence(self.m,p)
        for field in ('operations', 'rows', 'processes'):
            b = complete_sequence(self.m,p,'EXACT_FROZEN'); b[field].pop()
            with self.subTest(field=field):
                self.assertFalse(self.m.account_sequence(p,b)['complete'])
                self.assertIsNone(self.m.paired_comparisons(p,[a,b])[0]['fullLifecycleWallSpeedup'])

    def test_offline_verify_failure_evidence_and_semantic_tampering(self):
        # Deliberately blocked fixture checks retention/verification only, not benchmark results.
        p = self.m.load_protocol(PROTOCOL)
        with tempfile.TemporaryDirectory() as d:
            out = Path(d); classes=out/'classes'; classes.mkdir(); (classes/'worker.class').write_bytes(b'fixture')
            runtime = self.m.snapshot_runtime(str(classes),out)
            classpath_bytes=(str(classes)+'\n').encode()
            (out/'classpath-input.txt').write_bytes(classpath_bytes)
            (classes/'worker.class').unlink(); classes.rmdir()
            java={'executable':'/fixture/java','executableSha256':'a'*64,'releaseSha256':'b'*64,'version':'openjdk version "25.0.1"'}
            command=[java['executable'],'-Xmx512m','-cp',str(out/runtime['classpathEntries'][0]),self.m.JAVA_WORKER]
            metadata={'protocolHash':self.m.PROTOCOL_HASH,'informationRegime':'PUBLIC_DEVELOPMENT',
                'revision':p['revision'],'warmups':[],'sourceRevision':'a'*40,'sourceTreeHash':'git:'+'b'*40,
                'runtimeBytesHash':runtime['runtimeBytesHash'],'java':java,'command':command,
                'outputDirectoryAtExecution':str(out),'workerCpuScope':self.m.CPU_SCOPE,
                'sequenceWallScope':'FIRST_WORKER_START_THROUGH_LAST_SHUTDOWN_INCLUDING_IO_AND_EXACT_EXTERNAL_AUDIT'}
            sequences=[]
            for profile,length,repetition in self.m.schedule(p):
                seq={'id':f'{profile}.n{length}.r{repetition}','profile':profile,'length':length,'repetition':repetition,
                     'command':command,'operations':[{'name':n,'status':'BLOCKED','wallNanos':0} for n in self.m.operation_plan(p,profile)],
                     'rows':[],'processes':[],'errors':['fixture deliberately blocked'], 'wallNanos':1000000,
                     'controllerCpuNanos':1,'snapshotInventory':{}}
                for i,q in enumerate(p['queries'][:length]):
                    r={'status':'BLOCKED_WORKER_UNAVAILABLE'}
                    seq['rows'].append({'index':i,'id':q['id'],'task':q['task'],'response':r,'generationId':None,
                        'independentAudit':self.m.audit_response(q,r,profile,None),'transportWallNanos':0,'auditWallNanos':1,'wallNanos':1})
                seq['accounting']=self.m.account_sequence(p,seq); sequences.append(seq)
            from scripts.external_polynomial_comparison.run import canonical
            (out/'protocol.json').write_bytes(PROTOCOL.read_bytes())
            files={'metadata.json':metadata,'sequences.json':sequences,'rows.json':[r for s in sequences for r in s['rows']],
                   'summary.json':self.m.summarize(p,sequences),'paired-comparisons.json':self.m.paired_comparisons(p,sequences)}
            for name,value in files.items(): (out/name).write_bytes(canonical(value))
            (out/'report.md').write_text('Fixture only. No performance evidence.')
            self.m.write_manifest(out)
            import hashlib
            invocation=[sys.executable,'-m','scripts.external_polynomial_comparison.run_recurrence_knowledge',
                        '--execute-child','--protocol',str(PROTOCOL.resolve()),'--classpath','/original/classpath.txt',
                        '--output',str(out)]
            metadata['executeChild']={'command':invocation,'pythonExecutable':sys.executable,
                'protocolPath':str(PROTOCOL.resolve()),'classpathPath':'/original/classpath.txt',
                'classpathHash':'sha256:'+hashlib.sha256(classpath_bytes).hexdigest()}
            (out/'metadata.json').write_bytes(canonical(metadata)); self.m.write_manifest(out)
            receipt={'command':invocation,'returncode':1,'wallNanos':100000000,'supervisorCpuNanos':1,
                'manifestHash':'sha256:'+hashlib.sha256((out/'manifest.json').read_bytes()).hexdigest(),
                'wallScope':'ENTIRE_EXECUTE_CHILD_THROUGH_REPORTS_AND_MANIFEST;EXCLUDES_THIS_RECEIPT_WRITE',
                'childProcessTreeCpu':{'userNanos':2,'systemNanos':1,'totalNanos':3,
                    'scope':'EXECUTE_CHILD_AND_REAPED_WORKER_DESCENDANTS;INCLUDES_CONTROLLER'}}
            (out/'command-receipt.json').write_bytes(canonical(receipt))
            self.m.verify(out)
            # Relocation preserves recorded original invocation and does not require old inputs to exist.
            import shutil
            moved=out/'moved-copy'; shutil.copytree(out,moved)
            self.m.verify(moved); shutil.rmtree(moved)
            # Retained input bytes are bound independently of a rewritten manifest.
            (out/'classpath-input.txt').write_bytes(b'changed\n'); self.m.write_manifest(out)
            with self.assertRaises(ValueError): self.m.verify(out)
            (out/'classpath-input.txt').write_bytes(classpath_bytes); self.m.write_manifest(out)
            mutants=[]
            missing_command=copy.deepcopy(receipt); del missing_command['command']; mutants.append(missing_command)
            for index,value in [(0,'/other/python'),(2,'other.module'),(3,'--unexpected'),(5,'/wrong/protocol.json'),
                                (7,'/wrong/classpath.txt'),(9,'/wrong/output')]:
                changed=copy.deepcopy(receipt); changed['command'][index]=value; mutants.append(changed)
            mutants.append(dict(receipt,wallNanos=sum(seq['wallNanos'] for seq in sequences)-1))
            for changed in mutants:
                with self.subTest(receipt=changed):
                    (out/'command-receipt.json').write_bytes(canonical(changed))
                    with self.assertRaises(ValueError): self.m.verify(out)
            (out/'command-receipt.json').write_bytes(canonical(receipt))
            saved=(out/'command-receipt.json').read_bytes(); (out/'command-receipt.json').unlink()
            with self.assertRaises(ValueError): self.m.verify(out)
            (out/'command-receipt.json').write_bytes(saved)
            # Rehashing a changed summary must not bypass semantic recomputation.
            summary=files['summary.json']; summary['BASELINE']['solvedQueries']=99
            (out/'summary.json').write_bytes(canonical(summary)); self.m.write_manifest(out)
            with self.assertRaises(ValueError): self.m.verify(out)
            (out/'summary.json').write_bytes(canonical(self.m.summarize(p,sequences))); self.m.write_manifest(out)
            # Rehashed duplicate schedule is still rejected.
            sequences[-1]=copy.deepcopy(sequences[0]); (out/'sequences.json').write_bytes(canonical(sequences)); self.m.write_manifest(out)
            with self.assertRaises(ValueError): self.m.verify(out)

    def test_runtime_and_manifest_tampering(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d); classes = out / 'classes'; classes.mkdir(); (classes / 'test.class').write_bytes(b'bytecode')
            receipt = self.m.snapshot_runtime(str(classes), out)
            self.m.verify_runtime(out, receipt)
            (out / receipt['classpathEntries'][0] / 'test.class').write_bytes(b'tampered')
            with self.assertRaises(ValueError): self.m.verify_runtime(out, receipt)
            self.m.write_manifest(out)
            self.m.verify_manifest(out)
            (out / 'extra').write_text('unlisted')
            with self.assertRaises(ValueError): self.m.verify_manifest(out)


if __name__ == '__main__': unittest.main()
