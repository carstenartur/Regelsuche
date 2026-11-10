"""Verify the retained, independently maintained Primachsenraum consumer snapshot."""
import hashlib
import json
from pathlib import Path

PRIMACHSENRAUM_COMMIT = '9917424453fd4382e8ce088c969902cbced64302'


def verify_pinned_consumer(project: Path):
    manifest = json.loads((project / 'SOURCE.json').read_text())
    if manifest['commit'] != PRIMACHSENRAUM_COMMIT or manifest['repository'] != 'https://github.com/carstenartur/primachsenraum':
        raise ValueError('unexpected Primachsenraum source revision')
    actual_files = set()
    for file in project.rglob('*'):
        relative = file.relative_to(project)
        if any(part in ('build', 'target', '.gradle') for part in relative.parts):
            continue
        if file.is_symlink():
            raise ValueError('consumer source must not contain symlinks')
        if file.is_file():
            actual_files.add(relative.as_posix())
    if actual_files != set(manifest['files']) | {'SOURCE.json'}:
        raise ValueError('independent consumer source set differs from the pinned snapshot')
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


_UPSTREAM_DISCOVERY_RUNTIME = b"""def expectedRegelsucheRuntimeModules = [
    'regelsuche-core',
    'regelsuche-egraph',
    'regelsuche-search',
    'regelsuche-validation',
    'regelsuche-discovery',
    'regelsuche-discovery-sdk'
] as Set"""


def adapt_discovery_runtime_inventory(build: bytes) -> bytes:
    """Apply only the declared six-to-seven-module contract change; reject drift."""
    if (build.count(_UPSTREAM_DISCOVERY_RUNTIME) != 1
            or build.count(b'def expectedRegelsucheRuntimeModules') != 1):
        raise ValueError('upstream Discovery runtime inventory differs from exact pinned contract')
    replacement = _UPSTREAM_DISCOVERY_RUNTIME.replace(
        b"    'regelsuche-discovery-sdk'\n] as Set",
        b"    'regelsuche-discovery-sdk',\n    'regelsuche-extension-api'\n] as Set")
    return build.replace(_UPSTREAM_DISCOVERY_RUNTIME, replacement, 1)


def current_sdk_consumer_files(project: Path) -> dict[str, bytes]:
    """Verified derived distribution; SOURCE.json describes the retained upstream baseline."""
    manifest = verify_pinned_consumer(project)
    files = {name: (project / name).read_bytes() for name in manifest['files']}
    files['SOURCE.json'] = (project / 'SOURCE.json').read_bytes()
    original = files['build.gradle']
    adapted = adapt_discovery_runtime_inventory(original)
    files['build.gradle'] = adapted
    files['upstream-build.gradle'] = original
    receipt = {
        'schema': 'regelsuche.external-consumer-sdk-adaptation/v1',
        'adaptationId': 'discovery-extension-api-runtime-contract/v1',
        'upstreamRepository': manifest['repository'],
        'upstreamCommit': manifest['commit'],
        'upstreamBuildGitBlob': manifest['files']['build.gradle'],
        'upstreamBuildSha256': hashlib.sha256(original).hexdigest(),
        'adaptedBuildSha256': hashlib.sha256(adapted).hexdigest(),
        'addedRuntimeModules': ['regelsuche-extension-api'],
        'sourceManifestMeaning': 'SOURCE.json identifies the upstream baseline, not the adapted build.gradle; original build retained as upstream-build.gradle',
        'unchangedFiles': sorted(name for name in manifest['files'] if name != 'build.gradle'),
    }
    files['SDK-ADAPTATION.json'] = (json.dumps(receipt, indent=2, sort_keys=True) + '\n').encode()
    return files
