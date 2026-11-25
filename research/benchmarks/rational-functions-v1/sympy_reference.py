"""Separate fixed SymPy control and independent exact audit of every admitted row."""
import ast
import hashlib
import json
import platform
import sys
import time
from pathlib import Path

import sympy as sp

if sp.__version__ != "1.14.0":
    raise SystemExit("This reference is pinned to SymPy 1.14.0")


def nonzero_factors(value):
    """Irreducible monic polynomial guards, with subexpression holes already retained."""
    numerator = sp.cancel(value).as_numer_denom()[0]
    if numerator == 0:
        raise ValueError("identically zero nonzero requirement")
    symbols = sorted(numerator.free_symbols, key=str)
    if not symbols:
        return set()
    polynomial = sp.Poly(numerator, *symbols, domain=sp.QQ)
    return {factor.monic().as_expr() for factor, _ in polynomial.factor_list()[1]}


def inspect(text):
    """Visit the raw grammar before SymPy can erase a quotient or a zero power."""
    source = text.strip().replace("^", "**")
    guards = set()

    def visit(node):
        if isinstance(node, ast.Name):
            return sp.Symbol(node.id)
        if isinstance(node, ast.Constant) and type(node.value) in (int, float):
            return sp.Rational(ast.get_source_segment(source, node))
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd)):
            value = visit(node.operand)
            return -value if isinstance(node.op, ast.USub) else value
        if isinstance(node, ast.BinOp):
            left, right = visit(node.left), visit(node.right)
            if isinstance(node.op, ast.Add):
                return left + right
            if isinstance(node.op, ast.Sub):
                return left - right
            if isinstance(node.op, ast.Mult):
                return left * right
            if isinstance(node.op, ast.Div):
                guards.update(nonzero_factors(right))
                return left / right
            if isinstance(node.op, ast.Pow) and right.is_Integer and right >= 0:
                if right == 0:
                    guards.update(nonzero_factors(left))
                return left ** right
        raise ValueError("outside rational arithmetic grammar")

    return visit(ast.parse(source, mode="eval").body), guards


def conditions_match(conditions, original_guards):
    guards = set()
    try:
        for condition in conditions:
            lhs, rhs = condition.split("!=")
            left, left_guards = inspect(lhs)
            right, right_guards = inspect(rhs)
            guards.update(left_guards, right_guards, nonzero_factors(left - right))
    except (SyntaxError, ValueError):
        return False
    return guards == original_guards


def check_candidate(text, original_value, original_guards):
    try:
        value, guards = inspect(text)
    except (SyntaxError, ValueError):
        return False, False
    return sp.cancel(original_value - value) == 0, guards <= original_guards


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


def main():
    source, destination = map(Path, sys.argv[1:])
    raw = source.read_bytes()
    report = json.loads(raw)
    rows = []
    for row in report["rows"]:
        result = {"corpus": row["corpus"], "ordinal": row["ordinal"], "name": row["name"], "status": row["status"]}
        if row["status"] == "SUPPORTED":
            started, cpu_started = time.perf_counter_ns(), time.process_time_ns()
            try:
                expression, original_guards = inspect(row["source"])
            except (ValueError, SyntaxError) as error:
                result.update(nativeExactAndDomainChecked=False, sourceDomainError=str(error))
                rows.append(result)
                continue
            exact, defined = check_candidate(row["candidate"], expression, original_guards)
            match = conditions_match(row["requiredConditions"], original_guards)
            result.update(nativeExact=exact, nativeDefinedOnSourceDomain=defined,
                          declaredConditionsMatchSource=match, nativeExactAndDomainChecked=exact and defined and match)
            variants = [row["source"], emit(sp.cancel(expression)), emit(sp.factor(expression)),
                        emit(sp.factor(sp.cancel(expression)))]
            accepted = [text for text in variants if all(check_candidate(text, expression, original_guards))]
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
