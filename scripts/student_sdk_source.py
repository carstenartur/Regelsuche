"""Verify the retained, independently maintained Primachsenraum consumer snapshot."""
import hashlib
import json
from pathlib import Path

PRIMACHSENRAUM_COMMIT = '9917424453fd4382e8ce088c969902cbced64302'


def verify_pinned_consumer(project: Path):
    manifest = json.loads((project / 'SOURCE.json').read_text())
    if manifest['commit'] != PRIMACHSENRAUM_COMMIT or manifest['repository'] != 'https://github.com/carstenartur/primachsenraum':
        raise ValueError('unexpected Primachsenraum source revision')
    for relative, expected in manifest['files'].items():
        path = Path(relative)
        if path.is_absolute() or '..' in path.parts:
            raise ValueError('invalid consumer source path')
        file = project / path
        if file.is_symlink():
            raise ValueError('consumer source must not be a symlink')
        content = file.read_bytes()
        actual = hashlib.sha1(b'blob ' + str(len(content)).encode() + b'\0' + content).hexdigest()
        if actual != expected:
            raise ValueError(f'independent consumer source differs from pinned Git blob: {relative}')
    return manifest
