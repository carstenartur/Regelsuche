import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from external_polynomial_comparison import run_direct


class DirectPolynomialComparisonTest(unittest.TestCase):
    def test_extension_retains_frozen_inputs_and_deadlines(self):
        parent = run_direct.native.load_protocol(run_direct.PROTOCOL)
        protocol = run_direct.comparison_protocol(run_direct.PROTOCOL)
        for key in ('cases', 'queryTimeoutSeconds', 'setupTimeoutSeconds', 'repetitions'):
            self.assertEqual(parent[key], protocol[key])
        self.assertEqual(list(run_direct.schedule(protocol))[:3],
                         [(profile, 0) for profile in run_direct.PROFILES])
        with TemporaryDirectory() as directory:
            changed = Path(directory) / 'protocol.json'
            changed.write_text(json.dumps(parent | {'queryTimeoutSeconds': 3}))
            with self.assertRaisesRegex(ValueError, 'pre-execution freeze'):
                run_direct.comparison_protocol(changed)

    def test_setup_binds_model_budget_and_formation_work(self):
        model = json.dumps({'constructionProfile': run_direct.DIRECT, 'objective': run_direct.OBJECTIVE})
        digest = 'sha256:' + hashlib.sha256(model.encode()).hexdigest()
        response = dict(status='INITIALIZED', profile=run_direct.DIRECT, model=model, modelHash=digest,
                        settings=run_direct.SETTINGS, trainingWork=0, learnedPrograms=0)
        self.assertEqual(digest, run_direct.direct_setup(response))
        for changed in ({'trainingWork': 1}, {'modelHash': 'invalid'}, {'settings': {'workBudget': 16_385}}):
            with self.subTest(changed=changed), self.assertRaisesRegex(ValueError, 'setup differs'):
                run_direct.direct_setup(response | changed)
        changed_model = json.dumps({'constructionProfile': run_direct.DIRECT, 'objective': 'SHARED_EXACT_ARITHMETIC_SURFACE'})
        changed_digest = 'sha256:' + hashlib.sha256(changed_model.encode()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'different construction profile or objective'):
            run_direct.direct_setup(response | {'model': changed_model, 'modelHash': changed_digest})


if __name__ == '__main__':
    unittest.main()
