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

    def test_catalogue_identity_accepts_legitimate_build_origin_variation(self):
        prefix = 'extension=hello\norigin=greeting-plugin\ngreeting=Hello Ada\n'
        for digest in ['428dbb205a4dd3fb39c7ede138886729069edba5cb1a977734577d67019d52e2',
                       '382c54a84050795402d4b9850f0139f4a92bfd8b24bc7c476e0ce0c917194c5a']:
            output = prefix + 'catalog=sha256:' + digest + '\n'
            try:
                checked = self.verifier.check_cli_output('extension-runtime-java25', output, output)
            except ValueError as error:
                self.fail(f'legitimate source-origin catalogue identity rejected: {error}')
            self.assertEqual('sha256:' + digest, checked['catalogHash'])
            self.assertTrue(checked['repeatVerified'])

    def test_catalogue_output_rejects_missing_malformed_or_extra_content(self):
        prefix = 'extension=hello\norigin=greeting-plugin\ngreeting=Hello Ada\n'
        malformed = [prefix, prefix + 'catalog=not-a-hash\n',
                     prefix + 'catalog=sha256:' + 'A' * 64 + '\n',
                     prefix + 'catalog=sha256:' + 'a' * 63 + '\n',
                     prefix.replace('Hello Ada', 'Wrong greeting') + 'catalog=sha256:' + 'a' * 64 + '\n',
                     prefix + 'catalog=sha256:' + 'a' * 64 + '\nextra=unapproved\n']
        for output in malformed:
            with self.assertRaises(ValueError):
                self.verifier.check_cli_output('extension-runtime-java25', output, output)

    def test_catalogue_output_requires_identical_actual_repeat(self):
        prefix = 'extension=hello\norigin=greeting-plugin\ngreeting=Hello Ada\n'
        output = prefix + 'catalog=sha256:382c54a84050795402d4b9850f0139f4a92bfd8b24bc7c476e0ce0c917194c5a\n'
        changed = prefix + 'catalog=sha256:428dbb205a4dd3fb39c7ede138886729069edba5cb1a977734577d67019d52e2\n'
        for repeated in [None, changed]:
            with self.assertRaises(ValueError):
                self.verifier.check_cli_output('extension-runtime-java25', output, repeated)
        hello = self.verifier.CONSUMERS['hello-rule-java25']['stdout']
        self.verifier.check_cli_output('hello-rule-java25', hello)
        with self.assertRaises(ValueError):
            self.verifier.check_cli_output('hello-rule-java25', hello.replace('negative=REJECTED', 'negative=ACCEPTED'))

    def test_discovery_output_requires_result_counterexample_and_stable_evidence(self):
        name = 'geometric-sequence-domain-java25'
        prefix = (
            'provider=example-geometric-sequence-provider\n'
            'outcome=CONFIRMED\n'
            'candidate=Plan[multiplier=2, input=Input[observed=[2, 4, 8, 16], holdout=[32, 64], maxMultiplier=6]]\n'
            'counterexamples=[multiplier 1 predicts 2 but observed 4]\n'
        )
        digest = 'sha256:' + 'a' * 64
        output = prefix + 'evidenceHash=' + digest + '\n'
        checked = self.verifier.check_cli_output(name, output, output)
        self.assertEqual(digest, checked['evidenceHash'])
        self.assertTrue(checked['repeatVerified'])

        for invalid in [prefix, output.replace('CONFIRMED', 'REFUTED'),
                        output.replace('multiplier=2', 'multiplier=3'),
                        output.replace('observed 4', 'observed 5'),
                        output.replace(digest, 'sha256:' + 'A' * 64)]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.verifier.check_cli_output(name, invalid, invalid)
        with self.assertRaises(ValueError):
            self.verifier.check_cli_output(name, output, output.replace(digest, 'sha256:' + 'b' * 64))

    def test_failed_command_preserves_original_output_and_exit_status(self):
        log = self.root / 'failure.log'
        record = self.verifier.run_command(
            [sys.executable, '-c', 'import sys; print("original failure"); sys.exit(7)'], self.root, log)
        self.assertEqual(7, record['exitCode'])
        self.assertEqual('original failure\n', log.read_text())


if __name__ == '__main__':
    unittest.main()
