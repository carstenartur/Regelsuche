"""Versioned semantic comparison of two already retained schema-v2 archives.

Raw evidence is verified first and never rewritten. The projection is not a
performance comparison, proof of execution authenticity or replacement archive.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
from pathlib import Path

from .audit_typed import _load, _require
from .run import canonical

REVISION = 'regelsuche.schema-comparison-semantic-parity/v1'
MODEL_REVISION = 'regelsuche.checked-schema-comparison-profile/v2'
DIAGNOSTIC = 'RUNTIME_DIAGNOSTIC'


def _mask(record, *fields):
    # Only at the declared record boundary; retain field presence.
    for field in fields:
        if field in record:
            record[field] = DIAGNOSTIC


def semantic_model(raw, raw_hash, profile):
    """Bind raw bytes before projecting the three observation measurements."""
    _require(isinstance(raw, str) and raw_hash == 'sha256:' + hashlib.sha256(raw.encode('utf-8')).hexdigest(),
             'raw model hash mismatch')
    model = _load(raw)
    _require(model['schema'] == MODEL_REVISION and model['profile'] == profile,
             'unknown model projection contract')
    if profile == 'LEARNED_SELECTED':
        selection = _load(model['selectionEvidence'])
        _require(selection['schema'] in ('regelsuche.typed-source-policy-selection/v1',
                                        'regelsuche.typed-source-policy-selection/v3-accounting'),
                 'unknown selection projection contract')
        for trial in selection['trials']:
            for observation in trial['observations']:
                _mask(observation, 'wallNanos', 'cpuNanos', 'allocatedBytes')
        model['selectionEvidence'] = selection
    else:
        _require(profile in ('BASE', 'LEARNED_SCHEMA') and model['selectionEvidence'] == 'FIXED_PROFILE;NO_FITTING',
                 'unknown fixed model contract')
    digest = 'semantic-sha256:' + hashlib.sha256(canonical({'schema': REVISION, 'model': model})).hexdigest()
    return model, digest


def project_sequence(sequence):
    """Internal projection; caller must verify the archive before using it."""
    _require(sequence['accounting']['complete'] and not sequence['errors'], 'incomplete sequence cannot qualify')
    projected = deepcopy(sequence)
    _mask(projected, 'command', 'wallNanos', 'controllerCpuNanos')
    phases = projected['phases']
    profile = projected['profile']
    raw_hash = model = digest = None
    if not profile.startswith('SYMPY_'):
        training = phases['training']['response']
        raw_hash = training['modelHash']
        model, digest = semantic_model(training['model'], raw_hash, profile)
        for key in ('modelPersistence', 'modelLoad'):
            _require(phases[key]['modelHash'] == raw_hash, 'model receipt hash mismatch')
            phases[key]['modelHash'] = digest
    for key, phase in phases.items():
        _mask(phase, 'wallNanos', 'requestWallNanos', 'pid')
        if 'response' in phase:
            response = phase['response']
            _mask(response, 'nativeWallNanos', 'nativeCpuNanos', 'nativeAllocatedBytes')
            if key in ('training', 'restore'):
                _require(response['modelHash'] == raw_hash, 'phase model hash mismatch')
                restored, restored_digest = semantic_model(response['model'], raw_hash, profile)
                _require(restored == model and restored_digest == digest, 'phase model mismatch')
                response.update(model=model, modelHash=digest)
    for process in projected['processes']:
        _mask(process, 'pid', 'peakRssBytes')
        if process['cpu'] is not None:
            _mask(process['cpu'], 'userNanos', 'systemNanos', 'totalNanos')
    for row in projected['rows']:
        _mask(row, 'transportWallNanos', 'bindingAndPathAuditWallNanos', 'requestWallNanos')
        _mask(row['judgment'], 'verificationWallNanos', 'endToEndNanos')
        response = row['response']
        _mask(response, 'nativeWallNanos', 'nativeCpuNanos', 'nativeAllocatedBytes')
        if raw_hash is not None:
            _require(response['modelHash'] == raw_hash, 'query model hash mismatch')
            response['modelHash'] = digest
        if profile.startswith('SYMPY_'):
            for candidate in response['candidates']:
                _mask(candidate, 'wallNanos')
    account = projected['accounting']
    _mask(account, 'preparationAndShutdownWallNanos', 'queryAndVerificationWallNanos',
          'lifecycleWallNanos', 'controllerOverheadWallNanos', 'controllerCpuNanos',
          'peakWorkerRssBytes', 'reportedNativeMethodCpuNanos', 'reportedNativeMethodAllocatedBytes',
          'completeNativeMethodCpuNanos', 'completeNativeMethodAllocatedBytes')
    if account['wholeWorkerCpu'] is not None:
        _mask(account['wholeWorkerCpu'], 'userNanos', 'systemNanos', 'totalNanos')
    return projected


def project_archive(directory: Path) -> bytes:
    from .run_schema import verify

    verify(directory)
    metadata = _load((directory / 'metadata.json').read_text(encoding='utf-8'))
    sequences = _load((directory / 'sequences.json').read_text(encoding='utf-8'))
    return canonical({'schema': REVISION,
                      'source': {key: metadata[key] for key in ('sourceRevision', 'sourceTreeHash', 'protocolHash',
                                                               'freezeCommit', 'sourceLocalFreezeCommit', 'sympyVersion')},
                      'sequences': [project_sequence(sequence) for sequence in sequences]})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('left', type=Path)
    parser.add_argument('right', type=Path)
    args = parser.parse_args()
    left, right = project_archive(args.left), project_archive(args.right)
    _require(left == right, 'semantic archive parity mismatch')
    print(REVISION + ' sha256:' + hashlib.sha256(left).hexdigest())


if __name__ == '__main__':
    main()
