"""A registered revision must be connected to each requested original freeze by actual commit bytes."""
import base64
import gzip
import json
from pathlib import Path
import tempfile
import unittest

from external_polynomial_comparison.registered_source import _object, require_ancestor


class RegisteredSourceTest(unittest.TestCase):
    def test_parent_chain_is_required_and_raw_commit_substitution_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base = root/'build/authority-evidence'
            base.mkdir(parents=True)
            (root/'config/benchmarks').mkdir(parents=True)
            raw_parent = b'tree ' + b'0'*40 + b'\n\nfixture parent\n'
            parent = _object('commit', raw_parent)
            raw = b'tree ' + b'1'*40 + b'\nparent ' + parent.encode() + b'\n\nfixture head\n'
            head = _object('commit', raw)
            encode = lambda data: base64.b64encode(data).decode('ascii')
            source = {'schema':'regelsuche.quality.source-tree/v1', 'revision':head, 'commit':encode(raw)}
            (base/'quality-source-tree.json').write_text(json.dumps(source))
            original = {'schema':'regelsuche.polynomial-original-source-objects/v1',
                        'objects':{parent:{'type':'commit', 'data':encode(raw_parent)}}}
            (root/'config/benchmarks/polynomial-freeze-source-objects.json.gz').write_bytes(gzip.compress(json.dumps(original).encode()))
            proof = {'schema':'regelsuche.polynomial-source-witness/v1', 'revision':head,
                     'objects':{head:{'type':'commit', 'data':encode(raw)}}}
            receipt = base/'polynomial-source.json'
            receipt.write_text(json.dumps(proof))
            self.assertEqual(head, require_ancestor(root, receipt, parent))
            with self.assertRaisesRegex(ValueError, 'ancestor'):
                require_ancestor(root, receipt, 'f'*40)
            proof['objects'][head]['data'] = encode(raw+b'tampered')
            receipt.write_text(json.dumps(proof))
            with self.assertRaisesRegex(ValueError, 'object bytes'):
                require_ancestor(root, receipt, parent)
