"""Portable Schema admission must prove the source and original historical blob."""
from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from external_polynomial_comparison import run_schema as driver


class SchemaSourceTest(unittest.TestCase):
    key = 'REGELSUCHE_NATIVE_SOURCE_VERIFIER_CLASSPATH'

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        evidence = self.root / 'build/authority-evidence'
        evidence.mkdir(parents=True)
        self.receipts = [evidence / name for name in ('quality-source-tree.json', 'polynomial-source.json')]
        self.verified = {'revision': 'a' * 40, 'tree': 'b' * 40, 'trackedChangesPresent': False,
            'registeredProtocolSha256': driver.PROTOCOL_HASH, 'ancestors': {driver.FREEZE_COMMIT: True}}

    def stage(self):
        for receipt in self.receipts:
            receipt.write_text('{}', encoding='utf-8')

    def check(self, proof=None):
        proof = self.verified if proof is None else proof
        reply = subprocess.CompletedProcess([], 0, json.dumps(proof).encode(), b'')
        with patch.dict(os.environ, {self.key: '/verifier/classes'}, clear=True), \
                patch('external_polynomial_comparison.registered_source.subprocess.run', return_value=reply) as java, \
                patch.object(driver, 'check_freeze', side_effect=AssertionError('ambient Git fallback')), \
                patch.object(driver, 'source_provenance', side_effect=AssertionError('ambient Git fallback')):
            result = driver.schema_source_provenance(self.root)
        self.assertEqual(['schema-freeze', str(self.root)], java.call_args.args[0][-2:])
        return result

    def test_configured_execute_requires_both_receipts_before_output_or_git(self):
        with patch.dict(os.environ, {self.key: '/verifier/classes'}, clear=True), \
                patch.object(driver.Path, 'cwd', return_value=self.root), \
                patch.object(driver, 'load_protocol', return_value={}), \
                patch.object(driver, 'check_freeze', side_effect=AssertionError('ambient Git fallback')):
            with self.assertRaisesRegex(ValueError, 'source registration and ancestry witness'):
                driver.execute(self.root / 'protocol.json', self.root / 'classpath.txt', self.root / 'result')
        self.assertFalse((self.root / 'result').exists())

    def test_staged_receipts_cannot_fall_back_when_verifier_is_absent(self):
        for retained in self.receipts:
            with self.subTest(retained=retained.name):
                retained.write_text('{}')
                with patch.dict(os.environ, {}, clear=True), \
                        self.assertRaisesRegex(ValueError, 'source registration and ancestry witness'):
                    driver.schema_source_provenance(self.root)
                retained.unlink()

    def test_broken_staged_receipt_cannot_select_ambient_git(self):
        self.receipts[0].symlink_to(self.root / 'missing')
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(driver, 'check_freeze', side_effect=AssertionError('ambient Git fallback')), \
                self.assertRaisesRegex(ValueError, 'source registration and ancestry witness'):
            driver.schema_source_provenance(self.root)

    def test_failed_java_verification_cannot_select_ambient_git(self):
        self.stage()
        with patch.dict(os.environ, {self.key: '/verifier/classes'}, clear=True), \
                patch('external_polynomial_comparison.registered_source.subprocess.run',
                      side_effect=subprocess.CalledProcessError(1, 'java')), \
                patch.object(driver, 'check_freeze', side_effect=AssertionError('ambient Git fallback')), \
                self.assertRaises(subprocess.CalledProcessError):
            driver.schema_source_provenance(self.root)

    def test_requires_each_receipt_and_nonempty_verifier(self):
        for missing in self.receipts:
            self.stage()
            missing.unlink()
            with self.subTest(missing=missing.name), self.assertRaisesRegex(ValueError, 'source registration and ancestry witness'):
                self.check()
        self.stage()
        with patch.dict(os.environ, {self.key: ''}, clear=True), \
                self.assertRaisesRegex(ValueError, 'source registration and ancestry witness'):
            driver.schema_source_provenance(self.root)

    def test_verified_source_binds_the_published_freeze_and_historical_protocol(self):
        self.stage()
        self.assertEqual({'sourceRevision': 'a' * 40, 'sourceTreeHash': 'git:' + 'b' * 40,
            'freezeCommit': driver.FREEZE_COMMIT, 'freezeProtocolHash': driver.PROTOCOL_HASH}, self.check())
        # The original local commit is descriptive metadata, not a required ancestor.
        self.assertNotIn(driver.SOURCE_LOCAL_FREEZE_COMMIT, self.verified['ancestors'])

    def test_rejects_unbound_dirty_or_fabricated_proofs(self):
        self.stage()
        mutations = [dict(self.verified, trackedChangesPresent=value) for value in (True, 'false', 0, None)]
        mutations += [dict(self.verified, **{key: value}) for key, value in
            [('revision', 'A' * 40), ('tree', None), ('registeredProtocolSha256', 'c' * 64),
             ('ancestors', {}), ('ancestors', {driver.FREEZE_COMMIT: 1}), ('ancestors', [])]]
        for proof in mutations:
            with self.subTest(proof=proof), self.assertRaises(ValueError):
                self.check(proof)

    def test_ordinary_checkout_keeps_original_git_provenance(self):
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(driver, 'check_freeze', return_value={'freezeCommit': 'published'}) as freeze, \
                patch.object(driver, 'source_provenance', return_value={'sourceRevision': 'head'}) as source:
            self.assertEqual({'freezeCommit': 'published', 'sourceRevision': 'head'},
                             driver.schema_source_provenance(self.root))
        freeze.assert_called_once_with(self.root)
        source.assert_called_once_with(self.root)

    def test_execute_rechecks_source_and_freeze_after_sequences(self):
        protocol = self.root / 'protocol.json'
        protocol.write_text('{}')
        classpath = self.root / 'classpath.txt'
        classpath.write_text('/runtime')
        provenance = {'sourceRevision': 'a' * 40, 'sourceTreeHash': 'git:' + 'b' * 40,
            'freezeCommit': driver.FREEZE_COMMIT, 'freezeProtocolHash': driver.PROTOCOL_HASH}
        changed = deepcopy(provenance)
        changed['sourceTreeHash'] = 'git:' + 'c' * 40
        with patch.object(driver, 'schema_source_provenance', side_effect=[provenance, changed]) as admission, \
                patch.object(driver, 'load_protocol', return_value={}), \
                patch.object(driver, 'snapshot_runtime', return_value={'classpathEntries': [], 'runtimeBytesHash': 'fixture'}), \
                patch.object(driver, '_java_provenance', return_value={'executable': '/java'}), \
                patch.object(driver, 'schedule', return_value=[]), \
                patch.object(driver, 'require_sequences'), \
                self.assertRaisesRegex(ValueError, 'source changed during execution'):
            driver.execute(protocol, classpath, self.root / 'result')
        self.assertEqual(2, admission.call_count)


if __name__ == '__main__':
    unittest.main()
