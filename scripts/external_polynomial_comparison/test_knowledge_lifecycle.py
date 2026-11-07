import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


class KnowledgeLifecycleTest(unittest.TestCase):
    def setUp(self):
        from external_polynomial_comparison import run_knowledge_lifecycle
        self.runner = run_knowledge_lifecycle
        self.p = self.runner.load_protocol(Path(__file__).resolve().parents[2]/'config/benchmarks/knowledge-lifecycle-improvement-v1.json')

    def test_frozen_matrix_and_protocol_mutation(self):
        rows = list(self.runner.schedule(self.p))
        self.assertEqual(36, len(set(rows)))
        self.assertEqual(2448, sum(blocks*8 for _,blocks,_ in rows))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'changed.json'; path.write_text(json.dumps(self.p)+' ')
            with self.assertRaises(ValueError): self.runner.load_protocol(path)

    def test_independent_node_goal_identity_and_native_path_binding(self):
        task = dict(source='x^2-y^2', maximumNodes=7)
        output = '(x-y)*(x+y)'
        response = dict(status='RESULT', profile='LEGACY_SESSION', generationId='generation', task=task,
            solved=True, withinBudget=True, output=output, outputScore=7, nativeApiWork=20,
            normalizedInput=task['source'], witness=[dict(source=task['source'],target=output)])
        self.assertTrue(self.runner.audit(task,'LEGACY_SESSION',response,'generation')['solved'])
        for mutate in [lambda r:r.pop('witness'),lambda r:r.update(output=output+'+1'),
                       lambda r:r['witness'][0].update(source='u^2-v^2'),lambda r:r.update(outputScore=1),
                       lambda r:r.update(generationId='foreign')]:
            bad=copy.deepcopy(response);mutate(bad)
            self.assertFalse(self.runner.audit(task,'LEGACY_SESSION',bad,'generation')['valid'])
        response.update(solved=False,withinBudget=False)
        self.assertEqual(dict(valid=True,solved=False),self.runner.audit(task,'LEGACY_SESSION',response,'generation'))

    def test_targeted_sympy_uses_the_same_node_goal_without_answers(self):
        from external_polynomial_comparison.knowledge_sympy_worker import evaluate
        task = dict(source='(u+v)*(u-v)+v*v',maximumNodes=3)
        response=evaluate(task)
        self.assertTrue(self.runner.audit(task,'SYMPY',response,'STATELESS')['solved'])
        with self.assertRaises(ValueError): evaluate(dict(task,target='u^2'))

    def fixture(self):
        from external_polynomial_comparison.run_schema import CPU_SCOPE
        p=self.p
        operations=[dict(name='startup-query',status='COMPLETE',wallNanos=1,pid=123,processSpawned=True,ready=dict(status='READY',sympy='1.14.0')),
            dict(name='initialize',status='COMPLETE',wallNanos=1,request=dict(op='initialize',prelude=self.runner.prelude(p)),response=dict(status='INITIALIZED',profile='SYMPY',generationId='STATELESS',preludeHash=self.runner.digest(self.runner.canonical(self.runner.prelude(p))))),
            dict(name='load-0',status='COMPLETE',wallNanos=1,request=dict(op='load'),response=dict(status='LOADED',profile='SYMPY',generationId='STATELESS')),
            dict(name='shutdown-query',status='COMPLETE',wallNanos=1)]
        rows=[]
        for i,case in enumerate(p['queries']):
            task=case['task'];out=task['source']
            response=dict(status='RESULT',profile='SYMPY',generationId='STATELESS',task=task,output=out,
                          solved=self.runner.node_count(out)<=task['maximumNodes'],withinBudget=True)
            rows.append(dict(index=i,case=case['id'],task=task,generationId='STATELESS',response=response,
                judgment=self.runner.audit(task,'SYMPY',response,'STATELESS'),requestWallNanos=1,auditWallNanos=1))
        receipt=dict(stage='query',pid=123,exitCode=0,termination='GRACEFUL_EOF',receiptError=None,peakRssBytes=1024,
            rssScope=self.runner.RSS_SCOPE,cpu=dict(userNanos=2,systemNanos=1,totalNanos=3,scope=CPU_SCOPE))
        seq=dict(id='SYMPY.b1.r0',profile='SYMPY',blocks=1,repetition=0,operations=operations,rows=rows,
            processes=[receipt],errors=[],snapshots=[],wallNanos=1000,controllerCpuNanos=1)
        return p,seq

    def test_missing_or_relabelled_rows_and_process_costs_fail_closed(self):
        p,seq=self.fixture()
        self.assertTrue(self.runner.account_sequence(p,seq)['complete'])
        changes=[lambda s:s['rows'].pop(),lambda s:s['rows'][0].update(case='wrong'),
            lambda s:s.update(processes=[]),lambda s:s['processes'].append(s['processes'][0]),
            lambda s:s['processes'][0]['cpu'].update(totalNanos=1),lambda s:s.update(wallNanos=-1),
            lambda s:s['operations'][0]['ready'].update(sympy='wrong'),
            lambda s:s['operations'][1]['request'].update(prelude={}),
            lambda s:s['operations'][1]['response'].update(preludeHash='foreign')]
        for mutate in changes:
            bad=copy.deepcopy(seq);mutate(bad)
            with self.assertRaises(ValueError):self.runner.account_sequence(p,bad)

    def test_missing_resources_or_different_quality_suppresses_ratios(self):
        p,seq=self.fixture();seq['accounting']=self.runner.account_sequence(p,seq)
        self.assertIsNotNone(self.runner.paired(seq,seq)['wallRatio'])
        other=copy.deepcopy(seq);other['rows'][0]['judgment']['solved']=not other['rows'][0]['judgment']['solved']
        self.assertIsNone(self.runner.paired(seq,other)['wallRatio'])
        seq['processes'][0].update(cpu=None,peakRssBytes=None,receiptError='unavailable')
        seq['accounting']=self.runner.account_sequence(p,seq)
        self.assertFalse(seq['accounting']['complete']);self.assertIsNone(self.runner.paired(seq,seq)['wallRatio'])

    def test_real_failed_ready_cannot_lose_the_owned_process_receipt(self):
        p=copy.deepcopy(self.p);p['setupTimeoutSeconds']=.05
        with tempfile.TemporaryDirectory() as directory:
            seq=self.runner.run_sequence(p,'PRIMITIVE',1,0,[sys.executable,'-u','-c','import time;time.sleep(10)'],Path(directory))
            self.assertEqual(8,len(seq['rows']));self.assertEqual(1,len(seq['processes']))
            self.assertIsNotNone(seq['processes'][0]['cpu'])
            self.assertFalse(seq['accounting']['complete'])
            seq['processes']=[]
            with self.assertRaises(ValueError):self.runner.account_sequence(p,seq)

    def test_later_failed_load_retains_rows_without_reusing_stale_generation(self):
        # Synthetic protocol/worker tests transport failure, not performance or solver capability.
        p=copy.deepcopy(self.p);p['queries']=[dict(id='only',task=dict(source='x',maximumNodes=1))]
        script=('import json,sys\n'
            'print(json.dumps(dict(status="READY",java="25")),flush=True)\n'
            'loads=0\n'
            'for line in sys.stdin:\n'
            ' r=json.loads(line);op=r["op"]\n'
            f' out=dict(profile="PRIMITIVE",generationId="seed",nativeBudget={p["nativeBudget"]!r})\n'
            ' if op=="initialize":out["status"]="INITIALIZED"\n'
            ' elif op=="load":\n'
            '  loads+=1;out["status"]="LOADED" if loads==1 else "ERROR"\n'
            ' else:out.update(status="RESULT",task=r["task"],solved=True,withinBudget=True,output="x",outputScore=1,nativeApiWork=1,normalizedInput="x",witness=[])\n'
            ' print(json.dumps(out),flush=True)\n')
        with tempfile.TemporaryDirectory() as directory:
            seq=self.runner.run_sequence(p,'PRIMITIVE',2,0,[sys.executable,'-u','-c',script],Path(directory))
            self.assertEqual(2,len(seq['rows']));self.assertIsNone(seq['rows'][1]['generationId'])
            self.assertEqual(1,len(seq['processes']));self.assertFalse(seq['accounting']['complete'])

    def test_observer_rejection_still_preserves_original_rows_and_process_costs(self):
        p=copy.deepcopy(self.p);p['queries']=[dict(id='only',task=dict(source='x',maximumNodes=1))]
        # Deliberately wrong READY metadata exercises post-execution observer rejection.
        script=('import json,sys\nprint(\'{"status":"READY","java":"17"}\',flush=True)\n'
            'for line in sys.stdin:\n'
            ' r=json.loads(line);op=r["op"]\n'
            f' o=dict(profile="PRIMITIVE",generationId="seed",nativeBudget={p["nativeBudget"]!r})\n'
            ' if op=="initialize":o["status"]="INITIALIZED"\n'
            ' elif op=="load":o["status"]="LOADED"\n'
            ' else:o.update(status="RESULT",task=r["task"],solved=True,withinBudget=True,output="x",outputScore=1,nativeApiWork=1,normalizedInput="x",witness=[])\n'
            ' print(json.dumps(o),flush=True)\n')
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):self.runner.run_sequence(p,'PRIMITIVE',1,0,[sys.executable,'-u','-c',script],Path(directory))
            raw=json.loads((Path(directory)/'PRIMITIVE.b1.r0.raw.json').read_text())
            self.assertEqual(1,len(raw['rows']));self.assertEqual(1,len(raw['processes']))
            self.assertIsNotNone(raw['processes'][0]['cpu'])

    def test_model_bindings_remain_mandatory_after_relocation(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory);content=b'{}';identity=self.runner.digest(content)
            relative='models/LEGACY_SESSION.b1.r0/producer/'+identity[7:]+'.json'
            path=output/relative;path.parent.mkdir(parents=True);path.write_bytes(content)
            original='/original/evidence'
            reported=original+'/'+relative
            binding=dict(file=relative,sha256=identity,reportedPath=reported)
            seq=dict(id='LEGACY_SESSION.b1.r0',profile='LEGACY_SESSION',snapshots=[binding],operations=[dict(name='acquire',status='COMPLETE',response=dict(status='SAVED',snapshot=reported,generationId=identity))])
            self.runner.verify_models(output,seq,original)
            changed=copy.deepcopy(seq);changed['snapshots']=[]
            with self.assertRaises(ValueError):self.runner.verify_models(output,changed,original)
            changed=copy.deepcopy(seq);changed['operations'][0]['response']['snapshot']='/foreign/model.json'
            with self.assertRaises(ValueError):self.runner.verify_models(output,changed,original)

    def test_cost_based_adoption_is_rechecked_from_paid_trials(self):
        result = self.improvement_fixture()
        self.assertEqual('candidate',self.runner.verify_improvement(result,'seed',100))
        for mutate in [lambda x:x.update(activeId='foreign'),lambda x:x['attempts'][0]['paid'].update(cpuNanos=100000),
                       lambda x:x['attempts'][0]['trials'][0]['candidate'].update(solved=False),lambda x:x.update(knownWork=0)]:
            bad=copy.deepcopy(result);mutate(bad)
            with self.assertRaises(ValueError):self.runner.verify_improvement(bad,'seed',100)

    def improvement_fixture(self):
        old=dict(cost=dict(wallNanos=10,cpuNanos=8),solved=True,score=3,complete=True)
        new=dict(cost=dict(wallNanos=2,cpuNanos=1),solved=True,score=3,complete=True)
        attempt=dict(parentId='seed',candidateId='candidate',learnerDecision='PROMOTED',decision='ADOPT',knownWork=100,
            paid=dict(wallNanos=100,cpuNanos=100),trials=[dict(previous=old,candidate=new)],
            lessonGenerations=['seed','seed'],accountingComplete=True,failure='')
        result=dict(activeId='candidate',attempts=[attempt],knownWork=100,paid=dict(wallNanos=110,cpuNanos=110),accountingComplete=True,budgetExhausted=False)
        return result

    def test_promotion_samples_cannot_be_paid_less_than_their_sequential_cost(self):
        for key in ('wallNanos', 'cpuNanos'):
            bad=self.improvement_fixture()
            bad['attempts'][0]['paid'][key]=1
            with self.subTest(cost=key), self.assertRaises(ValueError):
                self.runner.verify_improvement(bad,'seed',100)

    def test_improvement_receipts_reject_impossible_costs_and_implicit_booleans(self):
        mutations=[lambda r:r['attempts'][0]['trials'][0]['candidate']['cost'].update(wallNanos=-100),
            lambda r:r['attempts'][0]['trials'][0]['candidate']['cost'].update(cpuNanos=-2),
            lambda r:r['attempts'][0]['trials'][0]['candidate'].update(complete=1),
            lambda r:r['attempts'][0]['trials'][0]['candidate'].update(solved=1),
            lambda r:r['attempts'][0].update(accountingComplete=1),
            lambda r:r['attempts'][0].update(decision='UNRECOGNIZED')]
        for index, mutate in enumerate(mutations):
            bad=self.improvement_fixture();mutate(bad)
            with self.subTest(mutation=index), self.assertRaises(ValueError):
                self.runner.verify_improvement(bad,'seed',100)

    def test_complete_setup_requires_request_and_response_receipts(self):
        p,seq=self.fixture()
        for field in ('request','response'):
            bad=copy.deepcopy(seq);bad['operations'][1].pop(field)
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.runner.account_sequence(p,bad)

    def evidence_fixture(self, output):
        from external_polynomial_comparison.run import canonical
        from external_polynomial_comparison.run_schema import _file_inventory
        p,seq=self.fixture();p=copy.deepcopy(p);p.update(profiles=['SYMPY'],blocks=[1],repetitions=2)
        sequences=[dict(copy.deepcopy(seq),id=f'SYMPY.b1.r{i}',repetition=i) for i in range(2)]
        for s in sequences:s['accounting']=self.runner.account_sequence(p,s)
        (output/'python-runtime').mkdir();files=_file_inventory(output/'python-runtime')
        python=dict(files=files,runtimeBytesHash=self.runner.digest(canonical(files)),
                    interpreter='/opt/python/bin/python3.12',commandExecutable='/opt/python/bin/python3',version='3.12.8 (fixture)')
        java=dict(runtimeBytesHash='java',classpathEntries=['runtime/entry-000','runtime/entry-001/dependency.jar'])
        root=Path('/original/evidence')
        bootstrap='import sys;sys.path.insert(0,'+repr(str(root/'python-runtime'))+');from external_polynomial_comparison.knowledge_sympy_worker import main;main()'
        metadata=dict(protocolHash=self.runner.PROTOCOL_HASH,runtimeBytesHash='java',pythonRuntimeHash=python['runtimeBytesHash'],originalOutput=str(root),
            commands=dict(java=['java',*p['javaOptions'],'-cp',':'.join(str(root/e) for e in java['classpathEntries']),self.runner.JAVA_WORKER],
                          sympy=[python['commandExecutable'],'-I','-B','-u','-c',bootstrap]))
        for name,value in [('protocol.json',p),('runtime-inventory.json',java),('python-inventory.json',python),
                           ('summary.json',self.runner.summarize(p,sequences))]:
            (output/name).write_bytes(canonical(value))
        def retain():
            for s in sequences:(output/(s['id']+'.raw.json')).write_bytes(canonical({k:v for k,v in s.items() if k!='accounting'}))
            (output/'sequences.json').write_bytes(canonical(sequences));(output/'metadata.json').write_bytes(canonical(metadata))
            inventory={k:v for k,v in _file_inventory(output).items() if k!='manifest.json'}
            (output/'manifest.json').write_bytes(canonical(inventory))
        return p,metadata,sequences,retain

    def test_offline_verification_binds_commands_to_retained_runtimes_and_frozen_options(self):
        mutations=[lambda m:m['commands'].update(java=['false'],sympy=['false']),
            lambda m:m['commands']['java'].__setitem__(1,'-Xmx8g'),
            lambda m:m['commands']['java'].__setitem__(-2,'/foreign/classes'),
            lambda m:m['commands']['java'].__setitem__(-1,'foreign.Worker'),
            lambda m:m['commands']['sympy'].__setitem__(0,'/bin/false'),
            lambda m:m['commands']['sympy'].__setitem__(-1,'print("unrelated worker")')]
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory);p,metadata,sequences,retain=self.evidence_fixture(output)
            original=copy.deepcopy(metadata)
            with patch.object(self.runner,'load_protocol',return_value=p),patch.object(self.runner,'verify_runtime'):
                retain();self.runner.verify(output)
                for index,mutate in enumerate(mutations):
                    metadata.clear();metadata.update(copy.deepcopy(original));mutate(metadata);retain()
                    with self.subTest(mutation=index),self.assertRaises(ValueError):self.runner.verify(output)

    def test_offline_verification_rejects_reordered_lifecycles_after_manifest_refresh(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory);p,metadata,sequences,retain=self.evidence_fixture(output)
            with patch.object(self.runner,'load_protocol',return_value=p),patch.object(self.runner,'verify_runtime'):
                retain();self.runner.verify(output);sequences.reverse();retain()
                with self.assertRaisesRegex(ValueError,'sequence'):self.runner.verify(output)

    def test_reused_evidence_or_receipt_is_rejected_before_launch(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory)/'evidence';receipt=output.with_name(output.name+'.command-receipt.json')
            args=SimpleNamespace(output=output,protocol=Path('protocol'),classpath=Path('classpath'))
            for existing in (output,receipt):
                original=b'{"wallNanos":123,"exitCode":0}';existing.write_bytes(original)
                with self.subTest(path=existing.name),patch.object(self.runner.subprocess,'Popen') as launch:
                    with self.assertRaisesRegex(ValueError,'exist'):self.runner.supervise(args)
                    launch.assert_not_called();self.assertEqual(original,existing.read_bytes())
                existing.unlink()



    def test_concurrent_supervisors_reserve_the_receipt_before_either_child_starts(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory)/'evidence'
            args=SimpleNamespace(output=output,protocol=Path('protocol'),classpath=Path('classpath'))
            usage=SimpleNamespace(ru_utime=.001,ru_stime=.002)
            def spawn(command):
                if launch.call_count>1: raise AssertionError('a second supervisor launched before reservation')
                with self.assertRaisesRegex(ValueError,'exist'): self.runner.supervise(args)
                return SimpleNamespace(pid=123,returncode=None)
            with patch.object(self.runner.subprocess,'Popen',side_effect=spawn) as launch, \
                    patch.object(self.runner.os,'wait4',return_value=(123,0,usage)):
                self.assertEqual(0,self.runner.supervise(args))
                launch.assert_called_once()
            receipt=json.loads(output.with_name(output.name+'.command-receipt.json').read_text())
            self.assertEqual(0,receipt['exitCode'])


if __name__=='__main__':unittest.main()
