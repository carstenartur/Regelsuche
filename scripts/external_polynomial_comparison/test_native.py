import json
import unittest
from external_polynomial_comparison import run_native as native
from external_polynomial_comparison.run import judge


class NativeComparisonTest(unittest.TestCase):
    def test_response_binding_rejects_wrong_source_model_and_hidden_costs(self):
        response = dict(status='CANDIDATE', profile='NATIVE_BASE', input='x+0', output='x', target='',
                        targetReached=False, modelHash='bound', search=json.dumps({'totalWork': 7}),
                        selectionWork=3, selectedReplayWork=2, totalWork=12, internalWorkWithinBudget=True)
        native.check_response('NATIVE_BASE', 'x+0', response, 'bound', 12)
        for key, value in [('input', 'y'), ('modelHash', 'wrong'), ('totalWork', 8),
                           ('internalWorkWithinBudget', False), ('target', 'x'), ('targetReached', True)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                native.check_response('NATIVE_BASE', 'x+0', dict(response, **{key: value}), 'bound', 12)

    def test_quality_requires_every_case_and_does_not_cancel_a_loss_with_a_win(self):
        protocol = dict(cases=[{'id': 'a', 'source': 'x+0', 'cohort': 'HISTORICAL'},
                               {'id': 'b', 'source': 'y+0', 'cohort': 'WITHHELD_PUBLIC_VARIANT'}],
                        profiles=['NATIVE_BASE', 'NATIVE_LEARNED', 'SYMPY_PORTFOLIO'], repetitions=1)
        rows = []
        for case in protocol['cases']:
            for profile in protocol['profiles']:
                output = case['source'] if profile == 'NATIVE_LEARNED' and case['id'] == 'b' else case['source'][0]
                response = dict(status='CANDIDATE', output=output)
                rows.append(dict(case=case['id'], profile=profile, repetition=0, source=case['source'],
                                 response=response, judgment=judge(case['source'], response, 0, 10**9)))
        report = native.quality(protocol, rows)
        self.assertTrue(report['NATIVE_BASE']['accepted'])
        self.assertFalse(report['NATIVE_LEARNED']['accepted'])
        self.assertEqual(1, report['NATIVE_LEARNED']['worse'])
        with self.assertRaises(ValueError):
            native.quality(protocol, rows[:-1])

    def test_short_incorrect_expression_fails_the_common_exact_judge(self):
        result = judge('(x+1)*(x-1)', dict(status='CANDIDATE', output='0'), 0, 10**9)
        self.assertFalse(result['valid'])
        self.assertEqual('INVALID_IDENTITY', result['verdict'])
