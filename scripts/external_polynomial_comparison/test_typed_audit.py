"""Replay audit regressions based on actual retained Java-25 worker receipts."""
from copy import deepcopy
import json
import gzip
from pathlib import Path
import unittest
from external_polynomial_comparison.audit_typed import audit_response

FIXTURE = Path(__file__).with_name('testdata') / 'typed-evidence-java25.json.gz'

class TypedEvidenceAuditTest(unittest.TestCase):
    def setUp(self):
        self.rows = json.loads(gzip.decompress(FIXTURE.read_bytes()).decode('utf-8'))['rows']

    def audit(self, row):
        return audit_response(row['source'], row['response'])

    def reject(self, row):
        with self.assertRaises(ValueError):
            self.audit(row)

    def test_actual_primitive_and_learned_paths_are_independently_checked(self):
        primitive = self.audit(self.rows[0])
        learned = self.audit(self.rows[1])
        self.assertEqual(2, primitive['primitiveSteps'])
        self.assertEqual(3, learned['primitiveSteps'])
        self.assertEqual(1, learned['selectedEdges'])
        self.assertEqual(1, learned['learnedEdges'])

    def test_unchanged_input_has_no_fabricated_path(self):
        self.assertEqual({'selectedEdges': 0, 'primitiveSteps': 0, 'learnedEdges': 0}, self.audit(self.rows[2]))

    def test_correct_endpoint_does_not_authorize_an_incorrect_macro_intermediate(self):
        row = self.rows[1]
        move = row['response']['witness'][0]['move']
        # The end result is still correct. Only an internal primitive changes.
        original = deepcopy(move)
        move['primitiveExpansion'][0]['transformedExpression'] = self.rows[2]['response']['typedInput']
        # Keep the selected graph edge aligned: the math check must reject it too.
        search = json.loads(row['response']['search'])
        for event in search['events']:
            if event['move'] == original:
                event['move'] = deepcopy(move)
        row['response']['search'] = json.dumps(search)
        self.reject(row)

    def test_selected_path_must_be_present_in_the_admitted_search_graph(self):
        row = self.rows[1]
        search = json.loads(row['response']['search'])
        search['events'] = []
        row['response']['search'] = json.dumps(search)
        self.reject(row)

    def test_missing_witness_cannot_hide_a_changed_typed_endpoint(self):
        row = self.rows[0]
        row['response']['witness'] = []
        self.reject(row)

    def test_disconnected_selected_edges_are_rejected(self):
        row = self.rows[0]
        row['response']['witness'].reverse()
        self.reject(row)

    def test_source_text_must_match_the_supplied_typed_input(self):
        row = self.rows[0]
        row['source'] = 'x+97'
        self.reject(row)

    def test_output_text_must_match_the_supplied_typed_output(self):
        row = self.rows[0]
        row['response']['output'] = 'x+97'
        self.reject(row)

    def test_replay_work_cannot_be_forged_even_with_a_balanced_total(self):
        row = self.rows[1]
        row['response']['totalWork'] -= row['response']['selectedReplayWork']
        row['response']['selectedReplayWork'] = 0
        self.reject(row)

    def test_search_work_partition_must_match_its_measured_components(self):
        row = self.rows[1]
        search = json.loads(row['response']['search'])
        search['totalWork'] -= 1
        row['response']['totalWork'] -= 1
        row['response']['search'] = json.dumps(search)
        self.reject(row)

    def test_rejected_receipts_do_not_authorize_a_selected_edge(self):
        row = self.rows[1]
        row['response']['witness'][0]['verification']['accepted'] = False
        self.reject(row)

    def test_conditional_premises_are_not_silently_accepted_by_the_unconditional_pilot(self):
        row = self.rows[1]
        row['response']['witness'][0]['move']['primitiveExpansion'][0]['assumptions'] = ['x!=0']
        self.reject(row)

    def test_unknown_typed_grammar_and_duplicate_json_fields_are_rejected(self):
        for document in ['{"schema":"wrong","expression":{}}',
                         '{"schema":"regelsuche.typed-move-expression/v1","expression":{},"expression":{}}']:
            row = deepcopy(self.rows[1])
            row['response']['typedInput'] = document
            self.reject(row)

    def test_primitive_expansion_must_reach_the_selected_typed_endpoint(self):
        row = self.rows[1]
        row['response']['witness'][0]['move']['primitiveExpansion'].pop()
        self.reject(row)

if __name__ == '__main__':
    unittest.main()
