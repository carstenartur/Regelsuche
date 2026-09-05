"""Black-box tests of the release helper; writes are confined to temporary fixtures."""

from __future__ import annotations

import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from xml.sax.saxutils import escape
import xml.etree.ElementTree as ET

REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / '.github/scripts/update-release-metadata.py'
METADATA = (
    'release.properties', 'CITATION.cff', 'CITATION.md', '.zenodo.json',
    'codemeta.json', 'app/src/main/resources/web/openapi/openapi.json',
)
NAMESPACE = {'m': 'http://maven.apache.org/POM/4.0.0'}
VERSION = re.search(
    r'^version=(\S+)$',
    (REPOSITORY / 'release.properties').read_text(encoding='utf-8'),
    re.MULTILINE,
).group(1)


def invoke(root: Path, version: str, *options: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, '-B', str(SCRIPT), version, *options],
        cwd=root, capture_output=True, text=True, encoding='utf-8', timeout=20,
    )


def snapshot(root: Path) -> dict[str, bytes]:
    return {
        str(path.relative_to(root)): path.read_bytes()
        for path in root.rglob('*') if path.is_file() and not path.is_symlink()
    }


class ReleaseMetadataTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory(prefix='regelsuche-release-metadata-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for relative in METADATA:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY / relative, destination)
        self.write_pom('pom.xml', root=True, modules=('component',), profiles=True)
        self.write_pom('component/pom.xml', modules=('nested',))
        self.write_pom('component/nested/pom.xml')
        self.write_pom('integration/pom.xml')
        bootstrap = self.root / 'playwright-bootstrap/pom.xml'
        bootstrap.parent.mkdir()
        shutil.copyfile(REPOSITORY / 'playwright-bootstrap/pom.xml', bootstrap)
        # Membership, not a recognizable version or valid XML, owns selection.
        ignored = self.root / 'unregistered/pom.xml'
        ignored.parent.mkdir()
        ignored.write_text('not a reactor POM', encoding='utf-8')

    def write_pom(self, relative: str, *, root: bool = False,
                  modules: tuple[str, ...] = (), profiles: bool = False) -> None:
        coordinates = (
            '<groupId>de.regelsuche</groupId>'
            '<artifactId>regelsuche-parent</artifactId>'
            f'<version>{VERSION}</version>'
        )
        if not root:
            coordinates = '<parent>' + coordinates + '</parent><artifactId>fixture</artifactId>'
        module_xml = '<modules>' + ''.join(
            '<module>' + escape(name) + '</module>' for name in modules
        ) + '</modules>'
        profile_xml = ''
        if profiles:
            profile_xml = (
                '<profiles><profile><id>full</id><modules>'
                '<module>integration</module><module>component</module>'
                '</modules></profile></profiles>'
            )
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<modelVersion>4.0.0</modelVersion>' + coordinates + module_xml
            + profile_xml + '</project>\n', encoding='utf-8',
        )

    def assert_success(self, result: subprocess.CompletedProcess) -> None:
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def assert_rejected_read_only(self, expected: str) -> None:
        before = snapshot(self.root)
        result = invoke(self.root, VERSION, '--check')
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(expected, result.stdout + result.stderr)
        self.assertEqual(before, snapshot(self.root))

    def test_real_checkout_preflight_is_aligned_and_read_only(self) -> None:
        paths = [REPOSITORY / relative for relative in METADATA]
        paths += [REPOSITORY / 'pom.xml', *REPOSITORY.glob('*/pom.xml')]
        before = {path: path.read_bytes() for path in paths}
        result = invoke(REPOSITORY, VERSION, '--check')
        self.assert_success(result)
        self.assertIn('Maven POMs and OpenAPI checked', result.stdout)
        self.assertEqual(before, {path: path.read_bytes() for path in paths})

    def test_profiles_and_nested_modules_are_included_once_without_standalone_builds(self) -> None:
        before = snapshot(self.root)
        result = invoke(self.root, VERSION, '--check')
        self.assert_success(result)
        self.assertIn('4 Maven POMs and OpenAPI checked', result.stdout)
        self.assertEqual(before, snapshot(self.root))

    def test_release_and_next_snapshot_update_only_the_complete_reactor(self) -> None:
        untouched = {
            relative: (self.root / relative).read_bytes()
            for relative in ('playwright-bootstrap/pom.xml', 'unregistered/pom.xml')
        }
        for version, options in (('9.8.7', ('--release',)), ('9.9.0-SNAPSHOT', ())):
            with self.subTest(version=version):
                self.assert_success(invoke(self.root, version, *options))
                self.assert_success(invoke(self.root, version, '--check'))
                for relative in ('pom.xml', 'component/pom.xml',
                                 'component/nested/pom.xml', 'integration/pom.xml'):
                    document = ET.parse(self.root / relative).getroot()
                    selector = 'm:version' if relative == 'pom.xml' else 'm:parent/m:version'
                    self.assertEqual(version, document.findtext(selector, namespaces=NAMESPACE))
                for relative, content in untouched.items():
                    self.assertEqual(content, (self.root / relative).read_bytes())
                cff = (self.root / 'CITATION.cff').read_text(encoding='utf-8')
                zenodo = json.loads((self.root / '.zenodo.json').read_text(encoding='utf-8'))
                codemeta = json.loads((self.root / 'codemeta.json').read_text(encoding='utf-8'))
                released = bool(options)
                self.assertEqual(released, 'date-released:' in cff)
                self.assertEqual(released, 'publication_date' in zenodo)
                self.assertEqual(released, 'datePublished' in codemeta)

    def test_drift_in_profile_or_nested_module_is_not_skipped(self) -> None:
        for relative in ('integration/pom.xml', 'component/nested/pom.xml'):
            with self.subTest(module=relative):
                path = self.root / relative
                original = path.read_text(encoding='utf-8')
                path.write_text(original.replace(VERSION, '0.0.1'), encoding='utf-8')
                self.assert_rejected_read_only(relative + ' declares Maven version')
                path.write_text(original, encoding='utf-8')

    def test_missing_declared_profile_module_is_an_error(self) -> None:
        (self.root / 'integration/pom.xml').unlink()
        self.assert_rejected_read_only('Missing declared Maven module POM: integration/pom.xml')

    def test_declared_module_without_product_parent_is_not_an_exemption(self) -> None:
        path = self.root / 'integration/pom.xml'
        path.write_text(path.read_text(encoding='utf-8').replace(
            'regelsuche-parent', 'unrelated-parent'), encoding='utf-8')
        self.assert_rejected_read_only('integration/pom.xml declares Maven version None')

    def test_malformed_namespaces_and_doctypes_are_rejected(self) -> None:
        path = self.root / 'integration/pom.xml'
        original = path.read_text(encoding='utf-8')
        for text, error in (
            ('<project>', 'ParseError'),
            (original.replace(NAMESPACE['m'], 'urn:wrong'), 'Invalid Maven module POM namespace'),
            ('<!DOCTYPE project>' + original, 'DOCTYPE is not allowed'),
        ):
            with self.subTest(error=error):
                path.write_text(text, encoding='utf-8')
                self.assert_rejected_read_only(error)
        path.write_text(original, encoding='utf-8')

    def test_unsafe_unresolved_and_cyclic_module_references_are_rejected(self) -> None:
        path = self.root / 'pom.xml'
        original = path.read_text(encoding='utf-8')
        for name in ('', '../outside', str(self.root / 'absolute'), '${module}', '.'):
            with self.subTest(module=name):
                path.write_text(original.replace(
                    '<module>component</module>', '<module>' + escape(name) + '</module>', 1,
                ), encoding='utf-8')
                self.assert_rejected_read_only('Maven module')
        path.write_text(original, encoding='utf-8')

    def test_symlinked_module_directories_and_poms_are_rejected(self) -> None:
        root_pom = self.root / 'pom.xml'
        original = root_pom.read_text(encoding='utf-8')
        alias = self.root / 'linked'
        alias.symlink_to(self.root / 'component', target_is_directory=True)
        root_pom.write_text(original.replace(
            '<module>component</module>', '<module>linked</module>', 1,
        ), encoding='utf-8')
        self.assert_rejected_read_only('Symbolic link in Maven module path')
        root_pom.write_text(original, encoding='utf-8')
        alias.unlink()
        pom = self.root / 'component/pom.xml'
        saved = pom.with_name('original.xml')
        pom.rename(saved)
        pom.symlink_to(saved)
        self.assert_rejected_read_only('Symbolic link in Maven module path')


if __name__ == '__main__':
    unittest.main()
