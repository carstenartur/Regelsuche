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


def native_scalar_reference(rows):
    """Execute the declared external operation on source-only input, with total-domain admission."""
    if sympy.__version__ != "1.14.0" or mpmath.__version__ != "1.3.0":
        raise ValueError("Pinned SymPy 1.14.0 and mpmath 1.3.0 required")
    if not isinstance(rows, list) or not 1 <= len(rows) <= 32:
        raise ValueError("Expected bounded comparison panel")
    results = []
    operations = {"factor_terms": sympy.factor_terms, "cancel": sympy.cancel}
    for row in rows:
        if set(row) != {"id", "source", "assumptions", "operation"} or len(row["source"]) > 256:
            raise ValueError("Unknown or oversized external input")
        expr, _ = read_source(row["source"], row["assumptions"])
        operation = operations[row["operation"]]
        denominators = [term.base for term in sympy.preorder_traversal(expr)
                        if isinstance(term, sympy.Pow) and term.exp.is_negative is True]
        result = {"id": row["id"], "operation": row["operation"],
                  "sourceOps": int(sympy.count_ops(expr)), "source": row["source"],
                  "assumptions": row["assumptions"], "proof": "NO_DETACHED_DERIVATION"}
        if any(sympy.ask(sympy.Q.nonzero(term)) is not True for term in denominators):
            result["status"] = "NONZERO_NOT_ESTABLISHED"
        else:
            value = operation(expr)
            result.update(status="RETURNED", output=sympy.sstr(value).replace("**", "^"),
                          outputOps=int(sympy.count_ops(value)))
        results.append(result)
    return {"sympy": sympy.__version__, "mpmath": mpmath.__version__, "cases": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="compare with the retained result")
    parser.add_argument("--native-scalar-request", action="store_true", help="read source-only scalar cases from stdin")
    args = parser.parse_args()
    if args.native_scalar_request:
        sys.stdout.buffer.write(encode(native_scalar_reference(json.loads(sys.stdin.buffer.read(65537)))))
        return
    result = encode(run_panel())
    if args.verify and result != EXPECTED.read_bytes():
        raise SystemExit("Named SymPy outputs differ from the retained panel")
    if not args.verify:
        sys.stdout.buffer.write(result)


if __name__ == "__main__":
    main()
