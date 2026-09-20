"""Actual train/persist/fresh-restore sequences for learned-schema efficiency v2.

The registered public family probes are not a blinded test. All attempts remain
in the evidence. Whole worker CPU/RSS, controller CPU, native method allocations,
logical work, quality and independently audited path validity have distinct scopes.
"""
from __future__ import annotations

import argparse
import ast
from fractions import Fraction
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import statistics
import subprocess
import sys
import time

from .audit_typed import _load, _require, _typed_polynomial
from .polynomial import LIMITS, operation_cost, parse, polynomial
from .run import canonical, judge, summarize, untimed, verify_row
from .run_typed import MeasuredSession, natural

PROTOCOL_HASH = '7e9b51c8f4c66655489350ef4be982411b6ab5ae0585ddb8b725b1ef15d7113c'
# API publication reconstructs commits on the published history. Commit IDs bind
# parent/metadata as well as trees, so the identical registration blob can have a
# different containing commit. Require the published ancestor AND its exact blob.
FREEZE_COMMIT = '5ce2980fe68eb78e352f262934cf338f002a72f4'
SOURCE_LOCAL_FREEZE_COMMIT = '5bc9e21e61131be287b89060ef39f93df111dc25'
REGISTERED_PROTOCOL_PATH = 'config/benchmarks/learned-schema-efficiency-v2.json'
JAVA_WORKER = 'de.regelsuche.evolution.CheckedSchemaComparisonWorker'
SYMPY_VERSION = '1.14.0'  # The unchanged native_worker pins this competitor version.
CPU_SCOPE = 'OWNED_WORKER_PROCESSES_ALL_THREADS_INCLUDING_STARTUP_AND_SHUTDOWN'
METHOD_SCOPE = 'REPORTED_NATIVE_METHODS_ONLY;EXCLUDES_STARTUP_SERIALIZATION_SHUTDOWN_AND_CONTROLLER'
JAVA_PHASES = ('trainingStartup', 'training', 'modelPersistence', 'trainingShutdown',
               'queryStartup', 'modelLoad', 'restore', 'queryShutdown')


def load_protocol(path: Path) -> dict:
    data = path.read_bytes()
    _require(hashlib.sha256(data).hexdigest() == PROTOCOL_HASH,
             'schema protocol differs from its pre-execution freeze')
    _require(LIMITS == {'inputCharacters': 2048, 'astNodes': 256, 'variables': 4,
                        'maxExponent': 12, 'maxTerms': 4096, 'coefficientBits': 2048},
             'shared independent judge limits changed')
    return _load(data.decode('utf-8'))


def check_freeze(repository: Path, freeze_commit=FREEZE_COMMIT) -> dict:
    """Require both published history and the registered historical protocol bytes.

    load_protocol separately checks the current bytes. The original local commit
    is descriptive provenance; published clones need not contain that Git object.
    """
    ancestry = subprocess.run(['git', 'merge-base', '--is-ancestor', freeze_commit, 'HEAD'],
                              cwd=repository, capture_output=True)
    _require(ancestry.returncode == 0, 'published protocol freeze is missing or is not an ancestor of HEAD')
    historical = subprocess.run(['git', 'show', freeze_commit + ':' + REGISTERED_PROTOCOL_PATH],
                                cwd=repository, capture_output=True)
    _require(historical.returncode == 0, 'published freeze lacks the registered protocol file')
    digest = hashlib.sha256(historical.stdout).hexdigest()
    _require(digest == PROTOCOL_HASH, 'published historical protocol bytes differ from the pre-execution registration')
    return {'freezeCommit': freeze_commit, 'freezeProtocolHash': digest}


def source_provenance(repository: Path) -> dict:
    """A clean HEAD names source; it does not claim freshly compiled bytecode."""
    def git(*args):
        return subprocess.check_output(['git', *args], cwd=repository).decode('utf-8').strip()

    clean = subprocess.run(['git', 'diff', '--exit-code', 'HEAD'], cwd=repository, capture_output=True)
    _require(clean.returncode == 0, 'tracked source/configuration differs from HEAD')
    untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z'], cwd=repository).decode('utf-8').split('\0')
    for name in filter(None, untracked):
        path = Path(name)
        if 'build' in path.parts or '__pycache__' in path.parts or path.suffix == '.pyc':
            continue
        _require(not (path.suffix in {'.java', '.py', '.gradle', '.kts', '.sh', '.json', '.yaml', '.yml', '.xml', '.toml', '.properties'}
                      or path.parts[0] in ('gradle', 'config')),
                 'untracked implementation/configuration is not bound to HEAD: ' + name)
    return {'sourceRevision': git('rev-parse', 'HEAD'), 'sourceTreeHash': 'git:' + git('rev-parse', 'HEAD^{tree}')}


def _file_inventory(directory: Path) -> dict:
    result = {}
    for path in sorted(directory.rglob('*')):
        _require(not path.is_symlink(), 'evidence snapshot contains a symbolic link')
        if path.is_file():
            result[path.relative_to(directory).as_posix()] = {
                'bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    return result


def snapshot_runtime(classpath: str, output: Path) -> dict:
    """Copy the exact ordered classpath once; launch every Java worker from it."""
    runtime = output / 'runtime'
    runtime.mkdir()
    entries, originals = [], []
    for index, name in enumerate(classpath.split(os.pathsep)):
        _require(bool(name) and '*' not in name, 'implicit or wildcard classpath entry is not reproducible')
        source = Path(name).resolve()
        destination = runtime / f'entry-{index:03d}'
        if source.is_dir():
            shutil.copytree(source, destination)
        elif source.is_file():
            destination.mkdir()
            destination = destination / source.name
            shutil.copy2(source, destination)
        else:
            # Gradle commonly lists a not-yet-created resources directory. An
            # empty snapshot entry preserves that absence without future reads.
            destination.mkdir()
        entries.append(destination.relative_to(output).as_posix())
        originals.append(str(source))
    _require(bool(entries), 'empty runtime classpath')
    inventory = _file_inventory(runtime)
    binding = {'classpathEntries': entries, 'files': inventory}
    receipt = dict(binding, originalClasspathEntries=originals,
                   runtimeBytesHash='sha256:' + hashlib.sha256(canonical(binding)).hexdigest(),
                   scope='ACTUAL_SNAPSHOTTED_CLASS_AND_DEPENDENCY_BYTES;SEPARATE_FROM_SOURCE_HEAD')
    (output / 'runtime-inventory.json').write_bytes(canonical(receipt))
    return receipt


def verify_runtime(output: Path, receipt: dict):
    for name in receipt['classpathEntries']:
        path = Path(name)
        _require(not path.is_absolute() and '..' not in path.parts and path.parts[0] == 'runtime'
                 and (output / path).exists(), 'invalid runtime classpath binding')
    actual = {'classpathEntries': receipt['classpathEntries'], 'files': _file_inventory(output / 'runtime')}
    _require(actual['files'] == receipt['files'] and
             'sha256:' + hashlib.sha256(canonical(actual)).hexdigest() == receipt['runtimeBytesHash'],
             'retained runtime bytecode/dependency hash mismatch')


def _java_provenance():
    executable = shutil.which('java')
    _require(executable is not None, 'Java executable is unavailable')
    executable = Path(executable).resolve()
    version = subprocess.run([str(executable), '-version'], check=True, capture_output=True, text=True)
    release = executable.parent.parent / 'release'
    return {'executable': str(executable), 'executableSha256': hashlib.sha256(executable.read_bytes()).hexdigest(),
            'releaseSha256': hashlib.sha256(release.read_bytes()).hexdigest() if release.is_file() else None,
            'version': (version.stdout + version.stderr).strip()}


class AccountedSession(MeasuredSession):
    """Reuse the existing transport, retaining wait4 receipts even after timeout.

    Normal lifecycle shutdown closes stdin and lets the worker exit. Request
    failures retain the existing immediate process-group kill behavior. Neither
    Popen.poll nor Popen.wait may reap a child before wait4 records its usage.
    """
    def __init__(self, command, stderr, timeout):
        self.receipt = None
        started = time.perf_counter_ns()
        try:
            super().__init__(command, stderr, timeout)
        finally:
            self.startup_nanos = time.perf_counter_ns() - started

    def close(self, graceful=False):
        if self.receipt is not None or not hasattr(self, 'process'):
            return
        self.failed = True
        process = self.process
        termination = 'GRACEFUL_EOF' if graceful else 'FORCED'
        if graceful:
            try:
                process.stdin.close()
            except (OSError, ValueError):
                pass
        else:
            try:
                os.killpg(process.pid, signal.SIGKILL) if os.name == 'posix' else process.kill()
            except ProcessLookupError:
                pass
        usage = None
        receipt_error = None
        if hasattr(os, 'wait4'):
            try:
                deadline = time.monotonic() + 5
                while True:
                    pid, status, usage = os.wait4(process.pid, os.WNOHANG)
                    if pid:
                        process.returncode = os.waitstatus_to_exitcode(status)
                        break
                    if time.monotonic() >= deadline:
                        termination = 'FORCED'
                        try:
                            os.killpg(process.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                    time.sleep(.005)
            except ChildProcessError:
                receipt_error = 'worker was already reaped; OS resource receipt unavailable'
        else:
            receipt_error = 'wait4 unavailable; whole worker CPU/RSS not measured'
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                termination = 'FORCED'
                process.kill()
                process.wait()
        cpu = None
        rss = None
        if usage is not None:
            user, system = round(usage.ru_utime * 10**9), round(usage.ru_stime * 10**9)
            cpu = {'userNanos': user, 'systemNanos': system, 'totalNanos': user + system,
                   'scope': CPU_SCOPE}
            rss = int(usage.ru_maxrss) * (1 if sys.platform == 'darwin' else 1024)
        self.process_cpu = cpu
        self.receipt = {'pid': process.pid, 'exitCode': process.returncode, 'termination': termination,
                        'cpu': cpu, 'peakRssBytes': rss, 'receiptError': receipt_error,
                        'rssScope': 'WAIT4_PROCESS_PEAK_RESIDENT_SET;MAX_ACROSS_SEQUENTIAL_WORKERS'}
        for stream in (process.stdin, process.stdout, self.stderr):
            if stream is not None:
                try:
                    stream.close()
                except (OSError, ValueError):
                    pass


def check_setup(profile: str, response: dict, *, restored=False, expected_model=None) -> str:
    _require(response.get('status') == 'INITIALIZED' and response.get('profile') == profile,
             'worker initialization/restore profile mismatch')
    model = response.get('model', expected_model if restored else None)
    _require(isinstance(model, str), 'exact model bytes missing')
    _load(model)
    model_hash = 'sha256:' + hashlib.sha256(model.encode('utf-8')).hexdigest()
    _require(response.get('modelHash') == model_hash, 'model byte/hash mismatch')
    if expected_model is not None:
        _require(model == expected_model, 'restored model differs from persisted bytes')
    work = natural(response['restoreWork' if restored else 'trainingWork'])
    if profile == 'BASE' and not restored:
        _require(work == 0, 'BASE performed unused learning')
    return model_hash


def _source_shape(node):
    """The admitted text parser's structure, including explicit unary subtraction."""
    if isinstance(node, ast.Constant) and type(node.value) is int:
        return ('number', str(node.value))
    if isinstance(node, ast.Name):
        return ('variable', node.id)
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
        # ExpressionParser represents unary minus as 0 SUB operand, without folding.
        return ('SUB', ('number', '0'), _source_shape(node.operand))
    if isinstance(node, ast.BinOp):
        operator = {ast.Add: 'ADD', ast.Sub: 'SUB', ast.Mult: 'MUL', ast.Div: 'DIV', ast.Pow: 'POW'}.get(type(node.op))
        _require(operator is not None, 'unsupported source operator')
        return (operator, _source_shape(node.left), _source_shape(node.right))
    raise ValueError('unsupported source structure')


def _typed_shape(node):
    # Call only after the bounded tagged interpreter has validated every node.
    if node['type'] == 'number':
        return ('number', node['value'])
    if node['type'] == 'variable':
        return ('variable', node['name'])
    return (node['operator'], _typed_shape(node['left']), _typed_shape(node['right']))


def _typed_surface(node, parent=0):
    """Independent data-only spelling of the formatter's precedence rules.

    ADD/MUL right association may disappear on the printed surface; no operand
    permutation, cancellation, factoring or other search step is performed here.
    Fractions that the Java formatter emits as decimals remain outside the frozen
    shared integer/fraction grammar, and are rejected by the same cost judge.
    """
    if node['type'] == 'variable':
        return node['name']
    if node['type'] == 'number':
        value = Fraction(node['value'])
        spelling, fraction = str(value.numerator), False
        if value.denominator != 1:
            denominator, twos, fives = value.denominator, 0, 0
            while denominator % 2 == 0:
                denominator //= 2
                twos += 1
            while denominator % 5 == 0:
                denominator //= 5
                fives += 1
            scale = max(twos, fives)
            if denominator == 1 and scale <= 256:
                digits = str(abs(value.numerator) * 10**scale // value.denominator).zfill(scale + 1)
                spelling = ('-' if value < 0 else '') + digits[:-scale] + '.' + digits[-scale:]
            else:
                spelling, fraction = str(value.numerator) + '/' + str(value.denominator), True
        return '(' + spelling + ')' if parent > 0 and (value < 0 or fraction) else spelling
    operator = node['operator']
    precedence = {'ADD': 1, 'SUB': 1, 'MUL': 2, 'DIV': 2, 'POW': 3}[operator]
    right_adjust = int(operator in ('SUB', 'DIV') or
        (operator == 'MUL' and node['right'].get('operator') == 'DIV'))
    spelling = _typed_surface(node['left'], precedence + int(operator == 'POW'))
    spelling += {'ADD': '+', 'SUB': '-', 'MUL': '*', 'DIV': '/', 'POW': '^'}[operator]
    spelling += _typed_surface(node['right'], precedence + right_adjust)
    return '(' + spelling + ')' if precedence < parent else spelling


def _bind_surfaces(source, response):
    typed_input = _load(response['typedInput'])['expression']
    typed_output = _load(response['typedOutput'])['expression']
    _require(_source_shape(parse(source)) == _typed_shape(typed_input), 'typed input differs from requested source structure')
    emitted = _typed_surface(typed_output)
    _require(ast.dump(parse(response['output'])) == ast.dump(parse(emitted)),
             'printed output differs from selected typed output structure')
    _require(natural(response['inputCost']) == operation_cost(source)
             and natural(response['outputCost']) == operation_cost(emitted),
             'reported cost differs from the bound typed surface')


def audit_response(source: str, response: dict) -> dict:
    """Check exact polynomial identities and selected lineage, not Java authority.

    Checked schema edges are statements of the polynomial theory; they need not
    expand to a primitive path. Their endpoints are interpreted independently.
    Any supplied primitive intermediate is independently checked as well.
    """
    _require(response.get('status') == 'CANDIDATE', 'candidate required')
    _require(response.get('input') == source and response.get('target') == ''
             and response.get('targetReached') is False, 'source-only input binding')
    search = _load(response['search'])
    _require(search.get('reached') is False, 'source-only search reached a target')
    work = sum(natural(search['metrics'][key]) for key in ('primitiveWork', 'searchWork', 'verificationWork'))
    _require(work == natural(search['totalWork']), 'search work partition mismatch')
    _require(work + natural(response['selectionWork']) + natural(response['selectedReplayWork'])
             == natural(response['totalWork']), 'query work partition mismatch')
    witness = response['witness']
    _require(isinstance(witness, list) and len(witness) <= 32, 'selected path bound')
    expected = polynomial(source)
    cache = {}

    def check(document):
        if document not in cache:
            cache[document] = _typed_polynomial(document)
        _require(cache[document] == expected, 'invalid selected polynomial identity')

    cursor = response['typedInput']
    check(cursor)
    check(response['typedOutput'])
    _require(polynomial(response['output']) == expected, 'output/typed endpoint mismatch')
    _bind_surfaces(source, response)
    counts = {'selectedEdges': 0, 'learnedEdges': 0, 'primitiveEdges': 0}
    replay_work, state = 0, None
    for step in witness:
        _require(step['source'] == cursor, 'disconnected selected path')
        check(step['source'])
        check(step['target'])
        move, receipt = step['move'], step['verification']
        _require(receipt.get('accepted') is True and isinstance(receipt.get('receipts'), list)
                 and bool(receipt['receipts']) and all(isinstance(item, str) and item for item in receipt['receipts']),
                 'accepted Java verification receipt missing')
        replay_work += natural(receipt['work'])
        _require(move.get('sourceKind') in ('PRIMITIVE', 'LEARNED'), 'unsupported move source')
        transformation = move['transformation']
        _require(move['assumptions'] == transformation['assumptions'] == [], 'unproved dynamic premises')
        _require(transformation['transformedExpression'] == step['target'], 'move endpoint mismatch')
        expansion = move.get('primitiveExpansion', [])
        _require(isinstance(expansion, list) and len(expansion) <= 8, 'primitive expansion bound')
        for primitive in expansion:
            _require(primitive['assumptions'] == [], 'unproved primitive premises')
            check(primitive['transformedExpression'])
        if expansion:
            _require(expansion[-1]['transformedExpression'] == step['target'], 'primitive expansion endpoint mismatch')
        admitted = [event for event in search['events'] if event['decision'] == 'ENQUEUED'
                    and event['source']['expression'] == step['source']
                    and event['target']['expression'] == step['target']
                    and event['move'] == move and event['verification'] == receipt
                    and (event['source'] == state if state is not None else
                         event['source']['searchDepth'] == event['source']['primitiveDepth'] == 0)]
        _require(bool(admitted), 'selected edge not admitted by search')
        edge = admitted[0]
        _require(edge['source']['assumptions'] == edge['target']['assumptions'] == [], 'unexpected state premises')
        _require(natural(edge['target']['searchDepth']) == natural(edge['source']['searchDepth']) + 1
                 and natural(edge['target']['primitiveDepth']) >= natural(edge['source']['primitiveDepth']),
                 'selected state depth mismatch')
        state, cursor = edge['target'], step['target']
        counts['selectedEdges'] += 1
        counts['learnedEdges'] += move['sourceKind'] == 'LEARNED'
        counts['primitiveEdges'] += move['sourceKind'] == 'PRIMITIVE'
    _require(cursor == response['typedOutput'], 'witness does not reach typed output')
    _require(replay_work == natural(response['selectedReplayWork']), 'selected replay work mismatch')
    return counts


def check_response(profile, source, response, model_hash, budget):
    if response.get('status') != 'CANDIDATE':
        return None
    _require(response.get('profile') == profile, 'worker profile mismatch')
    if profile.startswith('SYMPY_'):
        return None
    _require(model_hash is not None and response.get('modelHash') == model_hash, 'query model hash mismatch')
    total = natural(response['totalWork'])
    _require(response.get('internalWorkWithinBudget') is (total <= budget), 'hidden work budget overrun')
    return audit_response(source, response)


def _settings(protocol, response):
    if 'settings' in response:
        for key in ('workBudget', 'maxStates', 'maxDepth', 'maximumSchemasPerOccurrence'):
            _require(response['settings'].get(key) == protocol[key], 'worker settings differ from frozen protocol')


def _response(row):
    response = row.get('response', {})
    return response.get('retainedResponse', response)


def account_sequence(protocol: dict, sequence: dict) -> dict:
    """Fail closed on missing stages/rows; retain failures without zero receipts."""
    profile, length = sequence['profile'], natural(sequence['length'])
    phases, rows = sequence['phases'], sequence['rows']
    required = ('queryStartup', 'queryShutdown') if profile.startswith('SYMPY_') else JAVA_PHASES
    _require(all(key in phases for key in required), 'missing paid lifecycle phase')
    for phase in phases.values():
        _require(phase['status'] in ('COMPLETE', 'BLOCKED', 'ERROR'), 'unknown lifecycle phase status')
        elapsed = natural(phase['wallNanos'])
        _require(phase['status'] == 'BLOCKED' or elapsed > 0, 'executed lifecycle phase has no paid time')
        _require(elapsed >= natural(phase.get('requestWallNanos', 0)), 'lifecycle phase omits request time')
    complete_phases = all(phases[key]['status'] == 'COMPLETE' for key in required)
    if not profile.startswith('SYMPY_'):
        model = None
        if phases['training']['status'] == 'COMPLETE':
            check_setup(profile, phases['training']['response'])
            _settings(protocol, phases['training']['response'])
            model = phases['training']['response']['model']
        if phases['restore']['status'] == 'COMPLETE':
            _require(model is not None, 'restore without recorded training model')
            check_setup(profile, phases['restore']['response'], restored=True, expected_model=model)
            _settings(protocol, phases['restore']['response'])
    _require(len(rows) == length, 'incomplete actual sequence')
    for index, row in enumerate(rows):
        case = protocol['cases'][index % len(protocol['cases'])]
        _require(row['index'] == index and row['case'] == case['id'] and row['source'] == case['source']
                 and row['profile'] == profile and row['repetition'] == sequence['repetition']
                 and row['length'] == length, 'sequence row binding mismatch')
    setup_wall = sum(natural(phase['wallNanos']) for phase in phases.values())
    query_wall = sum(natural(row['judgment']['endToEndNanos']) for row in rows)
    lifecycle_wall = natural(sequence['wallNanos'])
    _require(lifecycle_wall >= setup_wall + query_wall, 'lifecycle omits preparation or verification time')
    processes = sequence['processes']
    _require(len({p['pid'] for p in processes}) == len(processes), 'training and restore reused a process')
    _require(len({p['stage'] for p in processes}) == len(processes), 'duplicate process stage receipt')
    expected_processes = 1 if profile.startswith('SYMPY_') else 2
    complete_receipts = len(processes) == expected_processes
    for process in processes:
        _require(process['stage'] in ('training', 'query'), 'unexpected process stage')
        startup = phases['trainingStartup' if process['stage'] == 'training' else 'queryStartup']
        if startup['status'] == 'COMPLETE':
            _require(startup['pid'] == process['pid'], 'startup/resource receipt process mismatch')
        cpu = process.get('cpu')
        if cpu is None or process.get('peakRssBytes') is None:
            complete_receipts = False
            continue
        _require(natural(cpu['totalNanos']) == natural(cpu['userNanos']) + natural(cpu['systemNanos']),
                 'whole worker CPU partition mismatch')
        natural(process['peakRssBytes'])
    cpu = None
    if complete_receipts:
        cpu = {key: sum(process['cpu'][key] for process in processes)
               for key in ('userNanos', 'systemNanos', 'totalNanos')}
        cpu['scope'] = CPU_SCOPE
    responses = [phase['response'] for phase in phases.values() if 'response' in phase] + [_response(row) for row in rows]
    method = {}
    for name, field in [('CpuNanos', 'nativeCpuNanos'), ('AllocatedBytes', 'nativeAllocatedBytes')]:
        values = [response.get(field) for response in responses]
        present = [value for value in values if type(value) is int and value >= 0]
        method['reportedNativeMethod' + name] = sum(present) if present else None
        method['completeNativeMethod' + name] = bool(values) and len(present) == len(values)
    training = phases.get('training', {}).get('response', {}).get('trainingWork')
    restored = phases.get('restore', {}).get('response', {}).get('restoreWork')
    if profile.startswith('SYMPY_'):
        training = restored = 0
    work = [_response(row).get('totalWork') for row in rows]
    logical_complete = all(type(value) is int and value >= 0 for value in [training, restored] + work)
    good_shutdown = all(p.get('exitCode') == 0 and p.get('termination') == 'GRACEFUL_EOF' for p in processes)
    return {'preparationAndShutdownWallNanos': setup_wall, 'queryAndVerificationWallNanos': query_wall,
            'lifecycleWallNanos': lifecycle_wall, 'controllerOverheadWallNanos': lifecycle_wall - setup_wall - query_wall,
            'controllerCpuNanos': natural(sequence['controllerCpuNanos']),
            'wholeWorkerCpu': cpu, 'completeProcessReceipts': complete_receipts,
            'peakWorkerRssBytes': max((p['peakRssBytes'] for p in processes if p.get('peakRssBytes') is not None), default=None),
            'trainingWork': training, 'restoreWork': restored,
            'queryWork': sum(work) if logical_complete else None,
            'totalLogicalWork': training + restored + sum(work) if logical_complete else None,
            'logicalWorkScope': 'JAVA_TRAINING_SELECTION_RESTORE_AND_ALL_QUERY_WORK;NOT_COMPARABLE_TO_CAS_INSTRUCTIONS',
            'nativeMethodScope': METHOD_SCOPE, **method,
            'allocationScope': 'REPORTED_CURRENT_JAVA_THREAD_METHOD_ALLOCATIONS;NOT_ALL_THREADS_OR_FULL_PROCESS_LIFETIME',
            'attemptedQueries': len(rows), 'eligibleQueries': sum(row['judgment']['valid'] for row in rows),
            'complete': not sequence['errors'] and complete_phases and complete_receipts and good_shutdown
                        and all(row['judgment']['valid'] for row in rows)}


def run_sequence(protocol, profile, length, repetition, command, output):
    """Execute a full fresh lifecycle; never multiply a measured short run."""
    started, controller = time.perf_counter_ns(), time.process_time_ns()
    name = f'{profile}.n{length}.r{repetition}'
    sequence = {'id': name, 'profile': profile, 'length': length, 'repetition': repetition,
                'configuredQueryWorkBudget': protocol['workBudget'], 'command': command, 'phases': {}, 'rows': [],
                'processes': [], 'errors': []}
    phases, workers = sequence['phases'], []
    model_hash, worker, model = None, None, None
    required = ('queryStartup', 'queryShutdown') if profile.startswith('SYMPY_') else JAVA_PHASES

    def phase(key, operation):
        begin = time.perf_counter_ns()
        record = phases[key] = {}
        try:
            value = operation(record)
            record['status'] = 'COMPLETE'
            return value
        except Exception as error:
            record.update(status='ERROR', error=f'{type(error).__name__}: {error}')
            raise
        finally:
            record['wallNanos'] = time.perf_counter_ns() - begin

    def launch(record, stage):
        owned = AccountedSession.__new__(AccountedSession)
        workers.append((stage, owned))
        AccountedSession.__init__(owned, command, output / f'{name}.{stage}.stderr.txt', protocol['setupTimeoutSeconds'])
        record.update(ready=owned.ready, pid=owned.process.pid)
        if profile.startswith('SYMPY_'):
            _require(owned.ready.get('sympy') == SYMPY_VERSION, 'wrong pinned SymPy version')
        return owned

    def initialize(record, owned, restored=False):
        payload = {'op': 'restore' if restored else 'initialize', 'profile': profile}
        if restored:
            payload['model'] = model
        response, request_wall = owned.request(payload, protocol['setupTimeoutSeconds'])
        record.update(response=response, requestWallNanos=request_wall)
        _settings(protocol, response)
        return check_setup(profile, response, restored=restored, expected_model=model if restored else None)

    try:
        if not profile.startswith('SYMPY_'):
            training_worker = phase('trainingStartup', lambda record: launch(record, 'training'))
            model_hash = phase('training', lambda record: initialize(record, training_worker))
            model = phases['training']['response']['model']
            model_path = output / f'{name}.model.json'

            def persist(record):
                with model_path.open('xb') as handle:
                    handle.write(model.encode('utf-8'))
                    handle.flush()
                    os.fsync(handle.fileno())
                record.update(file=model_path.name, modelHash=model_hash)

            phase('modelPersistence', persist)
            phase('trainingShutdown', lambda record: training_worker.close(graceful=True))
            _require(training_worker.receipt['termination'] == 'GRACEFUL_EOF'
                     and training_worker.receipt['exitCode'] == 0, 'training worker failed graceful shutdown')
            worker = phase('queryStartup', lambda record: launch(record, 'query'))

            def load(record):
                loaded = model_path.read_bytes().decode('utf-8')
                _require(loaded == model, 'persisted model bytes changed')
                record.update(file=model_path.name, modelHash=model_hash)
                return loaded

            model = phase('modelLoad', load)
            restored_hash = phase('restore', lambda record: initialize(record, worker, restored=True))
            _require(restored_hash == model_hash, 'restore model hash changed')
        else:
            worker = phase('queryStartup', lambda record: launch(record, 'query'))
    except Exception as error:
        sequence['errors'].append(f'{type(error).__name__}: {error}')
        worker = None
    try:
        for index in range(length):
            case = protocol['cases'][index % len(protocol['cases'])]
            response, elapsed = ({'status': 'BLOCKED_WORKER_UNAVAILABLE'}, 0) if worker is None else worker.request(
                {'op': 'run', 'profile': profile, 'source': case['source']}, protocol['queryTimeoutSeconds'])
            begin = time.perf_counter_ns()
            audit = None
            try:
                audit = check_response(profile, case['source'], response, model_hash, protocol['workBudget'])
            except (ValueError, KeyError, TypeError, RecursionError) as error:
                response = {'status': 'ERROR', 'error': str(error), 'retainedResponse': response}
            binding = time.perf_counter_ns() - begin
            judgment = judge(case['source'], response, elapsed + binding, protocol['queryTimeoutSeconds'] * 10**9)
            sequence['rows'].append({'case': case['id'], 'source': case['source'], 'family': case.get('family'),
                'profile': profile, 'length': length, 'repetition': repetition, 'index': index,
                'response': response, 'independentAudit': audit, 'transportWallNanos': elapsed,
                'bindingAndPathAuditWallNanos': binding, 'requestWallNanos': elapsed + binding, 'judgment': judgment})
    finally:
        for stage, owned in workers:
            key = 'trainingShutdown' if stage == 'training' else 'queryShutdown'
            if key not in phases:
                phase(key, lambda record, owned=owned: owned.close(graceful=True))
            if owned.receipt is not None:
                sequence['processes'].append(dict(owned.receipt, stage=stage))
        for key in required:
            phases.setdefault(key, {'status': 'BLOCKED', 'wallNanos': 0})
        sequence['wallNanos'] = time.perf_counter_ns() - started
        sequence['controllerCpuNanos'] = time.process_time_ns() - controller
    sequence['accounting'] = account_sequence(protocol, sequence)
    return sequence


def schedule(protocol):
    for repetition in range(protocol['repetitions']):
        for index, length in enumerate(protocol['sequenceLengths']):
            profiles = protocol['profiles']
            offset = (index + repetition) % len(profiles)
            for profile in profiles[offset:] + profiles[:offset]:
                yield profile, length, repetition


def require_sequences(protocol, sequences):
    actual = [(item['profile'], item['length'], item['repetition']) for item in sequences]
    _require(actual == list(schedule(protocol)), 'incomplete, duplicated or reordered sequence matrix')
    for item in sequences:
        _require(item['configuredQueryWorkBudget'] == protocol['workBudget'], 'query work budget changed')
        account_sequence(protocol, item)


def paired_comparisons(protocol, sequences):
    indexed = {(s['profile'], s['length'], s['repetition']): s for s in sequences}
    result = {}
    for learned in ('LEARNED_SCHEMA', 'LEARNED_SELECTED'):
        for comparator in protocol['profiles']:
            if comparator == learned:
                continue
            pairs = []
            for length in protocol['sequenceLengths']:
                for repetition in range(protocol['repetitions']):
                    a, b = indexed[learned, length, repetition], indexed[comparator, length, repetition]
                    row_pairs = []
                    for ar, br in zip(a['rows'], b['rows']):
                        aj, bj = ar['judgment'], br['judgment']
                        if aj['valid'] and bj['valid']:
                            outcome = 'TIE' if aj['outputCost'] == bj['outputCost'] else 'BETTER' if aj['outputCost'] < bj['outputCost'] else 'WORSE'
                        else:
                            outcome = 'LEARNED_ONLY' if aj['valid'] else 'COMPARATOR_ONLY' if bj['valid'] else 'NEITHER_VALID'
                        row_pairs.append({'index': ar['index'], 'case': ar['case'], 'outcome': outcome,
                                          'learnedCost': aj.get('outputCost'), 'comparatorCost': bj.get('outputCost')})
                    aa, ba = a['accounting'], b['accounting']
                    equal = aa['complete'] and ba['complete'] and all(p['outcome'] == 'TIE' for p in row_pairs)
                    pairs.append({'length': length, 'repetition': repetition, 'rows': row_pairs,
                        'equalVerifiedQualityAndQueryBudget': equal,
                        'learnedLifecycleWallNanos': aa['lifecycleWallNanos'], 'comparatorLifecycleWallNanos': ba['lifecycleWallNanos'],
                        'learnedTotalLogicalWork': aa['totalLogicalWork'], 'comparatorTotalLogicalWork': ba['totalLogicalWork'],
                        'fullLifecycleWallSpeedup': ba['lifecycleWallNanos'] / aa['lifecycleWallNanos'] if equal and aa['lifecycleWallNanos'] else None,
                        'wholeWorkerCpuSpeedup': ba['wholeWorkerCpu']['totalNanos'] / aa['wholeWorkerCpu']['totalNanos'] if equal and aa['wholeWorkerCpu']['totalNanos'] else None})
            result[learned + '_versus_' + comparator] = {'pairs': pairs,
                'scope': 'ACTUAL_SEQUENCES;EQUAL_VERIFIED_OUTPUT_COST_VECTOR_AND_QUERY_CAP;ALL_LIFECYCLE_COSTS_PAID;NO_EQUAL_TOTAL_BUDGET_ABILITY_CLAIM'}
    return result


def summarize_sequences(protocol, sequences):
    rows = [row for sequence in sequences for row in sequence['rows']]
    result = summarize(protocol, rows)
    for profile, summary in result.items():
        summary['sequencesByLength'] = {}
        for length in protocol['sequenceLengths']:
            accounts = [s['accounting'] for s in sequences if s['profile'] == profile and s['length'] == length]
            complete = [a for a in accounts if a['complete']]
            summary['sequencesByLength'][str(length)] = {'attempted': len(accounts), 'complete': len(complete),
                'allAttemptedWallNanos': sum(a['lifecycleWallNanos'] for a in accounts),
                'medianCompleteLifecycleWallNanos': statistics.median(a['lifecycleWallNanos'] for a in complete) if complete else None,
                'allTotalLogicalWork': [a['totalLogicalWork'] for a in accounts],
                'allPeakWorkerRssBytes': [a['peakWorkerRssBytes'] for a in accounts]}
    return result


def execute(protocol_path: Path, classpath_path: Path, output: Path):
    controller = time.process_time_ns()
    preparation = time.perf_counter_ns()
    protocol = load_protocol(protocol_path)
    freeze = check_freeze(Path.cwd())
    provenance = source_provenance(Path.cwd())
    revision = provenance['sourceRevision']
    classpath = classpath_path.read_text(encoding='utf-8').strip()
    _require(bool(classpath), 'empty Java classpath')
    output.mkdir(parents=True, exist_ok=False)
    (output / 'protocol.json').write_bytes(protocol_path.read_bytes())
    runtime = snapshot_runtime(classpath, output)
    java = _java_provenance()
    commands = {'java': [java['executable'], '-Xmx512m', '-cp',
                os.pathsep.join(str((output / entry).resolve()) for entry in runtime['classpathEntries']), JAVA_WORKER],
                'sympy': [sys.executable, '-u', '-m', 'external_polynomial_comparison.native_worker']}
    metadata = {'revision': revision, 'protocolHash': PROTOCOL_HASH, **freeze,
        'sourceLocalFreezeCommit': SOURCE_LOCAL_FREEZE_COMMIT,
        **provenance, 'runtimeBytesHash': runtime['runtimeBytesHash'], 'java': java, 'commands': commands,
        'outputDirectoryAtExecution': str(output.resolve()),
        'sharedHarnessPreparationWallNanos': time.perf_counter_ns() - preparation,
        'sharedHarnessPreparationControllerCpuNanos': time.process_time_ns() - controller,
        'sharedHarnessPreparationScope': 'ONE_TIME_SOURCE_CHECK_RUNTIME_SNAPSHOT_AND_JDK_IDENTIFICATION;REPORTED_SEPARATELY_FROM_PROFILE_LIFECYCLES',
        'platform': platform.platform(), 'python': sys.version, 'sympyVersion': SYMPY_VERSION,
        'judgeLimits': LIMITS, 'split': 'PUBLIC_REGISTERED_FAMILY_PROBES;NOT_BLINDED',
        'sequenceCaseOrder': 'CYCLE_PROTOCOL_CASES_FROM_INDEX_ZERO', 'warmups': [],
        'profileOrder': 'ROUND_ROBIN_BY_REPETITION_PLUS_SEQUENCE_LENGTH_INDEX',
        'scope': 'FRESH_TRAIN_AND_FRESH_RESTORED_WORKERS_PER_ACTUAL_SEQUENCE;NO_SCALED_AMORTIZATION'}
    sequences = []
    try:
        for profile, length, repetition in schedule(protocol):
            command = commands['sympy' if profile.startswith('SYMPY_') else 'java']
            sequences.append(run_sequence(protocol, profile, length, repetition, command, output))
            (output / 'sequences.json').write_bytes(canonical(sequences))
        require_sequences(protocol, sequences)
        _require(source_provenance(Path.cwd()) == provenance, 'source changed during execution')
        verify_runtime(output, runtime)
    finally:
        metadata['executionControllerCpuNanos'] = time.process_time_ns() - controller
        (output / 'metadata.json').write_bytes(canonical(metadata))
        (output / 'sequences.json').write_bytes(canonical(sequences))
        (output / 'rows.json').write_bytes(canonical([row for sequence in sequences for row in sequence['rows']]))
    summary, paired = summarize_sequences(protocol, sequences), paired_comparisons(protocol, sequences)
    (output / 'summary.json').write_bytes(canonical(summary))
    (output / 'paired-comparisons.json').write_bytes(canonical(paired))
    (output / 'canonical-sequences.json').write_bytes(canonical(untimed(sequences)))
    report = ['# Learned schema efficiency v2', '', 'Public registered family probes; not a blinded transfer test.', '',
        f'Protocol `{PROTOCOL_HASH}`; source `{revision}`.', '',
        '| Profile | Valid queries | Improved | Worse | Errors | Timeouts | Work overruns |',
        '|---|---:|---:|---:|---:|---:|---:|']
    for profile, values in summary.items():
        report.append('| ' + profile + ' | ' + ' | '.join(str(values[k]) for k in ('valid', 'improved', 'worse', 'errors', 'timeouts', 'internalWorkOverruns')) + ' |')
    report += ['', 'Each repetition and sequence length uses actual distinct processes. Java sequences pay startup,',
        'training and policy selection, fsynced model persistence, graceful shutdown, fresh startup, file load,',
        'semantic restore/recheck, all queries, independent selected-path and endpoint audits, and shutdown.',
        'No unpaid warmups or extrapolated sequence timings are used. SymPy pays its complete native portfolio.',
        'The sequence wall stopwatch includes controller overhead; whole worker CPU and peak RSS use wait4.',
        'Method allocations exclude startup, serialization and shutdown; no whole-process allocation claim is made.',
        'Speed ratios are present only for complete sequences with identical independently verified output costs',
        'and the same query cap. Training and restore work are added to query work. Equal query caps do not',
        'establish equal total-budget ability. Every quality disagreement, failure and losing comparison remains',
        'in the raw rows and paired comparison file. This report does not predeclare performance acceptance.']
    (output / 'report.md').write_text('\n'.join(report) + '\n', encoding='utf-8')
    manifest = {name: item['sha256'] for name, item in _file_inventory(output).items()}
    (output / 'manifest.json').write_bytes(canonical(manifest))
    verify(output)
    return summary


def verify(output: Path):
    protocol = load_protocol(output / 'protocol.json')
    manifest = _load((output / 'manifest.json').read_text(encoding='utf-8'))
    inventory = _file_inventory(output)
    _require(set(manifest) == set(inventory) - {'manifest.json'}, 'manifest inventory mismatch')
    for name, digest in manifest.items():
        _require(not Path(name).is_absolute() and '..' not in Path(name).parts
                 and inventory[name]['sha256'] == digest, 'manifest digest mismatch')
    metadata = _load((output / 'metadata.json').read_text(encoding='utf-8'))
    _require(metadata['protocolHash'] == metadata['freezeProtocolHash'] == PROTOCOL_HASH
             and metadata['freezeCommit'] == FREEZE_COMMIT
             and metadata['sourceLocalFreezeCommit'] == SOURCE_LOCAL_FREEZE_COMMIT,
             'provenance protocol binding mismatch')
    _require(metadata['revision'] == metadata['sourceRevision'] and metadata['sourceTreeHash'].startswith('git:'),
             'source revision/tree provenance mismatch')
    runtime = _load((output / 'runtime-inventory.json').read_text(encoding='utf-8'))
    verify_runtime(output, runtime)
    _require(metadata['runtimeBytesHash'] == runtime['runtimeBytesHash'], 'actual runtime provenance mismatch')
    expected_java = [metadata['java']['executable'], '-Xmx512m', '-cp',
        os.pathsep.join(str(Path(metadata['outputDirectoryAtExecution']) / entry) for entry in runtime['classpathEntries']), JAVA_WORKER]
    _require(metadata['commands']['java'] == expected_java, 'Java command does not launch the retained runtime')
    _require(metadata['commands']['sympy'][1:] == ['-u', '-m', 'external_polynomial_comparison.native_worker'],
             'external control command changed')
    sequences = _load((output / 'sequences.json').read_text(encoding='utf-8'))
    require_sequences(protocol, sequences)
    for sequence in sequences:
        profile, phases = sequence['profile'], sequence['phases']
        _require(sequence['command'] == metadata['commands']['sympy' if profile.startswith('SYMPY_') else 'java'],
                 'worker command differs from the recorded runtime')
        model_hash, model = None, None
        if not profile.startswith('SYMPY_') and phases['training']['status'] == 'COMPLETE':
            response = phases['training']['response']
            model_hash = check_setup(profile, response)
            model = response['model']
            if phases['modelPersistence']['status'] == 'COMPLETE':
                filename = phases['modelPersistence']['file']
                _require(Path(filename).name == filename and (output / filename).read_bytes() == model.encode('utf-8'), 'persisted model mismatch')
            if phases['restore']['status'] == 'COMPLETE':
                check_setup(profile, phases['restore']['response'], restored=True, expected_model=model)
        for row in sequence['rows']:
            _require(natural(row['transportWallNanos']) + natural(row['bindingAndPathAuditWallNanos']) == natural(row['requestWallNanos']), 'request timing partition mismatch')
            audit = check_response(profile, row['source'], row['response'], model_hash, protocol['workBudget'])
            _require(row['independentAudit'] == audit, 'independent audit mismatch')
            verify_row(row, protocol['queryTimeoutSeconds'] * 10**9)
        _require(sequence['accounting'] == account_sequence(protocol, sequence), 'sequence accounting mismatch')
    _require((output / 'rows.json').read_bytes() == canonical([r for s in sequences for r in s['rows']]), 'raw rows mismatch')
    _require((output / 'canonical-sequences.json').read_bytes() == canonical(untimed(sequences)), 'canonical sequences mismatch')
    _require(_load((output / 'summary.json').read_text(encoding='utf-8')) == summarize_sequences(protocol, sequences), 'summary mismatch')
    _require(_load((output / 'paired-comparisons.json').read_text(encoding='utf-8')) == paired_comparisons(protocol, sequences), 'paired comparison mismatch')


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--protocol', type=Path, default=Path('config/benchmarks/learned-schema-efficiency-v2.json'))
    parser.add_argument('--classpath', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    if args.verify:
        verify(args.output)
    else:
        if args.classpath is None:
            parser.error('--classpath is required for execution')
        summary = execute(args.protocol, args.classpath, args.output)
        print(json.dumps(summary, indent=2))
        if any(row['errors'] for row in summary.values()):
            raise SystemExit('v2 contains technical/incomplete rows; all evidence retained')


if __name__ == '__main__':
    main()
