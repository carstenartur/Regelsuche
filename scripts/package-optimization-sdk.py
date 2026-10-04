#!/usr/bin/env python3
"""Build a deterministic, commit-pinned standalone optimizer SDK and qualify its consumer.

Run the Maven reactor install goal and copy external runtime dependencies first. No
release is uploaded and no public repository availability is implied by this tool.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile
import xml.etree.ElementTree as ET

MODULES = ('regelsuche-core', 'regelsuche-egraph', 'regelsuche-search',
           'regelsuche-validation', 'regelsuche-math-algorithms', 'regelsuche-optimization-sdk')
FORBIDDEN = ('org/eclipse/', 'de/regelsuche/example/', 'org/springframework/', 'org/hibernate/')


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def write_zip(path, entries):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            info.create_system = 3
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def shade(jars):
    entries, services = {}, {}
    for jar in sorted(jars, key=lambda p: p.name):
        with zipfile.ZipFile(jar) as archive:
            for name in sorted(archive.namelist()):
                if name.endswith('/') or name.endswith('module-info.class') or name.upper() == 'META-INF/MANIFEST.MF':
                    continue
                if name.startswith('/') or '..' in Path(name).parts or '\\' in name:
                    raise ValueError('unsafe JAR entry')
                if name.startswith('META-INF/') and name.upper().endswith(('.SF', '.RSA', '.DSA', '.EC')):
                    continue
                if name.startswith(FORBIDDEN):
                    raise ValueError(f'forbidden SDK runtime dependency: {name}')
                data = archive.read(name)
                if name.startswith('META-INF/services/'):
                    services.setdefault(name, set()).update(data.decode().splitlines())
                    continue
                if name in entries and entries[name] != data:
                    if name.endswith('.class'):
                        raise ValueError(f'duplicate class with different bytecode: {name}')
                    name = f'META-INF/dependency-resources/{jar.name}/{name}'
                entries[name] = data
    for name, implementations in services.items():
        entries[name] = ('\n'.join(sorted(i for i in implementations if i.strip())) + '\n').encode()
    return entries


def run(command, cwd):
    return subprocess.run(command, cwd=cwd, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout


def package(root, external, output, java_home, skip_consumer=False):
    revision = run(['git', 'rev-parse', 'HEAD'], root).strip()
    if run(['git', 'status', '--porcelain', '--untracked-files=normal'], root).strip():
        raise ValueError('qualified distribution requires a clean committed source tree')
    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
    product = ET.parse(root / 'pom.xml').getroot().findtext('m:version', namespaces=namespace)
    version = product.removesuffix('-SNAPSHOT') + '-issue1657-' + revision[:12]
    jars = [root / module / 'target' / f'{module}-{product}.jar' for module in MODULES]
    if any(not path.is_file() for path in jars):
        raise ValueError('Maven package must build every optimizer dependency module first')
    dependencies = sorted(external.glob('*.jar'))
    if not dependencies:
        raise ValueError('external runtime dependency directory is empty')
    jars += dependencies
    entries = shade(jars)
    entries['META-INF/licenses/Regelsuche-LICENSE'] = (root / 'LICENSE').read_bytes()
    entries['META-INF/MANIFEST.MF'] = (
        'Manifest-Version: 1.0\r\n'
        'Implementation-Title: Regelsuche Optimization SDK\r\n'
        f'Implementation-Version: {version}\r\n'
        'Optimization-API-Revision: 1\r\n'
        'Numeric-Semantics-Revision: java25-numeric/v1\r\n'
        f'Source-Revision: {revision}\r\n'
        'Multi-Release: true\r\n\r\n').encode()
    provenance = {'schema': 'regelsuche.optimization-distribution/v1', 'sourceRevision': revision,
                  'version': version, 'apiRevision': '1', 'semanticsRevision': 'java25-numeric/v1',
                  'javaRelease': 25, 'publiclyPublished': False,
                  'inputs': {p.name: sha256(p.read_bytes()) for p in jars}}
    entries['META-INF/regelsuche/optimization-provenance.json'] = (json.dumps(provenance, indent=2, sort_keys=True) + '\n').encode()
    coordinate = output / 'repository/de/regelsuche/regelsuche-optimization-sdk' / version
    basename = f'regelsuche-optimization-sdk-{version}'
    binary = coordinate / f'{basename}-all.jar'
    write_zip(binary, entries)
    pom = coordinate / f'{basename}.pom'
    pom.write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>de.regelsuche</groupId><artifactId>regelsuche-optimization-sdk</artifactId><version>{version}</version>
<name>Qualified standalone Regelsuche optimization SDK</name><description>Local revision {revision}; classifier all contains the runtime closure.</description>
</project>
''')
    sources = {}
    for module in MODULES:
        for path in sorted((root / module / 'src/main/java').rglob('*.java')):
            sources[str(path.relative_to(root / module / 'src/main/java'))] = path.read_bytes()
    write_zip(coordinate / f'{basename}-sources.jar', sources)
    docs = root / 'regelsuche-optimization-sdk/target' / f'regelsuche-optimization-sdk-{product}-javadoc.jar'
    if not docs.is_file():
        raise ValueError('sdk-release Maven profile must build SDK Javadoc first')
    with zipfile.ZipFile(docs) as archive:
        write_zip(coordinate / f'{basename}-javadoc.jar', {n: archive.read(n) for n in archive.namelist() if not n.endswith('/')})
    consumer = root / 'examples/external-consumers/java-optimization-java25'
    if not skip_consumer:
        with tempfile.TemporaryDirectory(prefix='optimization-sdk-consumer-') as temporary:
            directory = Path(temporary)
            shutil.copytree(consumer / 'src/main/java', directory / 'src')
            (directory / 'classes').mkdir()
            (directory / 'empty-dependency-cache').mkdir()
            java = java_home / 'bin/java'; javac = java_home / 'bin/javac'
            run([str(javac), '--release', '25', '-cp', str(binary.resolve()), '-d', str(directory / 'classes'),
                 *map(str, sorted((directory / 'src').rglob('*.java')))], directory)
            log = run([str(java), '-Duser.home=' + str(directory / 'empty-dependency-cache'), '-cp',
                       str(directory / 'classes') + os.pathsep + str(binary.resolve()), 'example.JavaOptimization'], directory)
            if 'optimization=VERIFIED' not in log or 'checked=ORIGINAL_OVERFLOW_DETECTED' not in log:
                raise ValueError('standalone consumer did not verify the numeric contracts')
            provenance['consumer'] = {'freshDirectory': True, 'emptyDependencyCache': True, 'output': log.strip()}
    for artifact in sorted(coordinate.iterdir()):
        if not artifact.name.endswith('.sha256'):
            artifact.with_name(artifact.name + '.sha256').write_text(sha256(artifact.read_bytes()) + '\n')
    provenance['standaloneJarSha256'] = sha256(binary.read_bytes())
    manifest = output / f'{basename}-qualification.json'
    manifest.write_text(json.dumps(provenance, indent=2, sort_keys=True) + '\n')
    members = {str(path.relative_to(output)): path.read_bytes() for path in sorted(coordinate.iterdir())}
    members[manifest.name] = manifest.read_bytes()
    members['LICENSE'] = (root / 'LICENSE').read_bytes()
    members['README.md'] = (root / 'regelsuche-optimization-sdk/README.md').read_bytes()
    for path in sorted(consumer.rglob('*')):
        if path.is_file() and not any(part in ('target', 'build', '.gradle') for part in path.relative_to(consumer).parts):
            members['consumer/' + str(path.relative_to(consumer))] = path.read_bytes()
    archive = output / f'{basename}.zip'
    write_zip(archive, members)
    return {'version': version, 'revision': revision, 'jar': str(binary.resolve()),
            'sha256': provenance['standaloneJarSha256'], 'archive': str(archive.resolve())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--external-dependencies', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, default=Path(os.environ.get('JAVA_HOME', '')))
    args = parser.parse_args()
    print(json.dumps(package(args.root.resolve(), args.external_dependencies.resolve(), args.output.resolve(), args.java_home.resolve()), indent=2))


if __name__ == '__main__':
    main()
