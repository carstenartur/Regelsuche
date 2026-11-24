"""Public development comparison of the direct polynomial profile on the frozen native inputs."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import sys
import time

from . import run_native as native
from .polynomial import Unsupported, polynomial
from .run import canonical, judge, verify_row
from .run_schema import (AccountedSession, CPU_SCOPE, _file_inventory, _java_provenance,
                         audit_response, snapshot_runtime, verify_runtime)
from .run_typed import natural

DIRECT = 'POLYNOMIAL_DIRECT_V1'
OBJECTIVE = 'EXACT_POLYNOMIAL_SURFACE'
ORIGINAL = 'NATIVE_BASE'
REFERENCE = 'SYMPY_PORTFOLIO'
PROFILES = [ORIGINAL, DIRECT, REFERENCE]
SETTINGS = {'workBudget': 16_384, 'maxTheoryWork': 16_384, 'primitiveDepth': 9,
            'searchDepth': 9, 'maxStates': 256, 'complexityDebt': 24}
PROTOCOL = Path('config/benchmarks/native-polynomial-v1.json')
WORKER = 'de.regelsuche.evolution.TypedExternalPolynomialComparisonWorker'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def direct_setup(response):
    model = response['model']
    digest = 'sha256:' + hashlib.sha256(model.encode()).hexdigest()
    require(response['status'] == 'INITIALIZED' and response['profile'] == DIRECT
            and response['modelHash'] == digest and response['settings'] == SETTINGS
            and response['trainingWork'] == 0 and response['learnedPrograms'] == 0,
            'direct profile setup differs from the declared source-only contract')
    declaration = json.loads(model)
    require(declaration['constructionProfile'] == DIRECT and declaration['objective'] == OBJECTIVE,
            'model names a different construction profile or objective')
    return digest


def check_direct(source, response, model_hash):
    if response['status'] != 'CANDIDATE':
        return
    require(response['input'] == source and response['profile'] == DIRECT and response['modelHash'] == model_hash
            and response['internalWorkWithinBudget'] is (natural(response['totalWork']) <= SETTINGS['workBudget']),
            'direct response changed the source, model, profile or budget')
    audit_response(source, response, allowed_source_kinds=('PREPARATION',))


def direct_sequence(protocol, repetition, command, output):
    start, cpu = time.perf_counter_ns(), time.process_time_ns()
    worker = AccountedSession.__new__(AccountedSession)
    sequence = dict(profile=DIRECT, repetition=repetition, workers=[], operations=[], complete=False)
    rows = []
    deadline = protocol['queryTimeoutSeconds'] * 10**9

    def row(case, response, elapsed, binding):
        source = case['source']
        return dict(case=case['id'], profile=DIRECT, repetition=repetition, source=source, response=response,
                    requestWallNanos=elapsed + binding, bindingWallNanos=binding,
                    judgment=judge(source, response, elapsed + binding, deadline))

    try:
        worker.__init__(command, output / f'{DIRECT}-{repetition}.stderr.txt', protocol['setupTimeoutSeconds'])
        sequence['ready'] = worker.ready
        setup, duration = worker.request(dict(op='initialize', profile=DIRECT), protocol['setupTimeoutSeconds'])
        sequence['operations'].append(dict(op='initialize', response=setup, requestWallNanos=duration))
        model_hash = direct_setup(setup)
        for case in protocol['cases']:
            source = case['source']
            try:
                polynomial(source)
            except Unsupported as error:
                rows.append(row(case, dict(status='SHARED_FRAGMENT_UNSUPPORTED', reason=str(error)), 0, 0))
                continue
            response, elapsed = worker.request(dict(op='run', profile=DIRECT, source=source), protocol['queryTimeoutSeconds'])
            binding_start = time.perf_counter_ns()
            try:
                check_direct(source, response, model_hash)
            except (ValueError, KeyError, TypeError) as error:
                response = dict(status='ERROR', error=str(error), retainedResponse=response)
            rows.append(row(case, response, elapsed, time.perf_counter_ns() - binding_start))
        sequence['complete'] = True
    except Exception as error:
        sequence['error'] = f'{type(error).__name__}: {error}'
    finally:
        worker.close(graceful=True)
        if worker.receipt is not None:
            sequence['workers'].append(worker.receipt)
        sequence['wallNanos'] = time.perf_counter_ns() - start
        sequence['controllerCpuNanos'] = time.process_time_ns() - cpu
    observed = {entry['case'] for entry in rows}
    for case in protocol['cases']:
        if case['id'] not in observed:
            rows.append(row(case, dict(status='BLOCKED_WORKER_UNAVAILABLE'), 0, 0))
    return sequence, rows


def comparison_protocol(path):
    protocol = native.load_protocol(path)
    return dict(protocol, profiles=PROFILES, directSettings=SETTINGS, directObjective=OBJECTIVE,
                claim='PUBLIC_DEVELOPMENT_EXTENSION_OF_FROZEN_NATIVE_INPUTS')


def schedule(protocol):
    for repetition in range(protocol['repetitions']):
        for profile in PROFILES[repetition:] + PROFILES[:repetition]:
            yield profile, repetition


def commands_for(classpath, python):
    return {ORIGINAL: ['java', '-Xmx512m', '-cp', classpath, native.JAVA_WORKER],
            DIRECT: ['java', '-Xmx512m', '-cp', classpath, WORKER],
            REFERENCE: [python, '-u', '-m', 'external_polynomial_comparison.native_worker']}


def execute(classpath_file, output):
    protocol = comparison_protocol(PROTOCOL)
    provenance = native.source_provenance(Path.cwd())
    output.mkdir(parents=True, exist_ok=False)
    (output / 'source-protocol.json').write_bytes(PROTOCOL.read_bytes())
    runtime = snapshot_runtime(classpath_file.read_text().strip(), output)
    classpath = os.pathsep.join(str(output / entry) for entry in runtime['classpathEntries'])
    commands = commands_for(classpath, sys.executable)
    metadata = dict(provenance, java=_java_provenance(), python=sys.version, pythonExecutable=sys.executable,
                    sourceProtocolSha256=native.PROTOCOL_HASH, runtimeBytesHash=runtime['runtimeBytesHash'],
                    outputDirectoryAtExecution=str(output), commands=commands, sequences=[])
    if hasattr(os, 'sched_getaffinity'):
        metadata['affinityCpus'] = sorted(os.sched_getaffinity(0))
    rows = []
    for profile, repetition in schedule(protocol):
        if profile == DIRECT:
            sequence, cases = direct_sequence(protocol, repetition, commands[profile], output)
        else:
            sequence, cases = native._sequence(protocol, profile, repetition, commands[profile], output)
        metadata['sequences'].append(sequence)
        rows.extend(cases)
        print(json.dumps(dict(profile=profile, repetition=repetition, complete=sequence['complete'], rows=len(cases))), flush=True)
    require(native.source_provenance(Path.cwd()) == provenance, 'comparison source changed during execution')
    for name, data in [('rows', rows), ('protocol', protocol), ('metadata', metadata), ('quality', native.quality(protocol, rows))]:
        (output / f'{name}.json').write_bytes(canonical(data))
    (output / 'manifest.json').write_bytes(canonical(_file_inventory(output)))
    return verify(output)


def verify(output):
    def read(name):
        return json.loads((output / f'{name}.json').read_text())

    inventory = _file_inventory(output)
    inventory.pop('manifest.json')
    require(inventory == read('manifest'), 'evidence inventory differs')
    runtime = read('runtime-inventory')
    verify_runtime(output, runtime)
    protocol = comparison_protocol(output / 'source-protocol.json')
    require(protocol == read('protocol'), 'public direct panel protocol changed')
    rows, metadata = read('rows'), read('metadata')
    require(metadata['sourceProtocolSha256'] == native.PROTOCOL_HASH
            and metadata['runtimeBytesHash'] == runtime['runtimeBytesHash'], 'protocol or runtime binding changed')
    original_root = Path(metadata['outputDirectoryAtExecution'])
    classpath = os.pathsep.join(str(original_root / entry) for entry in runtime['classpathEntries'])
    require(original_root.is_absolute() and Path(metadata['pythonExecutable']).is_absolute()
            and metadata['commands'] == commands_for(classpath, metadata['pythonExecutable']), 'worker launch binding changed')
    quality = native.quality(protocol, rows)
    require(quality == read('quality'), 'quality summary differs from row results')
    sequences = metadata['sequences']
    require([(s['profile'], s['repetition']) for s in sequences] == list(schedule(protocol)), 'sequence rotation differs')
    models = {}
    resources = {profile: [] for profile in PROFILES}
    for sequence in sequences:
        profile, repetition = sequence['profile'], sequence['repetition']
        require(sequence['complete'] is True and len(sequence['workers']) == 1
                and sequence['ready']['status'] == 'READY', 'incomplete worker lifecycle')
        worker = sequence['workers'][0]
        require(worker['exitCode'] == 0 and worker['termination'] == 'GRACEFUL_EOF'
                and worker['cpu']['scope'] == CPU_SCOPE and natural(worker['pid']) > 0,
                'incomplete owned-worker receipt')
        cpu = worker['cpu']
        require(natural(cpu['userNanos']) + natural(cpu['systemNanos']) == natural(cpu['totalNanos']),
                'worker CPU partition changed')
        rss = natural(worker['peakRssBytes'])
        require(rss > 0, 'missing peak resident memory receipt')
        resources[profile].append(dict(wallSeconds=natural(sequence['wallNanos'])/1e9,
            cpuSeconds=(cpu['totalNanos'] + natural(sequence['controllerCpuNanos']))/1e9, peakWorkerRssMiB=rss/2**20))
        if profile == REFERENCE:
            require(not sequence['operations'] and sequence['ready']['sympy'] == protocol['sympyVersion'],
                    'reference procedure or version changed')
        else:
            require([op['op'] for op in sequence['operations']] == ['initialize'], 'unexpected setup lifecycle')
            response = sequence['operations'][0]['response']
            models[profile, repetition] = direct_setup(response) if profile == DIRECT else native._setup(protocol, profile, response)
    for row in rows:
        verify_row(row, protocol['queryTimeoutSeconds']*10**9)
        profile, response = row['profile'], row['response']
        if response['status'] != 'CANDIDATE' or profile == REFERENCE:
            continue
        model = models[profile, row['repetition']]
        if profile == DIRECT:
            check_direct(row['source'], response, model)
        else:
            native.check_response(profile, row['source'], response, model, protocol['workBudget'])
            native._audit(row['source'], response)
    return {profile: dict(quality={key: value for key, value in quality.get(profile, {}).items() if key != 'pairs'},
                          medians={key: statistics.median(s[key] for s in observations) for key in observations[0]},
                          observations=observations) for profile, observations in resources.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    output = args.output.resolve()
    if args.verify:
        report = verify(output)
    else:
        if args.classpath is None:
            parser.error('--classpath is required')
        report = execute(args.classpath, output)
    print(json.dumps(dict(evidenceVerified=True, profiles=report), indent=2))


if __name__ == '__main__':
    main()
