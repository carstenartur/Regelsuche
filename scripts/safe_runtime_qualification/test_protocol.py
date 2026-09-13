"""Adversarial decisions over observations supplied by an external producer."""
from copy import deepcopy
import importlib.util
from pathlib import Path
import unittest

MODULE = Path(__file__).with_name('protocol.py')


class QualificationDecisionTest(unittest.TestCase):
    def protocol(self):
        self.assertTrue(MODULE.is_file(), 'the independent runtime decision authority is missing')
        spec = importlib.util.spec_from_file_location('safe_runtime_protocol', MODULE)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def evidence(self, status, principal):
        candidate = {'rule': 'principal', 'expression': 'x', 'retainedAssumptions': ['b != 0'],
                     'primitiveRuleIds': ['support', 'principal'], 'executionWork': {'primitiveRewrites': 2}}
        return {'status': status, 'request': {'source': 'x+0', 'assumptions': ['b != 0'],
                    'ruleIds': ['principal', 'support'], 'preparationRuleIds': ['support'],
                    'maxWorkUnits': 1000, 'maxPrimitiveRewrites': 4, 'maxTheoryWorkUnits': 1000,
                    'preparationBudget': {'maxDepth': 2}, 'includeSymPy': False},
                'inventory': {'visible': [{'id': 'principal', 'selected': True}, {'id': 'support', 'selected': True}]},
                'implementation': {'contentHash': 'sha256:' + 'a' * 64},
                'work': {'setupUnits': 3, 'analysisUnits': 20, 'verificationUnits': 20,
                         'chargedUnits': 43, 'configuredWorkUnits': 1000, 'refusedReservationUnits': 0},
                'outcomes': ([{'id': 'principal', 'candidate': candidate}] if principal else []),
                'retainedAssumptions': ['b != 0']}

    def case(self):
        return {'id': 'prepared', 'kind': 'CAPABILITY', 'gainEligible': True,
                'expected': {'principalId': 'principal', 'directPresent': False, 'safePresent': True,
                             'retainedAssumptions': ['b != 0']}}

    def test_completed_no_match_and_replayed_principal_can_contribute_gain(self):
        result = self.protocol().evaluate_pair(self.case(), self.evidence('NO_MATCH', False),
                                              self.evidence('SUCCESS', True))
        self.assertTrue(result['expectationsSatisfied'])
        self.assertTrue(result['newPrincipalAvailable'])

    def test_failed_or_inconclusive_direct_baseline_never_creates_gain(self):
        for status in ('TECHNICAL_FAILURE', 'BUDGET_INCONCLUSIVE', 'UNSUPPORTED'):
            with self.subTest(status=status):
                result = self.protocol().evaluate_pair(self.case(), self.evidence(status, False),
                                                      self.evidence('SUCCESS', True))
                self.assertFalse(result['newPrincipalAvailable'])
                self.assertFalse(result['expectationsSatisfied'])

    def test_refused_direct_verification_is_inconclusive_even_if_status_claims_no_match(self):
        direct = self.evidence('NO_MATCH', False)
        direct['work']['refusedReservationUnits'] = 100
        result = self.protocol().evaluate_pair(self.case(), direct, self.evidence('SUCCESS', True))
        self.assertFalse(result['newPrincipalAvailable'])
        self.assertFalse(result['expectationsSatisfied'])

    def test_pairing_rejects_each_changed_source_budget_or_inventory(self):
        for key, value in [('source', 'other'), ('assumptions', []), ('ruleIds', []),
                           ('preparationRuleIds', []), ('maxWorkUnits', 999),
                           ('maxPrimitiveRewrites', 3), ('maxTheoryWorkUnits', 999),
                           ('preparationBudget', {'maxDepth': 1}), ('includeSymPy', True)]:
            with self.subTest(key=key):
                safe = self.evidence('SUCCESS', True)
                safe['request'][key] = value
                with self.assertRaisesRegex(ValueError, 'paired request'):
                    self.protocol().evaluate_pair(self.case(), self.evidence('NO_MATCH', False), safe)
        for key in ('inventory', 'implementation'):
            safe = self.evidence('SUCCESS', True)
            safe[key] = {}
            with self.assertRaisesRegex(ValueError, key):
                self.protocol().evaluate_pair(self.case(), self.evidence('NO_MATCH', False), safe)

    def test_common_candidate_or_assumption_loss_disqualifies_comparison(self):
        case = self.case()
        case['expected']['directPresent'] = True
        direct = self.evidence('SUCCESS', True)
        safe = self.evidence('SUCCESS', True)
        safe['outcomes'][0]['candidate']['retainedAssumptions'] = []
        result = self.protocol().evaluate_pair(case, direct, safe)
        self.assertFalse(result['expectationsSatisfied'])
        self.assertTrue(result['commonCandidateRegression'])

    def test_expected_technical_control_can_pass_but_never_contribute_gain(self):
        case = self.case()
        case.update(kind='EXPECTED_FAILURE_CONTROL', gainEligible=False)
        case['expected'].update(safePresent=False,
                               statuses={'DIRECT_V1': 'TECHNICAL_FAILURE', 'SAFE_PREPARATION_V4': 'TECHNICAL_FAILURE'})
        result = self.protocol().evaluate_pair(case, self.evidence('TECHNICAL_FAILURE', False),
                                              self.evidence('TECHNICAL_FAILURE', False))
        self.assertTrue(result['expectationsSatisfied'])
        self.assertFalse(result['newPrincipalAvailable'])

    def test_successful_support_cannot_hide_an_incomplete_direct_principal(self):
        for status in ('TECHNICAL_FAILURE', 'BUDGET_INCONCLUSIVE', 'UNSUPPORTED'):
            with self.subTest(status=status):
                direct, safe = self.evidence('SUCCESS', False), self.evidence('SUCCESS', True)
                support = deepcopy(safe['outcomes'][0])
                support['id'] = 'support'
                direct['outcomes'] = [{'id': 'principal', 'status': status}, support]
                safe['outcomes'].append(deepcopy(support))
                result = self.protocol().evaluate_pair(self.case(), direct, safe)
                self.assertFalse(result['expectationsSatisfied'])
                self.assertFalse(result['newPrincipalAvailable'])

    def test_declared_guard_control_keeps_its_support_success_without_creating_gain(self):
        case = self.case()
        case.update(kind='GUARD_CONTROL', gainEligible=False)
        case['expected']['safePresent'] = False
        direct, safe = self.evidence('SUCCESS', False), self.evidence('SUCCESS', False)
        support = deepcopy(self.evidence('SUCCESS', True)['outcomes'][0])
        support['id'] = 'support'
        for evidence in (direct, safe):
            evidence['outcomes'] = [{'id': 'principal', 'status': 'UNSUPPORTED'}, deepcopy(support)]
        result = self.protocol().evaluate_pair(case, direct, safe)
        self.assertTrue(result['expectationsSatisfied'])
        self.assertFalse(result['newPrincipalAvailable'])

    def test_guard_exception_does_not_authorize_an_unsupported_support_rule(self):
        case = self.case()
        case.update(kind='GUARD_CONTROL', gainEligible=False)
        case['expected']['safePresent'] = False
        direct, safe = self.evidence('UNSUPPORTED', False), self.evidence('UNSUPPORTED', False)
        for evidence in (direct, safe):
            evidence['outcomes'] = [{'id': 'principal', 'status': 'UNSUPPORTED'},
                                    {'id': 'support', 'status': 'UNSUPPORTED'}]
        result = self.protocol().evaluate_pair(case, direct, safe)
        self.assertFalse(result['expectationsSatisfied'])

    def test_declared_unsupported_control_cannot_hide_a_refused_direct_budget(self):
        case = self.case()
        case.update(kind='UNSUPPORTED_CONTROL', gainEligible=False)
        case['expected'].update(safePresent=False,
                               statuses={'DIRECT_V1': 'NO_MATCH', 'SAFE_PREPARATION_V4': 'UNSUPPORTED'})
        direct = self.evidence('NO_MATCH', False)
        direct['work']['refusedReservationUnits'] = 20
        self.assertFalse(self.protocol().evaluate_pair(case, direct, self.evidence('UNSUPPORTED', False))['expectationsSatisfied'])

    def test_charged_work_cannot_erase_setup_analysis_or_replay(self):
        direct = self.evidence('NO_MATCH', False)
        direct['work']['chargedUnits'] = 3
        with self.assertRaisesRegex(ValueError, 'charged work'):
            self.protocol().evaluate_pair(self.case(), direct, self.evidence('SUCCESS', True))

    def test_typed_success_requires_concrete_solution_and_its_relation(self):
        case = {'id': 'typed', 'kind': 'TYPED', 'gainEligible': False,
                'expected': {'relation': 'SOLUTION_SET_EQUIVALENCE', 'safeClassification': 'UNIQUE',
                             'safeSolution': ['9/10', '-1/2']}}
        direct, safe = self.evidence('SUCCESS', False), self.evidence('SUCCESS', False)
        for evidence in (direct, safe):
            evidence['authority'] = {'typedArtifact': self.protocol().canonical({'evidence': {
                'formation': {'coefficients': [['1', '0'], ['0', '1']], 'rightHandSide': ['9/10', '-1/2']},
                'solving': {}}}).decode()}
        result = self.protocol().evaluate_pair(case, direct, safe)
        self.assertFalse(result['expectationsSatisfied'], 'summary SUCCESS is not concrete typed replay evidence')
        artifact = __import__('json').loads(safe['authority']['typedArtifact'])
        artifact['evidence']['solving']['rref'] = {'relation': 'SOLUTION_SET_EQUIVALENCE',
            'classification': 'UNIQUE', 'particularSolution': ['9/10', '-1/2']}
        safe['authority']['typedArtifact'] = self.protocol().canonical(artifact).decode()
        self.assertTrue(self.protocol().evaluate_pair(case, direct, safe)['expectationsSatisfied'])
        artifact['evidence']['solving']['rref']['relation'] = 'SCALAR_EQUALITY'
        safe['authority']['typedArtifact'] = self.protocol().canonical(artifact).decode()
        self.assertFalse(self.protocol().evaluate_pair(case, direct, safe)['expectationsSatisfied'])


if __name__ == '__main__':
    unittest.main()
