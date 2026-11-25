import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from external_polynomial_comparison.qualify_archives import require_source, require_typed_setup
from external_polynomial_comparison.run_typed import FREEZE_COMMIT, PROTOCOL_HASH


class ArchiveSourceTest(unittest.TestCase):
    def test_sources_and_typed_protocol_must_match_the_admitted_checkout(self):
        revision, tree = 'a' * 40, 'b' * 40
        with TemporaryDirectory() as temporary:
            typed, native = (Path(temporary) / name for name in ('typed', 'native'))
            typed.mkdir()
            native.mkdir()
            typed_metadata = {'revision': revision, 'freezeCommit': FREEZE_COMMIT, 'protocolHash': PROTOCOL_HASH}
            native_metadata = {'sourceRevision': revision, 'sourceTreeHash': 'git:' + tree}
            for directory, metadata in ((typed, typed_metadata), (native, native_metadata)):
                (directory / 'metadata.json').write_text(json.dumps(metadata))
            require_source(typed, native, revision, tree)
            for directory, metadata in ((typed, typed_metadata), (native, native_metadata)):
                for key in metadata:
                    with self.subTest(key=key):
                        path = directory / 'metadata.json'
                        path.write_text(json.dumps({**metadata, key: 'changed'}))
                        with self.assertRaisesRegex(ValueError, 'source|protocol'):
                            require_source(typed, native, revision, tree)
                        path.write_text(json.dumps(metadata))

    def test_model_count_alone_cannot_cover_missing_profiles_or_warmups(self):
        profiles = ['BASE', 'SYMPY_FACTOR']
        metadata = {'setup': {'BASE': {'training': {}}, 'SYMPY_FACTOR': {}},
                    'warmups': [{'profile': profile} for profile in profiles]}
        require_typed_setup(profiles, metadata, {'BASE.model.json'})
        with self.assertRaisesRegex(ValueError, 'model'):
            require_typed_setup(profiles, metadata, {'other.model.json'})
        with self.assertRaisesRegex(ValueError, 'setup'):
            require_typed_setup(profiles, {**metadata, 'setup': {'BASE': {}}}, {'BASE.model.json'})
        with self.assertRaisesRegex(ValueError, 'warmup'):
            require_typed_setup(profiles, {**metadata, 'warmups': [metadata['warmups'][0]] * 2}, {'BASE.model.json'})
