"""Run fresh historical GraalPy processes over the externally reachable public prefixes."""

import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile


AREA = Path(__file__).resolve().parent


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def unpack_provision(provision, isolated, manifest):
    packed = provision / 'graalpy.tar.gz'
    runtime = isolated / 'graalpy3.12-25.1.3-linux-amd64'
    if digest(packed) != manifest['runtimeArchiveSha256']:
        raise ValueError('historical runtime archive digest differs')
    subprocess.run(['tar', '-xzf', packed, '-C', isolated], check=True)
    if digest(runtime / 'release') != manifest['runtimeReleaseSha256']:
        raise ValueError('historical runtime release digest differs')
    packages = isolated / 'packages'
    packages.mkdir()
    for filename, expected in manifest['wheels'].items():
        wheel = provision / 'wheels' / filename
        if digest(wheel) != expected:
            raise ValueError('historical wheel digest differs: ' + filename)
        with zipfile.ZipFile(wheel) as archive:
            archive.extractall(packages)
    return runtime / 'bin' / 'graalpy', packages


def run_mode(checkout, runtime, packages, rows, workers, output, mode):
    def run(row):
        command = [runtime, '-I', AREA / 'RunPrefix.py', checkout, packages, mode]
        process = subprocess.run(command, input=json.dumps(row), capture_output=True, check=True,
                                 text=True, env={'SYMPY_GROUND_TYPES': 'python'}, timeout=90)
        reply = json.loads(process.stdout)
        if row['inputId'] != reply['inputId']:
            raise ValueError('unexpected backend input binding')
        return reply

    with (output / f'{mode}.jsonl').open('w', encoding='utf-8', newline='\n') as target:
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as runner:
            for reply in runner.map(run, rows):
                target.write(json.dumps(reply, sort_keys=True, separators=(',', ':')) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--provision', type=Path, required=True)
    parser.add_argument('--prefixes', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--workers', type=int, default=3)
    args = parser.parse_args()
    if args.workers <= 0:
        parser.error('--workers must be positive')
    manifest = json.loads((AREA / 'protocol.json').read_bytes())
    if digest(args.prefixes) != manifest['files']['prefix-rows.jsonl']:
        raise ValueError('unrecognized frozen source-admission prefixes')
    checkout = AREA.parents[2]
    rows = [row for line in args.prefixes.read_bytes().splitlines()
            if 'bridgePayload' in (row := json.loads(line))]
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as isolated:
        runtime, packages = unpack_provision(args.provision.resolve(), Path(isolated), manifest)
        for mode in ('strict', 'raw'):
            run_mode(checkout, runtime, packages, rows, args.workers, args.output, mode)


if __name__ == '__main__':
    main()
