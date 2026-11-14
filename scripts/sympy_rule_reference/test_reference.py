import unittest

import reference


class NamedOperationsTest(unittest.TestCase):
    def test_all_operations_run_on_every_source_and_reproduce_retained_bytes(self):
        data = reference.run_panel()
        self.assertEqual(11, len(data["cases"]))
        for case in data["cases"]:
            self.assertEqual({"fu", "factor", "apart(n)"}, set(case["outputs"]))
        self.assertEqual(reference.encode(data), reference.EXPECTED.read_bytes())

    def test_near_miss_and_original_denominator_conditions_are_retained(self):
        rows = {case["id"]: case for case in reference.run_panel()["cases"]}
        row = rows["telescoping-step-two-near-miss"]
        self.assertEqual(["n != 0", "n + 2 != 0"], row["declaredAssumptions"])
        self.assertEqual("-1/(2*(n + 2)) + 1/(2*n)", row["outputs"]["apart(n)"])
        self.assertEqual("x**2 + y**2", rows["difference-squares-sum-near-miss"]["outputs"]["factor"])

    def test_symbol_premises_and_unevaluated_source_survive_until_operation(self):
        expr, symbols = reference.read_source("(x*a)/a", ["x != 0"])
        self.assertTrue(symbols["x"].is_nonzero)
        self.assertIsNone(symbols["a"].is_nonzero)
        self.assertIn(symbols["a"], expr.free_symbols)

    def test_unknown_operations_and_reference_fields_are_rejected(self):
        with self.assertRaises(ValueError):
            reference.read_source("arbitrary(x)", [])
        one = {"id": "x", "source": "x", "declaredAssumptions": [], "expected": "x"}
        with self.assertRaises(ValueError):
            reference.evaluate_case(one)


if __name__ == "__main__":
    unittest.main()
