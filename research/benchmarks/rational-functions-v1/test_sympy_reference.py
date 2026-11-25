"""Adversarial source/candidate/receipt triples through the reference program."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class ReferenceAuditTest(unittest.TestCase):
    def test_syntactic_holes_and_reported_guards(self):
        cases = [
            ("x", "(x*y)/y", ["y != 0"], False),
            ("x", "(x*y)/y", [], False),
            ("(x*y)/y", "x", [], False),
            ("(x*y)/y", "x", ["y != 0"], True),
            ("1/(1/x)", "x", [], False),
            ("1/(1/x)", "x", ["x != 0"], True),
            ("x", "x", ["y != 0"], False),
            ("x^0", "1", [], False),
            ("1", "x^0", ["x != 0"], False),
            ("(x/y)^0", "1", ["y != 0"], False),
            ("(x/y)^0", "1", ["x*y != 0"], True),
            ("(x/y)^0", "1", ["x != 0", "y != 0"], True),
            ("2^0", "1", [], True),
            ("1", "0^0", [], False),
            ("0^0", "1", [], False),
            ("x", "x/(x-x)", [], False),
            ("(y/y)/x", "1/x", ["x != 0"], False),
            ("x", "x", ["0 != 0"], False),
            ("1/(x*(x+1))", "1/x-1/(x+1)", ["x != -1", "x != 0"], True),
            ("1/(x*(x+1))", "1/x+1/(x+1)", ["x != -1", "x != 0"], False),
        ]
        rows = [
            dict(corpus="adversarial", ordinal=i, name=str(i), status="SUPPORTED", source=source,
                 candidate=candidate, requiredConditions=conditions,
                 sourceSha256=hashlib.sha256(source.encode()).hexdigest())
            for i, (source, candidate, conditions, _) in enumerate(cases)
        ]
        with tempfile.TemporaryDirectory() as temp:
            source, output = (Path(temp) / name for name in ("rows.json", "audit.json"))
            source.write_text(json.dumps(dict(rows=rows)))
            subprocess.run([sys.executable, str(Path(__file__).with_name("sympy_reference.py")),
                            str(source), str(output)], check=True)
            results = json.loads(output.read_bytes())["rows"]
        for row, case in zip(results, cases, strict=True):
            with self.subTest(case=case):
                self.assertEqual(case[3], row["nativeExactAndDomainChecked"])
        new_hole = results[0]
        self.assertTrue(new_hole["nativeExact"])
        self.assertFalse(new_hole["nativeDefinedOnSourceDomain"])
        self.assertFalse(new_hole["declaredConditionsMatchSource"])
        omitted_hole = results[2]
        self.assertTrue(omitted_hole["nativeExact"])
        self.assertTrue(omitted_hole["nativeDefinedOnSourceDomain"])
        self.assertFalse(omitted_hole["declaredConditionsMatchSource"])
        arithmetic_error = results[-1]
        self.assertFalse(arithmetic_error["nativeExact"])
        self.assertTrue(arithmetic_error["nativeDefinedOnSourceDomain"])
        self.assertTrue(arithmetic_error["declaredConditionsMatchSource"])
