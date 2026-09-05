"""Black-box tests of the release helper; writes are confined to temporary fixtures."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import textwrap
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


def workflow_step(workflow: str, name: str) -> str:
    """Read a named top-level step in the checked-in, fixed-indentation workflow."""
    matches = re.findall(
        r'^      - name: ' + re.escape(name) + r'\n.*?(?=^      - name: |\Z)',
        workflow, re.MULTILINE | re.DOTALL,
    )
    if len(matches) != 1:
        raise ValueError('Expected exactly one workflow step: ' + name)
    return matches[0]


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

    def assert_follow_up_binding(self, workflow: str) -> None:
        initial = workflow_step(workflow, 'Checkout authoritative source')
        checkout = workflow_step(workflow, 'Restore authoritative main for follow-up')
        prepare = workflow_step(workflow, 'Prepare next development metadata')
        publish = workflow_step(workflow, 'Open next development metadata PR')
        action = re.search(r'^        uses: (actions/checkout@[0-9a-f]{40})',
                           initial, re.MULTILINE).group(1)
        self.assertIn('        uses: ' + action, checkout)
        self.assertIn('          ref: main\n', checkout)
        self.assertIn('          fetch-depth: 0\n', checkout)
        self.assertIn('          path: .release-follow-up\n', checkout)
        self.assertIn('        working-directory: .release-follow-up\n', prepare)
        self.assertIn('          path: .release-follow-up\n', publish)
        for step in (checkout, prepare):
            self.assertIn("steps.release-state.outputs.dry_run != 'true'", step)
            self.assertIn("steps.release-state.outputs.managed_release == 'true'", step)
            for forbidden in ('git clean', 'sudo ', '|| true'):
                self.assertNotIn(forbidden, step)
        self.assertIn('          test -d .git\n', prepare)
        self.assertIn('          test "$(git rev-parse --show-toplevel)" = "$(pwd -P)"\n', prepare)
        self.assertIn('          test -z "$(git status --porcelain --untracked-files=all)"\n', prepare)
        self.assertIn("if: steps.next-development.outputs.required == 'true'", publish)
        paths = publish.partition('          add-paths: |\n')[2]
        self.assertEqual(set(METADATA) | {'pom.xml', '*/pom.xml'},
                         {line.strip() for line in paths.splitlines() if line.strip()})

    def test_follow_up_workflow_uses_an_isolated_checkout(self) -> None:
        self.assert_follow_up_binding(self.release_workflow())

    def test_follow_up_route_and_guard_mutations_are_rejected(self) -> None:
        workflow = self.release_workflow()
        for step_name, old, new in (
            ('Restore authoritative main for follow-up',
             '          path: .release-follow-up\n', '          path: .\n'),
            ('Prepare next development metadata',
             '        working-directory: .release-follow-up\n', '        working-directory: .\n'),
            ('Open next development metadata PR',
             '          path: .release-follow-up\n', '          path: .\n'),
            ('Restore authoritative main for follow-up',
             "managed_release == 'true'", "managed_release != 'true'"),
            ('Prepare next development metadata', '          test -d .git\n', ''),
            ('Prepare next development metadata',
             '          test -z "$(git status --porcelain --untracked-files=all)"\n', ''),
        ):
            with self.subTest(step=step_name, removed=old):
                step = workflow_step(workflow, step_name)
                self.assertIn(old, step)
                mutated = workflow.replace(step, step.replace(old, new, 1), 1)
                with self.assertRaises(AssertionError):
                    self.assert_follow_up_binding(mutated)

    @staticmethod
    def release_workflow() -> str:
        return (REPOSITORY / '.github/workflows/release.yml').read_text(encoding='utf-8')

    def git(self, root: Path, *arguments: str) -> str:
        result = subprocess.run(
            ['git', '-c', 'commit.gpgsign=false', '-c', 'core.autocrlf=false', *arguments],
            cwd=root, capture_output=True, text=True, encoding='utf-8', timeout=20,
            env={**os.environ, 'GIT_CONFIG_GLOBAL': os.devnull,
                 'GIT_CONFIG_NOSYSTEM': '1', 'GIT_TERMINAL_PROMPT': '0'},
        )
        self.assert_success(result)
        return result.stdout.strip()

    def follow_up_fixture(self, upstream_version: str) -> tuple[Path, Path]:
        # The local bare remote models checkout@... with ref=main and a separate path.
        # Neither an Actions service nor a GitHub write is used by this regression.
        self.assert_success(invoke(self.root, upstream_version))
        helper = self.root / '.github/scripts/update-release-metadata.py'
        helper.parent.mkdir(parents=True)
        shutil.copyfile(SCRIPT, helper)
        (self.root / '.gitignore').write_text('build/\n.release-follow-up/\n', encoding='utf-8')
        self.git(self.root, 'init', '--initial-branch=main')
        self.git(self.root, 'config', 'user.name', 'Release fixture')
        self.git(self.root, 'config', 'user.email', 'fixture@example.invalid')
        self.git(self.root, 'add', '--all')
        self.git(self.root, 'commit', '-m', 'Development metadata')
        external = tempfile.TemporaryDirectory(prefix='regelsuche-release-origin-')
        self.addCleanup(external.cleanup)
        remote = Path(external.name) / 'origin.git'
        self.git(self.root, 'clone', '--bare', '--no-local', str(self.root), str(remote))
        self.git(self.root, 'remote', 'add', 'origin', str(remote))
        self.git(self.root, 'checkout', '--detach')
        self.assert_success(invoke(self.root, '9.8.7', '--release'))
        self.git(self.root, 'add', '--all')
        self.git(self.root, 'commit', '-m', 'Published release metadata')
        self.git(self.root, 'tag', 'fixture-release')
        self.source_head = self.git(self.root, 'rev-parse', 'HEAD')
        self.source_files = {
            name: (self.root / name).read_bytes()
            for name in self.git(self.root, 'ls-files').splitlines()
        }
        self.locked = self.root / 'build/container-output/campaign'
        self.locked.mkdir(parents=True)
        self.evidence = self.locked / 'receipt.json'
        self.evidence.write_bytes(b'{"evidence":"must survive follow-up"}\n')
        self.locked.chmod(0o555)
        self.addCleanup(self.locked.chmod, 0o755)
        self.assertEqual(0o555, stat.S_IMODE(self.locked.stat().st_mode))
        follow_up = self.root / '.release-follow-up'
        self.git(self.root, 'clone', '--no-local', '--branch', 'main', str(remote), str(follow_up))
        self.assertEqual(str(follow_up.resolve()), self.git(follow_up, 'rev-parse', '--show-toplevel'))
        return follow_up, Path(external.name) / 'step-output.txt'

    def run_follow_up(self, directory: Path, output: Path) -> subprocess.CompletedProcess:
        step = workflow_step(self.release_workflow(), 'Prepare next development metadata')
        _, marker, body = step.partition('        run: |\n')
        self.assertTrue(marker, 'the regression must run the actual preparation shell')
        return subprocess.run(
            ['bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', textwrap.dedent(body)],
            cwd=directory, capture_output=True, text=True, encoding='utf-8', timeout=20,
            env={**os.environ, 'RELEASE_VERSION': '9.8.7', 'NEXT_VERSION': '9.9.0-SNAPSHOT',
                 'GITHUB_OUTPUT': str(output), 'PYTHONDONTWRITEBYTECODE': '1'},
        )

    def assert_source_preserved(self) -> None:
        self.assertEqual(self.source_head, self.git(self.root, 'rev-parse', 'HEAD'))
        self.assertEqual(self.source_head, self.git(self.root, 'rev-parse', 'fixture-release'))
        self.assertEqual('', self.git(self.root, 'diff', '--name-only'))
        self.assertEqual(self.source_files,
                         {name: (self.root / name).read_bytes() for name in self.source_files})
        self.assertEqual(b'{"evidence":"must survive follow-up"}\n', self.evidence.read_bytes())
        self.assertEqual(0o555, stat.S_IMODE(self.locked.stat().st_mode))

    def test_follow_up_retains_protected_output_and_changes_only_metadata(self) -> None:
        follow_up, output = self.follow_up_fixture('9.8.7-SNAPSHOT')
        # Root can bypass DAC. On the unprivileged release/CI runner, assert the
        # actual deletion failure as well as the preserved modes/bytes tested everywhere.
        if hasattr(os, 'geteuid') and os.geteuid() != 0:
            with self.assertRaises(PermissionError):
                self.evidence.unlink()
        self.assert_success(self.run_follow_up(follow_up, output))
        self.assertEqual('required=true\n', output.read_text(encoding='utf-8'))
        self.assert_success(invoke(follow_up, '9.9.0-SNAPSHOT', '--check'))
        expected = set(METADATA) | {
            'pom.xml', 'component/pom.xml', 'component/nested/pom.xml', 'integration/pom.xml',
        }
        self.assertEqual(expected, set(self.git(follow_up, 'diff', '--name-only').splitlines()))
        self.assert_source_preserved()

    def test_follow_up_already_at_next_version_is_read_only(self) -> None:
        follow_up, output = self.follow_up_fixture('9.9.0-SNAPSHOT')
        self.assert_success(self.run_follow_up(follow_up, output))
        self.assertEqual('required=false\n', output.read_text(encoding='utf-8'))
        self.assertEqual('', self.git(follow_up, 'status', '--porcelain', '--untracked-files=all'))
        self.assert_source_preserved()

    def test_follow_up_does_not_overwrite_another_development_line(self) -> None:
        follow_up, output = self.follow_up_fixture('10.0.0-SNAPSHOT')
        result = self.run_follow_up(follow_up, output)
        self.assert_success(result)
        self.assertIn('not creating a follow-up version PR', result.stdout)
        self.assertEqual('required=false\n', output.read_text(encoding='utf-8'))
        self.assertEqual('', self.git(follow_up, 'status', '--porcelain', '--untracked-files=all'))
        self.assert_source_preserved()

    def test_follow_up_rejects_a_dirty_checkout_before_changing_metadata(self) -> None:
        follow_up, output = self.follow_up_fixture('9.8.7-SNAPSHOT')
        for name in ('CITATION.md', 'unexpected.txt'):
            with self.subTest(path=name):
                path = follow_up / name
                original = path.read_bytes() if path.exists() else None
                path.write_bytes(b'Unrelated pending change\n')
                before = {relative: (follow_up / relative).read_bytes() for relative in METADATA}
                result = self.run_follow_up(follow_up, output)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(output.exists(), 'no follow-up may be authorized on failure')
                self.assertEqual(before, {relative: (follow_up / relative).read_bytes()
                                          for relative in METADATA})
                self.assertEqual(b'Unrelated pending change\n', path.read_bytes())
                if original is None:
                    path.unlink()
                else:
                    path.write_bytes(original)
        self.assert_source_preserved()

    def test_follow_up_rejects_parent_repository_fallback(self) -> None:
        _, output = self.follow_up_fixture('9.8.7-SNAPSHOT')
        wrong = self.root / 'not-a-checkout'
        wrong.mkdir()
        result = self.run_follow_up(wrong, output)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.exists())
        self.assert_source_preserved()


if __name__ == '__main__':
    unittest.main()
