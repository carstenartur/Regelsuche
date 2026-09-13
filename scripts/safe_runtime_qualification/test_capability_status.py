"""The public bounded experiment must not silently authorize a general default."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class RuntimeCapabilityStatusTest(unittest.TestCase):
    def test_product_status_retains_the_public_coverage_boundary(self):
        spec = importlib.util.spec_from_file_location('capability_status_generator', ROOT / 'scripts/generate-capability-status.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertTrue(hasattr(module, 'safe_runtime_capability'), 'runtime capability status is missing')
        result = module.safe_runtime_capability(ROOT)
        self.assertEqual('EXPERIMENTAL', result['status'])
        self.assertEqual(['FULL_VISIBLE_INVENTORY_NOT_QUALIFIED'], result['blockers'])
        self.assertIn('opt-in', result['claim'])
        self.assertGreaterEqual(len(result['evidenceRoots']), 4)
        self.assertTrue(any('14' in note and 'public' in note for note in result['notes']))


if __name__ == '__main__':
    unittest.main()
