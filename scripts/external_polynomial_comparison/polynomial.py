"""A bounded rational-polynomial judge, independent of both Java and SymPy.

No eval/sympify: parse a small arithmetic AST and compute sparse coefficients
using Fraction. Polynomial exponent zero is the multiplicative unit, including
zero to the zero power (formal polynomial-ring semantics, not real analysis).
"""
from __future__ import annotations
import ast
import re
from fractions import Fraction

LIMITS = {'inputCharacters': 2048, 'astNodes': 256, 'variables': 4,
          'maxExponent': 12, 'maxTerms': 4096, 'coefficientBits': 2048}
Monomial = tuple[tuple[str, int], ...]
Polynomial = dict[Monomial, Fraction]

class Unsupported(ValueError):
    """Outside the frozen shared grammar or the independent judge's limits."""


def parse(source: str) -> ast.expr:
    if not isinstance(source, str) or not source.strip() or len(source) > LIMITS['inputCharacters']:
        raise Unsupported('invalid input length')
    try:
        node = ast.parse(source.replace('^', '**'), mode='eval').body
    except (SyntaxError, ValueError, RecursionError) as error:
        raise Unsupported('invalid arithmetic syntax') from error
    if sum(1 for _ in ast.walk(node)) > LIMITS['astNodes']:
        raise Unsupported('AST node limit')
    names = {n.id for n in ast.walk(node) if isinstance(n, ast.Name)}
    if len(names) > LIMITS['variables'] or any(not re.fullmatch('[A-Za-z][A-Za-z0-9_]*', s) for s in names):
        raise Unsupported('variable contract')
    return node


def operation_cost(source: str) -> int:
    """One per binary arithmetic or unary minus; no simplification is applied."""
    node = parse(source)
    def visit(n: ast.expr) -> int:
        if isinstance(n, ast.Constant) and type(n.value) is int:
            return 0
        if isinstance(n, ast.Name):
            return 0
        if isinstance(n, ast.UnaryOp) and isinstance(n.op, ast.USub):
            return 1 + visit(n.operand)
        if isinstance(n, ast.BinOp) and isinstance(n.op, (ast.Add, ast.Sub, ast.Mult, ast.Div, ast.Pow)):
            return 1 + visit(n.left) + visit(n.right)
        raise Unsupported('unsupported arithmetic operator')
    return visit(node)


def _checked(value: Polynomial) -> Polynomial:
    result = {key: coefficient for key, coefficient in value.items() if coefficient}
    if len(result) > LIMITS['maxTerms']:
        raise Unsupported('polynomial term limit')
    if any(max(abs(c.numerator).bit_length(), c.denominator.bit_length()) > LIMITS['coefficientBits'] for c in result.values()):
        raise Unsupported('coefficient bit limit')
    return result


def _add(a: Polynomial, b: Polynomial) -> Polynomial:
    result = dict(a)
    for m, c in b.items():
        result[m] = result.get(m, Fraction(0)) + c
    return _checked(result)


def _multiply(a: Polynomial, b: Polynomial) -> Polynomial:
    if len(a) * len(b) > LIMITS['maxTerms'] * 16:
        raise Unsupported('polynomial multiplication work limit')
    result: Polynomial = {}
    for ma, ca in a.items():
        for mb, cb in b.items():
            powers = dict(ma)
            for name, power in mb:
                powers[name] = powers.get(name, 0) + power
            key = tuple(sorted(powers.items()))
            result[key] = result.get(key, Fraction(0)) + ca * cb
        _checked(result)
    return _checked(result)


def polynomial(source: str) -> Polynomial:
    def visit(n: ast.expr) -> Polynomial:
        if isinstance(n, ast.Constant) and type(n.value) is int:
            return _checked({(): Fraction(n.value)})
        if isinstance(n, ast.Name):
            return {((n.id, 1),): Fraction(1)}
        if isinstance(n, ast.UnaryOp) and isinstance(n.op, ast.USub):
            return {m: -c for m, c in visit(n.operand).items()}
        if not isinstance(n, ast.BinOp):
            raise Unsupported('not rational polynomial syntax')
        a = visit(n.left)
        if isinstance(n.op, ast.Pow):
            if not isinstance(n.right, ast.Constant) or type(n.right.value) is not int or not 0 <= n.right.value <= LIMITS['maxExponent']:
                raise Unsupported('power must have a bounded nonnegative literal exponent')
            result = {(): Fraction(1)}
            for _ in range(n.right.value):
                result = _multiply(result, a)
            return result
        b = visit(n.right)
        if isinstance(n.op, ast.Add):
            return _add(a, b)
        if isinstance(n.op, ast.Sub):
            return _add(a, {m: -c for m, c in b.items()})
        if isinstance(n.op, ast.Mult):
            return _multiply(a, b)
        if isinstance(n.op, ast.Div):
            if any(isinstance(child, ast.Name) for child in ast.walk(n.right)) or set(b) != {()}:
                raise Unsupported('denominator must be a nonzero rational constant')
            return _checked({m: c / b[()] for m, c in a.items()})
        raise Unsupported('unsupported arithmetic operator')
    return visit(parse(source))
