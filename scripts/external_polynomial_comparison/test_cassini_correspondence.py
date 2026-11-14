"""Controls for the public, post-freeze correspondence decision."""
import json
import unittest
from pathlib import Path

from external_polynomial_comparison.cassini_correspondence import REFERENCE, correspondence, evaluate
from external_polynomial_comparison.run import canonical


class CassiniCorrespondenceTest(unittest.TestCase):
    root = Path(__file__).resolve().parents[2] / 'docs/research/cassini-development-v1'

    def test_all_committed_rows_replay_including_nulls_and_exhaustion(self):
        result = evaluate((self.root/'sources.json').read_bytes(), (self.root/'candidates.json').read_bytes())
        self.assertEqual((self.root/'evaluation.json').read_bytes(), canonical(result))
        decisions = [[c['decision'] for c in r['candidates']] for r in result['rows']]
        self.assertEqual([['MATCH'], ['DIFFERENT_SOURCE'], ['DIFFERENT_SOURCE'] * 5, [], [], ['DIFFERENT_SOURCE']], decisions)
        self.assertEqual(['COMPLETE', 'BUDGET_INCONCLUSIVE'], [r['formation'] for r in result['rows'][3:5]])

    def test_reordered_or_omitted_candidates_or_changed_source_cannot_be_rebound(self):
        sources = (self.root/'sources.json').read_bytes()
        freeze = (self.root/'candidates.json').read_bytes()
        for change in (lambda laws: laws.pop(), lambda laws: laws.reverse()):
            altered = json.loads(freeze)
            change(altered['cases'][2]['laws'])
            with self.assertRaisesRegex(ValueError, 'freeze commitment'):
                evaluate(sources, canonical(altered))
        with self.assertRaisesRegex(ValueError, 'source commitment'):
            evaluate(sources + b' ', freeze)

    def test_exact_scale_base_evolution_and_primitive_replay(self):
        request = json.loads((self.root/'sources.json').read_bytes())['cases'][0]['request']
        negative = dict(REFERENCE, polynomial='2*x1^2-2*x0*(x0+x1)', initialValue='2')
        self.assertEqual({'decision': 'MATCH', 'scale': '-2'}, correspondence(request, negative, request, REFERENCE))
        for reference in (dict(REFERENCE, initialValue='1'), dict(REFERENCE, evolution='FIXED'),
                          dict(REFERENCE, polynomial='x0*x1-x1^2')):
            self.assertNotEqual('MATCH', correspondence(request, negative, request, reference)['decision'])
        for bad in (dict(negative, polynomial='x0*x1-x1^2'), dict(negative, initialValue='1'),
                    dict(negative, evolution='FIXED')):
            with self.assertRaises(ValueError):
                correspondence(request, bad, request, REFERENCE)
