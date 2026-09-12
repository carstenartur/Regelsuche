"""Artifact completeness and reproducibility, independent of Maven/Gradle orchestration."""
import importlib.util
import json
import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('sdk_package', Path(__file__).with_name('package-student-sdk.py'))
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


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
                     'docs/superpowers/specs/2026-09-11-generic-extension-program-discovery-design.md'):
            self.write(path, 'fixture')
        for example in package.EXAMPLES:
            self.write(f'examples/external-consumers/{example}/build.gradle', 'version=0.5.0-SNAPSHOT')
        content = b'version=0.5.0-SNAPSHOT'
        self.write('examples/external-consumers/number-theory-plan-java25/SOURCE.json', json.dumps({
            'commit': package.verify_pinned_consumer.__globals__['PRIMACHSENRAUM_COMMIT'],
            'repository': 'https://github.com/carstenartur/primachsenraum',
            'files': {'build.gradle': hashlib.sha1(b'blob ' + str(len(content)).encode() + b'\0' + content).hexdigest()}}))
        for module in ('regelsuche-discovery-sdk', 'regelsuche-bom'):
            directory = self.repo / 'de/regelsuche' / module / self.version
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
                self.assertEqual(b'version=0.5.0-SNAPSHOT' if example == 'number-theory-plan-java25' else b'version=0.5.0', archive.read(prefix + f'examples/external-consumers/{example}/build.gradle'))
        with self.assertRaises(FileExistsError):
            package.package(self.root, self.repo, self.version, one)

    def test_bundle_includes_extension_example_and_lifetime_documentation(self):
        # Deliberately name the required consumer independently of package.EXAMPLES.
        base = 'examples/external-consumers/extension-runtime-java25/'
        files = {
            'build.gradle': 'implementation "de.regelsuche:regelsuche-extension-runtime:0.5.0-SNAPSHOT"',
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


if __name__ == '__main__':
    unittest.main()
