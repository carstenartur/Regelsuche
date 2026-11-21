"""SDK artifact completeness, reproducibility and release reactor coverage."""
import importlib.util
import json
import hashlib
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('sdk_package', Path(__file__).with_name('package-student-sdk.py'))
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


class MavenReleaseWorkflowContractTest(unittest.TestCase):
    def test_isolated_release_reactor_installs_every_packaged_sdk_module(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/release.yml').read_text()
        step = re.search(
            r'^      - name: Build independent Java SDK distribution\n.*?(?=^      - name: |\Z)',
            workflow, re.MULTILINE | re.DOTALL).group()
        command = re.search(r'^\s*mvn (.+?)(?<!\\)\n', step, re.MULTILINE | re.DOTALL).group(1)
        arguments = shlex.split(command.replace('\\\n', ' '))
        selections = [arguments[index + 1] for index, value in enumerate(arguments)
                      if value in ('-pl', '--projects')]
        self.assertEqual(1, len(selections), 'SDK release must select its isolated reactor')
        selected = set(selections[0].split(','))

        namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
        parent = ET.parse(root / 'pom.xml').getroot()
        models = {parent.findtext('m:artifactId', namespaces=namespace): parent}
        for module in parent.findall('m:modules/m:module', namespace):
            model = ET.parse(root / module.text / 'pom.xml').getroot()
            models[model.findtext('m:artifactId', namespaces=namespace)] = model
        self.assertFalse(selected - models.keys(), 'SDK release selects unknown reactor modules')

        installed = set()
        pending = list(selected)
        while pending:
            module = pending.pop()
            if module in installed:
                continue
            installed.add(module)
            if '-am' in arguments or '--also-make' in arguments:
                model = models[module]
                dependencies = model.findall('m:dependencies/m:dependency', namespace)
                dependencies += model.findall('m:parent', namespace)
                for dependency in dependencies:
                    if dependency.findtext('m:groupId', namespaces=namespace) == 'io.github.carstenartur':
                        owner = dependency.findtext('m:artifactId', namespaces=namespace)
                        if owner in models:
                            pending.append(owner)

        policy = json.loads((root / 'config/sdk/public-api.json').read_text())
        required = set(policy['modules']) | {'regelsuche-bom', 'regelsuche-parent'}
        self.assertFalse(required - installed,
                         'Isolated Maven release omits packaged SDK artifacts: '
                         + ', '.join(sorted(required - installed)))


class SdkPackageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / 'repository'
        self.version = '0.5.0'
        self.write('config/sdk/public-api.json', json.dumps({'modules': ['regelsuche-discovery-sdk']}))
        for path in ('LICENSE', 'release.properties', 'gradlew', 'gradlew.bat',
                     'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
                     'scripts/create-student-discovery-domain.py', 'docs/java-discovery-sdk.md',
                     'docs/java-sdk-quickstart.md', 'docs/java-sdk-api-policy.md',
                     'docs/java-sdk-domain-tutorial.md', 'docs/java-sdk-human-dx.md',
                     'docs/generic-extensions.md',
                     'regelsuche-optimization-sdk/README.md',
                     'docs/java-optimization-sdk-qualification.md',
                     'docs/superpowers/specs/2026-09-11-generic-extension-program-discovery-design.md'):
            self.write(path, 'fixture')
        for example in package.EXAMPLES:
            self.write(f'examples/external-consumers/{example}/build.gradle', 'version=0.5.0-SNAPSHOT')
        pinned = Path(__file__).resolve().parents[1] / 'examples/external-consumers/number-theory-plan-java25'
        destination = self.root / 'examples/external-consumers/number-theory-plan-java25'
        shutil.rmtree(destination)
        shutil.copytree(pinned, destination)
        for module in ('regelsuche-discovery-sdk', 'regelsuche-bom'):
            directory = self.repo / 'io/github/carstenartur' / module / self.version
            directory.mkdir(parents=True)
            (directory / f'{module}-{self.version}.pom').write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                f'<version>{self.version}</version></project>')
            if module != 'regelsuche-bom':
                for classifier in ('', '-sources', '-javadoc'):
                    with zipfile.ZipFile(directory / f'{module}-{self.version}{classifier}.jar', 'w') as archive:
                        archive.writestr('index.html' if classifier == '-javadoc' else 'fixture', 'content')

    def write(self, path, text):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)

    def test_reproducible_complete_bundle_with_versioned_examples(self):
        one, two = self.root / 'one.zip', self.root / 'two.zip'
        package.package(self.root, self.repo, self.version, one)
        package.package(self.root, self.repo, self.version, two)
        self.assertEqual(one.read_bytes(), two.read_bytes())
        with zipfile.ZipFile(one) as archive:
            prefix = 'regelsuche-sdk-0.5.0/'
            self.assertIn(prefix + 'sdk-compatibility.json', archive.namelist())
            self.assertIn(prefix + 'javadoc/regelsuche-discovery-sdk/index.html', archive.namelist())
            self.assertIn(prefix + 'SHA256SUMS.txt', archive.namelist())
            for example in package.EXAMPLES:
                if example == 'number-theory-plan-java25':
                    derived = package.current_sdk_consumer_files(self.root / 'examples/external-consumers' / example)
                    for name, data in derived.items():
                        self.assertEqual(data, archive.read(prefix + f'examples/external-consumers/{example}/{name}'))
                else:
                    self.assertEqual(b'version=0.5.0', archive.read(prefix + f'examples/external-consumers/{example}/build.gradle'))
        with self.assertRaises(FileExistsError):
            package.package(self.root, self.repo, self.version, one)

    def test_bundle_includes_extension_example_and_lifetime_documentation(self):
        # Deliberately name the required consumer independently of package.EXAMPLES.
        base = 'examples/external-consumers/extension-runtime-java25/'
        files = {
            'build.gradle': 'implementation "io.github.carstenartur:regelsuche-extension-runtime:0.5.0-SNAPSHOT"',
            'settings.gradle': "rootProject.name = 'extension-runtime-consumer'",
            'README.md': '# Generic extensions 0.5.0-SNAPSHOT',
            'src/main/java/example/GreetingPlugin.java': 'class GreetingPlugin {}',
            'src/test/java/example/ExtensionRuntimeConsumerTest.java': 'class ExtensionRuntimeConsumerTest {}',
            'src/main/resources/META-INF/services/de.regelsuche.extension.RegelsuchePlugin': 'example.GreetingPlugin\n',
        }
        for name, data in files.items():
            self.write(base + name, data)
        self.write('docs/generic-extensions.md', 'Published generations live until runtime.close().')
        output = self.root / 'extensions.zip'
        package.package(self.root, self.repo, self.version, output)
        with zipfile.ZipFile(output) as archive:
            prefix = 'regelsuche-sdk-0.5.0/'
            checksums = archive.read(prefix + 'SHA256SUMS.txt').decode().splitlines()
            for name, original in files.items():
                expected = original.replace('0.5.0-SNAPSHOT', self.version).encode()
                self.assertIn(prefix + base + name, archive.namelist())
                self.assertEqual(expected, archive.read(prefix + base + name))
                self.assertIn(hashlib.sha256(expected).hexdigest() + '  ' + base + name, checksums)
            self.assertEqual(b'Published generations live until runtime.close().',
                             archive.read(prefix + 'docs/generic-extensions.md'))
            self.assertIn(prefix + 'docs/superpowers/specs/2026-09-11-generic-extension-program-discovery-design.md',
                          archive.namelist())

    def test_missing_required_extension_example_prevents_publication(self):
        import shutil
        consumer = self.root / 'examples/external-consumers/extension-runtime-java25'
        if consumer.exists():
            shutil.rmtree(consumer)
        output = self.root / 'missing-extension.zip'
        with self.assertRaisesRegex(ValueError, 'required SDK example'):
            package.package(self.root, self.repo, self.version, output)
        self.assertFalse(output.exists())

    def test_missing_sources_prevents_publication(self):
        source = next(self.repo.rglob('*-sources.jar'))
        source.unlink()
        with self.assertRaisesRegex(ValueError, 'sources'):
            package.package(self.root, self.repo, self.version, self.root / 'invalid.zip')
        self.assertFalse((self.root / 'invalid.zip').exists())

    def test_mixed_pom_versions_are_rejected(self):
        pom = next(self.repo.rglob('regelsuche-bom-*.pom'))
        pom.write_text(pom.read_text().replace('0.5.0', '0.6.0'))
        with self.assertRaisesRegex(ValueError, 'POM version'):
            package.package(self.root, self.repo, self.version, self.root / 'invalid.zip')

    def test_modified_or_extra_independent_consumer_source_is_rejected(self):
        consumer = self.root / 'examples/external-consumers/number-theory-plan-java25'
        extra = consumer / 'Unexpected.java'
        extra.write_text('class Unexpected {}')
        with self.assertRaisesRegex(ValueError, 'source set'):
            package.package(self.root, self.repo, self.version, self.root / 'invalid.zip')
        extra.unlink()
        (consumer / 'build.gradle').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'Git blob'):
            package.package(self.root, self.repo, self.version, self.root / 'invalid.zip')

    def test_missing_inherited_parent_is_rejected(self):
        pom = next(self.repo.rglob('regelsuche-discovery-sdk-*.pom'))
        pom.write_text(pom.read_text().replace('<version>', '<parent><version>').replace('</version>', '</version></parent>'))
        with self.assertRaisesRegex(ValueError, 'missing regelsuche-parent'):
            package.package(self.root, self.repo, self.version, self.root / 'invalid.zip')

    def test_invalid_version_is_rejected(self):
        with self.assertRaises(ValueError):
            package.package(self.root, self.repo, '../wrong', self.root / 'invalid.zip')


def archive_members(path):
    """Compare the consumer-visible ZIP contract independently of the deflater."""
    with zipfile.ZipFile(path) as archive:
        return [(entry.filename, entry.date_time, entry.create_system, entry.external_attr >> 16,
                 entry.compress_type, archive.read(entry)) for entry in archive.infolist()]


def assert_archive_parity(test, oracle, candidate):
    expected, actual = archive_members(oracle), archive_members(candidate)
    test.assertEqual([item[0] for item in expected], [item[0] for item in actual], 'ZIP inventory or ordering')
    for wanted, found in zip(expected, actual):
        name = wanted[0]
        test.assertEqual(wanted[1:5], found[1:5], name + ' ZIP metadata')
        test.assertTrue(wanted[5] == found[5], name + ' member bytes')


class MavenStudentSdkParityTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('REGELSUCHE_STUDENT_SDK_PARITY_MAVEN_ARGS'), 'requires an explicit Maven invocation')
    def test_java_and_python_archive_the_same_release_and_snapshot_artifacts(self):
        root = Path(__file__).resolve().parents[1]
        modules = json.loads((root / 'config/sdk/public-api.json').read_text())['modules']
        maven = json.loads(os.environ['REGELSUCHE_STUDENT_SDK_PARITY_MAVEN_ARGS'])
        with tempfile.TemporaryDirectory() as directory:
            staged = Path(directory) / 'staged'
            for version in ('0.5.4', '0.5.4-SNAPSHOT'):
                with self.subTest(version=version):
                    for module in modules + ['regelsuche-bom', 'regelsuche-parent']:
                        artifacts = staged / 'io/github/carstenartur' / module / version
                        artifacts.mkdir(parents=True)
                        coordinate = f'{module}-{version}'
                        pom = f'<version>{version}</version>'
                        if module != 'regelsuche-parent':
                            pom = f'<parent>{pom}</parent>'
                        (artifacts / f'{coordinate}.pom').write_text(
                            f'<project xmlns="http://maven.apache.org/POM/4.0.0">{pom}</project>')
                        if module not in ('regelsuche-bom', 'regelsuche-parent'):
                            for suffix in ('.jar', '-sources.jar', '-javadoc.jar'):
                                with zipfile.ZipFile(artifacts / (coordinate + suffix), 'x') as jar:
                                    jar.writestr('index.html' if suffix == '-javadoc.jar' else 'fixture', coordinate + suffix)
                    oracle = Path(directory) / f'{version}-python.zip'
                    candidate = Path(directory) / f'{version}-java.zip'
                    package.package(root, staged, version, oracle)
                    subprocess.run(
                        [*maven, '-pl', 'maven-build-contract', '-am',
                         '-Dtest=MavenStudentSdkArchiveTest#packageConfiguredRepository',
                         '-Dregelsuche.studentSdk.package=true', f'-Dregelsuche.studentSdk.repository={staged}',
                         f'-Dregelsuche.studentSdk.version={version}', f'-Dregelsuche.studentSdk.output={candidate}', 'test'],
                        cwd=root, check=True)
                    assert_archive_parity(self, oracle, candidate)


def qualify_staged_archive(staged, version, candidate, evidence):
    """Compare the actual Maven package with the old producer and run its consumer."""
    root = Path(__file__).resolve().parents[1]
    prefix = f'regelsuche-sdk-{version}/'
    with tempfile.TemporaryDirectory(prefix='student-sdk-parity-') as workspace:
        workspace = Path(workspace)
        oracle = workspace / 'python.zip'
        package.package(root, staged, version, oracle)
        assert_archive_parity(unittest.TestCase(), oracle, candidate)
        files = []
        consumer_repository = workspace / 'consumer-repository'
        for name, date, system, mode, method, data in archive_members(candidate):
            if not name.startswith(prefix) or system != 3 or mode not in (0o100644, 0o100755):
                raise ValueError(f'invalid archive member: {name}')
            relative = Path(name.removeprefix(prefix))
            if relative.is_absolute() or '..' in relative.parts or '\\' in name:
                raise ValueError(f'invalid archive member: {name}')
            files.append({'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                          'dateTime': date, 'unixMode': oct(mode), 'zipMethod': method})
            if relative.parts[0] == 'repository':
                output = consumer_repository.joinpath(*relative.parts[1:])
                output.parent.mkdir(parents=True, exist_ok=True)
                with output.open('xb') as stream:
                    stream.write(data)
        (evidence / 'parity.json').write_text(json.dumps({
            'schema': 'regelsuche.student-sdk-archive-parity/v1', 'productVersion': version,
            'pythonArchiveSha256': hashlib.sha256(oracle.read_bytes()).hexdigest(),
            'javaArchiveSha256': hashlib.sha256(candidate.read_bytes()).hexdigest(),
            'identicalMemberDataAndMetadata': True, 'members': files,
        }, indent=2, sort_keys=True) + '\n')
        with (evidence / 'consumer.log').open('xb') as log:
            subprocess.run(
                [sys.executable, '-B', str(root / 'scripts/verify-student-java-sdk-consumer.py'),
                 '--repository-root', str(root), '--published-repository', str(consumer_repository),
                 '--gradle', str(root / 'gradlew'), '--publication-mode', 'MAVEN_STAGED_JAVA_ARCHIVE'],
                cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
        report_path = root / 'build/reports/student-java-sdk/consumer-report.json'
        report = json.loads(report_path.read_bytes())
        if (report['publicationMode'], report['sdkVersion'], report['result']) != ('MAVEN_STAGED_JAVA_ARCHIVE', version, 'success'):
            raise ValueError('consumer report does not identify the Maven-staged archive and version')
        indexed = {file['path'].removeprefix(prefix): file for file in files}
        for artifact in report['artifacts'].values():
            member = indexed[artifact['path']]
            if (artifact['sha256'], artifact['bytes']) != (member['sha256'], member['bytes']):
                raise ValueError(f'consumer artifact differs from packaged bytes: {artifact["path"]}')
        shutil.copyfile(report_path, evidence / 'consumer-report.json')


if __name__ == '__main__':
    if len(sys.argv) == 6 and sys.argv[1] == '--qualify-staged-archive':
        _, _, staged, version, candidate, evidence = sys.argv
        qualify_staged_archive(Path(staged), version, Path(candidate), Path(evidence))
    else:
        unittest.main()
