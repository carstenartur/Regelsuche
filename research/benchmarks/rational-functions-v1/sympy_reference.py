"""Separate fixed SymPy control and independent exact audit of every admitted row."""
import ast
import hashlib
import json
import platform
import re
import sys
import time
from pathlib import Path

import sympy as sp

if sp.__version__ != "1.14.0":
    raise SystemExit("This reference is pinned to SymPy 1.14.0")


def parse(text):
    names = set(re.findall(r"[A-Za-z][A-Za-z0-9_]*", text))
    return sp.sympify(text.replace("^", "**"), locals={s: sp.Symbol(s) for s in names}, rational=True)


def emit(expr):
    if expr.is_Integer or expr.is_Symbol:
        return str(expr)
    if expr.is_Rational:
        return f"({expr.p}/{expr.q})"
    if expr.is_Add:
        terms = expr.as_ordered_terms()
        result = emit(terms[0])
        for term in terms[1:]:
            negative = term.could_extract_minus_sign()
            result += ("-" if negative else "+") + emit(-term if negative else term)
        return "(" + result + ")"
    numerator, denominator = expr.as_numer_denom()
    if denominator != 1:
        return "(" + emit(numerator) + ")/(" + emit(denominator) + ")"
    if expr.is_Mul:
        return "(" + "*".join(emit(x) for x in expr.as_ordered_factors()) + ")"
    if expr.is_Pow and expr.exp.is_Integer and expr.exp >= 0:
        return "(" + emit(expr.base) + "^" + str(expr.exp) + ")"
    raise ValueError("outside rational arithmetic grammar: " + str(expr))


def cost(text):
    def visit(node):
        if isinstance(node, ast.BinOp):
            if isinstance(node.op, ast.Pow):
                n = ast.literal_eval(node.right)
                return 0 if n == 0 else n * visit(node.left) + n - 1
            return 1 + visit(node.left) + visit(node.right)
        if isinstance(node, ast.UnaryOp):
            return 0 if isinstance(node.operand, ast.Constant) else 1 + visit(node.operand)
        if isinstance(node, (ast.Name, ast.Constant)):
            return 0
        raise ValueError("outside measured grammar")
    return visit(ast.parse(text.replace("^", "**"), mode="eval").body)


def on_original_domain(candidate, conditions):
    denominator = sp.fraction(sp.cancel(candidate))[1]
    if not denominator.free_symbols:
        return denominator != 0
    guards = [parse(c.split("!=")[0]) - parse(c.split("!=")[1]) for c in conditions]
    product = sp.prod(guards)
    symbols = sorted(product.free_symbols | denominator.free_symbols, key=str)
    source_radical = sp.Poly(product, *symbols, domain=sp.QQ).sqf_part()
    target_radical = sp.Poly(denominator, *symbols, domain=sp.QQ).sqf_part()
    return source_radical.rem(target_radical).is_zero


def main():
    source, destination = map(Path, sys.argv[1:])
    raw = source.read_bytes()
    report = json.loads(raw)
    rows = []
    for row in report["rows"]:
        result = {"corpus": row["corpus"], "ordinal": row["ordinal"], "name": row["name"], "status": row["status"]}
        if row["status"] == "SUPPORTED":
            started, cpu_started = time.perf_counter_ns(), time.process_time_ns()
            expression = parse(row["source"])
            native = parse(row["candidate"])
            result["nativeExactAndDomainChecked"] = bool(sp.cancel(expression - native) == 0
                and on_original_domain(native, row["requiredConditions"]))
            variants = [row["source"], emit(sp.cancel(expression)), emit(sp.factor(expression)),
                        emit(sp.factor(sp.cancel(expression)))]
            accepted = [text for text in variants if sp.cancel(expression - parse(text)) == 0
                        and on_original_domain(parse(text), row["requiredConditions"])]
            best = min(accepted, key=lambda text: (cost(text), text))
            result.update(sourceSha256=row["sourceSha256"], candidate=best,
                          sourceOperations=cost(row["source"]), nativeOperations=cost(row["candidate"]),
                          fixedOperations=cost(best), variants=len(variants),
                          wallNanos=time.perf_counter_ns() - started, processCpuNanos=time.process_time_ns() - cpu_started)
        rows.append(result)
    destination.write_text(json.dumps({"schema": "regelsuche.rational-fixed-reference/v1", "sympy": sp.__version__,
        "python": platform.python_version(), "inputSha256": hashlib.sha256(raw).hexdigest(),
        "fixedPolicy": "min(source,cancel,factor,factor(cancel)); exact equality and original domain checked",
        "timingScope": "per row includes native audit and all fixed candidates; excludes interpreter startup/imports",
        "rows": rows}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
