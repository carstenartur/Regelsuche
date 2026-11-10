#!/usr/bin/env python3
"""Qualify current external Maven consumers against the actual pinned SDK release.

Build orchestration only: no mathematical implementation, reactor build or mock success.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile

VERSION = '0.5.2'
RELEASE_URL = 'https://github.com/carstenartur/Regelsuche/releases/download/v0.5.2/regelsuche-sdk-0.5.2.zip'
RELEASE_SHA256 = '0746b8c6088539c6db3d228131952b30e8066ac8569215b6a508d08df601d9cb'
INTERNAL_CHECKSUM_COUNT = 4034
PREFIX = 'regelsuche-sdk-0.5.2'
MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024
MAX_EXPANDED_BYTES = 256 * 1024 * 1024
EVIDENCE_PATH = Path('build/reports/released-sdk-maven-consumers')
CONSUMERS = {
    'hello-rule-java25': {
        'main': 'example.HelloRule',
        'modules': {'regelsuche-core', 'regelsuche-egraph', 'regelsuche-search', 'regelsuche-plugin-api'},
        'tests': {'example.HelloRuleTest#loadsAndReplaysExactIdentity',
                  'example.HelloRuleTest#refusesDifferentRightOperand'},
        'stdout': 'rule=hello-add-zero\nbefore=x + 0\nafter=x\nreplay=VERIFIED\nnegative=REJECTED\nproof=AUTHOR_SUPPLIED_IDENTITY\n',
    },
    'extension-runtime-java25': {
        'main': 'example.Main',
        'modules': {'regelsuche-core', 'regelsuche-extension-api', 'regelsuche-extension-runtime'},
        'tests': {'example.ExtensionRuntimeConsumerTest#classpathPluginContributesTypedExtensionWithStableReloadIdentity'},
        'stdoutPrefix': 'extension=hello\norigin=greeting-plugin\ngreeting=Hello Ada\n',
    },
}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def safe_relative(name):
    path = PurePosixPath(name)
    if (not name or path.is_absolute() or '\\' in name or ':' in name
            or any(part in ('.', '..') for part in path.parts)
            or path.as_posix() != name.rstrip('/')):
        raise ValueError(f'unsafe archive path: {name}')
    return path


def no_symlinks(path):
    path = Path(path).absolute()
    for item in (path, *path.parents):
        if item.is_symlink():
            raise ValueError(f'path traverses symlink: {item}')


def extract_verified_release(archive, destination, expected_sha=RELEASE_SHA256,
                             expected_count=INTERNAL_CHECKSUM_COUNT, prefix=PREFIX):
    """Validate the entire archive/checksum inventory before writing any members."""
    archive, destination = Path(archive), Path(destination)
    no_symlinks(archive)
    no_symlinks(destination)
    if not archive.is_file() or sha256(archive) != expected_sha:
        raise ValueError('release archive SHA-256 mismatch')
    if destination.exists():
        raise ValueError('extraction destination must be fresh')
    with zipfile.ZipFile(archive) as zipped:
        entries = zipped.infolist()
        names = set()
        files = {}
        if len(entries) > 5000 or sum(item.file_size for item in entries) > MAX_EXPANDED_BYTES:
            raise ValueError('release archive exceeds file/expanded-byte limits')
        for item in entries:
            path = safe_relative(item.filename)
            if path.parts[0] != prefix or len(path.parts) < 2:
                raise ValueError('archive member is outside the release prefix')
            mode = item.external_attr >> 16
            if stat.S_ISLNK(mode) or (stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)):
                raise ValueError('archive member is not a regular file/directory (symlink forbidden)')
            if item.filename in names:
                raise ValueError('duplicate archive member')
            names.add(item.filename)
            if not item.is_dir():
                files[path.relative_to(prefix).as_posix()] = item
        manifest = files.get('SHA256SUMS.txt')
        if manifest is None:
            raise ValueError('missing internal checksum manifest')
        checksums = {}
        for line in zipped.read(manifest).decode('utf-8').splitlines():
            match = re.fullmatch(r'([0-9a-f]{64})  (.+)', line)
            if not match:
                raise ValueError('malformed internal checksum line')
            name = safe_relative(match[2]).as_posix()
            if name in checksums:
                raise ValueError('duplicate internal checksum path')
            checksums[name] = match[1]
        if len(checksums) != expected_count or set(checksums) != set(files) - {'SHA256SUMS.txt'}:
            raise ValueError('internal checksum coverage/count mismatch')
        for name, digest in checksums.items():
            with zipped.open(files[name]) as stream:
                actual = hashlib.sha256()
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    actual.update(block)
                if actual.hexdigest() != digest:
                    raise ValueError(f'internal checksum mismatch: {name}')
        destination.mkdir(mode=0o700)
        for item in entries:
            target = destination.joinpath(*safe_relative(item.filename).parts)
            if item.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with zipped.open(item) as source, target.open('xb') as output:
                    shutil.copyfileobj(source, output)
    return {'sha256': expected_sha, 'verifiedInternalChecksums': len(checksums),
            'archiveBytes': archive.stat().st_size, 'prefix': prefix}


def copy_consumer(source, destination):
    """Copy complete current inputs; stale build outputs never enter qualification."""
    source, destination = Path(source), Path(destination)
    no_symlinks(source)
    no_symlinks(destination)
    if not source.is_dir() or destination.exists():
        raise ValueError('consumer source missing or destination not fresh')
    inputs = []
    for path in sorted(source.rglob('*')):
        relative = path.relative_to(source)
        if relative.parts[0] in {'target', 'build', '.gradle', '.git'}:
            continue
        if path.is_symlink():
            raise ValueError(f'consumer input is a symlink: {relative}')
        if path.is_file():
            inputs.append((relative, path))
        elif not path.is_dir():
            raise ValueError(f'consumer input is not regular: {relative}')
    destination.mkdir()
    hashes = {}
    for relative, source_file in inputs:
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source_file, target)
        hashes[relative.as_posix()] = sha256(target)
    return hashes


def read_surefire(directory, required_cases):
    reports = sorted(Path(directory).glob('TEST-*.xml'))
    if not reports:
        raise ValueError('no actual Surefire TEST reports')
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    cases = set()
    for path in reports:
        no_symlinks(path)
        suite = ET.parse(path).getroot()
        if suite.tag != 'testsuite':
            raise ValueError('unexpected Surefire report root')
        declared = {key: int(suite.attrib[key]) for key in totals}
        tests = suite.findall('testcase')
        if declared['tests'] != len(tests) or any(value < 0 for value in declared.values()):
            raise ValueError('Surefire test counts do not match actual cases')
        for test in tests:
            name = test.attrib['classname'] + '#' + test.attrib['name']
            if name in cases:
                raise ValueError('duplicate Surefire test case')
            cases.add(name)
            if any(test.find(kind) is not None for kind in ('failure', 'error', 'skipped')):
                raise ValueError('Surefire contains failed/error/skipped cases')
        for key, value in declared.items():
            totals[key] += value
    if totals['failures'] or totals['errors'] or totals['skipped']:
        raise ValueError('Surefire contains failures/errors/skips')
    if not required_cases <= cases or totals['tests'] == 0:
        raise ValueError(f'missing expected Surefire cases: {sorted(required_cases - cases)}')
    return {**totals, 'cases': sorted(cases)}


def check_runtime_closure(graph, dependencies, repository, expected_modules):
    """Check resolved runtime coordinates AND copied SDK bytes against the release."""
    no_symlinks(graph)
    text = Path(graph).read_text(encoding='utf-8')
    if '\n#' not in text:
        raise ValueError('missing Maven runtime dependency graph')
    coordinates = []
    nodes = set()
    for index, line in enumerate(text.split('\n#', 1)[0].splitlines()):
        match = re.fullmatch(r'(\d+) ([^\s]+)', line)
        if not match or match[1] in nodes:
            raise ValueError('malformed or duplicate Maven dependency node')
        nodes.add(match[1])
        coordinate = match[2]
        parts = coordinate.split(':')
        if index == 0:
            if len(parts) != 4 or parts[0] != 'example' or parts[2] != 'jar':
                raise ValueError('unexpected external consumer graph root')
            continue
        if len(parts) != 5 or parts[2] != 'jar' or parts[4] not in ('compile', 'runtime'):
            raise ValueError(f'unsupported runtime coordinate: {coordinate}')
        group, artifact, _, version, _ = parts
        if (group.startswith(('org.springframework', 'org.hibernate', 'jakarta.persistence'))
                or any(marker in artifact.lower() for marker in ('spring-', 'hibernate', 'persistence'))):
            raise ValueError(f'forbidden runtime dependency: {coordinate}')
        coordinates.append(coordinate)
    actual = [item.split(':')[1] for item in coordinates if item.startswith('de.regelsuche:')]
    if len(actual) != len(expected_modules) or set(actual) != expected_modules:
        raise ValueError(f'unexpected Regelsuche module closure: {sorted(actual)}')
    jars = {}
    for path in Path(dependencies).glob('*.jar'):
        no_symlinks(path)
        jars[path.name] = sha256(path)
    expected_jars = set()
    for coordinate in coordinates:
        group, artifact, _, version, _ = coordinate.split(':')
        filename = f'{artifact}-{version}.jar'
        expected_jars.add(filename)
        if filename not in jars:
            raise ValueError(f'resolved runtime JAR not copied: {filename}')
        if group == 'de.regelsuche':
            if version != VERSION:
                raise ValueError(f'SDK version differs from release: {coordinate}')
            original = Path(repository) / 'de/regelsuche' / artifact / VERSION / filename
            if jars[filename] != sha256(original):
                raise ValueError(f'copied SDK JAR bytes differ from release SHA-256: {filename}')
    if set(jars) != expected_jars:
        raise ValueError('copied runtime JAR set differs from resolved dependency graph')
    return {'regelsucheModules': sorted(actual), 'coordinates': sorted(coordinates), 'jarSha256': jars}


def check_toolchain(maven_output, java_output):
    maven_output = re.sub(r'\x1b\[[0-9;]*m', '', maven_output)
    maven = re.search(r'Apache Maven (\d+)\.(\d+)\.(\d+)(?=\s|$)', maven_output)
    maven_java = re.search(r'Java version:\s*(\d+)', maven_output)
    java = re.search(r'(?:version\s+"|openjdk\s+)(\d+)', java_output)
    if not maven or not ((3, 9, 9) <= tuple(map(int, maven.groups())) < (4, 0, 0)):
        raise ValueError('Maven 3.9.9+ from the 3.x line is required')
    if not maven_java or maven_java[1] != '25' or not java or java[1] != '25':
        raise ValueError('Maven and CLI must both use Java 25')
    return {'mavenVersion': '.'.join(maven.groups()), 'mavenJavaMajor': 25, 'cliJavaMajor': 25}


def check_cli_output(name, stdout, repeat_stdout=None):
    contract = CONSUMERS[name]
    if 'stdout' in contract:
        if stdout != contract['stdout']:
            raise ValueError('actual CLI output differs from expected released consumer contract')
        return {'policy': 'EXACT_OUTPUT'}
    # Classpath origins include their absolute CodeSource URL in the catalogue
    # manifest. Require canonical identity and same-build stability, not a hash
    # copied from a different private compilation/runtime directory.
    match = re.fullmatch(re.escape(contract['stdoutPrefix'])
                         + r'catalog=(sha256:[0-9a-f]{64})\n', stdout)
    if not match:
        raise ValueError('actual extension CLI output lacks the exact greeting/canonical catalogue hash')
    if repeat_stdout is None or repeat_stdout != stdout:
        raise ValueError('extension CLI catalogue identity is not stable across actual same-build runs')
    return {'policy': 'CANONICAL_CATALOGUE_AND_STABLE_REPEAT',
            'catalogHash': match[1], 'repeatVerified': True}


def run_command(command, cwd, log, timeout=1200):
    environment = dict(os.environ)
    for key in ('MAVEN_ARGS', 'MAVEN_OPTS', 'MAVEN_PROJECTBASEDIR', 'JAVA_TOOL_OPTIONS',
                'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        environment.pop(key, None)
    environment['LC_ALL'] = 'C.UTF-8'
    with Path(log).open('xb') as output:
        try:
            result = subprocess.run(command, cwd=cwd, env=environment, stdout=output,
                                    stderr=subprocess.STDOUT, timeout=timeout, check=False)
            code = result.returncode
        except subprocess.TimeoutExpired:
            output.write(b'\nQualification command timed out.\n')
            code = 124
        except OSError as error:
            output.write((str(error) + '\n').encode('utf-8'))
            code = 127
    return {'command': command, 'cwd': str(cwd), 'log': Path(log).name, 'exitCode': code}


def download_release(destination):
    request = urllib.request.Request(RELEASE_URL, headers={'User-Agent': 'Regelsuche-release-consumer-verifier'})
    with urllib.request.urlopen(request, timeout=60) as response, Path(destination).open('xb') as output:
        if urllib.parse.urlparse(response.url).scheme != 'https':
            raise ValueError('release download redirected away from HTTPS')
        total = 0
        while block := response.read(1024 * 1024):
            total += len(block)
            if total > MAX_DOWNLOAD_BYTES:
                raise ValueError('release archive exceeds download limit')
            output.write(block)


def evidence_directory(root):
    base = root / EVIDENCE_PATH
    no_symlinks(base)
    base.mkdir(parents=True, exist_ok=True)
    destination = base / uuid.uuid4().hex
    destination.mkdir(mode=0o700)
    return destination


def qualify(root, evidence, maven):
    summary = {'schema': 'regelsuche.released-sdk-maven-consumers/v1', 'status': 'RUNNING',
               'releaseUrl': RELEASE_URL, 'releaseVersion': VERSION, 'expectedSha256': RELEASE_SHA256,
               'consumers': {}, 'commands': []}
    summary_path = evidence / 'qualification.json'
    def save():
        summary_path.write_text(json.dumps(summary, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    def execute(command, cwd, log, commands):
        record = run_command(command, cwd, log)
        commands.append(record)
        save()
        if record['exitCode']:
            raise RuntimeError(f'command failed with exit {record["exitCode"]}; original output: {log}')
    save()
    try:
        revision = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root,
                                  capture_output=True, text=True, check=True)
        summary['checkoutCommit'] = revision.stdout.strip()
        java_home = os.environ.get('JAVA_HOME')
        java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
        if not java or not maven:
            raise ValueError('java and mvn executables are required')
        execute([maven, '--version'], root, evidence / 'maven-version.log', summary['commands'])
        execute([java, '-version'], root, evidence / 'java-version.log', summary['commands'])
        summary['toolchain'] = check_toolchain((evidence / 'maven-version.log').read_text(),
                                             (evidence / 'java-version.log').read_text())
        with tempfile.TemporaryDirectory(prefix='regelsuche-released-maven-') as temporary:
            work = Path(temporary).resolve()
            if work == root or root in work.parents:
                raise ValueError('qualification scratch directory must be outside checkout')
            archive = work / 'sdk.zip'
            download_release(archive)
            summary['archive'] = extract_verified_release(archive, work / 'extracted')
            repository = work / 'extracted' / PREFIX / 'repository'
            settings = work / 'empty-settings.xml'
            settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>\n')
            for name, contract in CONSUMERS.items():
                result = {'status': 'RUNNING', 'commands': [], 'cacheInitiallyEmpty': True}
                summary['consumers'][name] = result
                output = evidence / name
                output.mkdir()
                consumer = work / name
                try:
                    result['sourceSha256'] = copy_consumer(root / 'examples/external-consumers' / name, consumer)
                    model = ET.parse(consumer / 'pom.xml').getroot()
                    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
                    if model.find('m:parent', ns) is not None or model.findall('.//m:modules/m:module', ns):
                        raise ValueError('consumer must remain independent of parent/reactor')
                    cache = work / (name + '-empty-cache')
                    cache.mkdir()
                    if any(cache.iterdir()):
                        raise ValueError('consumer Maven cache was not empty')
                    arguments = [maven, '--batch-mode', '--no-transfer-progress', '--settings', str(settings),
                                 '--global-settings', str(settings), f'-Dmaven.repo.local={cache}',
                                 f'-Dregelsuche.repository={repository.as_uri()}', '-Dregelsuche.version=' + VERSION,
                                 '-DskipTests=false', '-Dmaven.test.skip=false']
                    execute(arguments + ['clean', 'package'], consumer, output / 'maven-package.log', result['commands'])
                    result['surefire'] = read_surefire(consumer / 'target/surefire-reports', contract['tests'])
                    graph = output / 'runtime-dependencies.tgf'
                    execute(arguments + ['org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree',
                                          '-Dscope=runtime', '-DoutputType=tgf', f'-DoutputFile={graph}'],
                            consumer, output / 'maven-runtime-tree.log', result['commands'])
                    result['runtimeClosure'] = check_runtime_closure(
                        graph, consumer / 'target/dependency', repository, contract['modules'])
                    cli = [java, '-cp', 'target/classes' + os.pathsep + 'target/dependency/*', contract['main']]
                    execute(cli, consumer, output / 'cli.log', result['commands'])
                    stdout = (output / 'cli.log').read_text(encoding='utf-8')
                    result['stdout'] = stdout
                    repeated = None
                    if 'stdoutPrefix' in contract:
                        execute(cli, consumer, output / 'cli-repeat.log', result['commands'])
                        repeated = (output / 'cli-repeat.log').read_text(encoding='utf-8')
                        result['repeatStdout'] = repeated
                    result['cliIdentity'] = check_cli_output(name, stdout, repeated)
                    result['status'] = 'PASSED'
                except Exception as error:
                    result.update(status='FAILED', error=str(error))
                finally:
                    reports = consumer / 'target/surefire-reports'
                    if reports.is_dir():
                        result['surefireReportSha256'] = copy_consumer(reports, output / 'surefire-reports')
                    save()
            if any(item['status'] != 'PASSED' for item in summary['consumers'].values()):
                raise RuntimeError('released SDK Maven consumer qualification failed; inspect retained consumer logs')
        summary['status'] = 'PASSED'
    except Exception as error:
        summary.update(status='FAILED', error=str(error))
    finally:
        save()
    return summary['status'] == 'PASSED'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--maven', default='mvn', help='Existing Maven executable; no distribution is downloaded')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = evidence_directory(root)
    maven = shutil.which(args.maven)
    success = qualify(root, output, maven)
    print(('PASSED' if success else 'FAILED') + ': ' + str(output / 'qualification.json'))
    return 0 if success else 1


if __name__ == '__main__':
    raise SystemExit(main())
