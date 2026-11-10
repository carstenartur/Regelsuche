"""Narrow safety and acceptance contracts for the real released-Maven qualifier."""
import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).with_name('verify-released-sdk-maven-consumers.py')


class ReleasedConsumerContracts(unittest.TestCase):
    def setUp(self):
        if not SCRIPT.is_file():
            self.fail('released Maven consumer verifier is not implemented')
        spec = importlib.util.spec_from_file_location('released_maven', SCRIPT)
        self.verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.verifier)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def archive(self, members):
        archive = self.root / 'sdk.zip'
        with zipfile.ZipFile(archive, 'w') as output:
            for name, value in members.items():
                output.writestr(name, value)
        return archive, hashlib.sha256(archive.read_bytes()).hexdigest()

    def test_archive_hash_is_checked_before_opening_or_extracting(self):
        archive = self.root / 'not-a-zip'
        archive.write_bytes(b'wrong release bytes')
        destination = self.root / 'extracted'
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            self.verifier.extract_verified_release(archive, destination, '0' * 64, 1, 'sdk')
        self.assertFalse(destination.exists())

    def test_archive_rejects_traversal_and_symlinks_before_writing(self):
        for name in ['sdk/../escape', '/absolute', 'sdk/back\\slash']:
            archive, digest = self.archive({name: b'unsafe'})
            destination = self.root / 'extracted'
            with self.assertRaises(ValueError):
                self.verifier.extract_verified_release(archive, destination, digest, 1, 'sdk')
            self.assertFalse(destination.exists())
        with zipfile.ZipFile(self.root / 'sdk.zip', 'w') as output:
            link = zipfile.ZipInfo('sdk/link')
            link.external_attr = 0o120777 << 16
            output.writestr(link, '../escape')
        archive = self.root / 'sdk.zip'
        with self.assertRaisesRegex(ValueError, 'symlink|regular'):
            self.verifier.extract_verified_release(
                archive, self.root / 'extracted', hashlib.sha256(archive.read_bytes()).hexdigest(), 1, 'sdk')

    def test_archive_requires_complete_internal_checksum_coverage(self):
        value = b'release payload'
        line = hashlib.sha256(value).hexdigest() + '  payload\n'
        archive, digest = self.archive({'sdk/payload': value, 'sdk/SHA256SUMS.txt': line})
        target = self.root / 'good'
        result = self.verifier.extract_verified_release(archive, target, digest, 1, 'sdk')
        self.assertEqual(value, (target / 'sdk/payload').read_bytes())
        self.assertEqual(1, result['verifiedInternalChecksums'])
        archive, digest = self.archive({'sdk/payload': value, 'sdk/extra': b'unlisted', 'sdk/SHA256SUMS.txt': line})
        with self.assertRaisesRegex(ValueError, 'coverage|checksum'):
            self.verifier.extract_verified_release(archive, self.root / 'bad', digest, 1, 'sdk')
        self.assertFalse((self.root / 'bad').exists())

    def test_surefire_requires_actual_expected_cases_without_skips_or_failures(self):
        reports = self.root / 'reports'
        reports.mkdir()
        expected = {'example.Test#realCase'}
        with self.assertRaisesRegex(ValueError, 'Surefire|report'):
            self.verifier.read_surefire(reports, expected)
        report = reports / 'TEST-example.Test.xml'
        report.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="example.Test" name="realCase"/></testsuite>')
        self.assertEqual(1, self.verifier.read_surefire(reports, expected)['tests'])
        with self.assertRaisesRegex(ValueError, 'expected|missing'):
            self.verifier.read_surefire(reports, {'example.Test#notRun'})
        report.write_text('<testsuite tests="1" failures="0" errors="0" skipped="1"><testcase classname="example.Test" name="realCase"><skipped/></testcase></testsuite>')
        with self.assertRaisesRegex(ValueError, 'skip|fail|error'):
            self.verifier.read_surefire(reports, expected)

    def test_runtime_closure_rejects_extra_modules_and_different_release_bytes(self):
        deps = self.root / 'dependency'
        deps.mkdir()
        repository = self.root / 'repository'
        module = 'regelsuche-extension-runtime'
        jar = f'{module}-0.5.2.jar'
        original = repository / 'de/regelsuche' / module / '0.5.2' / jar
        original.parent.mkdir(parents=True)
        original.write_bytes(b'actual release jar')
        (deps / jar).write_bytes(original.read_bytes())
        graph = self.root / 'runtime.tgf'
        graph.write_text(f'1 example:consumer:jar:1.0.0\n2 de.regelsuche:{module}:jar:0.5.2:compile\n#\n1 2 compile\n')
        result = self.verifier.check_runtime_closure(graph, deps, repository, {module})
        self.assertEqual([module], result['regelsucheModules'])
        graph.write_text(f'1 example:consumer:jar:1.0.0\n2 de.regelsuche:{module}:jar:0.5.2:compile\n3 org.springframework:spring-context:jar:7.0.0\n#\n1 2 compile\n')
        with self.assertRaises(ValueError):
            self.verifier.check_runtime_closure(graph, deps, repository, {module})
        graph.write_text(f'1 example:consumer:jar:1.0.0\n2 de.regelsuche:{module}:jar:0.5.2:compile\n#\n1 2 compile\n')
        (deps / jar).write_bytes(b'same name, different bytes')
        with self.assertRaisesRegex(ValueError, 'bytes|SHA-256'):
            self.verifier.check_runtime_closure(graph, deps, repository, {module})
        (deps / jar).write_bytes(original.read_bytes())
        for extra in ['de.regelsuche:regelsuche-app:jar:0.5.2:runtime',
                      'org.springframework:spring-context:jar:7.0.0:runtime',
                      'org.hibernate.orm:hibernate-core:jar:7.0.0:runtime']:
            graph.write_text(f'1 example:consumer:jar:1.0.0\n2 de.regelsuche:{module}:jar:0.5.2:compile\n3 {extra}\n#\n1 2 compile\n1 3 runtime\n')
            with self.assertRaises(ValueError):
                self.verifier.check_runtime_closure(graph, deps, repository, {module})

    def test_toolchain_requires_java25_and_supported_maven3(self):
        good = 'Apache Maven 3.9.16\nJava version: 25.0.1, vendor: Eclipse Adoptium\n'
        self.assertEqual('3.9.16', self.verifier.check_toolchain(good, 'openjdk version "25.0.1"')['mavenVersion'])
        for bad in [good.replace('3.9.16', '3.8.9'), good.replace('3.9.16', '4.0.0'), good.replace('3.9.16', '3.9.9-SNAPSHOT'), good.replace('version: 25', 'version: 21')]:
            with self.assertRaises(ValueError):
                self.verifier.check_toolchain(bad, 'openjdk version "25.0.1"')

    def test_copy_rejects_symlinks_and_omits_only_generated_outputs(self):
        source = self.root / 'consumer'
        source.mkdir()
        (source / 'pom.xml').write_text('<project/>')
        (source / 'target').mkdir()
        (source / 'target/stale').write_text('stale')
        destination = self.root / 'copied'
        self.verifier.copy_consumer(source, destination)
        self.assertTrue((destination / 'pom.xml').is_file())
        self.assertFalse((destination / 'target').exists())
        (source / 'linked').symlink_to(self.root / 'outside')
        with self.assertRaisesRegex(ValueError, 'symlink'):
            self.verifier.copy_consumer(source, self.root / 'unsafe-copy')
        self.assertFalse((self.root / 'unsafe-copy').exists())

    def test_failed_command_preserves_original_output_and_exit_status(self):
        log = self.root / 'failure.log'
        record = self.verifier.run_command(
            [sys.executable, '-c', 'import sys; print("original failure"); sys.exit(7)'], self.root, log)
        self.assertEqual(7, record['exitCode'])
        self.assertEqual('original failure\n', log.read_text())


if __name__ == '__main__':
    unittest.main()
