"""Qualify the transition using retained public archives, without running solvers."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import shutil
from tempfile import TemporaryDirectory

from . import native_parity, typed_parity
from .audit_typed import _load, _require
from .run import canonical


def _read(directory, name):
    return _load((directory / name).read_text(encoding='utf-8'))


def _change(directory, name, transform):
    path = directory / name
    raw = transform(path.read_bytes())
    path.write_bytes(raw)
    manifest = _read(directory, 'manifest.json')
    digest = hashlib.sha256(raw).hexdigest()
    manifest[name] = ({'bytes': len(raw), 'sha256': digest}
                      if isinstance(manifest[name], dict) else digest)
    (directory / 'manifest.json').write_bytes(canonical(manifest))


def _reject(directory, verifier, name, transform, message):
    # Rebind the outer inventory so this tests the underlying semantic/inner
    # runtime verifier, not merely the obvious outer digest mismatch.
    with TemporaryDirectory(prefix='polynomial-parity-negative-') as temporary:
        copy = Path(temporary) / 'archive'
        shutil.copytree(directory, copy)
        _change(copy, name, transform)
        try:
            verifier(copy)
        except ValueError as error:
            _require(message in str(error), f'unexpected rejection: {error}')
        else:
            raise ValueError(f'manipulated archive accepted: {name}')


def _flip_judgment(raw):
    rows = _load(raw.decode('utf-8'))
    rows[0]['judgment']['valid'] = not rows[0]['judgment']['valid']
    return canonical(rows)


def qualify(typed_left, typed_right, native_left, native_right, revision, tree):
    for directory in (typed_left, typed_right):
        _require(_read(directory, 'metadata.json')['revision'] == revision,
                 'typed archive source revision mismatch')
    for directory in (native_left, native_right):
        metadata = _read(directory, 'metadata.json')
        _require(metadata['sourceRevision'] == revision and metadata['sourceTreeHash'] == 'git:' + tree,
                 'native archive source revision/tree mismatch')
    typed = typed_parity.compare(typed_left, typed_right)
    native = native_parity.compare(native_left, native_right)
    _require(typed['rows'] == 729 and native['rows'] == 675 and native['sequences'] == 9,
             'incomplete registered comparison')
    append = lambda raw: raw + b'\n'
    _reject(typed_right, typed_parity.project_archive, 'BASE.model.json', append,
            'model file differs from pre-query setup')
    _reject(typed_left, typed_parity.project_archive, 'rows.json', _flip_judgment,
            'judgment mismatch: valid')
    _reject(native_right, native_parity.project_archive, 'NATIVE_LEARNED-0.model.json', append,
            'persisted model bytes differ from the acquisition receipt')
    runtime_file = sorted(_read(native_left, 'runtime-inventory.json')['files'])[0]
    _reject(native_left, native_parity.project_archive, 'runtime/' + runtime_file, append,
            'retained runtime bytecode/dependency hash mismatch')
    return {'schema': 'regelsuche.polynomial-parity-qualification/v1',
            'sourceRevision': revision, 'sourceTree': tree, 'typed': typed, 'native': native,
            'rejectedControls': ['typed-model', 'typed-judgment', 'native-model', 'native-runtime']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('typed_left', 'typed_right', 'native_left', 'native_right'):
        parser.add_argument(name, type=Path)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--tree', required=True)
    args = parser.parse_args()
    print(canonical(qualify(**vars(args))).decode('utf-8'), end='')


if __name__ == '__main__':
    main()
