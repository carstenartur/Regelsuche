"""Independently verify the two current Maven study archives without launching solvers."""
import argparse
import hashlib
from pathlib import Path

from . import native_parity, typed_parity
from .audit_typed import _require
from .qualify_parity import _read, reject_tampering
from .run import canonical
from .run_typed import FREEZE_COMMIT, PROTOCOL_HASH


def require_source(typed, native, revision, tree):
    metadata = _read(typed, 'metadata.json')
    _require(metadata['revision'] == revision, 'typed archive source revision mismatch')
    _require(metadata['freezeCommit'] == FREEZE_COMMIT and metadata['protocolHash'] == PROTOCOL_HASH,
             'typed archive frozen protocol mismatch')
    metadata = _read(native, 'metadata.json')
    _require(metadata['sourceRevision'] == revision and metadata['sourceTreeHash'] == 'git:' + tree,
             'native archive source revision/tree mismatch')


def require_typed_setup(profiles, metadata, models):
    _require(set(metadata['setup']) == set(profiles), 'incomplete registered typed setup')
    _require([row['profile'] for row in metadata['warmups']] == profiles, 'incomplete registered typed warmups')
    trained = [name for name in profiles if not name.startswith('SYMPY_')]
    _require(all('training' in metadata['setup'][name] and 'error' not in metadata['setup'][name]
                 for name in trained), 'incomplete registered typed model setup')
    _require(set(models) == {name + '.model.json' for name in trained}, 'registered typed model inventory mismatch')


def archive_receipt(directory, projection):
    return {'rows': projection['rowCount'], 'orderedRowsSha256': projection['orderedRowsSha256'],
            'protocolSha256': projection['protocolSha256'], 'modelHashes': projection['modelHashes'],
            'manifestSha256': hashlib.sha256((directory / 'manifest.json').read_bytes()).hexdigest()}


def qualify(typed, native, revision, tree):
    require_source(typed, native, revision, tree)
    typed_projection = typed_parity.project_archive(typed)
    typed_receipt = archive_receipt(typed, typed_projection)
    require_typed_setup(_read(typed, 'protocol.json')['profiles'], typed_projection['metadata'],
                        typed_receipt['modelHashes'])
    native_projection = native_parity.project_archive(native)
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
