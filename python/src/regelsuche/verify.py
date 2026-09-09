"""Independent exact solution-set checker. No Java, eval, or server audit flag is trusted.

This verifies mathematics, not solver work, policy provenance, or a Lean proof.
"""
from __future__ import annotations

import ast
import json
import re
from fractions import Fraction
from pathlib import Path
from typing import Any

MAX_BYTES = 4 * 1024 * 1024
MAX_BITS = 4096


class VerificationError(ValueError):
    """The supplied artifact does not establish a supported exact solution set."""


def _object(value: Any) -> dict:
    if not isinstance(value, dict):
        raise VerificationError("Expected a JSON object")
    return value


def _bounded(value: Fraction) -> Fraction:
    if max(value.numerator.bit_length(), value.denominator.bit_length()) > MAX_BITS:
        raise VerificationError("Rational exceeds the independent checker's 4096-bit bound")
    return value


def _rational(value: Any) -> Fraction:
    if not isinstance(value, str) or len(value) > 2500:
        raise VerificationError("Expected a bounded exact rational string")
    if not re.fullmatch(r"[+-]?\d+(?:/\d+)?", value):
        raise VerificationError("Invalid rational value")
    try:
        return _bounded(Fraction(value))
    except (ValueError, ZeroDivisionError) as error:
        raise VerificationError("Invalid rational value") from error


def loads(source: str | bytes) -> dict:
    """Read bounded JSON; reject duplicate keys, non-finite numbers and malformed roots."""
    if len(source.encode("utf-8") if isinstance(source, str) else source) > MAX_BYTES:
        raise VerificationError("Artifact exceeds 4 MiB")

    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise VerificationError("Duplicate JSON key")
            result[key] = value
        return result

    def nonfinite(value):
        raise VerificationError("Non-finite JSON number")

    try:
        return _object(json.loads(source, object_pairs_hook=pairs, parse_constant=nonfinite))
    except (ValueError, UnicodeError, RecursionError) as error:
        raise VerificationError("Malformed solution JSON: " + str(error)) from error


def load(path: str | Path) -> dict:
    with Path(path).open("rb") as stream:
        return loads(stream.read(MAX_BYTES + 1))


# Sparse affine forms are (coefficients, constant); original names are retained separately.
def _add(left, right):
    names = left[0].keys() | right[0].keys()
    values = {name: _bounded(left[0].get(name, Fraction(0)) + right[0].get(name, Fraction(0))) for name in names}
    return {name: value for name, value in values.items() if value}, _bounded(left[1] + right[1])


def _scale(form, scalar):
    return {name: _bounded(value * scalar) for name, value in form[0].items() if value * scalar}, _bounded(form[1] * scalar)


def _expression(source: str, names: set[str]):
    source = source.strip().replace("^", "**")
    if not source or len(source) > 1024:
        raise VerificationError("Unsupported expression length")
    try:
        root = ast.parse(source, mode="eval")
    except (SyntaxError, ValueError, RecursionError) as error:
        raise VerificationError("Expected explicit arithmetic, e.g. 2*x") from error
    if sum(1 for _ in ast.walk(root)) > 512:
        raise VerificationError("Too many expression nodes")

    def visit(node, depth=0):
        if depth > 64:
            raise VerificationError("Expression depth exceeds 64")
        if isinstance(node, ast.Constant) and type(node.value) in (int, float):
            token = ast.get_source_segment(source, node)
            if token is None or len(token) > 128 or not re.fullmatch(r"(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?", token):
                raise VerificationError("Unsupported numeric literal")
            exponent = re.search(r"[eE]([+-]?\d+)$", token)
            if exponent and abs(int(exponent.group(1))) > 64:
                raise VerificationError("Numeric exponent exceeds 64")
            return {}, _bounded(Fraction(token))
        if isinstance(node, ast.Name) and re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", node.id):
            names.add(node.id)
            return {node.id: Fraction(1)}, Fraction(0)
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
            return _scale(visit(node.operand, depth + 1), Fraction(-1 if isinstance(node.op, ast.USub) else 1))
        if not isinstance(node, ast.BinOp):
            raise VerificationError("Expression outside exact affine arithmetic")
        left, right = visit(node.left, depth + 1), visit(node.right, depth + 1)
        if isinstance(node.op, ast.Add):
            return _add(left, right)
        if isinstance(node.op, ast.Sub):
            return _add(left, _scale(right, Fraction(-1)))
        if isinstance(node.op, ast.Mult):
            if left[0] and right[0]:
                raise VerificationError("Nonlinear multiplication")
            return _scale(left, right[1]) if not right[0] else _scale(right, left[1])
        if isinstance(node.op, ast.Div):
            if right[0] or not right[1]:
                raise VerificationError("Division requires a nonzero rational constant")
            return _scale(left, 1 / right[1])
        if isinstance(node.op, ast.Pow):
            if right[0] or right[1].denominator != 1 or abs(right[1]) > 64:
                raise VerificationError("Power requires an integer exponent between -64 and 64")
            exponent = int(right[1])
            if exponent == 1:
                return left
            if exponent == 0:
                if not left[0] and not left[1]:
                    raise VerificationError("Undefined zero power")
                return {}, Fraction(1)
            if left[0] or (not left[1] and exponent < 0):
                raise VerificationError("Non-affine or undefined power")
            return {}, _bounded(left[1] ** exponent)
        raise VerificationError("Unsupported arithmetic operator")

    return visit(root.body)


def _solve(equations: Any):
    if not isinstance(equations, list) or not 1 <= len(equations) <= 16:
        raise VerificationError("Expected 1 to 16 equations")
    forms = []
    names: set[str] = set()
    for equation in equations:
        if not isinstance(equation, str) or len(equation) > 512 or equation.count("=") != 1:
            raise VerificationError("Invalid source equation")
        left, right = equation.split("=")
        forms.append(_add(_expression(left, names), _scale(_expression(right, names), Fraction(-1))))
    variables = sorted(names)
    if not 1 <= len(variables) <= 16:
        raise VerificationError("Expected 1 to 16 variables")
    matrix = [[form[0].get(name, Fraction(0)) for name in variables] + [-form[1]] for form in forms]
    pivots = []
    row = 0
    for column in range(len(variables)):
        pivot = next((i for i in range(row, len(matrix)) if matrix[i][column]), None)
        if pivot is None:
            continue
        matrix[row], matrix[pivot] = matrix[pivot], matrix[row]
        scalar = matrix[row][column]
        matrix[row] = [_bounded(value / scalar) for value in matrix[row]]
        for i in range(len(matrix)):
            if i != row and matrix[i][column]:
                scalar = matrix[i][column]
                matrix[i] = [_bounded(a - _bounded(scalar * b)) for a, b in zip(matrix[i], matrix[row])]
        pivots.append(column)
        row += 1
    if any(not any(values[:-1]) and values[-1] for values in matrix):
        return variables, "INCONSISTENT", None, []
    particular = [Fraction(0)] * len(variables)
    for row, column in enumerate(pivots):
        particular[column] = matrix[row][-1]
    basis = []
    for free in range(len(variables)):
        if free not in pivots:
            vector = [Fraction(0)] * len(variables)
            vector[free] = Fraction(1)
            for row, column in enumerate(pivots):
                vector[column] = -matrix[row][free]
            basis.append(vector)
    return variables, "UNDERDETERMINED" if basis else "UNIQUE", particular, basis


def verify_artifact(artifact: dict, expected_equations: list[str] | None = None) -> dict:
    """Recompute the full canonical affine solution set from original equations."""
    artifact = _object(artifact)
    if artifact.get("schema") != "regelsuche.linear-solution-artifact/v1":
        raise VerificationError("Unsupported solution artifact schema")
    evidence = _object(artifact.get("evidence"))
    request, result = _object(evidence.get("request")), _object(evidence.get("result"))
    if request.get("schema") != "regelsuche.linear-solve-request/v1":
        raise VerificationError("Unsupported source request schema")
    equations = request.get("equations")
    if expected_equations is not None and equations != expected_equations:
        raise VerificationError("Response is not bound to the requested equations")
    if result.get("equations") != equations or result.get("status") != "SOLVED":
        raise VerificationError("No solved result bound to these source equations")
    variables, classification, particular, basis = _solve(equations)
    solution = _object(result.get("solution"))
    if result.get("variables") != variables or solution.get("variables") != variables:
        raise VerificationError("Variable order or set differs from original equations")
    if solution.get("classification") != classification:
        raise VerificationError("Incorrect solution classification")
    if not isinstance(solution.get("basis"), list) or any(not isinstance(row, list) for row in solution["basis"]):
        raise VerificationError("Invalid nullspace basis")
    if [[_rational(value) for value in row] for row in solution["basis"]] != basis:
        raise VerificationError("Incomplete or incorrect nullspace basis")
    if particular is None:
        if "particular" in solution or _rational(solution.get("contradiction")) != 1:
            raise VerificationError("Invalid inconsistency consequence")
    elif not isinstance(solution.get("particular"), list) or [_rational(value) for value in solution["particular"]] != particular or "contradiction" in solution:
        raise VerificationError("Incorrect particular solution")
    return {"classification": classification, "variables": variables, "particular": particular, "basis": basis}


def verify_study(study: dict) -> dict[str, int]:
    """Check every solved mathematical result; this does not attest learning or costs."""
    if _object(study).get("schema") != "regelsuche.representation-transfer-study/v1":
        raise VerificationError("Unsupported study schema")
    verified = unsolved = 0
    for collection in (study.get("training"), study.get("rows")):
        if not isinstance(collection, list) or not collection:
            raise VerificationError("Missing study observations")
        for row in collection:
            artifact = _object(_object(row).get("artifact"))
            result = _object(_object(artifact.get("evidence")).get("result"))
            if result.get("status") == "SOLVED":
                verify_artifact(artifact)
                verified += 1
            else:
                if "solution" in result:
                    raise VerificationError("Unsolved result contains a claimed solution")
                unsolved += 1
    return {"verified": verified, "unsolved": unsolved}
