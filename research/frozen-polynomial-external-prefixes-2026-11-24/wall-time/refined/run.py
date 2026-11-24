"""Run the refined native engine through the existing cold comparison receiver."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import runpy
import statistics
import subprocess
import tempfile

STUDY = Path(__file__).resolve().parent


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_rows(path):
    return [json.loads(line) for line in path.read_bytes().splitlines()]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    protocol_bytes = (STUDY / 'protocol.json').read_bytes()
    protocol = json.loads(protocol_bytes)
    previous = STUDY.parent
    for filename, expected in protocol['sharedFiles'].items():
        if digest((previous / filename).read_bytes()) != expected:
            raise ValueError('original comparison input differs: ' + filename)
    receiver = runpy.run_path(str(previous / 'compare.py'))
    rows = [row for row in read_rows(previous.parent / 'prefix-rows.jsonl') if 'bridgePayload' in row]
    original_bridge = {row['inputId']: row['observation'] for row in read_rows(previous.parent / 'raw.jsonl')}
    assert len(rows) == protocol['selectedOccurrences'] == len(original_bridge)
    source = (previous / 'NativeFactorizationWorker.java').read_text()
    assert source.count('rationalsWithFastPath') == 1
    source = source.replace('rationalsWithFastPath', 'rationalsWithRefinement')
    if digest(source.encode()) != protocol['refinedWorkerSourceSha256']:
        raise ValueError('refined worker source differs')
    observations = []
    with tempfile.TemporaryDirectory() as isolated:
        target = Path(isolated)
        java_file = target / 'NativeFactorizationWorker.java'
        java_file.write_text(source)
        subprocess.run([args.java.with_name('javac'), '-cp', args.classpath, '-d', target, java_file], check=True)
        classpath = str(target) + os.pathsep + args.classpath
        command = [args.java, '-Xmx384m', '-cp', classpath, 'NativeFactorizationWorker']
        for repetition in range(protocol['repetitions']):
            for row in rows:
                observation = receiver['timed'](command, row, 'native', repetition, protocol['wallSecondsPerQuery'])
                observation['arm'] = 'native-refined'
                if observation['status'] == 'COMPLETED':
                    oracle = original_bridge[row['inputId']]
                    reference = receiver['checked_score'](json.loads(row['bridgePayload']), oracle, 'external')
                    actual = observation['degrees']
                    observation['externalDegrees'] = reference
                    observation['matchesExternalDegrees'] = actual == reference or actual is None and len(reference) == 1
                observations.append(observation)
        worker_sha256 = digest((target / 'NativeFactorizationWorker.class').read_bytes())
    completed = [row for row in observations if row['status'] == 'COMPLETED']
    summary = {
        'requests': len(observations), 'completed': len(completed),
        'nontrivial': sum(len(row['degrees'] or []) > 1 for row in completed),
        'sameExternalDegreesOrBothTrivial': sum(row['matchesExternalDegrees'] for row in completed),
        'medianWallMillis': (statistics.median(row['elapsedNanos'] for row in completed) / 1e6 if completed else None),
        'totalWallSeconds': sum(row['elapsedNanos'] for row in observations) / 1e9,
    }
    args.output.mkdir(parents=True, exist_ok=True)
    raw = ''.join(json.dumps(row, separators=(',', ':'), sort_keys=True) + '\n' for row in observations)
    (args.output / 'observations.jsonl').write_text(raw)
    receipt = {
        'comparisonProtocolSha256': digest(protocol_bytes), 'affinity': sorted(os.sched_getaffinity(0)),
        'javaVersion': subprocess.run([args.java, '-version'], capture_output=True, check=True, text=True).stderr,
        'nativeWorkerSha256': worker_sha256, 'observationsSha256': digest(raw.encode()), 'summary': summary,
    }
    (args.output / 'receipt.json').write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
    print(json.dumps(summary, sort_keys=True))


if __name__ == '__main__':
    main()
