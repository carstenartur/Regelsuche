"""Compare verified native-polynomial archives without equating runtime measurements.

The original archives remain untouched. This is a semantic comparison, not
execution authentication, runtime parity or a replacement for either verifier.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
from pathlib import Path

from .audit_typed import _load, _require
from .run import canonical

REVISION = 'regelsuche.native-polynomial-semantic-parity/v1'
DIAGNOSTIC = 'RUNTIME_DIAGNOSTIC'
METADATA_FIELDS = {'sourceRevision', 'sourceTreeHash', 'platform', 'python',
                   'java', 'runtimeBytesHash', 'commands', 'sequences'}


def _mask(record, *fields):
    for field in fields:
        if field in record and record[field] is not None:
            _require(type(record[field]) is int and record[field] >= 0,
                     'invalid measurement: ' + field)
            record[field] = DIAGNOSTIC


def project_row(row):
    """Mask only the seven declared measurement paths; keep presence and nulls."""
    result = deepcopy(row)
    _mask(result, 'bindingWallNanos', 'requestWallNanos')
    _mask(result['judgment'], 'verificationWallNanos', 'endToEndNanos')
    response = result['response']
    _mask(response, 'nativeWallNanos', 'nativeCpuNanos')
    if result['profile'] == 'SYMPY_PORTFOLIO':
        for candidate in response.get('candidates', []):
            _mask(candidate, 'wallNanos')
    return result


def project_sequence(sequence):
    result = deepcopy(sequence)
    _require(result['complete'] is True and not result.get('error'),
             'incomplete sequence cannot qualify')
    _mask(result, 'wallNanos', 'controllerCpuNanos')
    for operation in result['operations']:
        _mask(operation, 'requestWallNanos')
        _mask(operation['response'], 'nativeWallNanos', 'nativeCpuNanos')
    for worker in result['workers']:
        _mask(worker, 'pid', 'peakRssBytes')
        _mask(worker['cpu'], 'userNanos', 'systemNanos', 'totalNanos')
    return result


def _read(directory, filename):
    return _load((directory / filename).read_text(encoding='utf-8'))


def project_archive(directory: Path):
    from .run_native import verify

    checked = verify(directory)
    _require(checked['resources']['complete'] is True, 'incomplete resource accounting')
    _require(all(item['accepted'] is True for item in checked['quality'].values()),
             'native quality acceptance unmet')
    metadata = _read(directory, 'metadata.json')
    _require(set(metadata) == METADATA_FIELDS, 'unknown metadata projection contract')
    # Raw verification binds the full inventory, runtime bytecode and summaries.
    # Require strict JSON as well for the retained inventory receipts.
    _read(directory, 'manifest.json')
    _read(directory, 'runtime-inventory.json')
    rows = _read(directory, 'rows.json')
    row_hash = hashlib.sha256()
    for row in rows:
        row_hash.update(canonical(project_row(row)))
    model_hashes = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                    for path in sorted(directory.glob('*.model.json'))}
    return {'schema': REVISION,
            'source': {key: metadata[key] for key in ('sourceRevision', 'sourceTreeHash')},
            'protocolSha256': hashlib.sha256((directory / 'protocol.json').read_bytes()).hexdigest(),
            'quality': _read(directory, 'quality.json'),
            'modelHashes': model_hashes,
            'sequences': [project_sequence(item) for item in metadata['sequences']],
            'rowCount': len(rows), 'orderedRowsSha256': row_hash.hexdigest()}


def compare(left: Path, right: Path):
    first, second = project_archive(left), project_archive(right)
    _require(canonical(first) == canonical(second), 'native semantic archive parity mismatch')
    return {'schema': REVISION, 'source': first['source'], 'rows': first['rowCount'],
            'sequences': len(first['sequences']), 'models': len(first['modelHashes']),
            'sha256': hashlib.sha256(canonical(first)).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('left', type=Path)
    parser.add_argument('right', type=Path)
    args = parser.parse_args()
    print(canonical(compare(args.left, args.right)).decode('utf-8'), end='')


if __name__ == '__main__':
    main()
