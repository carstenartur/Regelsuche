"""Declared native polynomial quality comparison, using the existing frontier and independent judge."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import sys
import time
from .polynomial import Unsupported, polynomial
from .run import canonical, judge, require_matrix, summarize, verify_row
from .run_schema import (AccountedSession, audit_response, snapshot_runtime, source_provenance,
                         verify_runtime, _file_inventory, _java_provenance)
from .run_typed import natural

PROTOCOL_HASH = 'c35902e09c4c6e0b49e7b1c5257bf5f696ca867ae8c6a1880a071f29ad758f3e'
JAVA_WORKER = 'de.regelsuche.evolution.NativePolynomialComparisonWorker'
SETTINGS = ('workBudget', 'maxTheoryWork', 'primitiveDepth', 'searchDepth', 'maxStates', 'complexityDebt')


def load_protocol(path):
    raw = Path(path).read_bytes()
    if hashlib.sha256(raw).hexdigest() != PROTOCOL_HASH:
        raise ValueError('native protocol differs from its pre-execution freeze')
    return json.loads(raw)


def check_response(profile, source, response, model_hash, budget):
    if response.get('status') != 'CANDIDATE':
        return
    if response.get('profile') != profile:
        raise ValueError('response changed profile')
    if profile == 'SYMPY_PORTFOLIO':
        return
    if (response.get('input') != source or response.get('modelHash') != model_hash
            or response.get('target') != '' or response.get('targetReached') is not False):
        raise ValueError('response is not bound to source-only frozen knowledge')
    total = sum(natural(response.get(key, 0)) for key in ('adapterWork', 'selectionWork', 'selectedReplayWork'))
    total += natural(json.loads(response['search'])['totalWork'])
    if total != natural(response['totalWork']) or response.get('internalWorkWithinBudget') is not (total <= budget):
        raise ValueError('adapter/search/replay costs missing or budget outcome hidden')


def quality(protocol, rows):
    require_matrix(protocol, rows)
    indexed = {(row['case'], row['profile'], row['repetition']): row for row in rows}
    result = {}
    for profile in protocol['profiles']:
        if profile == 'SYMPY_PORTFOLIO':
            continue
        pairs = []
        for case in protocol['cases']:
            for repetition in range(protocol['repetitions']):
                a = indexed[case['id'], profile, repetition]['judgment']
                b = indexed[case['id'], 'SYMPY_PORTFOLIO', repetition]['judgment']
                if a['verdict'] == b['verdict'] == 'SHARED_FRAGMENT_UNSUPPORTED':
                    outcome = 'UNSUPPORTED'
                elif not a['valid'] or not b['valid']:
                    outcome = 'INCOMPLETE'
                else:
                    delta = a['outputCost'] - b['outputCost']
                    outcome = 'BETTER' if delta < 0 else 'WORSE' if delta > 0 else 'TIE'
                pairs.append(dict(case=case['id'], cohort=case['cohort'], repetition=repetition, outcome=outcome,
                                  nativeCost=a.get('outputCost'), sympyCost=b.get('outputCost')))
        counts = {name.lower(): sum(p['outcome'] == name for p in pairs)
                  for name in ('BETTER', 'TIE', 'WORSE', 'INCOMPLETE', 'UNSUPPORTED')}
        result[profile] = dict(counts, accepted=not counts['worse'] and not counts['incomplete'], pairs=pairs)
    return result


def _setup(protocol, profile, response, restored=False):
    expected = 'INITIALIZED' if restored or profile == 'NATIVE_BASE' else 'ACQUIRED'
    if response.get('status') != expected or response.get('profile') != profile:
        raise ValueError('worker setup failed')
    if response.get('settings') != {key: protocol[key] for key in SETTINGS}:
        raise ValueError('native worker settings differ from protocol')
    digest = 'sha256:' + hashlib.sha256(response['model'].encode()).hexdigest()
    if response.get('modelHash') != digest:
        raise ValueError('model hash mismatch')
    if profile == 'NATIVE_BASE' and (response['trainingWork'] or response['restorationWork'] or response['schemas']):
        raise ValueError('baseline performed unused learning')
    if profile == 'NATIVE_LEARNED' and not (response['restorationWork'] if restored else response['trainingWork']) > 0:
        raise ValueError('missing paid training/restoration')
    return digest


def _audit(source, response):
    normalized = dict(response, totalWork=response['totalWork'] - response['adapterWork'])
    return audit_response(source, normalized, allowed_source_kinds=('PRIMITIVE', 'LEARNED', 'PREPARATION'))


def _sequence(protocol, profile, repetition, command, output):
    started, cpu = time.perf_counter_ns(), time.process_time_ns()
    sequence = dict(profile=profile, repetition=repetition, workers=[], operations=[], complete=False)
    worker, rows, model_hash = None, [], None
    try:
        worker = AccountedSession(command, output / f'{profile}-{repetition}.stderr.txt', protocol['setupTimeoutSeconds'])
        sequence['ready'] = worker.ready
        if profile == 'SYMPY_PORTFOLIO':
            if worker.ready.get('sympy') != protocol['sympyVersion']:
                raise ValueError('wrong SymPy version')
        else:
            acquired, elapsed = worker.request(dict(op='initialize', profile=profile), protocol['setupTimeoutSeconds'])
            sequence['operations'].append(dict(op='initialize', response=acquired, requestWallNanos=elapsed))
            model_hash = _setup(protocol, profile, acquired)
            if profile == 'NATIVE_LEARNED':
                model = output / f'{profile}-{repetition}.model.json'
                model.write_text(acquired['model'], encoding='utf-8')
                worker.close(graceful=True)
                sequence['workers'].append(worker.receipt)
                worker = AccountedSession(command, output / f'{profile}-{repetition}-consumer.stderr.txt', protocol['setupTimeoutSeconds'])
                restored, elapsed = worker.request(dict(op='restore', profile=profile, model=model.read_text()), protocol['setupTimeoutSeconds'])
                sequence['operations'].append(dict(op='restore', response=restored, requestWallNanos=elapsed))
                if _setup(protocol, profile, restored, True) != model_hash:
                    raise ValueError('fresh consumer changed model bytes')
        for case in protocol['cases']:
            try:
                polynomial(case['source'])
            except Unsupported as error:
                response, elapsed, binding = dict(status='SHARED_FRAGMENT_UNSUPPORTED', reason=str(error)), 0, 0
            else:
                response, elapsed = worker.request(dict(op='run', profile=profile, source=case['source']), protocol['queryTimeoutSeconds'])
                binding_start = time.perf_counter_ns()
                try:
                    check_response(profile, case['source'], response, model_hash, protocol['workBudget'])
                    if response.get('status') == 'CANDIDATE' and profile != 'SYMPY_PORTFOLIO':
                        _audit(case['source'], response)
                except (ValueError, KeyError, TypeError) as error:
                    response = dict(status='ERROR', error=str(error), retainedResponse=response)
                binding = time.perf_counter_ns() - binding_start
            judgment = judge(case['source'], response, elapsed + binding, protocol['queryTimeoutSeconds'] * 10**9)
            rows.append(dict(case=case['id'], profile=profile, repetition=repetition, source=case['source'], response=response,
                             requestWallNanos=elapsed + binding, bindingWallNanos=binding, judgment=judgment))
        sequence['complete'] = True
    except Exception as error:
        sequence['error'] = str(error)
    finally:
        if worker is not None:
            worker.close(graceful=True)
            sequence['workers'].append(worker.receipt)
        sequence['wallNanos'] = time.perf_counter_ns() - started
        sequence['controllerCpuNanos'] = time.process_time_ns() - cpu
    present = {row['case'] for row in rows}
    for case in protocol['cases']:
        if case['id'] not in present:
            response = dict(status='BLOCKED_WORKER_UNAVAILABLE')
            rows.append(dict(case=case['id'], profile=profile, repetition=repetition, source=case['source'], response=response,
                             requestWallNanos=0, judgment=judge(case['source'], response, 0, protocol['queryTimeoutSeconds'] * 10**9)))
    return sequence, rows


def execute(protocol_path, classpath_path, output):
    protocol = load_protocol(protocol_path)
    provenance = source_provenance(Path.cwd())
    output.mkdir(parents=True, exist_ok=False)
    (output / 'protocol.json').write_bytes(Path(protocol_path).read_bytes())
    runtime = snapshot_runtime(Path(classpath_path).read_text().strip(), output)
    java = ['java', '-Xmx512m', '-cp', os.pathsep.join(str((output / p).resolve()) for p in runtime['classpathEntries']), JAVA_WORKER]
    sympy = [sys.executable, '-u', '-m', 'external_polynomial_comparison.native_worker']
    metadata = dict(provenance, platform=platform.platform(), python=sys.version, java=_java_provenance(),
                    runtimeBytesHash=runtime['runtimeBytesHash'], commands=dict(java=java, sympy=sympy), sequences=[])
    rows = []
    for repetition in range(protocol['repetitions']):
        profiles = protocol['profiles'][repetition:] + protocol['profiles'][:repetition]
        for profile in profiles:
            sequence, attempted = _sequence(protocol, profile, repetition, sympy if profile == 'SYMPY_PORTFOLIO' else java, output)
            metadata['sequences'].append(sequence)
            rows.extend(attempted)
            print(json.dumps(dict(profile=profile, repetition=repetition, complete=sequence['complete'], rows=len(attempted))), flush=True)
    for name, value in [('metadata.json', metadata), ('rows.json', rows), ('summary.json', summarize(protocol, rows)),
                        ('quality.json', quality(protocol, rows))]:
        (output / name).write_bytes(canonical(value))
    inventory = _file_inventory(output)
    (output / 'manifest.json').write_bytes(canonical(inventory))
    verify(output)
    return quality(protocol, rows)


def verify(output):
    protocol = load_protocol(output / 'protocol.json')
    inventory = _file_inventory(output)
    inventory.pop('manifest.json')
    if inventory != json.loads((output / 'manifest.json').read_text()):
        raise ValueError('evidence inventory/digest mismatch')
    runtime = json.loads((output / 'runtime-inventory.json').read_text())
    verify_runtime(output, runtime)
    rows = json.loads((output / 'rows.json').read_text())
    require_matrix(protocol, rows)
    metadata = json.loads((output / 'metadata.json').read_text())
    if metadata['runtimeBytesHash'] != runtime['runtimeBytesHash']:
        raise ValueError('runtime binding mismatch')
    setups = {(s['profile'], s['repetition']): s for s in metadata['sequences']}
    for row in rows:
        verify_row(row, protocol['queryTimeoutSeconds'] * 10**9)
        sequence = setups[row['profile'], row['repetition']]
        if row['profile'] != 'SYMPY_PORTFOLIO' and row['response'].get('status') == 'CANDIDATE':
            operations = sequence['operations']
            first = operations[0]['response']
            model = _setup(protocol, row['profile'], first)
            if row['profile'] == 'NATIVE_LEARNED':
                if _setup(protocol, row['profile'], operations[1]['response'], True) != model:
                    raise ValueError('restoration changed frozen knowledge')
            check_response(row['profile'], row['source'], row['response'], model, protocol['workBudget'])
            _audit(row['source'], row['response'])
    for name, value in [('summary.json', summarize(protocol, rows)), ('quality.json', quality(protocol, rows))]:
        if json.loads((output / name).read_text()) != value:
            raise ValueError('retained summary mismatch')


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--protocol', type=Path, default=Path('config/benchmarks/native-polynomial-v1.json'))
    parser.add_argument('--classpath', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    if args.verify:
        verify(args.output)
    else:
        if args.classpath is None:
            parser.error('--classpath is required')
        result = execute(args.protocol, args.classpath, args.output)
        print(json.dumps({p: {k: v for k, v in r.items() if k != 'pairs'} for p, r in result.items()}, indent=2))
        if not all(value['accepted'] for value in result.values()):
            raise SystemExit('native polynomial quality acceptance unmet; complete evidence retained')


if __name__ == '__main__':
    main()
