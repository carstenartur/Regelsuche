"""Filesystem regression tests; the separate consumer gate runs the real Java build."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("create-student-discovery-domain.py")
spec = importlib.util.spec_from_file_location("student_starter", SCRIPT)
starter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(starter)


class StudentDiscoveryStarterTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.repository = self.root / "checkout"
        self.source = self.repository / starter.SOURCE_RELATIVE
        self.output = self.root / "generated"
        self.write(self.source / "build.gradle", "mainClass = 'example.GeometricSequenceExample'\n")
        self.write(self.source / "settings.gradle",
                   "rootProject.name = 'regelsuche-geometric-sequence-example'\n")
        self.write(self.source / "README.md", "# Geometric sequence discovery domain\n"
                   "```bash\ngradle clean test run\n```\n")
        for source_set in ("main", "test"):
            self.write(self.source / f"src/{source_set}/java/example/Example.java",
                       'package example;\nclass Example {\n'
                       ' String domain = "example-geometric-sequence";\n'
                       ' String provider = "example-geometric-sequence-provider";\n}\n')
        for relative in starter.WRAPPER_FILES:
            self.write(self.repository / relative, "fixture-only-wrapper\n")
        self.properties = self.repository / starter.WRAPPER_FILES[-1]
        self.write(self.properties,
                   "distributionUrl=https\\://services.gradle.org/distributions/"
                   f"gradle-{starter.GRADLE_WRAPPER_VERSION}-bin.zip\n"
                   "distributionSha256Sum=" + "a" * 64 + "\n")

    @staticmethod
    def write(path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def generate(self, package="org.example.generated", project="my-project",
                 domain="my-domain", provider="my-provider", output=None):
        starter.generate(self.repository, output or self.output, package,
                         project, domain, provider)

    def assert_generated(self, package):
        self.generate(package=package)
        for source_set in ("main", "test"):
            path = self.output / "src" / source_set / "java"
            files = list(path.rglob("*.java"))
            self.assertEqual([path.joinpath(*package.split(".")) / "Example.java"], files)
            self.assertIn(f"package {package};", files[0].read_text())
        service = self.output / ("src/main/resources/META-INF/services/"
                                  "de.regelsuche.sdk.discovery.DiscoveryDomainProvider")
        self.assertEqual(f"{package}.GeometricSequenceDomainProvider\n", service.read_text())
        self.assertEqual(package, json.loads((self.output / "regelsuche-starter.json").read_text())["package"])

    def test_custom_package_and_provider(self):
        self.assert_generated("org.example.generated")

    def test_original_package_is_a_valid_choice(self):
        self.assert_generated("example")

    def test_package_nested_below_original_package(self):
        self.assert_generated("example.student.discovery")

    def test_replacements_do_not_rewrite_user_supplied_ids(self):
        domain = "external-geometric-sequence"
        provider = "example-geometric-sequence-custom-provider"
        project = "example-geometric-sequence-provider"
        self.generate(domain=domain, provider=provider, project=project)
        java = next((self.output / "src/main/java").rglob("*.java")).read_text()
        self.assertIn(f'"{domain}"', java)
        self.assertIn(f'"{provider}"', java)
        self.assertIn(f"rootProject.name = '{project}'", (self.output / "settings.gradle").read_text())

    def test_java_keywords_literals_and_invalid_segments_fail_before_writing(self):
        for package in ("a.class", "a.true", "a.false", "a.null", "", "a..b", "a.1b", "a/b"):
            with self.subTest(package=package), self.assertRaises(SystemExit):
                self.generate(package=package)
            self.assertFalse(self.output.exists())

    def test_invalid_ids_fail_before_writing(self):
        for option in ("project", "domain", "provider"):
            with self.subTest(option=option), self.assertRaises(SystemExit):
                self.generate(**{option: "../escape"})
            self.assertFalse(self.output.exists())

    def test_existing_directory_is_not_modified(self):
        self.write(self.output / "keep.txt", "keep")
        with self.assertRaises(SystemExit):
            self.generate()
        self.assertEqual(["keep.txt"], [path.name for path in self.output.iterdir()])
        self.assertEqual("keep", (self.output / "keep.txt").read_text())

    def test_existing_file_is_not_modified(self):
        self.write(self.output, "keep")
        with self.assertRaises(SystemExit):
            self.generate()
        self.assertEqual("keep", self.output.read_text())

    def symlink(self, link, target):
        try:
            link.symlink_to(target, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest("symbolic links are unavailable")

    def test_dangling_output_symlink_is_not_followed(self):
        target = self.root / "outside"
        self.symlink(self.output, target)
        with self.assertRaises(SystemExit):
            self.generate()
        self.assertTrue(self.output.is_symlink())
        self.assertFalse(target.exists())

    def test_cli_does_not_resolve_away_dangling_output_symlink(self):
        target = self.root / "outside"
        self.symlink(self.output, target)
        result = subprocess.run([sys.executable, str(SCRIPT), "--repository-root",
                                 str(self.repository), "--output", str(self.output)],
                                capture_output=True, text=True, check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("refusing to overwrite", result.stderr)
        self.assertFalse(target.exists())

    def test_template_overlap_is_rejected(self):
        output = self.source / "nested-output"
        with self.assertRaises(SystemExit):
            self.generate(output=output)
        self.assertFalse(output.exists())

    def test_missing_wrapper_fails_before_writing(self):
        (self.repository / starter.WRAPPER_FILES[2]).unlink()
        with self.assertRaises(SystemExit):
            self.generate()
        self.assertFalse(self.output.exists())

    def test_wrapper_checksum_must_be_real_configuration_not_a_comment(self):
        original = self.properties.read_text()
        for value in (original.replace("distributionSha256Sum=", "# distributionSha256Sum="),
                      original.replace("a" * 64, ""),
                      original.replace("a" * 64, "A" * 64),
                      original + "distributionSha256Sum=" + "b" * 64 + "\n"):
            self.properties.write_text(value)
            with self.subTest(value=value), self.assertRaises(SystemExit):
                self.generate()
            self.assertFalse(self.output.exists())

    def test_wrapper_distribution_must_match_exactly(self):
        self.properties.write_text(self.properties.read_text().replace(
            "services.gradle.org", "example.invalid"))
        with self.assertRaises(SystemExit):
            self.generate()
        self.assertFalse(self.output.exists())

    def test_source_symlink_is_rejected_before_copy(self):
        outside = self.root / "outside"
        outside.mkdir()
        self.symlink(self.source / "linked", outside)
        with self.assertRaises(SystemExit):
            self.generate()
        self.assertFalse(self.output.exists())

    def test_missing_source_package_is_rejected_before_writing(self):
        (self.source / "src/test/java/example/Example.java").unlink()
        with self.assertRaises(SystemExit):
            self.generate()
        self.assertFalse(self.output.exists())

    def test_wrapper_bytes_permissions_and_readme_are_preserved(self):
        self.generate()
        for relative in starter.WRAPPER_FILES:
            self.assertEqual((self.repository / relative).read_bytes(),
                             (self.output / relative).read_bytes())
        if os.name != "nt":
            self.assertTrue(os.access(self.output / "gradlew", os.X_OK))
        self.assertIn("\n./gradlew clean test run", (self.output / "README.md").read_text())

    def test_template_remains_unchanged_and_build_caches_are_not_copied(self):
        self.write(self.source / "build/stale.txt", "stale")
        self.write(self.source / ".gradle/stale.txt", "stale")
        self.write(self.source / "repository/stale.txt", "stale")
        before = {str(path): path.read_bytes() for path in self.source.rglob("*") if path.is_file()}
        self.generate()
        after = {str(path): path.read_bytes() for path in self.source.rglob("*") if path.is_file()}
        self.assertEqual(before, after)
        for path in ("build", ".gradle", "repository"):
            self.assertFalse((self.output / path).exists())

class DiscoveryRuntimeContractTest(unittest.TestCase):
    """Check real maintained consumer contracts against both build dependency graphs."""

    def test_maintained_consumers_and_generated_starter_have_exact_sdk_closure(self):
        import re
        import xml.etree.ElementTree as ET
        root = SCRIPT.parent.parent
        namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}

        def closure(module, build):
            result = {module}
            if build == 'gradle':
                dependencies = re.findall(r"(?:api|implementation) project\(':([^']+)'\)",
                                          (root / module / 'build.gradle').read_text())
            else:
                tree = ET.parse(root / module / 'pom.xml')
                dependencies = [dependency.find('m:artifactId', namespace).text
                                for dependency in tree.findall('./m:dependencies/m:dependency', namespace)
                                if dependency.find('m:groupId', namespace).text == 'de.regelsuche']
            for dependency in dependencies:
                result.update(closure(dependency, build))
            return result

        expected = closure('regelsuche-discovery-sdk', 'gradle')
        self.assertEqual(expected, closure('regelsuche-discovery-sdk', 'maven'))
        self.assertIn('regelsuche-extension-api', expected)
        examples = root / 'examples/external-consumers'
        for name in ('geometric-sequence-domain-java25', 'finite-difference-domain-java25',
                     'solver-adapter-java25'):
            with self.subTest(consumer=name):
                build = (examples / name / 'build.gradle').read_text()
                block = re.search(r'def expectedRegelsucheRuntimeModules = \[(.*?)\] as Set', build, re.S)
                self.assertIsNotNone(block)
                self.assertEqual(expected, set(re.findall(r"'(regelsuche-[^']+)'", block[1])))
                self.assertIn('if (observed != expectedRegelsucheRuntimeModules)', build)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'generated'
            starter.generate(root, output, 'org.example.regression', 'runtime-contract',
                             'runtime-domain', 'runtime-provider')
            build = (output / 'build.gradle').read_text()
            block = re.search(r'def expectedRegelsucheRuntimeModules = \[(.*?)\] as Set', build, re.S)
            self.assertEqual(expected, set(re.findall(r"'(regelsuche-[^']+)'", block[1])))

    def test_pinned_consumer_adaptation_is_exact_reproducible_and_preserves_upstream(self):
        import hashlib
        import student_sdk_source
        source = SCRIPT.parent.parent / 'examples/external-consumers/number-theory-plan-java25'
        before = {path.relative_to(source).as_posix(): path.read_bytes()
                  for path in source.rglob('*') if path.is_file()}
        files = student_sdk_source.current_sdk_consumer_files(source)
        self.assertEqual(files, student_sdk_source.current_sdk_consumer_files(source))
        original = before['build.gradle']
        expected = original.replace(b"    'regelsuche-discovery-sdk'\n] as Set",
                                    b"    'regelsuche-discovery-sdk',\n    'regelsuche-extension-api'\n] as Set")
        self.assertEqual(expected, files['build.gradle'])
        self.assertEqual(original, files['upstream-build.gradle'])
        receipt = json.loads(files['SDK-ADAPTATION.json'])
        self.assertEqual(hashlib.sha256(original).hexdigest(), receipt['upstreamBuildSha256'])
        self.assertEqual(hashlib.sha256(expected).hexdigest(), receipt['adaptedBuildSha256'])
        self.assertEqual(json.loads(before['SOURCE.json'])['files']['build.gradle'], receipt['upstreamBuildGitBlob'])
        for name, data in before.items():
            self.assertEqual(data, (source / name).read_bytes())
            if name != 'build.gradle':
                self.assertEqual(data, files[name])
        for malformed in (original.replace(b"'regelsuche-core'", b"'unexpected-module'"), original + original):
            with self.assertRaises(ValueError):
                student_sdk_source.adapt_discovery_runtime_inventory(malformed)


if __name__ == "__main__":
    unittest.main()
