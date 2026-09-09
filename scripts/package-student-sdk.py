#!/usr/bin/env python3
"""Package a verified, independently consumable SDK Maven repository and tutorials."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile
import xml.etree.ElementTree as ET
from student_sdk_source import verify_pinned_consumer

EXAMPLES = ('hello-rule-java25', 'geometric-sequence-domain-java25',
            'finite-difference-domain-java25', 'solver-adapter-java25', 'number-theory-plan-java25')


def single(directory, pattern):
    files = [file for file in directory.glob(pattern) if file.is_file() and not file.is_symlink()]
    if len(files) != 1:
        raise ValueError(f'expected one {pattern} in {directory}, found {len(files)}')
    return files[0]


def package(root, repository, version, output):
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-SNAPSHOT)?', version):
        raise ValueError('SDK version must be a product version')
    policy = json.loads((root / 'config/sdk/public-api.json').read_text())
    members = {}
    ledger = {}
    requires_parent = False
    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
    for module in policy['modules'] + ['regelsuche-bom']:
        directory = repository / 'de/regelsuche' / module / version
        pom = single(directory, f'{module}-*.pom')
        model = ET.fromstring(pom.read_bytes())
        requires_parent |= model.find('m:parent', namespace) is not None
        declared_version = model.findtext('m:version', namespaces=namespace)
        if declared_version is None:
            declared_version = model.findtext('m:parent/m:version', namespaces=namespace)
        if declared_version != version:
            raise ValueError(f'{module} POM version differs from SDK version')
        artifacts = [pom]
        if module != 'regelsuche-bom':
            jars = [file for file in directory.glob(f'{module}-*.jar')
                    if not file.name.endswith(('-sources.jar', '-javadoc.jar'))]
            if len(jars) != 1:
                raise ValueError(f'{module} binary JAR missing or ambiguous')
            artifacts += jars + [single(directory, f'{module}-*-{classifier}.jar')
                                 for classifier in ('sources', 'javadoc')]
        for artifact in artifacts:
            if artifact.is_symlink():
                raise ValueError('SDK artifacts must not be symbolic links')
            data = artifact.read_bytes()
            # Publish canonical coordinate filenames, including Maven snapshots.
            classifier = '-sources' if artifact.name.endswith('-sources.jar') else '-javadoc' if artifact.name.endswith('-javadoc.jar') else ''
            extension = artifact.suffix
            destination = f'repository/de/regelsuche/{module}/{version}/{module}-{version}{classifier}{extension}'
            members[destination] = data
            members[destination + '.sha256'] = (hashlib.sha256(data).hexdigest() + '\n').encode()
            ledger[destination] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
            if classifier == '-javadoc':
                with zipfile.ZipFile(artifact) as docs:
                    if 'index.html' not in docs.namelist():
                        raise ValueError(f'{module} Javadoc has no index.html')
                    for name in docs.namelist():
                        path = Path(name)
                        if name.endswith('/') or name.startswith('META-INF/'):
                            continue
                        if path.is_absolute() or '..' in path.parts or '\\' in name:
                            raise ValueError('invalid Javadoc path')
                        members[f'javadoc/{module}/{name}'] = docs.read(name)
    # Maven-published POMs inherit the reactor parent. Gradle publications are parentless.
    parent_dir = repository / 'de/regelsuche/regelsuche-parent' / version
    if parent_dir.exists():
        parent = single(parent_dir, 'regelsuche-parent-*.pom')
        if ET.fromstring(parent.read_bytes()).findtext('m:version', namespaces=namespace) != version:
            raise ValueError('parent POM version differs from SDK version')
        members[f'repository/de/regelsuche/regelsuche-parent/{version}/regelsuche-parent-{version}.pom'] = parent.read_bytes()
    elif requires_parent:
        raise ValueError('published SDK POM requires missing regelsuche-parent')
    mapping = {**policy, 'productVersion': version, 'artifacts': ledger,
               'distribution': 'GitHub Release SDK archive', 'publicMavenCentral': False}
    members['sdk-compatibility.json'] = (json.dumps(mapping, indent=2, sort_keys=True) + '\n').encode()
    inputs = ['LICENSE', 'release.properties', 'gradlew', 'gradlew.bat',
              'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
              'scripts/create-student-discovery-domain.py', 'docs/java-discovery-sdk.md',
              'docs/java-sdk-quickstart.md', 'docs/java-sdk-api-policy.md',
              'docs/java-sdk-domain-tutorial.md', 'docs/java-sdk-human-dx.md']
    for name in inputs:
        members[name] = (root / name).read_bytes()
    for example in EXAMPLES:
        source = root / 'examples/external-consumers' / example
        if example == 'number-theory-plan-java25':
            verify_pinned_consumer(source)
        for file in sorted(source.rglob('*')):
            relative = file.relative_to(root)
            if any(part in ('build', '.gradle', 'target', 'repository') for part in relative.parts):
                continue
            if file.is_symlink():
                raise ValueError('SDK examples must not contain symbolic links')
            if file.is_file():
                data = file.read_bytes()
                if file.suffix in ('.gradle', '.md') and example != 'number-theory-plan-java25':
                    data = data.replace(b'0.5.0-SNAPSHOT', version.encode()).replace(b'0.4.0-SNAPSHOT', version.encode())
                members[relative.as_posix()] = data
    members['README.md'] = (f'# Regelsuche Java SDK {version}\n\n'
        'Start with [the 15-minute quickstart](docs/java-sdk-quickstart.md).\n\n'
        'Use the `repository` directory as a Maven repository; `sdk-compatibility.json`\n'
        'records coordinates, API revisions and artifact hashes. Each public module has\n'
        'sources and Javadoc JARs; browse `javadoc/<module>/index.html` locally.\n').encode()
    checksum_lines = [f'{hashlib.sha256(data).hexdigest()}  {name}' for name, data in sorted(members.items())]
    members['SHA256SUMS.txt'] = ('\n'.join(checksum_lines) + '\n').encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(members.items()):
            entry = zipfile.ZipInfo(f'regelsuche-sdk-{version}/{name}', date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = (0o100755 if name == 'gradlew' else 0o100644) << 16
            archive.writestr(entry, data)
    return mapping


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--repository', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    package(args.root, args.repository, args.version, args.output)


if __name__ == '__main__':
    main()
