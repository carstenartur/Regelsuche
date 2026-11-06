"""Frozen historical goals, fresh native/SymPy lifecycles and independently verified evidence."""
import argparse
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
from .historical_judge import audit_task, check_payload, natural, require
from .run import canonical
from .run_recurrence_knowledge import ReceiptSession
from .run_schema import CPU_SCOPE, _file_inventory, _java_provenance, snapshot_runtime, source_provenance, verify_runtime

PROTOCOL = 'config/benchmarks/historical-sympy-v1.json'
PROTOCOL_HASH = 'fcd686dabd14c6bdf5318602c37b0915134a010a0ae5acc85dbe28fc48abbb1a'
JAVA_WORKER = 'de.regelsuche.evolution.HistoricalComparisonWorker'
RSS_SCOPE = 'WAIT4_PROCESS_PEAK_RESIDENT_SET;MAX_ACROSS_SEQUENTIAL_WORKERS'


def digest(data):
    return 'sha256:' + hashlib.sha256(data).hexdigest()


def load_protocol(path):
    raw = Path(path).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == PROTOCOL_HASH, 'protocol differs from pre-execution freeze')
    p = json.loads(raw)
    require(p['profiles'] == ['NATIVE_BASE', 'NATIVE_LEARNED', 'SYMPY'] and p['lengths'] == [1, 18, 72]
            and p['repetitions'] == 3 and len(p['cases']) == 18, 'frozen experiment shape')
    require(len({c['id'] for c in p['cases']}) == len(p['cases']), 'duplicate case IDs')
    for case in p['cases']:
        check_payload(case['task'])
    return p


def schedule(p):
    for repetition in range(p['repetitions']):
        for index, length in enumerate(p['lengths']):
            offset = (repetition + index) % len(p['profiles'])
            for profile in p['profiles'][offset:] + p['profiles'][:offset]:
                yield profile, length, repetition


def operations(profile):
    return (['startup-producer', 'initialize', 'shutdown-producer', 'startup-query', 'restore', 'shutdown-query']
            if profile == 'NATIVE_LEARNED' else ['startup-query', 'initialize', 'shutdown-query'])


def validate_rows(p, sequence):
    require(len(sequence['rows']) == sequence['length'], 'incomplete query matrix')
    for index, row in enumerate(sequence['rows']):
        case = p['cases'][index % len(p['cases'])]
        require(type(row['index']) is int and row['index'] == index and row['case'] == case['id']
                and row['task'] == case['task'], 'case/index is not bound to the frozen input')


def setup(p, profile, response, status, generation=None):
    require(response.get('status') == status and response.get('profile') == profile, 'setup failed or changed profile')
    require(response.get('preludeHash') == digest(canonical(p['prelude'])), 'teaching prelude differs from protocol')
    actual = response.get('generationId')
    require(isinstance(actual, str) and actual and (generation is None or generation == actual), 'knowledge generation changed')
    if profile != 'SYMPY':
        require(response['structuralBudget'] == p['nativeStructuralBudget'], 'native structural budget changed')
        for key in ('acquisitionApiWork', 'restorationApiWork', 'schemaCount', 'recurrenceCount', 'recurrenceFamilyCount'):
            natural(response[key])
        if profile == 'NATIVE_BASE':
            require(not any(response[key] for key in ('acquisitionApiWork', 'restorationApiWork', 'schemaCount',
                                                      'recurrenceCount', 'recurrenceFamilyCount')),
                    'baseline acquired unused knowledge')
    else:
        require(actual == 'STATELESS', 'stateless SymPy knowledge changed')
    return actual


def judge(p, profile, task, response, generation):
    try:
        require(response.get('profile') == profile and response.get('generationId') == generation, 'query changed profile/generation')
        if profile != 'SYMPY':
            work = natural(response['nativeApiWork'])
            cap = task['recurrence']['workBudget'] if task['kind'] == 'INVARIANT' else p['nativeStructuralBudget'][-1]
            require(not response.get('withinBudget') or work <= cap, 'native API work exceeds declared budget')
        if profile == 'NATIVE_BASE':
            require(response.get('reused') is False, 'baseline claims learned reuse')
        return audit_task(task, response)
    except (ValueError, KeyError, TypeError, RecursionError, ZeroDivisionError) as error:
        return dict(valid=False, solved=False, error=f'{type(error).__name__}: {error}')


def run_sequence(p, profile, length, repetition, command, output):
    started, cpu = time.perf_counter_ns(), time.process_time_ns()
    name = f'{profile}.n{length}.r{repetition}'
    seq = dict(id=name, profile=profile, length=length, repetition=repetition, operations=[], rows=[], processes=[], errors=[])
    owned, query_worker, generation = [], None, None

    def phase(name, fn):
        record = dict(name=name); seq['operations'].append(record)
        begin = time.perf_counter_ns()
        try:
            result = fn(record); record['status'] = 'COMPLETE'; return result
        except Exception as error:
            record.update(status='ERROR', error=f'{type(error).__name__}: {error}'); raise
        finally:
            record['wallNanos'] = time.perf_counter_ns() - begin

    def launch(stage, record):
        worker = ReceiptSession.__new__(ReceiptSession); owned.append((stage, worker))
        try:
            worker.__init__(command, output/f'{name}.{stage}.stderr.txt', p['setupTimeoutSeconds'], seq['processes'], stage)
        finally:
            record['processSpawned'] = hasattr(worker, 'process')
            if record['processSpawned']: record['pid'] = worker.process.pid
        record['ready'] = worker.ready
        require(worker.ready.get('status') == 'READY', 'missing READY')
        if profile == 'SYMPY':
            require(worker.ready.get('sympy') == p['sympyVersion'], 'wrong SymPy version')
        return worker

    def request(worker, payload, record, status, expected=None):
        record['request'] = payload
        response, elapsed = worker.request(payload, p['setupTimeoutSeconds'])
        record.update(response=response, requestWallNanos=elapsed)
        setup(p, profile, response, status, expected)
        return response

    try:
        stage = 'producer' if profile == 'NATIVE_LEARNED' else 'query'
        worker = phase('startup-'+stage, lambda r: launch(stage, r))
        directory = (output/'models'/name).resolve()
        if profile == 'NATIVE_LEARNED': directory.mkdir(parents=True)
        if profile == 'NATIVE_LEARNED':
            def initialize(r):
                response = request(worker, dict(op='initialize', profile=profile,
                    prelude=p['prelude'], directory=str(directory)), r, 'SAVED')
                path = Path(response['snapshot']).resolve()
                require(path.parent == directory and path.is_file(), 'snapshot outside owned directory')
                snapshot_hash = digest(path.read_bytes())
                require(response['snapshotHash'] == snapshot_hash == response['generationId'], 'saved model binding mismatch')
                seq['snapshot'] = dict(file=path.relative_to(output.resolve()).as_posix(), sha256=snapshot_hash)
                return response
        else:
            initialize = lambda r: request(worker, dict(op='initialize', profile=profile,
                prelude=p['prelude'], directory=str(directory)), r, 'INITIALIZED')
        initial = phase('initialize', initialize)
        generation = initial['generationId']
        if profile == 'NATIVE_LEARNED':
            snapshot_hash = seq['snapshot']['sha256']
            phase('shutdown-producer', lambda r: worker.close(graceful=True))
            query_worker = phase('startup-query', lambda r: launch('query', r))
            def restore(r):
                response = request(query_worker, dict(op='restore', profile=profile,
                    prelude=p['prelude'], snapshot=str(output.resolve()/seq['snapshot']['file'])), r, 'RESTORED', generation)
                require(response['snapshotHash'] == snapshot_hash, 'fresh consumer changed snapshot bytes')
                return response
            phase('restore', restore)
        else:
            query_worker = worker
    except Exception as error:
        seq['errors'].append(f'{type(error).__name__}: {error}'); query_worker = None
    try:
        for index in range(length):
            case = p['cases'][index % len(p['cases'])]
            response, elapsed = (dict(status='BLOCKED_WORKER_UNAVAILABLE'), 0) if query_worker is None else query_worker.request(
                dict(op='run', task=case['task']), p['queryTimeoutSeconds'])
            audit_start = time.perf_counter_ns()
            judgment = judge(p, profile, case['task'], response, generation)
            audit_wall = time.perf_counter_ns() - audit_start
            if elapsed + audit_wall > p['queryTimeoutSeconds']*10**9:
                judgment = dict(valid=False, solved=False, error='end-to-end query deadline exceeded')
            seq['rows'].append(dict(index=index, case=case['id'], task=case['task'], response=response,
                judgment=judgment, requestWallNanos=elapsed, auditWallNanos=audit_wall))
    finally:
        for stage, worker in owned:
            if not any(op['name'] == 'shutdown-'+stage for op in seq['operations']) and hasattr(worker, 'process'):
                try: phase('shutdown-'+stage, lambda r, worker=worker: worker.close(graceful=True))
                except Exception as error: seq['errors'].append(f'{type(error).__name__}: {error}')
        seq.update(wallNanos=time.perf_counter_ns()-started, controllerCpuNanos=time.process_time_ns()-cpu)
    seq['accounting'] = account_sequence(p, seq)
    return seq


def account_sequence(p, seq):
    validate_rows(p, seq)
    wall, controller = natural(seq['wallNanos']), natural(seq['controllerCpuNanos'])
    expected = operations(seq['profile']); observed = [o['name'] for o in seq['operations']]
    require(len(observed) == len(set(observed)) and all(name in expected for name in observed), 'unexpected lifecycle operation')
    completed = observed == expected and all(o['status'] == 'COMPLETE' for o in seq['operations'])
    require(completed or seq['errors'], 'incomplete lifecycle has no retained failure')
    spawned = []
    for op in seq['operations']:
        if not op['name'].startswith('startup-'): continue
        # Older successful READY records already carry a PID. Failed startups
        # must explicitly distinguish spawn failure from failure after spawning.
        did_spawn = op.get('processSpawned', True if op['status'] == 'COMPLETE' else None)
        require(type(did_spawn) is bool and (did_spawn or op['status'] == 'ERROR'), 'missing startup ownership state')
        if did_spawn:
            require(natural(op['pid']) > 0, 'spawned process has no identity')
            spawned.append((op['name'].split('-')[1], op['pid']))
        else:
            require('pid' not in op, 'unspawned operation claims a process')
    require([(r['stage'], r['pid']) for r in seq['processes']] == spawned, 'missing/surplus or misbound process receipts')
    stage_pids = {r['stage']: r['pid'] for r in seq['processes']}
    pids, resources, total_cpu, peaks = set(), True, 0, []
    for receipt in seq['processes']:
        pid = natural(receipt['pid'])
        require(pid and pid not in pids, 'invalid/duplicated process identity'); pids.add(pid)
        require(type(receipt['exitCode']) is int and receipt['termination'] in ('GRACEFUL_EOF', 'FORCED'), 'process exit receipt')
        resources &= receipt['exitCode'] == 0 and receipt['termination'] == 'GRACEFUL_EOF'
        usage, rss = receipt['cpu'], receipt['peakRssBytes']
        if usage is None or rss is None:
            require(receipt.get('receiptError'), 'unexplained absent resource measurement'); resources = False
        if usage is not None:
            require(usage['scope'] == CPU_SCOPE and natural(usage['totalNanos']) == natural(usage['userNanos'])+natural(usage['systemNanos']), 'worker CPU partition/scope')
            total_cpu += usage['totalNanos']
        if rss is not None:
            require(natural(rss) > 0 and receipt['rssScope'] == RSS_SCOPE, 'RSS scope/measurement'); peaks.append(rss)
    generation = None
    for op in seq['operations']:
        natural(op['wallNanos'])
        if op['status'] != 'COMPLETE': continue
        if op['name'].startswith('startup'):
            require(op['pid'] == stage_pids.get(op['name'].split('-')[1]) and op['ready'].get('status') == 'READY', 'startup/process receipt binding')
            if seq['profile'] == 'SYMPY': require(op['ready'].get('sympy') == p['sympyVersion'], 'SymPy version mismatch')
        elif op['name'] in ('initialize', 'restore'):
            request = op['request']
            require(request.get('op') == op['name'] and request.get('profile') == seq['profile']
                    and request.get('prelude') == p['prelude'], 'setup input/prelude binding')
            status = 'RESTORED' if op['name'] == 'restore' else 'SAVED' if seq['profile'] == 'NATIVE_LEARNED' else 'INITIALIZED'
            generation = setup(p, seq['profile'], op['response'], status, generation)
    paid = sum(o['wallNanos'] for o in seq['operations'])
    valid = True; solved = 0
    for row in seq['rows']:
        elapsed = natural(row['requestWallNanos'])+natural(row['auditWallNanos']); paid += elapsed
        actual = judge(p, seq['profile'], row['task'], row['response'], generation)
        if elapsed > p['queryTimeoutSeconds']*10**9:
            actual = dict(valid=False, solved=False, error='end-to-end query deadline exceeded')
        require(row['judgment'] == actual, 'retained judgment differs from independent audit')
        valid &= actual['valid']; solved += actual['solved']
    require(wall >= paid, 'sequence wall omits lifecycle/query/audit intervals')
    complete = bool(completed and resources and valid and not seq['errors'])
    return dict(complete=complete, solved=solved, queries=len(seq['rows']), workerCpuNanos=total_cpu if resources else None,
        fullCpuNanos=total_cpu+controller if resources else None, peakWorkerRssBytes=max(peaks) if peaks and resources else None)


def pair(native, reference):
    matched = (native['accounting']['complete'] and reference['accounting']['complete']
        and [r['judgment']['solved'] for r in native['rows']] == [r['judgment']['solved'] for r in reference['rows']])
    return dict(qualityMatched=bool(matched), wallRatioNativeToSympy=native['wallNanos']/reference['wallNanos'] if matched else None)


def summarize(p, sequences):
    groups, comparisons, quality = [], [], []
    indexed = {(s['profile'], s['length'], s['repetition']): s for s in sequences}
    for profile in p['profiles']:
        for length in p['lengths']:
            group = [indexed[profile, length, r] for r in range(p['repetitions'])]
            complete = all(s['accounting']['complete'] for s in group)
            groups.append(dict(profile=profile, length=length, complete=complete,
                solved=[s['accounting']['solved'] for s in group], wallSeconds=statistics.median(s['wallNanos']/1e9 for s in group),
                fullCpuSeconds=statistics.median(s['accounting']['fullCpuNanos']/1e9 for s in group) if complete else None,
                peakWorkerRssMiB=statistics.median(s['accounting']['peakWorkerRssBytes']/2**20 for s in group) if complete else None))
            if profile != 'SYMPY':
                for s in group:
                    comparisons.append(dict(profile=profile, length=length, repetition=s['repetition'],
                        **pair(s, indexed['SYMPY', length, s['repetition']])))
        for case in p['cases']:
            rows = [r for s in sequences if s['profile'] == profile and s['length'] == len(p['cases'])
                    for r in s['rows'] if r['case'] == case['id']]
            quality.append(dict(profile=profile, case=case['id'], role=case['role'],
                solved=sum(r['judgment']['solved'] for r in rows), valid=sum(r['judgment']['valid'] for r in rows), attempts=len(rows)))
    return dict(groups=groups, comparisons=comparisons, quality=quality, notMeasured=p.get('notMeasured', []))


def snapshot_python(output):
    destination = output/'python-runtime'; destination.mkdir()
    for name in ('sympy', 'mpmath'):
        spec = importlib.util.find_spec(name)
        require(spec is not None and spec.submodule_search_locations, 'missing pinned Python package')
        shutil.copytree(Path(spec.origin).parent, destination/name, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    shutil.copytree(Path(__file__).parent, destination/'external_polynomial_comparison',
                    ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    # Prepare ordinary readable bytecode once, like the prebuilt Java class files.
    # Workers use -B to prevent changing retained bytes, not to deny cache reads.
    require(compileall.compile_dir(destination, quiet=2, force=True,
        invalidation_mode=py_compile.PycInvalidationMode.CHECKED_HASH), 'Python runtime bytecode preparation failed')
    files = _file_inventory(destination)
    interpreter = Path(sys.executable).resolve()
    receipt = dict(files=files, runtimeBytesHash=digest(canonical(files)), interpreter=str(interpreter),
                   interpreterSha256=digest(interpreter.read_bytes()), version=sys.version,
                   bytecodePolicy='PRECOMPILED_CHECKED_HASH;READ_ENABLED_WRITE_DISABLED_IN_WORKERS')
    (output/'python-inventory.json').write_bytes(canonical(receipt))
    return receipt


def verify_metadata(metadata, python, java):
    require(metadata['protocolHash'] == PROTOCOL_HASH and metadata['pythonRuntimeHash'] == python['runtimeBytesHash']
            and metadata['runtimeBytesHash'] == java['runtimeBytesHash'], 'metadata/runtime binding mismatch')


def verify_snapshot(output, sequence):
    initial = [op for op in sequence['operations'] if op['name'] == 'initialize' and op['status'] == 'COMPLETE']
    if sequence['profile'] == 'NATIVE_LEARNED' and initial:
        require('snapshot' in sequence, 'saved learned setup omitted retained snapshot binding')
    if 'snapshot' not in sequence:
        return
    require(sequence['profile'] == 'NATIVE_LEARNED' and len(initial) == 1, 'snapshot without learned save')
    path = Path(sequence['snapshot']['file'])
    require(not path.is_absolute() and '..' not in path.parts and path.parts[:1] == ('models',), 'snapshot path outside evidence')
    saved = Path(initial[0]['response']['snapshot'])
    directory = Path(initial[0]['request']['directory'])
    name = f"{sequence['profile']}.n{sequence['length']}.r{sequence['repetition']}"
    require(directory.is_absolute() and '..' not in directory.parts and saved.is_absolute()
            and saved.parent == directory and directory.parts[-2:] == ('models', name)
            and path == Path('models')/name/saved.name, 'saved model path differs from owned retained snapshot')
    expected_hash = digest((output/path).read_bytes())
    require(expected_hash == sequence['snapshot']['sha256'], 'persisted model bytes changed')
    for op in sequence['operations']:
        if op['name'] in ('initialize', 'restore') and op['status'] == 'COMPLETE':
            require(op['response']['snapshotHash'] == expected_hash == op['response']['generationId'], 'model/generation not bound to retained bytes')
            if op['name'] == 'restore':
                require(op['request']['snapshot'] == str(saved), 'restore input differs from saved retained model')


def verify(output):
    output = Path(output)
    inventory = _file_inventory(output); inventory.pop('manifest.json')
    require(inventory == json.loads((output/'manifest.json').read_text()), 'evidence inventory/digest mismatch')
    p = load_protocol(output/'protocol.json')
    java = json.loads((output/'runtime-inventory.json').read_text())
    verify_runtime(output, java)
    python = json.loads((output/'python-inventory.json').read_text())
    files = _file_inventory(output/'python-runtime')
    require(files == python['files'] and digest(canonical(files)) == python['runtimeBytesHash'], 'Python runtime changed')
    metadata = json.loads((output/'metadata.json').read_text())
    sequences = json.loads((output/'sequences.json').read_text())
    actual = [(s['profile'], s['length'], s['repetition']) for s in sequences]
    require(len(actual) == len(set(actual)) and set(actual) == set(schedule(p)), 'incomplete/duplicated lifecycle matrix')
    for s in sequences:
        require(s['accounting'] == account_sequence(p, s), 'lifecycle accounting summary mismatch')
        verify_snapshot(output, s)
    summary = summarize(p, sequences)
    require(summary == json.loads((output/'summary.json').read_text()), 'retained comparison summary mismatch')
    verify_metadata(metadata, python, java)
    return summary


def execute(protocol_path, classpath_path, output):
    output = Path(output).resolve(); p = load_protocol(protocol_path)
    provenance = source_provenance(Path.cwd())
    output.mkdir(parents=True, exist_ok=False)
    (output/'protocol.json').write_bytes(Path(protocol_path).read_bytes())
    runtime = snapshot_runtime(Path(classpath_path).read_text().strip(), output)
    python = snapshot_python(output)
    java = ['java', *p['javaOptions'], '-cp', os.pathsep.join(str(output/x) for x in runtime['classpathEntries']), JAVA_WORKER]
    bootstrap = 'import sys;sys.path.insert(0,'+repr(str(output/'python-runtime'))+');from external_polynomial_comparison.historical_sympy_worker import main;main()'
    sympy = [sys.executable, '-I', '-B', '-u', '-c', bootstrap]
    host = dict(platform=platform.platform(), cpusVisible=os.cpu_count(),
        affinityCpus=len(os.sched_getaffinity(0)) if hasattr(os, 'sched_getaffinity') else None,
        cpuMax=Path('/sys/fs/cgroup/cpu.max').read_text().strip() if Path('/sys/fs/cgroup/cpu.max').exists() else None,
        memoryMax=Path('/sys/fs/cgroup/memory.max').read_text().strip() if Path('/sys/fs/cgroup/memory.max').exists() else None,
        scope='SHARED_HOST_OBSERVATION;NOT_EXCLUSIVE_HOST_OR_PRECISION_STUDY')
    metadata = dict(provenance, java=_java_provenance(), host=host, pythonRuntimeHash=python['runtimeBytesHash'],
                    protocolHash=PROTOCOL_HASH, runtimeBytesHash=runtime['runtimeBytesHash'], commands=dict(java=java, sympy=sympy))
    sequences = []
    for profile, length, repetition in schedule(p):
        sequence = run_sequence(p, profile, length, repetition, sympy if profile == 'SYMPY' else java, output)
        sequences.append(sequence)
        # Checkpoint every completed/failed lifecycle; export costs remain outside per-profile boundaries.
        (output/'sequences.json').write_bytes(canonical(sequences))
        print(json.dumps(dict(profile=profile, length=length, repetition=repetition, accounting=sequence['accounting'])), flush=True)
    for name, value in [('metadata.json', metadata), ('summary.json', summarize(p, sequences))]:
        (output/name).write_bytes(canonical(value))
    (output/'manifest.json').write_bytes(canonical(_file_inventory(output)))
    return verify(output)


def supervise(args):
    require(hasattr(os, 'wait4'), 'POSIX wait4 is required for measured execution')
    command = [sys.executable, '-B', '-m', 'external_polynomial_comparison.run_historical', '--execute-child',
               '--protocol', str(args.protocol), '--classpath', str(args.classpath), '--output', str(args.output)]
    started, cpu = time.perf_counter_ns(), time.process_time_ns()
    process = subprocess.Popen(command)
    _, status, usage = os.wait4(process.pid, 0)
    process.returncode = os.waitstatus_to_exitcode(status)
    receipt = dict(command=command, exitCode=process.returncode, wallNanos=time.perf_counter_ns()-started,
        supervisorCpuNanos=time.process_time_ns()-cpu,
        childTreeCpuNanos=round((usage.ru_utime+usage.ru_stime)*1e9),
        scope='SUPERVISE_FUNCTION_BEFORE_SPAWN_THROUGH_CHILD_EXIT;EXCLUDES_SUPERVISOR_IMPORTS_AND_FINAL_RECEIPT_WRITE')
    args.output.with_name(args.output.name+'.command-receipt.json').write_bytes(canonical(receipt))
    return process.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=Path, default=Path(PROTOCOL))
    parser.add_argument('--classpath', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--verify', action='store_true')
    parser.add_argument('--execute-child', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.verify:
        result = verify(args.output)
        print(json.dumps(dict(evidenceVerified=True, complete=all(g['complete'] for g in result['groups']))))
    else:
        if args.classpath is None: parser.error('--classpath is required')
        if not args.execute_child: raise SystemExit(supervise(args))
        result = execute(args.protocol, args.classpath, args.output)
        print(json.dumps(dict(groups=result['groups'], quality=result['quality']), indent=2))
        if not all(g['complete'] for g in result['groups']):
            raise SystemExit('incomplete historical experiment; all outcomes retained')


if __name__ == '__main__':
    main()
