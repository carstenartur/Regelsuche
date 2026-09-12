"""Fail-closed archive and output checks; these fixtures do not qualify Java execution."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


def load(name, script):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(script))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


api = load('extension_api_verifier', 'verify-sdk-api-compatibility.py')
consumer = load('extension_consumer_verifier', 'verify-student-java-sdk-consumer.py')
# Independent list of the published revision-2 contract, including its public nested enum.
CLASSES = {
    'regelsuche-extension-api': {
        'de/regelsuche/extension/' + name + '.class' for name in (
            'ExtensionApi', 'ExtensionCatalog', 'ExtensionCatalogs', 'ExtensionContext',
            'ExtensionDescriptor', 'ExtensionOrigin', 'ExtensionOrigin$OriginKind',
            'ExtensionPoint', 'PluginDependency', 'PluginDescriptor', 'RegelsuchePlugin',
            'RegisteredExtension',
        )
    },
    'regelsuche-extension-runtime': {
        'de/regelsuche/extension/runtime/' + name + '.class' for name in (
            'AdmittedPluginArtifact', 'CatalogReloadResult', 'ExtensionRuntime',
            'ExtensionRuntimeConfig', 'PluginArtifactAdmission',
        )
    },
}
POLICY = {
    'extensionApiVersion': '2',
    'stablePackages': ['de.regelsuche.extension', 'de.regelsuche.extension.runtime'],
    'baselineCompatibilityPackages': [],
}


class ExtensionVerificationBoundariesTest(unittest.TestCase):
    def jars(self, directory, classes):
        result = []
        for module, entries in classes.items():
            path = Path(directory) / (module + '-0.5.0-SNAPSHOT.jar')
            with zipfile.ZipFile(path, 'w') as archive:
                for entry in sorted(entries):
                    archive.writestr(entry, b'entry-presence fixture; not executable bytecode')
            result.append(path)
        return result

    def test_complete_owning_artifacts_are_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            api.validate_extension_revision(POLICY, self.jars(temporary, CLASSES))

    def test_every_public_type_is_required_in_its_module(self):
        for module, entries in CLASSES.items():
            for missing in sorted(entries):
                with self.subTest(missing=missing), tempfile.TemporaryDirectory() as temporary:
                    classes = {key: set(value) for key, value in CLASSES.items()}
                    classes[module].remove(missing)
                    with self.assertRaises(RuntimeError):
                        api.validate_extension_revision(POLICY, self.jars(temporary, classes))

    def test_complete_class_union_in_wrong_modules_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            modules = list(CLASSES)
            swapped = {modules[0]: CLASSES[modules[1]], modules[1]: CLASSES[modules[0]]}
            with self.assertRaises(RuntimeError):
                api.validate_extension_revision(POLICY, self.jars(temporary, swapped))

    def test_duplicate_stable_types_in_another_artifact_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            classes = {**CLASSES, 'unrelated': {'de/regelsuche/extension/ExtensionContext.class'}}
            with self.assertRaises(RuntimeError):
                api.validate_extension_revision(POLICY, self.jars(temporary, classes))

    def test_missing_or_duplicate_module_artifacts_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            jars = self.jars(temporary, CLASSES)
            for bad in (jars[:1], jars + jars[:1]):
                with self.subTest(jars=bad), self.assertRaises(RuntimeError):
                    api.validate_extension_revision(POLICY, bad)

    def test_sources_cannot_replace_binary_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            jars = self.jars(temporary, CLASSES)
            sources = jars[0].with_name(jars[0].stem + '-sources.jar')
            jars[0].rename(sources)
            with self.assertRaises(RuntimeError):
                api.validate_extension_revision(POLICY, [sources, jars[1]])

    def test_duplicate_zip_entries_for_every_public_type_are_rejected(self):
        payloads = (b'entry-presence fixture; not executable bytecode', b'different entry')
        for module, entries in CLASSES.items():
            for entry in sorted(entries):
                for payload in payloads:
                    with self.subTest(entry=entry, payload=payload), tempfile.TemporaryDirectory() as temporary:
                        jars = self.jars(temporary, CLASSES)
                        owner = next(jar for jar in jars if jar.name.startswith(module + '-'))
                        with zipfile.ZipFile(owner, 'a') as archive:
                            with self.assertWarnsRegex(UserWarning, 'Duplicate name'):
                                archive.writestr(entry, payload)
                        with self.assertRaisesRegex(RuntimeError, 'duplicate ZIP entries'):
                            api.validate_extension_revision(POLICY, jars)

    def test_duplicate_zip_metadata_is_rejected(self):
        for module in CLASSES:
            with self.subTest(module=module), tempfile.TemporaryDirectory() as temporary:
                jars = self.jars(temporary, CLASSES)
                owner = next(jar for jar in jars if jar.name.startswith(module + '-'))
                with zipfile.ZipFile(owner, 'a') as archive:
                    archive.writestr('META-INF/MANIFEST.MF', b'Manifest-Version: 1.0\n')
                    with self.assertWarnsRegex(UserWarning, 'Duplicate name'):
                        archive.writestr('META-INF/MANIFEST.MF', b'Manifest-Version: 1.0\n')
                with self.assertRaisesRegex(RuntimeError, 'duplicate ZIP entries'):
                    api.validate_extension_revision(POLICY, jars)

    def test_unique_directory_and_metadata_entries_are_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            jars = self.jars(temporary, CLASSES)
            for jar in jars:
                with zipfile.ZipFile(jar, 'a') as archive:
                    archive.writestr('META-INF/', b'')
                    archive.writestr('META-INF/MANIFEST.MF', b'Manifest-Version: 1.0\n')
            api.validate_extension_revision(POLICY, jars)

    def test_exact_catalog_hash_token_is_required(self):
        malformed = ('', 'a' * 63, 'a' * 65, 'A' * 64, 'g' * 64, 'a' * 64 + '!',
                     'a' * 32 + ' ' + 'a' * 32)
        for value in malformed:
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                consumer.require_output('extension=hello origin=greeting-plugin catalog=sha256:'
                    + value, ('extension=hello', 'origin=greeting-plugin', 'catalog=sha256:'),
                    'extension-runtime-java25')

    def test_valid_token_is_accepted_in_normal_consumer_output(self):
        for separator in (' ', '\n', '\r\n'):
            text = separator.join(('extension=hello', 'origin=greeting-plugin',
                'catalog=sha256:' + '0123456789abcdef' * 4))
            consumer.require_output(text, ('extension=hello', 'origin=greeting-plugin'),
                'extension-runtime-java25')

    def test_duplicate_or_prefixed_catalog_tokens_are_rejected(self):
        token = 'catalog=sha256:' + 'a' * 64
        for text in (token + ' ' + token, 'not-' + token, token + ' catalog=sha256:bad'):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                consumer.require_output(text, ('catalog=sha256:',), 'extension-runtime-java25')

    def test_unrelated_consumer_output_contract_is_unchanged(self):
        consumer.require_output('outcome=CONFIRMED', ('outcome=CONFIRMED',), 'other-consumer')
        with self.assertRaises(RuntimeError):
            consumer.require_output('outcome=REFUTED', ('outcome=CONFIRMED',), 'other-consumer')


if __name__ == '__main__':
    unittest.main()
