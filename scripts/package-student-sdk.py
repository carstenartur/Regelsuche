#!/usr/bin/env python3
"""Package a verified, independently consumable SDK Maven repository and tutorials."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile
import xml.etree.ElementTree as ET
from xml.dom import minidom
from student_sdk_source import current_sdk_consumer_files

EXAMPLES = ('hello-rule-java25', 'geometric-sequence-domain-java25',
            'finite-difference-domain-java25', 'solver-adapter-java25', 'number-theory-plan-java25',
            'extension-runtime-java25', 'java-optimization-java25', 'checked-math-java25')


def stage_consumer_pom(source, version):
    """Compatibility producer for the same archive-owned Maven coordinates."""
    data = source.read_bytes()
    if b'<!DOCTYPE' in data:
        raise ValueError('consumer Maven project must not declare a DTD')
    document = minidom.parseString(data)
    root = document.documentElement
    namespace = 'http://maven.apache.org/POM/4.0.0'
    if root.localName != 'project' or root.namespaceURI != namespace:
        raise ValueError('invalid consumer Maven project')

    def child(parent, name):
        found = [node for node in parent.childNodes
                 if node.nodeType == node.ELEMENT_NODE and node.localName == name
                 and node.namespaceURI == namespace]
        if len(found) != 1:
            raise ValueError(f'expected one consumer POM element: {name}')
        return found[0]

    def value(element, text):
        for node in list(element.childNodes):
            element.removeChild(node)
        element.appendChild(document.createTextNode(text))

    properties = child(root, 'properties')
    value(child(properties, 'regelsuche.groupId'), 'io.github.carstenartur')
    value(child(properties, 'regelsuche.version'), version)
    value(child(properties, 'regelsuche.repository'), 'file://${project.basedir}/../../../repository')
    repository = child(child(root, 'repositories'), 'repository')
    if child(repository, 'id').firstChild.data != 'regelsuche-sdk':
        raise ValueError('consumer must declare its SDK repository')
    value(child(child(repository, 'snapshots'), 'enabled'), str(version.endswith('-SNAPSHOT')).lower())
    return (root.toxml() + '\n').encode('utf-8')


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
        directory = repository / 'io/github/carstenartur' / module / version
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
            destination = f'repository/io/github/carstenartur/{module}/{version}/{module}-{version}{classifier}{extension}'
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
    parent_dir = repository / 'io/github/carstenartur/regelsuche-parent' / version
    if parent_dir.exists():
        parent = single(parent_dir, 'regelsuche-parent-*.pom')
        if ET.fromstring(parent.read_bytes()).findtext('m:version', namespaces=namespace) != version:
            raise ValueError('parent POM version differs from SDK version')
        members[f'repository/io/github/carstenartur/regelsuche-parent/{version}/regelsuche-parent-{version}.pom'] = parent.read_bytes()
    elif requires_parent:
        raise ValueError('published SDK POM requires missing regelsuche-parent')
    mapping = {**policy, 'productVersion': version, 'artifacts': ledger,
               'distribution': 'GitHub Release SDK archive', 'publicMavenCentral': False}
    members['sdk-compatibility.json'] = (json.dumps(mapping, indent=2, sort_keys=True) + '\n').encode()
    inputs = ['LICENSE', 'release.properties', 'gradlew', 'gradlew.bat',
              'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
              'scripts/create-student-discovery-domain.py', 'docs/java-discovery-sdk.md',
              'docs/java-sdk-quickstart.md', 'docs/java-sdk-api-policy.md',
              'docs/java-sdk-domain-tutorial.md', 'docs/java-sdk-human-dx.md',
              'docs/generic-extensions.md',
              'regelsuche-optimization-sdk/README.md',
              'docs/java-optimization-sdk-qualification.md',
              'docs/superpowers/specs/2026-09-11-generic-extension-program-discovery-design.md']
    for name in inputs:
        data = (root / name).read_bytes()
        if name.endswith('.md'):
            data = data.replace(b'0.5.0-SNAPSHOT', version.encode())
        members[name] = data
    for example in EXAMPLES:
        source = root / 'examples/external-consumers' / example
        if not source.is_dir() or source.is_symlink():
            raise ValueError(f'required SDK example is missing or invalid: {example}')
        if example == 'number-theory-plan-java25':
            for name, data in current_sdk_consumer_files(source).items():
                members[f'examples/external-consumers/{example}/{name}'] = data
            continue
        for file in sorted(source.rglob('*')):
            relative = file.relative_to(root)
            if any(part in ('build', '.gradle', 'target', 'repository') for part in relative.parts):
                continue
            if file.is_symlink():
                raise ValueError('SDK examples must not contain symbolic links')
            if file.is_file():
                data = file.read_bytes()
                if file.name == 'pom.xml':
                    data = stage_consumer_pom(file, version)
                if file.suffix in ('.gradle', '.md') and example != 'number-theory-plan-java25':
                    data = data.replace(b'0.5.0-SNAPSHOT', version.encode()).replace(b'0.4.0-SNAPSHOT', version.encode())
                members[relative.as_posix()] = data
    members['README.md'] = (f'# Regelsuche Java SDK {version}\n\n'
        'Run the [six checked calculations](examples/external-consumers/checked-math-java25/README.md) with Java 25 and Maven 3.9.9+:\n\n'
        '```sh\nmvn -f examples/external-consumers/checked-math-java25/pom.xml clean verify\n```\n\n'
        "The bundled Maven projects use this archive's SDK version without overrides.\n"
        'The [SDK guide](docs/java-sdk-quickstart.md) also documents the published Central release and historical archives.\n\n'
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
