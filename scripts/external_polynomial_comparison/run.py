"""Supervise source-only workers, retain every row and independently judge outputs.

Logical Java work is not a CAS instruction count. The cross-system boundary is
wall time including request transport and the common exact verification. Setup,
TRAIN and fixture warmup are retained separately. Timing is never canonical.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import queue
import signal
import statistics
import subprocess
import sys
import threading
import time
from .polynomial import LIMITS, Unsupported, operation_cost, polynomial

PROTOCOL_HASH = '3308fe57ae55566552cd675fb7e43d4287c84402afa23b73ebd86777f2e82afc'
FREEZE_COMMIT = '476d41e1f3fecdcff9b4ccba45b13aa8a9265542'


def canonical(value) -> bytes:
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False, separators=(',', ':'))+'\n').encode('utf-8')


def load_protocol(path: Path) -> dict:
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != PROTOCOL_HASH:
        raise ValueError('protocol differs from pre-execution freeze; a new revision is required')
    result = json.loads(data)
    if result['limits'] != LIMITS:
        raise ValueError('judge limits differ from the frozen protocol')
    return result


class Session:
    """One persistent worker. Timeout kills its process group, never just a future."""
    def __init__(self, command: list[str], stderr: Path, timeout: float):
        self.messages = queue.Queue()
        self.failed = False
        self.stderr = stderr.open('w', encoding='utf-8')
        start = time.perf_counter_ns()
        try:
            self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=self.stderr, text=True, encoding='utf-8', bufsize=1, start_new_session=True)
        except BaseException:
            self.stderr.close()
            raise
        def read():
            try:
                while True:
                    line = self.process.stdout.readline(32*1024*1024 + 1)
                    if not line:
                        self.messages.put(RuntimeError('worker closed stdout'))
                        return
                    if len(line) > 32*1024*1024 or not line.endswith('\n'):
                        self.messages.put(RuntimeError('worker response size limit'))
                        return
                    self.messages.put(line)
            except (OSError, ValueError) as error:
                self.messages.put(error)
        threading.Thread(target=read, daemon=True).start()
        try:
            self.ready = self.receive(timeout)
            if self.ready.get('status') != 'READY':
                raise ValueError('worker did not announce READY')
        except BaseException:
            self.close()
            raise
        self.startup_nanos = time.perf_counter_ns() - start

    def receive(self, timeout: float) -> dict:
        value = self.messages.get(timeout=timeout)
        if isinstance(value, BaseException):
            raise value
        parsed = json.loads(value)
        if not isinstance(parsed, dict):
            raise ValueError('worker response is not an object')
        return parsed

    def request(self, payload: dict, timeout: float) -> tuple[dict, int]:
        if self.failed:
            return {'status': 'BLOCKED_WORKER_UNAVAILABLE'}, 0
        start = time.perf_counter_ns()
        try:
            self.process.stdin.write(json.dumps(payload, allow_nan=False)+'\n')
            self.process.stdin.flush()
            response = self.receive(timeout)
        except queue.Empty:
            self.close()
            response = {'status':'TIMEOUT'}
        except (OSError, RuntimeError, ValueError) as error:
            self.close()
            response = {'status':'ERROR', 'error':str(error)}
        return response, time.perf_counter_ns() - start

    def close(self):
        self.failed = True
        if self.process.poll() is None:
            if os.name == 'posix':
                os.killpg(self.process.pid, signal.SIGKILL)
            else:
                self.process.kill()
        self.process.wait()
        for stream in (self.process.stdin, self.process.stdout):
            if stream is not None:
                stream.close()
        self.stderr.close()


def judge(source: str, response: dict, request_nanos: int, limit_nanos: int) -> dict:
    started = time.perf_counter_ns()
    result = {'valid':False, 'improved':False, 'substantialImprovement':False,
              'verdict':response.get('status', 'ERROR'), 'savedOperations':None}
    if response.get('status') == 'SHARED_FRAGMENT_UNSUPPORTED':
        try:
            polynomial(source)
            result['verdict'] = 'COVERAGE_CONTRACT_MISMATCH'
        except Unsupported:
            pass
    if response.get('status') == 'CANDIDATE':
        try:
            output = response['output']
            before, after = operation_cost(source), operation_cost(output)
            result.update(inputCost=before, outputCost=after, savedOperations=before-after)
            if polynomial(source) != polynomial(output):
                result['verdict'] = 'INVALID_IDENTITY'
            elif ('outputCost' in response and response['outputCost'] != after) or ('inputCost' in response and response['inputCost'] != before):
                result['verdict'] = 'COST_CONTRACT_MISMATCH'
            else:
                result.update(valid=True, improved=after < before,
                    substantialImprovement=before > 0 and 5*(before-after) >= before,
                    verdict='IMPROVED' if after < before else 'UNCHANGED' if after == before else 'WORSE')
        except (Unsupported, KeyError, TypeError) as error:
            result.update(verdict='JUDGE_UNSUPPORTED', reason=str(error))
    result['verificationWallNanos'] = time.perf_counter_ns() - started
    result['endToEndNanos'] = request_nanos + result['verificationWallNanos']
    if response.get('status') == 'CANDIDATE' and result['endToEndNanos'] > limit_nanos:
        result.update(valid=False, improved=False, substantialImprovement=False, verdict='DEADLINE_EXCEEDED')
    return result


def require_matrix(protocol: dict, rows: list[dict]) -> None:
    expected = {(c['id'], profile, rep) for c in protocol['cases'] for profile in protocol['profiles'] for rep in range(protocol['repetitions'])}
    actual = [(row['case'], row['profile'], row['repetition']) for row in rows]
    if len(actual) != len(set(actual)) or set(actual) != expected:
        raise ValueError('incomplete, duplicated or unexpected case/profile/repetition matrix')


def untimed(value):
    if isinstance(value, dict):
        return {key:untimed(item) for key,item in value.items() if not key.endswith('Nanos')}
    if isinstance(value, list):
        return [untimed(item) for item in value]
    return value


def summarize(protocol: dict, rows: list[dict]) -> dict:
    result = {}
    for profile in protocol['profiles']:
        group = [row for row in rows if row['profile'] == profile]
        valid = [row for row in group if row['judgment']['valid']]
        result[profile] = {'rows':len(group), 'valid':len(valid),
            'improved':sum(row['judgment']['improved'] for row in group),
            'substantialImprovement':sum(row['judgment']['substantialImprovement'] for row in group),
            'worse':sum(row['judgment']['verdict']=='WORSE' for row in group),
            'unchanged':sum(row['judgment']['verdict']=='UNCHANGED' for row in group),
            'unsupported':sum(row['response']['status']=='SHARED_FRAGMENT_UNSUPPORTED' for row in group),
            'invalid':sum(row['judgment']['verdict']=='INVALID_IDENTITY' for row in group),
            'timeouts':sum(row['judgment']['verdict'] in ('TIMEOUT','DEADLINE_EXCEEDED') for row in group),
            'errors':sum(row['judgment']['verdict'] in ('ERROR','BLOCKED_WORKER_UNAVAILABLE','COST_CONTRACT_MISMATCH','COVERAGE_CONTRACT_MISMATCH','JUDGE_UNSUPPORTED') for row in group),
            'internalWorkOverruns':sum(row['response'].get('internalWorkWithinBudget') is False for row in group),
            'medianValidEndToEndNanos':statistics.median(row['judgment']['endToEndNanos'] for row in valid) if valid else None}
    return result


def paired_comparisons(protocol: dict, rows: list[dict]) -> dict:
    """Report every paired outcome; costs are compared only at equal output quality."""
    indexed = {(row['case'], row['profile'], row['repetition']): row for row in rows}
    result = {}
    for comparator in protocol['profiles']:
        if comparator == 'LEARNED_RANKED':
            continue
        pairs, counts, ratios = [], {}, []
        for case in protocol['cases']:
            for repetition in range(protocol['repetitions']):
                a = indexed[case['id'], 'LEARNED_RANKED', repetition]
                b = indexed[case['id'], comparator, repetition]
                ja, jb = a['judgment'], b['judgment']
                if a['response']['status'] == b['response']['status'] == 'SHARED_FRAGMENT_UNSUPPORTED':
                    outcome = 'SHARED_UNSUPPORTED'
                elif ja['valid'] and jb['valid']:
                    outcome = 'BETTER' if ja['outputCost'] < jb['outputCost'] else 'WORSE' if ja['outputCost'] > jb['outputCost'] else 'TIE'
                else:
                    outcome = 'RANKED_ONLY' if ja['valid'] else 'COMPARATOR_ONLY' if jb['valid'] else 'NEITHER_VALID'
                ratio = ja['endToEndNanos'] / jb['endToEndNanos'] if outcome == 'TIE' and jb['endToEndNanos'] > 0 else None
                if ratio is not None:
                    ratios.append(ratio)
                counts[outcome] = counts.get(outcome, 0) + 1
                pairs.append({'case': case['id'], 'repetition': repetition, 'outcome': outcome,
                    'rankedCost': ja.get('outputCost'), 'comparatorCost': jb.get('outputCost'),
                    'rankedEndToEndNanos': ja['endToEndNanos'], 'comparatorEndToEndNanos': jb['endToEndNanos'],
                    'equalQualityTimeRatio': ratio})
        result[comparator] = {'counts': counts, 'pairs': pairs, 'equalQualityCostRatios': ratios,
            'medianEqualQualityTimeRatio': statistics.median(ratios) if ratios else None}
    return result


def verify_row(row: dict, limit_nanos: int) -> None:
    """Recompute mathematical eligibility and check the retained timing partition."""
    retained = row['judgment']
    for value in (row['requestWallNanos'], retained['verificationWallNanos'], retained['endToEndNanos']):
        if type(value) is not int or value < 0:
            raise ValueError('invalid timing field')
    if row['requestWallNanos'] + retained['verificationWallNanos'] != retained['endToEndNanos']:
        raise ValueError('retained timing sum mismatch')
    checked = judge(row['source'], row['response'], 0, 10**18)
    if row['response'].get('status') == 'CANDIDATE' and retained['endToEndNanos'] > limit_nanos:
        checked.update(valid=False, improved=False, substantialImprovement=False, verdict='DEADLINE_EXCEEDED')
    for key in ('valid', 'improved', 'substantialImprovement', 'verdict', 'savedOperations', 'inputCost', 'outputCost'):
        if checked.get(key) != retained.get(key):
            raise ValueError(f'judgment mismatch: {key}')


def execute(protocol_path: Path, classpath_path: Path, output: Path) -> dict:
    protocol = load_protocol(protocol_path)
    subprocess.run(['git','merge-base','--is-ancestor',FREEZE_COMMIT,'HEAD'], check=True, capture_output=True)
    revision = subprocess.check_output(['git','rev-parse','HEAD'], text=True).strip()
    if output.exists():
        raise ValueError('output directory already exists; never overwrite a retained run')
    output.mkdir(parents=True)
    (output/'protocol.json').write_bytes(protocol_path.read_bytes())
    metadata = {'revision':revision, 'protocolHash':PROTOCOL_HASH, 'freezeCommit':FREEZE_COMMIT,
                'platform':platform.platform(), 'python':sys.version, 'setup':{}, 'warmups':[]}
    workers = {}
    rows = []
    try:
        commands = {'java':['java','-Xmx512m','-cp',classpath_path.read_text(encoding='utf-8').strip(),
            'de.regelsuche.evolution.ExternalPolynomialComparisonWorker'],
            'sympy':[sys.executable,'-u','-m','external_polynomial_comparison.native_worker']}
        for name, command in commands.items():
            try:
                workers[name] = Session(command, output/f'{name}.stderr.txt', protocol['setupTimeoutSeconds'])
                metadata['setup'][name] = {'ready':workers[name].ready,'startupNanos':workers[name].startup_nanos}
            except Exception as error:
                metadata['setup'][name] = {'error':str(error)}
        if 'sympy' in workers and workers['sympy'].ready.get('sympy') != protocol['sympyVersion']:
            raise ValueError('wrong SymPy version')
        if 'java' in workers:
            training, elapsed = workers['java'].request({'op':'initialize'}, protocol['setupTimeoutSeconds'])
            metadata['setup']['java']['training'] = training
            metadata['setup']['java']['trainingRequestNanos'] = elapsed
            expected = {key:protocol[key] for key in ('workBudget','primitiveDepth','searchDepth','maxStates','complexityDebt')}
            if training.get('settings') != expected:
                raise ValueError('Java preparation failed or settings differ from freeze')
        for profile in protocol['profiles']:
            worker = workers.get('sympy' if profile.startswith('SYMPY_') else 'java')
            if worker:
                response, elapsed = worker.request({'op':'run','profile':profile,'source':protocol['warmupSource']}, protocol['setupTimeoutSeconds'])
                metadata['warmups'].append({'profile':profile,'response':response,'requestWallNanos':elapsed})
        for repetition in range(protocol['repetitions']):
            for index, case in enumerate(protocol['cases']):
                try:
                    polynomial(case['source'])
                    unsupported = None
                except Unsupported as error:
                    unsupported = str(error)
                # Predeclared round-robin order; no winner-first timing or TEST feedback.
                offset = (index + repetition) % len(protocol['profiles'])
                profiles = protocol['profiles'][offset:] + protocol['profiles'][:offset]
                for profile in profiles:
                    worker = workers.get('sympy' if profile.startswith('SYMPY_') else 'java')
                    if unsupported:
                        response, elapsed = {'status':'SHARED_FRAGMENT_UNSUPPORTED','reason':unsupported}, 0
                    elif worker is None:
                        response, elapsed = {'status':'BLOCKED_WORKER_UNAVAILABLE'}, 0
                    else:
                        response, elapsed = worker.request({'op':'run','profile':profile,'source':case['source']}, protocol['queryTimeoutSeconds'])
                        if response.get('status') == 'CANDIDATE' and response.get('profile') != profile:
                            response = {'status':'ERROR','error':'worker profile mismatch','retainedResponse':response}
                    judgment = judge(case['source'], response, elapsed, protocol['queryTimeoutSeconds'] * 10**9)
                    rows.append({'case':case['id'],'profile':profile,'repetition':repetition,'source':case['source'],
                                 'response':response,'requestWallNanos':elapsed,'judgment':judgment})
        require_matrix(protocol, rows)
    finally:
        for worker in workers.values():
            worker.close()
        (output/'metadata.json').write_bytes(canonical(metadata))
        (output/'rows.json').write_bytes(canonical(rows))
    summary = summarize(protocol, rows)
    paired = paired_comparisons(protocol, rows)
    (output/'paired-comparisons.json').write_bytes(canonical(paired))
    (output/'canonical-rows.json').write_bytes(canonical(untimed(rows)))
    (output/'summary.json').write_bytes(canonical(summary))
    report = ['# External polynomial pilot', '', '**Public pilot, not a sealed final test or general superiority claim.**', '',
        f'Protocol `{PROTOCOL_HASH}`; source `{revision}`.', '',
        'Counts include three repetitions, not additional independent mathematical tasks.', '',
        '| Profile | Equivalent within deadline | Improved | At least 20% shorter | Worse | Unsupported | Timeouts | Errors |',
        '|---|---:|---:|---:|---:|---:|---:|---:|']
    for profile, row in summary.items():
        report.append('| '+profile+' | '+' | '.join(str(row[key]) for key in ('valid','improved','substantialImprovement','worse','unsupported','timeouts','errors'))+' |')
    report += ['', '## Paired output quality: LEARNED_RANKED versus each comparator', '',
        '| Comparator | Better | Tie | Worse | Ranked only | Comparator only | Neither valid | Shared unsupported |',
        '|---|---:|---:|---:|---:|---:|---:|---:|']
    for comparator, values in paired.items():
        report.append('| '+comparator+' | '+' | '.join(str(values['counts'].get(key, 0)) for key in
            ('BETTER','TIE','WORSE','RANKED_ONLY','COMPARATOR_ONLY','NEITHER_VALID','SHARED_UNSUPPORTED'))+' |')
    report += ['', 'Setup, TRAIN, warmups, all candidates, per-query native CPU/wall time, complete request time,',
               'common verification time, primitive replay and internal logical-work overruns are retained in JSON.',
               'The Java setup trains the shared experimental model once. No zero-cost learning or product-default qualification is implied.',
               'Internal work units are not comparable with SymPy instructions. Walltime is diagnostic, not a CI speed threshold.',
               'All supported failures stay in the matrix; no amortization or lifetime win is inferred from selected successes.']
    (output/'report.md').write_text('\n'.join(report)+'\n', encoding='utf-8')
    manifest = {path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(output.iterdir()) if path.is_file()}
    (output/'manifest.json').write_bytes(canonical(manifest))
    verify(output)
    return summary


def verify(output: Path) -> None:
    protocol = load_protocol(output/'protocol.json')
    manifest = json.loads((output/'manifest.json').read_text(encoding='utf-8'))
    if set(manifest) != {p.name for p in output.iterdir() if p.is_file() and p.name != 'manifest.json'}:
        raise ValueError('manifest file inventory mismatch')
    for name, digest in manifest.items():
        if Path(name).name != name or hashlib.sha256((output/name).read_bytes()).hexdigest() != digest:
            raise ValueError('manifest digest mismatch')
    rows = json.loads((output/'rows.json').read_text(encoding='utf-8'))
    require_matrix(protocol, rows)
    sources = {case['id']:case['source'] for case in protocol['cases']}
    for row in rows:
        if row['source'] != sources[row['case']]:
            raise ValueError('case source mismatch')
        verify_row(row, protocol['queryTimeoutSeconds']*10**9)
    if (output/'canonical-rows.json').read_bytes() != canonical(untimed(rows)):
        raise ValueError('canonical rows do not correspond to raw evidence')
    if json.loads((output/'summary.json').read_text(encoding='utf-8')) != summarize(protocol, rows):
        raise ValueError('summary mismatch')
    if json.loads((output/'paired-comparisons.json').read_text(encoding='utf-8')) != paired_comparisons(protocol, rows):
        raise ValueError('paired comparison mismatch')


def main() -> None:
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--protocol', type=Path, default=Path('config/benchmarks/external-polynomial-v1.json'))
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
        if any(value['errors'] for value in summary.values()):
            raise SystemExit('pilot contains technical/incomplete rows; see retained artifacts')

if __name__ == '__main__':
    main()
