"""Compare cold native and GraalPy factorization on identical source-only inputs."""

import argparse
import collections
from fractions import Fraction
import hashlib
import json
import os
from pathlib import Path
import resource
import runpy
import statistics
import subprocess
import tempfile
import time

STUDY = Path(__file__).resolve().parent
ROOT = STUDY.parents[3]
BRIDGE = ROOT / ('regelsuche-math-sympy/src/main/resources/GRAALPY-VFS/de.regelsuche/'
                 'regelsuche-math-sympy/src/regelsuche_sympy_factor.py')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def polynomial(terms):
    result = {}
    for term in terms:
        degree, = term['exponents']
        assert type(degree) is int and degree >= 0 and degree not in result
        coefficient = Fraction(int(term['numerator']), int(term['denominator']))
        assert coefficient
        result[degree] = coefficient
    return result


def multiply(left, right):
    result = collections.defaultdict(Fraction)
    for x, a in left.items():
        for y, b in right.items():
            result[x + y] += a * b
    return {degree: coefficient for degree, coefficient in result.items() if coefficient}


def checked_score(request, reply, arm):
    if 'factors' not in reply:
        if arm != 'native' or reply['outcome'] not in ('NO_FACTORIZATION_FOUND', 'BUDGET_INCONCLUSIVE'):
            raise ValueError('unexpected result without factors: ' + str(reply))
        return None
    unit = reply['unit']
    product = {0: Fraction(int(unit['numerator']), int(unit['denominator']))}
    degrees = []
    for factor in reply['factors']:
        terms = polynomial(factor['terms'])
        degree = max(terms)
        multiplicity = factor['multiplicity']
        assert degree > 0 and type(multiplicity) is int and multiplicity > 0
        for _ in range(multiplicity):
            product = multiply(product, terms)
            degrees.append(degree)
    assert product == polynomial(request['terms'])
    return sorted(degrees, reverse=True)


def cpu():
    usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    return usage.ru_utime + usage.ru_stime


def timed(command, row, arm, repetition, limit):
    payload = row['bridgePayload'].encode()
    output = {'case': row['case'], 'inputId': row['inputId'], 'checkpoint': row['checkpoint'], 'path': row['path'],
              'arm': arm, 'repetition': repetition}
    before_cpu = cpu()
    start = time.monotonic_ns()
    try:
        result = subprocess.run(command, input=payload, capture_output=True, timeout=limit,
                                check=True, env={'SYMPY_GROUND_TYPES': 'python'})
        reply = json.loads(result.stdout)
        if reply['inputSha256'] != digest(payload):
            raise ValueError('response differs from requested polynomial')
        score = checked_score(json.loads(payload), reply, arm)
        elapsed = time.monotonic_ns() - start
        if elapsed <= limit * 1_000_000_000:
            output.update(status='COMPLETED', elapsedNanos=elapsed, degrees=score, reply=reply)
        else:
            output.update(status='TIME_LIMIT', elapsedNanos=elapsed)
    except subprocess.TimeoutExpired:
        output.update(status='TIME_LIMIT', elapsedNanos=time.monotonic_ns() - start)
    output['childCpuSeconds'] = cpu() - before_cpu
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--provision', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    pins = json.loads((STUDY.parent / 'protocol.json').read_bytes())
    protocol_bytes = (STUDY / 'protocol.json').read_bytes()
    protocol = json.loads(protocol_bytes)
    prefixes = (STUDY.parent / 'prefix-rows.jsonl').read_bytes()
    if digest(prefixes) != protocol['prefixRowsSha256'] or digest(prefixes) != pins['files']['prefix-rows.jsonl']:
        raise ValueError('source-only comparison corpus differs')
    for name, expected in protocol['workers'].items():
        if digest((STUDY / name).read_bytes()) != expected:
            raise ValueError('comparison worker source differs: ' + name)
    rows = [row for line in prefixes.splitlines() if 'bridgePayload' in (row := json.loads(line))]
    assert len(rows) == protocol['selectedOccurrences']
    unpack = runpy.run_path(str(STUDY.parent / 'reproduce.py'))['unpack_provision']
    observations = []
    with tempfile.TemporaryDirectory() as isolated:
        setup_start = time.monotonic_ns()
        runtime, packages = unpack(args.provision, Path(isolated), pins)
        provision_nanos = time.monotonic_ns() - setup_start
        commands = {
            'native': [args.java, '-Xmx384m', '-cp', args.classpath, 'NativeFactorizationWorker'],
            'external': [runtime, '-I', STUDY / 'reference.py', BRIDGE, packages],
        }
        for repetition in range(protocol['repetitions']):
            for index, row in enumerate(rows):
                arms = ('native', 'external') if (repetition + index) % 2 == 0 else ('external', 'native')
                for arm in arms:
                    observations.append(timed(commands[arm], row, arm, repetition, protocol['wallSecondsPerQuery']))
    summary = {}
    for arm in ('native', 'external'):
        own = [v for v in observations if v['arm'] == arm]
        completed = [v for v in own if v['status'] == 'COMPLETED']
        summary[arm] = {'requests': len(own), 'completed': len(completed),
                        'nontrivial': sum(len(v['degrees'] or []) > 1 for v in completed),
                        'medianWallMillis': statistics.median(v['elapsedNanos'] for v in completed) / 1e6,
                        'totalWallSeconds': sum(v['elapsedNanos'] for v in own) / 1e9}
    receipt = {
        'comparisonProtocolSha256': digest(protocol_bytes), 'affinity': sorted(os.sched_getaffinity(0)),
        'javaVersion': subprocess.run([args.java, '-version'], capture_output=True, check=True, text=True).stderr,
        'externalArchiveProvisionNanos': provision_nanos,
        'nativeWorkerSha256': digest((Path(args.classpath.split(os.pathsep)[0]) / 'NativeFactorizationWorker.class').read_bytes()),
        'observations': observations, 'summary': summary,
    }
    args.output.write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
    print(json.dumps(summary, sort_keys=True))


if __name__ == '__main__':
    main()
