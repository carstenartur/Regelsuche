"""Pinned native competitors. Requests contain sources, never reference answers."""
from __future__ import annotations
import ast
import json
import sys
import time
import sympy
from sympy.core.cache import clear_cache
from .polynomial import operation_cost, parse, polynomial

VERSION = '1.14.0'
PORTFOLIO = ('identity', 'simplify', 'factor', 'cancel', 'horner')
PROFILES = {'SYMPY_SIMPLIFY': ('simplify',), 'SYMPY_FACTOR': ('factor',),
            'SYMPY_CANCEL': ('cancel',), 'SYMPY_PORTFOLIO': PORTFOLIO}


def construct(node: ast.expr):
    """Translate only an already admitted arithmetic tree, without Python eval."""
    if isinstance(node, ast.Constant) and type(node.value) is int:
        return sympy.Integer(node.value)
    if isinstance(node, ast.Name):
        return sympy.Symbol(node.id, real=True)
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
        return sympy.Mul(-1, construct(node.operand), evaluate=False)
    if isinstance(node, ast.BinOp):
        a, b = construct(node.left), construct(node.right)
        if isinstance(node.op, ast.Add):
            return sympy.Add(a, b, evaluate=False)
        if isinstance(node.op, ast.Sub):
            return sympy.Add(a, sympy.Mul(-1, b, evaluate=False), evaluate=False)
        if isinstance(node.op, ast.Mult):
            return sympy.Mul(a, b, evaluate=False)
        if isinstance(node.op, ast.Div):
            return sympy.Mul(a, sympy.Pow(b, -1, evaluate=False), evaluate=False)
        if isinstance(node.op, ast.Pow):
            return sympy.Pow(a, b, evaluate=False)
    raise ValueError('unsupported syntax')


def evaluate(profile: str, source: str) -> dict:
    if sympy.__version__ != VERSION:
        raise ValueError(f'expected SymPy {VERSION}, found {sympy.__version__}')
    if profile not in PROFILES:
        raise ValueError('unknown profile')
    start, cpu = time.perf_counter_ns(), time.process_time_ns()
    clear_cache()  # Avoid answer memoization across repeated measured requests.
    polynomial(source)
    expression = construct(parse(source))
    candidates = []
    for operation in PROFILES[profile]:
        op_start = time.perf_counter_ns()
        output = source if operation == 'identity' else str(getattr(sympy, operation)(expression))
        candidates.append({'operation': operation, 'output': output,
                           'cost': operation_cost(output), 'wallNanos': time.perf_counter_ns() - op_start})
    chosen = min(candidates, key=lambda item: (item['cost'], item['output']))
    return {'status': 'CANDIDATE', 'profile': profile, 'output': chosen['output'],
            'inputCost': operation_cost(source), 'outputCost': chosen['cost'],
            'candidates': candidates, 'nativeWallNanos': time.perf_counter_ns() - start,
            'nativeCpuNanos': time.process_time_ns() - cpu, 'trainingWork': 0}


def handle(request: dict) -> dict:
    if not isinstance(request, dict) or set(request) != {'op', 'profile', 'source'} or request['op'] != 'run':
        raise ValueError('only source/profile run requests are allowed')
    if not isinstance(request['source'], str) or not isinstance(request['profile'], str):
        raise ValueError('invalid request types')
    return evaluate(request['profile'], request['source'])


def main() -> None:
    print(json.dumps({'status': 'READY', 'sympy': sympy.__version__}), flush=True)
    for line in sys.stdin:
        try:
            response = handle(json.loads(line))
        except Exception as error:
            response = {'status': 'ERROR', 'error': f'{type(error).__name__}: {error}'}
        print(json.dumps(response, sort_keys=True, allow_nan=False), flush=True)

if __name__ == '__main__':
    main()
