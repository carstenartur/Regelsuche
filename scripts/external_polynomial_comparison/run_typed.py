"""Accounted typed-learning development comparison; no protected FINAL TEST is read."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import signal
from pathlib import Path
import platform
import subprocess
import sys
import time
from .run import (Session, canonical, judge, paired_comparisons, require_matrix,
                  summarize, untimed, verify_row)
from .polynomial import LIMITS, Unsupported, polynomial

PROTOCOL_HASH = 'ccf2d953cbc43b99f9eb9ec5abe3512bcd9eb8df63a3ae2ccbf8c09264d7078e'
FREEZE_COMMIT = 'c7f0f6cfc69e1b45b37497109bf5a11686d08f9a'
SETTING_KEYS = ('workBudget', 'primitiveDepth', 'searchDepth', 'maxStates', 'complexityDebt')
WORK_KEYS = {'formation', 'historySearch', 'historyMemory', 'policyTrials'}


class MeasuredSession(Session):
    """Keep the existing transport; reap this owned worker with OS CPU receipts.

    wait4 includes startup, model serialization and all worker threads. The
    controller is measured separately. No invented CPU value on unsupported OSes.
    """
    def __init__(self, command, stderr, timeout):
        self.process_cpu = None
        super().__init__(command, stderr, timeout)

    def close(self):
        if self.process.returncode is None and hasattr(os, 'wait4'):
            self.failed = True
            try:
                os.killpg(self.process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            try:
                _, status, usage = os.wait4(self.process.pid, 0)
                self.process.returncode = os.waitstatus_to_exitcode(status)
                user, system = round(usage.ru_utime * 10**9), round(usage.ru_stime * 10**9)
                self.process_cpu = {'userNanos': user, 'systemNanos': system, 'totalNanos': user + system,
                                    'scope': 'OWNED_WORKER_PROCESS_ALL_THREADS;CONTROLLER_REPORTED_SEPARATELY'}
            except ChildProcessError:
                # A previously reaped child has no available receipt, not zero CPU.
                pass
        super().close()


def load_protocol(path: Path) -> dict:
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != PROTOCOL_HASH:
        raise ValueError('typed protocol differs from its pre-execution freeze')
    if LIMITS != {'inputCharacters': 2048, 'astNodes': 256, 'variables': 4,
                  'maxExponent': 12, 'maxTerms': 4096, 'coefficientBits': 2048}:
        raise ValueError('shared judge limits changed')
    return json.loads(data)


def natural(value) -> int:
    if type(value) is not int or value < 0:
        raise ValueError('expected a nonnegative integer measurement')
    return value


def check_setup(protocol: dict, profile: str, setup: dict) -> str:
    if setup.get('status') != 'INITIALIZED' or setup.get('profile') != profile:
        raise ValueError('typed profile initialization failed')
    if setup.get('settings') != {key: protocol[key] for key in SETTING_KEYS}:
        raise ValueError('typed settings differ from the frozen protocol')
    model_hash = 'sha256:' + hashlib.sha256(setup['model'].encode('utf-8')).hexdigest()
    if model_hash != setup.get('modelHash') or json.loads(setup['model']).get('profile') != profile:
        raise ValueError('frozen model/profile hash mismatch')
    costs = setup['trainingWorkComponents']
    if set(costs) != WORK_KEYS or sum(natural(value) for value in costs.values()) != natural(setup['trainingWork']):
        raise ValueError('training work partition mismatch')
    if profile in ('BASE', 'EXPERT') and (setup['trainingWork'] != 0 or setup['learnedPrograms'] != 0):
        raise ValueError('fixed control performed unused learning')
    return model_hash


def check_response(profile: str, response: dict, model_hash: str | None, budget: int) -> None:
    if response.get('status') != 'CANDIDATE':
        return
    if response.get('profile') != profile:
        raise ValueError('worker switched profile')
    if profile.startswith('SYMPY_'):
        return
    if model_hash is None or response.get('modelHash') != model_hash or response.get('target') != '' or response.get('targetReached') is not False:
        raise ValueError('model changed or evaluation received a target')
    search = json.loads(response['search'])
    if search.get('reached') is not False:
        raise ValueError('source-only search reported a target hit')
    total = natural(search['totalWork']) + natural(response['selectionWork']) + natural(response['selectedReplayWork'])
    if total != natural(response['totalWork']) or response.get('internalWorkWithinBudget') is not (total <= budget):
        raise ValueError('selection/replay work missing or overrun hidden')


def lifecycle(protocol: dict, metadata: dict, rows: list[dict]) -> dict:
    """All attempts count. Native method CPU is diagnostic, not whole-process CPU."""
    result = {}
    for profile in protocol['profiles']:
        setup = metadata['setup'].get(profile, {})
        warmups = [item for item in metadata['warmups'] if item['profile'] == profile]
        queries = [row for row in rows if row['profile'] == profile]
        preparation = sum(natural(setup.get(key, 0)) for key in ('startupNanos', 'trainingRequestNanos', 'freezePersistenceNanos'))
        warmup_time = sum(natural(item['requestWallNanos']) for item in warmups)
        query_time = sum(natural(row['judgment']['endToEndNanos']) for row in queries)
        responses = [item.get('response', {}) for item in warmups] + [row['response'] for row in queries]
        if 'training' in setup:
            responses.append(setup['training'])
        measured = [response.get('nativeCpuNanos') for response in responses if response.get('status') != 'SHARED_FRAGMENT_UNSUPPORTED']
        result[profile] = {'preparationWallNanos': preparation, 'warmupWallNanos': warmup_time,
            'queryAndVerificationWallNanos': query_time, 'lifecycleWallNanos': preparation + warmup_time + query_time,
            'trainingWork': setup.get('training', {}).get('trainingWork', 0),
            'reportedNativeMethodCpuNanos': sum(natural(value) for value in measured if value is not None),
            'completeNativeCpu': bool(measured) and all(value is not None for value in measured),
            'cpuScope': 'REPORTED_NATIVE_METHODS_ONLY;EXCLUDES_STARTUP_AND_SERIALIZATION_CPU;NOT_LIFECYCLE_CPU',
            'wholeWorkerCpu': setup.get('processCpu'),
            'attemptedQueries': len(queries), 'eligibleQueries': sum(row['judgment']['valid'] for row in queries)}
    return result


def execute(protocol_path: Path, classpath_path: Path, output: Path) -> dict:
    controller_start = time.process_time_ns()
    protocol = load_protocol(protocol_path)
    subprocess.run(['git', 'merge-base', '--is-ancestor', FREEZE_COMMIT, 'HEAD'], check=True, capture_output=True)
    subprocess.run(['git', 'diff', '--exit-code', 'HEAD'], check=True, capture_output=True)
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    classpath = classpath_path.read_text(encoding='utf-8').strip()
    if not classpath:
        raise ValueError('empty Java classpath')
    output.mkdir(parents=True, exist_ok=False)
    (output / 'protocol.json').write_bytes(protocol_path.read_bytes())
    metadata = {'revision': revision, 'protocolHash': PROTOCOL_HASH, 'freezeCommit': FREEZE_COMMIT,
                'platform': platform.platform(), 'python': sys.version, 'setup': {}, 'warmups': [],
                'split': 'PUBLIC_DEVELOPMENT;PER_PROFILE_IN_PROCESS_TRAIN_FREEZE;NOT_BLINDED_OR_PROCESS_RESTORED_MODEL'}
    workers, all_workers, models, rows = {}, {}, {}, []
    try:
        for profile in protocol['profiles']:
            command = ([sys.executable, '-u', '-m', 'external_polynomial_comparison.native_worker']
                if profile.startswith('SYMPY_') else ['java', '-Xmx512m', '-cp', classpath,
                    'de.regelsuche.evolution.TypedExternalPolynomialComparisonWorker'])
            try:
                worker = MeasuredSession(command, output / f'{profile}.stderr.txt', protocol['setupTimeoutSeconds'])
                workers[profile] = worker
                all_workers[profile] = worker
                metadata['setup'][profile] = {'ready': worker.ready, 'startupNanos': worker.startup_nanos}
                if profile.startswith('SYMPY_'):
                    if worker.ready.get('sympy') != protocol['sympyVersion']:
                        raise ValueError('wrong SymPy version')
                else:
                    training, elapsed = worker.request({'op': 'initialize', 'profile': profile}, protocol['setupTimeoutSeconds'])
                    metadata['setup'][profile].update(training=training, trainingRequestNanos=elapsed)
                    start = time.perf_counter_ns()
                    models[profile] = check_setup(protocol, profile, training)
                    (output / f'{profile}.model.json').write_bytes(training['model'].encode('utf-8'))
                    metadata['setup'][profile]['freezePersistenceNanos'] = time.perf_counter_ns() - start
            except Exception as error:
                metadata['setup'].setdefault(profile, {})['error'] = str(error)
                if profile in workers:
                    workers[profile].close()
                    del workers[profile]
        for profile, worker in workers.items():
            response, elapsed = worker.request({'op': 'run', 'profile': profile, 'source': protocol['warmupSource']}, protocol['setupTimeoutSeconds'])
            metadata['warmups'].append({'profile': profile, 'response': response, 'requestWallNanos': elapsed})
            try:
                check_response(profile, response, models.get(profile), protocol['workBudget'])
            except (ValueError, KeyError, TypeError):
                worker.close()
        for repetition in range(protocol['repetitions']):
            for index, case in enumerate(protocol['cases']):
                try:
                    polynomial(case['source'])
                    unsupported = None
                except Unsupported as error:
                    unsupported = str(error)
                offset = (index + repetition) % len(protocol['profiles'])
                for profile in protocol['profiles'][offset:] + protocol['profiles'][:offset]:
                    worker = workers.get(profile)
                    if unsupported:
                        response, elapsed = {'status': 'SHARED_FRAGMENT_UNSUPPORTED', 'reason': unsupported}, 0
                    elif worker is None:
                        response, elapsed = {'status': 'BLOCKED_WORKER_UNAVAILABLE'}, 0
                    else:
                        response, elapsed = worker.request({'op': 'run', 'profile': profile, 'source': case['source']}, protocol['queryTimeoutSeconds'])
                    start = time.perf_counter_ns()
                    try:
                        check_response(profile, response, models.get(profile), protocol['workBudget'])
                    except (ValueError, KeyError, TypeError) as error:
                        response = {'status': 'ERROR', 'error': str(error), 'retainedResponse': response}
                    binding = time.perf_counter_ns() - start
                    judgment = judge(case['source'], response, elapsed + binding, protocol['queryTimeoutSeconds'] * 10**9)
                    rows.append({'case': case['id'], 'profile': profile, 'repetition': repetition, 'source': case['source'],
                        'response': response, 'transportWallNanos': elapsed, 'bindingWallNanos': binding,
                        'requestWallNanos': elapsed + binding, 'judgment': judgment})
        require_matrix(protocol, rows)
    finally:
        for profile, worker in all_workers.items():
            worker.close()
            metadata['setup'][profile]['processCpu'] = worker.process_cpu
        metadata['controllerProcessCpuNanos'] = time.process_time_ns() - controller_start
        metadata['controllerCpuScope'] = 'SETUP_TRAIN_FREEZE_WARMUP_QUERIES_BINDING_AND_COMMON_VERIFICATION;EXCLUDES_FINAL_REPORT_EXPORT'
        (output / 'metadata.json').write_bytes(canonical(metadata))
        (output / 'rows.json').write_bytes(canonical(rows))
    summary = summarize(protocol, rows)
    for name, value in [('summary.json', summary), ('paired-comparisons.json', paired_comparisons(protocol, rows)),
                        ('canonical-rows.json', untimed(rows)), ('lifecycle.json', lifecycle(protocol, metadata, rows))]:
        (output / name).write_bytes(canonical(value))
    (output / 'report.md').write_text('# Typed external development comparison\n\n'
        '24 polynomial sources, three unsupported controls, nine profiles, three repetitions.\n'
        'Public development evidence, not a blinded FINAL TEST or a general superiority claim.\n\n'
        'See summary.json and paired-comparisons.json for all successes and failures.\n'
        'lifecycle.json pays startup, actual training, model freeze, warmup and all requests plus common verification.\n'
        'OS wait4 receipts retain each complete worker CPU lifetime, including startup and serialization.\n'
        'Controller CPU is retained separately, not assigned for free to one competing profile.\n'
        'BASE and EXPERT do not run unused training. All other actual learning costs are retained.\n'
        'Logical work is not a CAS instruction count. Selection/replay overruns are ineligible, not erased.\n'
        'The original polynomial pilot and protected studies remain unchanged.\n', encoding='utf-8')
    manifest = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(output.iterdir()) if path.is_file()}
    (output / 'manifest.json').write_bytes(canonical(manifest))
    verify(output)
    return summary


def verify(output: Path) -> None:
    protocol = load_protocol(output / 'protocol.json')
    manifest = json.loads((output / 'manifest.json').read_text(encoding='utf-8'))
    if set(manifest) != {path.name for path in output.iterdir() if path.is_file() and path.name != 'manifest.json'}:
        raise ValueError('manifest inventory mismatch')
    for name, digest in manifest.items():
        if Path(name).name != name or hashlib.sha256((output / name).read_bytes()).hexdigest() != digest:
            raise ValueError('manifest digest mismatch')
    metadata = json.loads((output / 'metadata.json').read_text(encoding='utf-8'))
    models = {}
    for profile, setup in metadata['setup'].items():
        if 'error' not in setup and not profile.startswith('SYMPY_'):
            models[profile] = check_setup(protocol, profile, setup['training'])
            if (output / f'{profile}.model.json').read_bytes() != setup['training']['model'].encode('utf-8'):
                raise ValueError('model file differs from pre-query setup')
    rows = json.loads((output / 'rows.json').read_text(encoding='utf-8'))
    require_matrix(protocol, rows)
    sources = {case['id']: case['source'] for case in protocol['cases']}
    for row in rows:
        if row['source'] != sources[row['case']] or natural(row['transportWallNanos']) + natural(row['bindingWallNanos']) != row['requestWallNanos']:
            raise ValueError('source or request timing partition mismatch')
        check_response(row['profile'], row['response'], models.get(row['profile']), protocol['workBudget'])
        verify_row(row, protocol['queryTimeoutSeconds'] * 10**9)
    for name, value in [('summary.json', summarize(protocol, rows)), ('paired-comparisons.json', paired_comparisons(protocol, rows)),
                        ('canonical-rows.json', untimed(rows)), ('lifecycle.json', lifecycle(protocol, metadata, rows))]:
        if (output / name).read_bytes() != canonical(value):
            raise ValueError(f'{name} differs from retained evidence')


def main() -> None:
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--protocol', type=Path, default=Path('config/benchmarks/typed-external-polynomial-v1.json'))
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
        if any(row['errors'] or row['invalid'] for row in summary.values()):
            raise SystemExit('typed comparison contains technical/invalid rows; original evidence retained')

if __name__ == '__main__':
    main()
