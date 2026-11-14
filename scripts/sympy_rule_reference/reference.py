"""Replay the fixed external operations for the public rule-amplification panel."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import sys

import mpmath
import sympy
from sympy.simplify.fu import fu


HERE = Path(__file__).resolve().parent
SOURCES = HERE / "sources.json"
EXPECTED = HERE / "expected.json"
SYMBOLS = ("a", "b", "n", "x", "y")


def encode(data):
    return (json.dumps(data, indent=2, sort_keys=True) + "\n").encode("utf-8")


def read_source(source, assumptions):
    """Validate the finite scalar grammar before passing source to the SymPy parser."""
    functions = {"sin": sympy.sin, "cos": sympy.cos}
    symbols = {}
    for name in SYMBOLS:
        properties = {"real": True}
        if name + " != 0" in assumptions:
            properties["nonzero"] = True
        symbols[name] = sympy.Symbol(name, **properties)
    expression = source.replace("^", "**")
    names = symbols | functions
    for node in ast.walk(ast.parse(expression, mode="eval")):
        if isinstance(node, ast.Name) and node.id in names:
            continue
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and (
                node.func.id in functions and len(node.args) == 1 and not node.keywords):
            continue
        if isinstance(node, ast.Constant) and type(node.value) is int:
            continue
        if isinstance(node, (ast.Expression, ast.Load, ast.BinOp, ast.Add, ast.Sub,
                             ast.Mult, ast.Div, ast.Pow, ast.UnaryOp, ast.USub)):
            continue
        raise ValueError("Unsupported input to the pinned reference grammar")
    for condition in assumptions:
        if not re.fullmatch(r"(?:a|b|n|x|y)(?: \+ [0-9]+)? != 0", condition):
            raise ValueError("Unsupported source assumption")
    return sympy.sympify(expression, locals=names, evaluate=False), symbols


def evaluate_case(case):
    if set(case) != {"id", "source", "declaredAssumptions"}:
        raise ValueError("Reference operation accepts only the case identity, source and assumptions")
    expr, symbols = read_source(case["source"], case["declaredAssumptions"])
    outputs = {
        "fu": fu(expr),
        "factor": sympy.factor(expr),
        "apart(n)": sympy.apart(expr, symbols["n"]),
    }
    return {**case, "outputs": {name: str(result) for name, result in outputs.items()}}


def run_panel():
    if (sympy.__version__, mpmath.__version__) != ("1.14.0", "1.3.0"):
        raise ValueError("The reference panel requires SymPy 1.14.0 and mpmath 1.3.0")
    source_bytes = SOURCES.read_bytes()
    cases = json.loads(source_bytes)
    ids = [case["id"] for case in cases]
    if len(ids) != 11 or len(set(ids)) != len(ids):
        raise ValueError("Expected eleven distinct public cases")
    return {"schema": "regelsuche.sympy-preparation-reference/v1",
            "sympy": sympy.__version__, "mpmath": mpmath.__version__,
            "sourcesSha256": hashlib.sha256(source_bytes).hexdigest(),
            "cases": [evaluate_case(case) for case in cases]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="compare with the retained result")
    args = parser.parse_args()
    result = encode(run_panel())
    if args.verify and result != EXPECTED.read_bytes():
        raise SystemExit("Named SymPy outputs differ from the retained panel")
    if not args.verify:
        sys.stdout.buffer.write(result)


if __name__ == "__main__":
    main()
