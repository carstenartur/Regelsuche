"""Additional exact offline audit of the public query-reuse report.

Uses the existing bounded Fraction polynomial judge, independent of Java and
SymPy. This audit is outside the measured Java arm/process costs.
"""
from __future__ import annotations

import ast
import gzip
import json
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts"))
from external_polynomial_comparison.polynomial import parse, polynomial


def structure(source: str) -> str:
    return ast.dump(parse(source))


def nodes(source: str) -> int:
    def visit(node: ast.expr) -> int:
        if isinstance(node, (ast.Name, ast.Constant)):
            return 1
        if isinstance(node, ast.BinOp):
            return 1 + visit(node.left) + visit(node.right)
        raise ValueError("outside this panel's occurrence-node grammar")
    return visit(parse(source))


def verify(path: Path) -> dict:
    begin = time.perf_counter_ns()
    raw = gzip.decompress(path.read_bytes()) if path.suffix == ".gz" else path.read_bytes()
    report = json.loads(raw)
    rows = edges = 0
    for arm in report["arms"]:
        assert arm["accountingComplete"] and not arm["failure"]
        assert arm["knownWork"] == arm["setupWork"] + sum(
            row["queryWork"] + row["auditWork"] for row in arm["queries"])
        assert arm["statistics"]["knownWork"] == sum(row["queryWork"] for row in arm["queries"])
        assert arm["statistics"]["hits"] == sum(row["reused"] for row in arm["queries"])
        for row in arm["queries"]:
            rows += 1
            assert row["verified"] and not row["failure"]
            assert polynomial(row["input"]["source"]) == polynomial(row["output"])
            assert nodes(row["output"]) == row["score"]
            assert row["solved"] == (
                row["score"] <= row["input"]["maximumNodes"] and row["queryWork"] <= 200_000)
            current = structure(row["input"]["source"])
            for edge in row["witness"]:
                edges += 1
                assert structure(edge["source"]) == current
                assert edge["accepted"] and edge["verificationWork"] > 0
                assert polynomial(edge["source"]) == polynomial(edge["target"])
                current = structure(edge["target"])
            assert current == structure(row["output"])
            if row["reused"]:
                assert row["witness"] and row["verificationWork"] > 0 and row["replayWork"] > 0
    for comparison in report["comparisons"]:
        pair = [arm for arm in report["arms"]
                if arm["repetition"] == comparison["repetition"]
                and arm["scenario"] == comparison["scenario"]]
        previous = next(arm for arm in pair if arm["profile"] == "PERSISTENT")
        candidate = next(arm for arm in pair if arm["profile"] == "RETAINED_PATH")
        assert len(previous["queries"]) == len(candidate["queries"])
        for before, after in zip(previous["queries"], candidate["queries"]):
            assert before["input"] == after["input"]
            assert not before["solved"] or after["solved"]
            assert after["score"] <= before["score"]
        assert comparison["qualityNonRegressing"]
        assert comparison["observedWorkSavings"] == previous["knownWork"] - candidate["knownWork"]
    return {"revision": "regelsuche.task-query-reuse-python-audit/v2",
            "report": path.name, "rows": rows, "edges": edges, "passed": True,
            "wallNanos": time.perf_counter_ns() - begin,
            "scope": "additional post-run Python Fraction audit; outside all measured Java arm/process totals"}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: python3 verify-report.py REPORT.json[.gz]")
    print(json.dumps(verify(Path(sys.argv[1])), indent=2))
