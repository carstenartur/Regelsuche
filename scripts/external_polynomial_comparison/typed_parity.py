"""Versioned typed-polynomial parity, excluding only declared measurements."""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
from pathlib import Path

from .audit_typed import _load, _require, audit_directory
from .native_parity import _mask
from .run import canonical

REVISION = 'regelsuche.typed-polynomial-semantic-parity/v1'
METADATA_FIELDS = {'controllerCpuScope', 'controllerProcessCpuNanos', 'freezeCommit',
                   'platform', 'protocolHash', 'python', 'revision', 'setup', 'split', 'warmups'}


def _project_response(response, profile):
    _mask(response, 'nativeWallNanos', 'nativeCpuNanos')
    if profile.startswith('SYMPY_'):
        for candidate in response.get('candidates', []):
            _mask(candidate, 'wallNanos')


def project_row(row):
    result = deepcopy(row)
    _mask(result, 'bindingWallNanos', 'transportWallNanos', 'requestWallNanos')
    _mask(result['judgment'], 'verificationWallNanos', 'endToEndNanos')
    _project_response(result['response'], result['profile'])
    return result


def project_metadata(metadata):
    _require(set(metadata) == METADATA_FIELDS, 'unknown typed metadata projection contract')
    result = deepcopy(metadata)
    # Environment descriptions may differ, but must exist and be nonempty strings.
    for key in ('platform', 'python'):
        _require(isinstance(result[key], str) and bool(result[key].strip()), 'invalid environment: ' + key)
        del result[key]
    _mask(result, 'controllerProcessCpuNanos')
    for profile, setup in result['setup'].items():
        _mask(setup, 'startupNanos', 'trainingRequestNanos', 'freezePersistenceNanos')
        if setup.get('processCpu') is not None:
            _mask(setup['processCpu'], 'userNanos', 'systemNanos', 'totalNanos')
        if 'training' in setup:
            _project_response(setup['training'], profile)
    for warmup in result['warmups']:
        _mask(warmup, 'requestWallNanos')
        _project_response(warmup['response'], warmup['profile'])
    return result


def _read(directory, filename):
    return _load((directory / filename).read_text(encoding='utf-8'))


def project_archive(directory: Path):
    # Includes the complete raw verifier and the independent selected-lineage audit.
    audit = audit_directory(directory)
    _read(directory, 'manifest.json')
    metadata = project_metadata(_read(directory, 'metadata.json'))
    rows = _read(directory, 'rows.json')
    row_hash = hashlib.sha256()
    for row in rows:
        row_hash.update(canonical(project_row(row)))
    model_hashes = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                    for path in sorted(directory.glob('*.model.json'))}
    return {'schema': REVISION, 'metadata': metadata, 'audit': audit,
            'protocolSha256': hashlib.sha256((directory / 'protocol.json').read_bytes()).hexdigest(),
            'canonicalRowsSha256': hashlib.sha256((directory / 'canonical-rows.json').read_bytes()).hexdigest(),
            'modelHashes': model_hashes, 'rowCount': len(rows), 'orderedRowsSha256': row_hash.hexdigest()}


def compare(left: Path, right: Path):
    first, second = project_archive(left), project_archive(right)
    _require(canonical(first) == canonical(second), 'typed semantic archive parity mismatch')
    return {'schema': REVISION, 'sourceRevision': first['metadata']['revision'],
            'rows': first['rowCount'], 'models': len(first['modelHashes']),
            'sha256': hashlib.sha256(canonical(first)).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('left', type=Path)
    parser.add_argument('right', type=Path)
    args = parser.parse_args()
    print(canonical(compare(args.left, args.right)).decode('utf-8'), end='')


if __name__ == '__main__':
    main()
