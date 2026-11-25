"""Independently verify the two current Maven study archives without launching solvers."""
import argparse
import hashlib
from pathlib import Path

from . import native_parity, typed_parity
from .audit_typed import _require
from .qualify_parity import _read, reject_tampering
from .run import canonical
from .run_typed import FREEZE_COMMIT, PROTOCOL_HASH, check_response, load_protocol


def require_source(typed, native, revision, tree):
    metadata = _read(typed, 'metadata.json')
    _require(metadata['revision'] == revision, 'typed archive source revision mismatch')
    _require(metadata['freezeCommit'] == FREEZE_COMMIT and metadata['protocolHash'] == PROTOCOL_HASH,
             'typed archive frozen protocol mismatch')
    metadata = _read(native, 'metadata.json')
    _require(metadata['sourceRevision'] == revision and metadata['sourceTreeHash'] == 'git:' + tree,
             'native archive source revision/tree mismatch')


def require_typed_setup(protocol, metadata, models):
    profiles = protocol['profiles']
    _require(set(metadata['setup']) == set(profiles), 'incomplete registered typed setup')
    _require([row['profile'] for row in metadata['warmups']] == profiles, 'incomplete registered typed warmups')
    trained = [name for name in profiles if not name.startswith('SYMPY_')]
    _require(all('training' in metadata['setup'][name] and 'error' not in metadata['setup'][name]
                 for name in trained), 'incomplete registered typed model setup')
    _require(set(models) == {name + '.model.json' for name in trained}, 'registered typed model inventory mismatch')

    for warmup in metadata['warmups']:
        profile = warmup['profile']
        setup = metadata['setup'][profile]
        ready = setup.get('ready', {})
        _require(ready.get('status') == 'READY' and 'error' not in setup, 'typed worker not ready: ' + profile)
        model_hash = None
        if profile.startswith('SYMPY_'):
            _require(ready.get('sympy') == protocol['sympyVersion'], 'wrong typed SymPy version')
        else:
            # The raw archive verifier binds this pre-query training receipt to the persisted model bytes.
            model_hash = setup['training']['modelHash']
        response = warmup['response']
        _require(response.get('status') == 'CANDIDATE', 'registered typed warmup failed: ' + profile)
        check_response(profile, response, model_hash, protocol['workBudget'])


def row_identities(rows):
    return [(row['repetition'], row['case'], row['profile']) for row in rows]


def require_typed_outcomes(protocol, rows, summary):
    profiles = protocol['profiles']
    _require(all(not summary[profile]['errors'] and not summary[profile]['invalid'] for profile in profiles),
             'typed study contains errors or invalid rows')
    expected = []
    for repetition in range(protocol['repetitions']):
        for index, case in enumerate(protocol['cases']):
            offset = (index + repetition) % len(profiles)
            expected.extend((repetition, case['id'], profile) for profile in profiles[offset:] + profiles[:offset])
    _require(row_identities(rows) == expected, 'typed row order differs from the registered execution')


def require_native_order(protocol, rows, sequences):
    profiles = protocol['profiles']
    expected = [(rep, profile) for rep in range(protocol['repetitions'])
                for profile in profiles[rep:] + profiles[:rep]]
    _require([(sequence['repetition'], sequence['profile']) for sequence in sequences] == expected,
             'native sequence order differs from the registered execution')
    _require(row_identities(rows) == [(rep, case['id'], profile) for rep, profile in expected
                                      for case in protocol['cases']],
             'native row order differs from the registered execution')


def archive_receipt(directory, projection):
    return {'rows': projection['rowCount'], 'orderedRowsSha256': projection['orderedRowsSha256'],
            'protocolSha256': projection['protocolSha256'], 'modelHashes': projection['modelHashes'],
            'manifestSha256': hashlib.sha256((directory / 'manifest.json').read_bytes()).hexdigest()}


def qualify(typed, native, revision, tree):
    require_source(typed, native, revision, tree)
    typed_protocol = load_protocol(typed / 'protocol.json')
    require_typed_setup(typed_protocol, _read(typed, 'metadata.json'), {p.name for p in typed.glob('*.model.json')})
    typed_projection = typed_parity.project_archive(typed)
    typed_receipt = archive_receipt(typed, typed_projection)
    require_typed_outcomes(typed_protocol, _read(typed, 'rows.json'), _read(typed, 'summary.json'))
    native_projection = native_parity.project_archive(native)
    require_native_order(_read(native, 'protocol.json'), _read(native, 'rows.json'), native_projection['sequences'])
    native_receipt = archive_receipt(native, native_projection)
    native_receipt['sequences'] = len(native_projection['sequences'])
    _require(typed_receipt['rows'] == 729 and len(typed_receipt['modelHashes']) == 5,
             'incomplete registered typed study')
    native_models = {f'NATIVE_LEARNED-{repetition}.model.json' for repetition in range(3)}
    _require(native_receipt['rows'] == 675 and set(native_receipt['modelHashes']) == native_models
             and native_receipt['sequences'] == 9, 'incomplete registered native study')
    return {'schema': 'regelsuche.polynomial-archive-qualification/v1',
            'sourceRevision': revision, 'sourceTree': tree, 'typed': typed_receipt, 'native': native_receipt,
            'rejectedControls': reject_tampering(typed_model=typed, typed_rows=typed,
                                                native_model=native, native_runtime=native)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('typed', type=Path)
    parser.add_argument('native', type=Path)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--tree', required=True)
    print(canonical(qualify(**vars(parser.parse_args()))).decode('utf-8'), end='')


if __name__ == '__main__':
    main()
