"""Preregistered prepared-knowledge lifecycles with exact independent audits."""
import argparse
import ast
import compileall
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import py_compile
import shutil
import statistics
import subprocess
import sys
import time
from .polynomial import parse, polynomial
from .run import canonical
from .run_recurrence_knowledge import ReceiptSession
from .run_schema import CPU_SCOPE, _file_inventory, _java_provenance, snapshot_runtime, source_provenance, verify_runtime

PROTOCOL = 'config/benchmarks/knowledge-lifecycle-improvement-v1.json'
PROTOCOL_HASH = 'e36bf231ea8c8005078df94ea7c99d2326b6f3299fadd910eccb346322ccc4c2'
JAVA_WORKER = 'de.regelsuche.evolution.TaskKnowledgeLifecycleWorker'
RSS_SCOPE = 'WAIT4_PROCESS_PEAK_RESIDENT_SET;MAX_ACROSS_SEQUENTIAL_WORKERS'
LEARNED = {'LEGACY_SESSION', 'LEGACY_RELOAD', 'PREPARED_REUSE', 'MEASURED_IMPROVEMENT'}


def require(condition, message):
    if not condition: raise ValueError(message)


def natural(value):
    require(type(value) is int and value >= 0, 'nonnegative integer required')
    return value


def digest(data): return 'sha256:' + hashlib.sha256(data).hexdigest()


def load_protocol(path):
    raw = Path(path).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == PROTOCOL_HASH, 'protocol differs from preregistration')
    p = json.loads(raw)
    require(p['profiles'] == ['PRIMITIVE','LEGACY_SESSION','LEGACY_RELOAD','PREPARED_REUSE','MEASURED_IMPROVEMENT','SYMPY']
            and p['blocks'] == [1,16] and p['repetitions'] == 3 and len(p['queries']) == 8, 'registered matrix changed')
    require(len({q['id'] for q in p['queries']}) == 8, 'duplicate query IDs')
    for q in p['queries']: polynomial(q['task']['source'])
    return p


def prelude(p): return {k:p[k] for k in ('seedBlocks','improvementBatches')}


def schedule(p):
    for r in range(p['repetitions']):
        for index, blocks in enumerate(p['blocks']):
            offset = (r + index) % len(p['profiles'])
            for profile in p['profiles'][offset:] + p['profiles'][:offset]: yield profile, blocks, r


def node_count(source):
    def visit(node):
        if isinstance(node, (ast.Name, ast.Constant)): return 1
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
            return 1 if isinstance(node.operand, ast.Constant) else 2 + visit(node.operand)
        require(isinstance(node, ast.BinOp), 'arithmetic node required')
        return 1 + visit(node.left) + visit(node.right)
    polynomial(source)
    return visit(parse(source))


def audit(task, profile, response, generation):
    try:
        require(response.get('status') == 'RESULT' and response.get('profile') == profile
                and response.get('task') == task and response.get('generationId') == generation, 'source/profile/generation binding')
        require(type(response.get('solved')) is bool and type(response.get('withinBudget')) is bool, 'explicit outcomes required')
        output = response['output']
        require(polynomial(task['source']) == polynomial(output), 'false polynomial identity')
        score = node_count(output)
        require(response['solved'] == (score <= task['maximumNodes'] and response['withinBudget']), 'goal/budget inconsistency')
        if profile != 'SYMPY':
            require(not response['withinBudget'] or natural(response['nativeApiWork']) <= 200000, 'native budget exceeded')
            require(type(response['outputScore']) is int and response['outputScore'] == score, 'reported node score differs from independent count')
            require('normalizedInput' in response and isinstance(response.get('witness'), list), 'missing native path')
            identity = lambda text: ast.dump(parse(text), include_attributes=False)
            current = response['normalizedInput']
            require(identity(current) == identity(task['source']), 'normalized source syntax mismatch')
            for edge in response['witness']:
                require(identity(current) == identity(edge['source']), 'disconnected/source-rebound edge')
                require(polynomial(edge['source']) == polynomial(edge['target']), 'false selected edge')
                current = edge['target']
            require(identity(current) == identity(output), 'path endpoint mismatch')
        return dict(valid=True, solved=response['solved'])
    except (ValueError, KeyError, TypeError, RecursionError, ZeroDivisionError, OverflowError) as error:
        return dict(valid=False, solved=False, error=f'{type(error).__name__}: {error}')


def operation_names(profile, blocks):
    names = ['startup-producer','acquire','shutdown-producer','startup-query','restore'] if profile in LEARNED else ['startup-query','initialize']
    return names + [f'load-{b}' for b in range(blocks)] + ['shutdown-query']


def run_sequence(p, profile, blocks, repetition, command, output):
    started, cpu = time.perf_counter_ns(), time.process_time_ns()
    name = f'{profile}.b{blocks}.r{repetition}'
    seq = dict(id=name,profile=profile,blocks=blocks,repetition=repetition,operations=[],rows=[],processes=[],errors=[],snapshots=[])
    owned, query, generation = [], None, None
    def phase(name, fn):
        op = dict(name=name); seq['operations'].append(op); begin=time.perf_counter_ns()
        try: value=fn(op);op['status']='COMPLETE';return value
        except Exception as error: op.update(status='ERROR',error=f'{type(error).__name__}: {error}');raise
        finally: op['wallNanos']=time.perf_counter_ns()-begin
    def launch(stage, op):
        worker=ReceiptSession.__new__(ReceiptSession);owned.append((stage,worker))
        try: worker.__init__(command,output/f'{name}.{stage}.stderr.txt',p['setupTimeoutSeconds'],seq['processes'],stage)
        finally:
            op['processSpawned']=hasattr(worker,'process')
            if op['processSpawned']:op['pid']=worker.process.pid
        op['ready']=worker.ready
        require(worker.ready.get('status')=='READY','missing READY')
        if profile=='SYMPY':require(worker.ready.get('sympy')==p['sympyVersion'],'wrong SymPy version')
        return worker
    def request(worker,payload,op,status):
        op['request']=payload
        response,elapsed=worker.request(payload,p['setupTimeoutSeconds']);op.update(response=response,requestWallNanos=elapsed)
        require(response.get('status')==status and response.get('profile')==profile,'setup/load failed or profile changed')
        if profile!='SYMPY':require(response['nativeBudget']==p['nativeBudget'],'native capabilities/budget changed')
        snapshot=response.get('snapshot')
        if snapshot and status in ('SAVED','LOADED'):
            path=Path(snapshot).resolve();relative=path.relative_to(output.resolve())
            require(relative.parts[:2]==('models',name),'snapshot outside owned model directory')
            require(path.is_file() and digest(path.read_bytes())==response['generationId'],'snapshot/generation mismatch')
            binding=dict(file=relative.as_posix(),sha256=response['generationId'],reportedPath=snapshot)
            if binding not in seq['snapshots']:seq['snapshots'].append(binding)
        return response
    try:
        if profile in LEARNED:
            producer=phase('startup-producer',lambda op:launch('producer',op))
            saved=phase('acquire',lambda op:request(producer,dict(op='acquire',profile=profile,seedBlocks=p['seedBlocks'],
                directory=str((output/'models'/name/'producer').resolve())),op,'SAVED'))
            phase('shutdown-producer',lambda op:producer.close(graceful=True))
            query=phase('startup-query',lambda op:launch('query',op))
            phase('restore',lambda op:request(query,dict(op='restore',profile=profile,snapshot=saved['snapshot'],
                directory=str((output/'models'/name/'query').resolve()),improvementBatches=p['improvementBatches'],futureQueries=blocks*len(p['queries'])),op,'CONFIGURED'))
        else:
            query=phase('startup-query',lambda op:launch('query',op))
            payload=dict(op='initialize',prelude=prelude(p)) if profile=='SYMPY' else dict(op='initialize',profile=profile)
            phase('initialize',lambda op:request(query,payload,op,'INITIALIZED'))
    except Exception as error:seq['errors'].append(f'{type(error).__name__}: {error}');query=None
    try:
        for block in range(blocks):
            generation=None
            if query is not None:
                try:
                    loaded=phase(f'load-{block}',lambda op:request(query,dict(op='load'),op,'LOADED'))
                    generation=loaded['generationId']
                except Exception as error:seq['errors'].append(f'{type(error).__name__}: {error}');query=None
            else:seq['operations'].append(dict(name=f'load-{block}',status='BLOCKED',wallNanos=0))
            for case in p['queries']:
                response,elapsed=(dict(status='BLOCKED'),0) if query is None else query.request(dict(op='run',task=case['task']),p['queryTimeoutSeconds'])
                begin=time.perf_counter_ns();judgment=audit(case['task'],profile,response,generation);audit_wall=time.perf_counter_ns()-begin
                if elapsed+audit_wall>p['queryTimeoutSeconds']*10**9:judgment=dict(valid=False,solved=False,error='query deadline exceeded')
                seq['rows'].append(dict(index=len(seq['rows']),case=case['id'],task=case['task'],generationId=generation,response=response,
                    judgment=judgment,requestWallNanos=elapsed,auditWallNanos=audit_wall))
    finally:
        for stage,worker in owned:
            if hasattr(worker,'process') and not any(op['name']=='shutdown-'+stage for op in seq['operations']):
                try:phase('shutdown-'+stage,lambda op,worker=worker:worker.close(graceful=True))
                except Exception as error:seq['errors'].append(f'{type(error).__name__}: {error}')
        seq.update(wallNanos=time.perf_counter_ns()-started,controllerCpuNanos=time.process_time_ns()-cpu)
    seq['accounting']=account_sequence(p,seq)
    return seq


def verify_improvement(result,seed,horizon):
    current=seed;work=0;wall=0;cpu=0
    for attempt in result['attempts']:
        require(attempt['parentId']==current and all(x==current for x in attempt['lessonGenerations']),'improvement parent/lesson binding')
        work+=natural(attempt['knownWork']);paid=attempt['paid'];wall+=natural(paid['wallNanos'])
        if paid['cpuNanos']>=0:cpu+=natural(paid['cpuNanos'])
        decision=attempt['decision'];trials=attempt['trials']
        if decision in ('ADOPT','NO_GAIN','QUALITY_REGRESSION','INCOMPLETE') and trials:
            complete=paid['cpuNanos']>=0 and all(s['complete'] and s['cost']['cpuNanos']>=0 for t in trials for s in t.values())
            quality=all((not t['previous']['solved'] or t['candidate']['solved']) and t['candidate']['score']<=t['previous']['score'] for t in trials)
            deltas={key:sum(t['previous']['cost'][key]-t['candidate']['cost'][key] for t in trials) for key in ('wallNanos','cpuNanos')}
            expected='INCOMPLETE' if not complete else 'QUALITY_REGRESSION' if not quality else (
                'ADOPT' if all(deltas[key]*horizon>paid[key]*len(trials) for key in deltas) else 'NO_GAIN')
            require(decision==expected,'adoption differs from actual paid trials')
        if decision=='ADOPT':
            require(attempt['accountingComplete'] and attempt['learnerDecision']=='PROMOTED' and trials and attempt['candidateId'],'unproved/incomplete adoption')
            current=attempt['candidateId']
        if decision=='NO_CANDIDATE':require(attempt['learnerDecision']!='PROMOTED','promoted learner hidden as no candidate')
    require(result['activeId']==current and natural(result['knownWork'])==work,'active generation or paid work ledger mismatch')
    require(natural(result['paid']['wallNanos'])>=wall,'improvement omits paid wall time')
    if result['paid']['cpuNanos']>=0:require(result['paid']['cpuNanos']>=cpu,'improvement omits paid CPU')
    return current


def verify_operation(p,seq,op):
    name=op['name'];r=op['response'];request=op['request'];profile=seq['profile']
    expected='SAVED' if name=='acquire' else 'CONFIGURED' if name=='restore' else 'INITIALIZED' if name=='initialize' else 'LOADED'
    require(r.get('status')==expected and r.get('profile')==profile,'operation status/profile mismatch')
    require(request.get('op')==('load' if name.startswith('load-') else name),'operation input mismatch')
    if name=='initialize':
        if profile=='SYMPY':
            require(request==dict(op='initialize',prelude=prelude(p)) and r['preludeHash']==digest(canonical(prelude(p))),'teaching prelude differs')
        else:require(request==dict(op='initialize',profile=profile),'primitive initialization mismatch')
    elif name=='acquire':
        require(request['profile']==profile and request['seedBlocks']==p['seedBlocks'],'seed acquisition input mismatch')
    elif name=='restore':
        require(request['profile']==profile and request['improvementBatches']==p['improvementBatches']
                and request['futureQueries']==seq['blocks']*len(p['queries']),'improvement input/horizon mismatch')
    elif name.startswith('load-'):
        require(request==dict(op='load'),'load input changed')
        improvement=r.get('improvement')
        if improvement is not None:
            require(profile=='MEASURED_IMPROVEMENT' and name=='load-0','unexpected online learning')
            require(verify_improvement(improvement,r['seedId'],seq['blocks']*len(p['queries']))==r['generationId'],'improvement generation mismatch')
            require(improvement['paid']['wallNanos']<=op['wallNanos'],'load omits paid improvement time')
        if profile=='MEASURED_IMPROVEMENT' and name=='load-0':require(improvement is not None,'missing improvement ledger')
    if profile=='SYMPY':require(r['generationId']=='STATELESS','stateless procedure changed')


def account_sequence(p, seq):
    profile=seq['profile'];require(profile in p['profiles'],'unknown profile')
    expected=operation_names(profile,seq['blocks']);observed=[o['name'] for o in seq['operations']]
    require(len(observed)==len(set(observed)) and all(n in expected for n in observed),'duplicate/unexpected operation')
    stages=[]
    for op in seq['operations']:
        natural(op['wallNanos'])
        if op['name'].startswith('startup-'):
            require(type(op.get('processSpawned')) is bool,'missing process ownership state')
            if op['processSpawned']:require(natural(op['pid'])>0,'missing PID');stages.append((op['name'][8:],op['pid']))
            else:require('pid' not in op and op['status']=='ERROR','false spawn identity')
        if op['status']=='COMPLETE' and op['name'].startswith('startup-'):
            require(op['ready'].get('status')=='READY','missing READY binding')
            if profile=='SYMPY':require(op['ready'].get('sympy')==p['sympyVersion'],'wrong SymPy')
            else:require(str(op['ready'].get('java','')).startswith('25'),'Java25 worker required')
        if op['status']=='COMPLETE' and 'response' in op:
            verify_operation(p,seq,op)
            r=op['response']
            if profile!='SYMPY':require(r['nativeBudget']==p['nativeBudget'],'changed native budget')
    require([(r['stage'],r['pid']) for r in seq['processes']]==stages,'missing/duplicated process receipts')
    require(len({pid for _,pid in stages})==len(stages),'duplicated process identity')
    resources=True;worker_cpu=0;peaks=[]
    for receipt in seq['processes']:
        require(type(receipt['exitCode']) is int and receipt['termination'] in ('GRACEFUL_EOF','FORCED'),'invalid exit receipt')
        resources &= receipt['exitCode']==0 and receipt['termination']=='GRACEFUL_EOF'
        usage,rss=receipt['cpu'],receipt['peakRssBytes']
        if usage is None or rss is None:require(receipt.get('receiptError'),'unexplained missing resource');resources=False
        if usage is not None:
            require(usage['scope']==CPU_SCOPE and natural(usage['totalNanos'])==natural(usage['userNanos'])+natural(usage['systemNanos']),'CPU partition/scope')
            worker_cpu+=usage['totalNanos']
        if rss is not None:require(natural(rss)>0 and receipt['rssScope']==RSS_SCOPE,'RSS measurement/scope');peaks.append(rss)
    completed=observed==expected and all(o['status']=='COMPLETE' for o in seq['operations'])
    require(completed or seq['errors'],'incomplete operations have no failure receipt')
    require(len(seq['rows'])==seq['blocks']*len(p['queries']),'incomplete query matrix')
    paid=sum(o['wallNanos'] for o in seq['operations']);valid=True;solved=0
    loads={o['name']:o['response']['generationId'] for o in seq['operations'] if o['name'].startswith('load-') and o['status']=='COMPLETE'}
    for index,row in enumerate(seq['rows']):
        case=p['queries'][index%len(p['queries'])]
        require(type(row['index']) is int and row['index']==index and row['case']==case['id'] and row['task']==case['task'],'case/source/index binding')
        generation=loads.get('load-'+str(index//len(p['queries'])))
        require(row['generationId']==generation,'query generation differs from actual load')
        actual=audit(case['task'],profile,row['response'],generation)
        elapsed=natural(row['requestWallNanos'])+natural(row['auditWallNanos']);paid+=elapsed
        if elapsed>p['queryTimeoutSeconds']*10**9:actual=dict(valid=False,solved=False,error='query deadline exceeded')
        require(row['judgment']==actual,'retained independent judgment mismatch');valid &= actual['valid'];solved+=actual['solved']
    require(natural(seq['wallNanos'])>=paid,'unpaid lifecycle/query/audit time')
    controller=natural(seq['controllerCpuNanos'])
    return dict(complete=bool(completed and resources and valid and not seq['errors']),solved=solved,queries=len(seq['rows']),
        workerCpuNanos=worker_cpu if resources else None,fullCpuNanos=worker_cpu+controller if resources else None,
        peakWorkerRssBytes=max(peaks) if resources and peaks else None)


def verify_models(output,seq,original_output):
    bindings=seq['snapshots'];by_path={b['reportedPath']:b for b in bindings}
    require(len(by_path)==len(bindings),'duplicate model binding')
    for binding in bindings:
        relative=Path(binding['file']);require(not relative.is_absolute() and '..' not in relative.parts and relative.parts[:2]==('models',seq['id']),'model outside sequence')
        require(binding['reportedPath']==str(Path(original_output)/relative),'reported model path mismatch')
        require(digest((output/relative).read_bytes())==binding['sha256'] and relative.name==binding['sha256'][7:]+'.json','model bytes/content address changed')
    saved=None
    for op in seq['operations']:
        if op['status']!='COMPLETE':continue
        r=op.get('response',{})
        if op['name']=='acquire' or (op['name'].startswith('load-') and seq['profile'] in LEARNED):
            require(r.get('snapshot') in by_path and by_path[r['snapshot']]['sha256']==r.get('generationId'),'mandatory model/generation binding missing')
        if op['name']=='acquire':saved=r
        if op['name']=='acquire' and 'request' in op:
            require(Path(op['request']['directory'])==Path(r['snapshot']).parent,'saved snapshot differs from acquisition directory')
        if op['name']=='restore':
            require(saved is not None and op['request']['snapshot']==saved['snapshot'],'restore input differs from acquired model')
        if op['name'].startswith('load-') and seq['profile'] in LEARNED:
            require(saved is not None and r['seedId']==saved['generationId'],'fresh consumer seed differs from acquired model')


def paired(candidate, reference):
    matched=candidate['accounting']['complete'] and reference['accounting']['complete']
    matched &= [r['judgment']['solved'] for r in candidate['rows']]==[r['judgment']['solved'] for r in reference['rows']]
    if matched:
        matched=all(node_count(a['response']['output'])<=node_count(b['response']['output']) for a,b in zip(candidate['rows'],reference['rows']))
    return dict(qualityMatched=bool(matched),wallRatio=candidate['wallNanos']/reference['wallNanos'] if matched else None)


def summarize(p,sequences):
    groups=[];pairs=[];indexed={(s['profile'],s['blocks'],s['repetition']):s for s in sequences}
    for profile in p['profiles']:
        for blocks in p['blocks']:
            group=[indexed[profile,blocks,r] for r in range(p['repetitions'])];complete=all(s['accounting']['complete'] for s in group)
            groups.append(dict(profile=profile,blocks=blocks,complete=complete,solved=[s['accounting']['solved'] for s in group],
                wallSeconds=statistics.median(s['wallNanos']/1e9 for s in group),
                fullCpuSeconds=statistics.median(s['accounting']['fullCpuNanos']/1e9 for s in group) if complete else None,
                peakWorkerRssMiB=statistics.median(s['accounting']['peakWorkerRssBytes']/2**20 for s in group) if complete else None))
            references=['SYMPY'] if profile!='SYMPY' else []
            if profile in ('PREPARED_REUSE','MEASURED_IMPROVEMENT'):references+=['LEGACY_SESSION','LEGACY_RELOAD']
            for reference in references:
                for s in group:pairs.append(dict(profile=profile,reference=reference,blocks=blocks,repetition=s['repetition'],**paired(s,indexed[reference,blocks,s['repetition']])))
    return dict(groups=groups,pairs=pairs)


def snapshot_python(output):
    destination=output/'python-runtime';destination.mkdir()
    for name in ('sympy','mpmath'):
        spec=importlib.util.find_spec(name);require(spec is not None and spec.submodule_search_locations,'missing pinned package')
        shutil.copytree(Path(spec.origin).parent,destination/name,ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
    shutil.copytree(Path(__file__).parent,destination/'external_polynomial_comparison',ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
    require(compileall.compile_dir(destination,quiet=2,force=True,invalidation_mode=py_compile.PycInvalidationMode.CHECKED_HASH),'bytecode preparation failed')
    files=_file_inventory(destination);interpreter=Path(sys.executable).resolve()
    receipt=dict(files=files,runtimeBytesHash=digest(canonical(files)),interpreter=str(interpreter),interpreterHash=digest(interpreter.read_bytes()),version=sys.version,
        bytecodePolicy='CHECKED_HASH_PRECOMPILED;READ_ENABLED_WRITE_DISABLED')
    (output/'python-inventory.json').write_bytes(canonical(receipt));return receipt


def verify(output):
    output=Path(output);inventory=_file_inventory(output);inventory.pop('manifest.json')
    require(inventory==json.loads((output/'manifest.json').read_text()),'evidence inventory changed')
    p=load_protocol(output/'protocol.json');java=json.loads((output/'runtime-inventory.json').read_text());verify_runtime(output,java)
    python=json.loads((output/'python-inventory.json').read_text());files=_file_inventory(output/'python-runtime')
    require(files==python['files'] and digest(canonical(files))==python['runtimeBytesHash'],'Python runtime bytes changed')
    metadata=json.loads((output/'metadata.json').read_text())
    require(metadata['protocolHash']==PROTOCOL_HASH and metadata['runtimeBytesHash']==java['runtimeBytesHash'] and metadata['pythonRuntimeHash']==python['runtimeBytesHash'],'metadata/runtime binding')
    sequences=json.loads((output/'sequences.json').read_text());actual=[(s['profile'],s['blocks'],s['repetition']) for s in sequences]
    require(len(actual)==len(set(actual)) and set(actual)==set(schedule(p)),'incomplete/duplicated sequence matrix')
    for seq in sequences:
        require(seq['id']==f"{seq['profile']}.b{seq['blocks']}.r{seq['repetition']}",'sequence identity mismatch')
        require(seq['accounting']==account_sequence(p,seq),'accounting summary mismatch')
        verify_models(output,seq,metadata['originalOutput'])
    summary=summarize(p,sequences);require(summary==json.loads((output/'summary.json').read_text()),'summary mismatch');return summary


def execute(args):
    p=load_protocol(args.protocol);output=args.output.resolve();provenance=source_provenance(Path.cwd())
    output.mkdir(parents=True,exist_ok=False);(output/'protocol.json').write_bytes(args.protocol.read_bytes())
    runtime=snapshot_runtime(args.classpath.read_text().strip(),output);python=snapshot_python(output)
    java=['java',*p['javaOptions'],'-cp',os.pathsep.join(str(output/x) for x in runtime['classpathEntries']),JAVA_WORKER]
    bootstrap='import sys;sys.path.insert(0,'+repr(str(output/'python-runtime'))+');from external_polynomial_comparison.knowledge_sympy_worker import main;main()'
    sympy=[sys.executable,'-I','-B','-u','-c',bootstrap]
    metadata=dict(provenance,protocolHash=PROTOCOL_HASH,runtimeBytesHash=runtime['runtimeBytesHash'],pythonRuntimeHash=python['runtimeBytesHash'],
        originalOutput=str(output),java=_java_provenance(),commands=dict(java=java,sympy=sympy),host=dict(platform=platform.platform(),
        affinityCpus=len(os.sched_getaffinity(0)) if hasattr(os,'sched_getaffinity') else None,
        cpuMax=Path('/sys/fs/cgroup/cpu.max').read_text().strip() if Path('/sys/fs/cgroup/cpu.max').exists() else None,
        memoryMax=Path('/sys/fs/cgroup/memory.max').read_text().strip() if Path('/sys/fs/cgroup/memory.max').exists() else None))
    sequences=[]
    for profile,blocks,repetition in schedule(p):
        seq=run_sequence(p,profile,blocks,repetition,sympy if profile=='SYMPY' else java,output);sequences.append(seq)
        (output/'sequences.json').write_bytes(canonical(sequences))
        print(json.dumps(dict(profile=profile,blocks=blocks,repetition=repetition,accounting=seq['accounting'])),flush=True)
    (output/'metadata.json').write_bytes(canonical(metadata));(output/'summary.json').write_bytes(canonical(summarize(p,sequences)))
    (output/'manifest.json').write_bytes(canonical(_file_inventory(output)));summary=verify(output)
    require(all(g['complete'] for g in summary['groups']),'incomplete experiment; all observed outcomes retained')


def supervise(args):
    require(hasattr(os,'wait4'),'POSIX wait4 required')
    command=[sys.executable,'-B','-m','external_polynomial_comparison.run_knowledge_lifecycle','--execute-child','--protocol',str(args.protocol),'--classpath',str(args.classpath),'--output',str(args.output)]
    start,cpu=time.perf_counter_ns(),time.process_time_ns();process=subprocess.Popen(command)
    _,status,usage=os.wait4(process.pid,0);process.returncode=os.waitstatus_to_exitcode(status)
    receipt=dict(command=command,exitCode=process.returncode,wallNanos=time.perf_counter_ns()-start,supervisorCpuNanos=time.process_time_ns()-cpu,
        childTreeCpuNanos=round((usage.ru_utime+usage.ru_stime)*1e9),scope='BEFORE_CHILD_SPAWN_THROUGH_REAP;EXCLUDES_SUPERVISOR_IMPORTS_AND_FINAL_RECEIPT_WRITE')
    args.output.with_name(args.output.name+'.command-receipt.json').write_bytes(canonical(receipt));return process.returncode


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--protocol',type=Path,default=Path(PROTOCOL));parser.add_argument('--classpath',type=Path)
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--verify',action='store_true');parser.add_argument('--execute-child',action='store_true',help=argparse.SUPPRESS)
    args=parser.parse_args()
    if args.verify:
        summary=verify(args.output);print(json.dumps(dict(evidenceVerified=True,complete=all(g['complete'] for g in summary['groups']))))
    elif args.classpath is None:parser.error('--classpath is required')
    elif args.execute_child:execute(args)
    else:raise SystemExit(supervise(args))


if __name__=='__main__':main()
