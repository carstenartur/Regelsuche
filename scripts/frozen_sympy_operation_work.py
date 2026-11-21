"""Exact operation instrumentation; no claim of complete SymPy coverage by itself.

Arithmetic, accesses, comparisons and iterator attempts are separate retained
events. Merely entering a Python expression is not a mathematical work unit.
The companion pinned-source installer must establish coverage before any study
adapter can use this component.
"""

import ast
import builtins
import dis
import functools
import json
import re
import sys
from collections import Counter
import operator
import types
import math

from frozen_sympy_sequence_work import binary_sequence, compare_sequence, read_slice
from frozen_sympy_coefficient_work import OperationLimit
from frozen_sympy_native_work import native_call, is_native, qualified, NOT_HANDLED, UnmeasuredOperation


_BINARY = {
    'add': operator.add, 'sub': operator.sub, 'mul': operator.mul,
    'truediv': operator.truediv, 'floordiv': operator.floordiv,
    'mod': operator.mod, 'pow': operator.pow, 'matmul': operator.matmul,
    'lshift': operator.lshift, 'rshift': operator.rshift,
    'and': operator.and_, 'or': operator.or_, 'xor': operator.xor,
    'iadd': operator.iadd, 'isub': operator.isub, 'imul': operator.imul,
    'itruediv': operator.itruediv, 'ifloordiv': operator.ifloordiv,
    'imod': operator.imod, 'ipow': operator.ipow, 'imatmul': operator.imatmul,
    'ilshift': operator.ilshift, 'irshift': operator.irshift,
    'iand': operator.iand, 'ior': operator.ior, 'ixor': operator.ixor,
}
_UNARY = {'neg': operator.neg, 'pos': operator.pos, 'invert': operator.invert,
          'not': operator.not_}
_COMPARE = {'eq': operator.eq, 'ne': operator.ne, 'lt': operator.lt,
            'le': operator.le, 'gt': operator.gt, 'ge': operator.ge,
            'is': operator.is_, 'isnot': operator.is_not,
            'in': lambda a, b: operator.contains(b, a),
            'notin': lambda a, b: not operator.contains(b, a)}
_BINARY_AST = {
    ast.Add: 'add', ast.Sub: 'sub', ast.Mult: 'mul', ast.Div: 'truediv',
    ast.FloorDiv: 'floordiv', ast.Mod: 'mod', ast.Pow: 'pow', ast.MatMult: 'matmul',
    ast.LShift: 'lshift', ast.RShift: 'rshift', ast.BitAnd: 'and', ast.BitOr: 'or', ast.BitXor: 'xor',
}
_UNARY_AST = {ast.USub: 'neg', ast.UAdd: 'pos', ast.Invert: 'invert', ast.Not: 'not'}
_COMPARE_AST = {ast.Eq: 'eq', ast.NotEq: 'ne', ast.Lt: 'lt', ast.LtE: 'le',
                ast.Gt: 'gt', ast.GtE: 'ge', ast.Is: 'is', ast.IsNot: 'isnot',
                ast.In: 'in', ast.NotIn: 'notin'}
_ATOMIC_BUILTINS = {abs: 'abs', divmod: 'divmod', pow: 'pow', round: 'round'}
_ATOMIC_MATH = {getattr(math, name): 'math.' + name for name in
                ('gcd', 'lcm', 'isqrt', 'sqrt', 'ceil', 'floor', 'log', 'log2', 'log10', 'fabs')}


class OperationWork:
    """A single non-resettable operation ledger, with admission before execution."""
    def __init__(self, limit, *, retain_events=True, require_native_coverage=False, require_complete_work=False):
        if type(limit) is not int or limit < 0:
            raise ValueError('nonnegative integer work limit required')
        self.limit = limit
        self.used = 0
        self.stages = Counter()
        self.events = [] if retain_events else None
        self.native_calls = Counter()
        self.opaque_calls = Counter()
        if require_complete_work and not require_native_coverage:
            raise ValueError('complete work requires strict operation coverage')
        self.require_complete_work = require_complete_work
        self.boundary_gaps = Counter()
        self.require_native_coverage = require_native_coverage
        self.interrupted = None

    def halt(self, error):
        if self.interrupted is None:
            self.interrupted = error
        raise self.interrupted

    def require_uninterrupted(self):
        if self.interrupted is not None:
            raise self.interrupted

    def consume(self, stage):
        self.require_uninterrupted()
        if self.used >= self.limit:
            self.halt(OperationLimit(stage))
        self.used += 1
        self.stages[stage] += 1
        if self.events is not None:
            self.events.append(stage)

    def binary(self, stage, op, left, right):
        self.consume(stage + '.' + op)
        result = binary_sequence(self, stage, op, left, right)
        return _BINARY[op](left, right) if result is NOT_HANDLED else result

    def unary(self, stage, op, value):
        self.consume(stage + '.' + op)
        return _UNARY[op](value)

    def compare(self, stage, op, left, right):
        self.consume(stage + '.' + op)
        result = compare_sequence(self, stage, op, left, right)
        return _COMPARE[op](left, right) if result is NOT_HANDLED else result

    def compare_chain(self, stage, left, operations, operands):
        for index, (op, evaluate) in enumerate(zip(operations, operands)):
            right = evaluate()
            result = self.compare(stage, op, left, right)
            # Preserve rich-comparison return values and the final unevaluated truth value.
            if index + 1 < len(operations) and not result:
                return result
            left = right
        return result

    def getitem(self, stage, value, key):
        self.consume(stage + '.getitem')
        result = read_slice(self, stage, value, key)
        return value[key] if result is NOT_HANDLED else result

    def setitem(self, stage, replacement, value, key):
        self.consume(stage + '.setitem')
        value[key] = replacement

    def target(self, stage, value, key):
        return _ItemTarget(self, stage, value, key, self.getitem(stage, value, key))

    def attribute_target(self, stage, value, key):
        self.consume(stage + '.getattr')
        return _AttributeTarget(self, stage, value, key, getattr(value, key))

    def access(self, stage, value):
        return _Access(self, stage, value)

    def iterate(self, stage, value):
        iterator = iter(value)
        while True:
            self.consume(stage + '.iter-next')
            try:
                item = next(iterator)
            except StopIteration:
                return
            yield item

    def while_test(self, stage, value):
        self.consume(stage + '.loop-test')
        return value

    def call(self, stage, function, /, *args, **kwargs):
        if function is json.loads or function is json.dumps:
            gap = 'transport.json.' + function.__name__
            self.boundary_gaps[gap] += 1
            if self.require_complete_work:
                self.halt(UnmeasuredOperation(gap))
        native = is_native(function)
        if native:
            self.native_calls[qualified(function)] += 1
            result = native_call(self, stage, function, args, kwargs)
            if result is not NOT_HANDLED:
                return result
        operation = _ATOMIC_BUILTINS.get(function) if type(function) is type(abs) else None
        if operation is None and type(function) is type(abs):
            operation = _ATOMIC_MATH.get(function)
        if operation is not None:
            self.consume(stage + '.' + operation)
        if function is builtins.sum:
            return self.sum_values(stage, *args, **kwargs)
        if function is builtins.min or function is builtins.max:
            return self.extremum(stage, function, args, kwargs)
        if native and operation is None:
            name = qualified(function)
            self.opaque_calls[(stage, name)] += 1
            if self.require_native_coverage:
                self.halt(UnmeasuredOperation(stage + ': ' + name))
        return function(*args, **kwargs)

    def sum_values(self, stage, iterable, /, start=0):
        total = start
        for value in self.iterate(stage + '.sum', iterable):
            # CPython 3.12 has distinct compensated float summation semantics.
            # Do not replace that algorithm with a purported exact coefficient sum.
            if type(total) in (float, complex) or type(value) in (float, complex):
                raise ValueError('floating-point native sum is outside exact coefficient instrumentation')
            total = self.binary(stage + '.sum', 'add', total, value)
        return total

    def extremum(self, stage, function, arguments, keywords):
        if not arguments:
            return function(*arguments, **keywords)
        if any(key not in ('key', 'default') for key in keywords) or len(arguments) > 1 and 'default' in keywords:
            return function(*arguments, **keywords)
        name = '.min' if function is builtins.min else '.max'
        comparator = 'lt' if function is builtins.min else 'gt'
        iterator = self.iterate(stage + name, arguments[0] if len(arguments) == 1 else arguments)
        try:
            best = next(iterator)
        except StopIteration:
            if 'default' in keywords:
                return keywords['default']
            raise ValueError(function.__name__ + '() arg is an empty sequence')
        key = keywords.get('key')
        best_key = best if key is None else key(best)
        for value in iterator:
            value_key = value if key is None else key(value)
            if self.compare(stage + name, comparator, value_key, best_key):
                best, best_key = value, value_key
        return best


class _ItemTarget:
    def __init__(self, work, stage, value, key, original):
        self.work, self.stage = work, stage
        self.value, self.key, self.original = value, key, original

    def apply(self, operation, right):
        result = self.work.binary(self.stage, operation, self.original, right)
        self.work.setitem(self.stage, result, self.value, self.key)


class _AttributeTarget(_ItemTarget):
    def apply(self, operation, right):
        result = self.work.binary(self.stage, operation, self.original, right)
        self.work.consume(self.stage + '.setattr')
        setattr(self.value, self.key, result)


class _Access:
    def __init__(self, work, stage, value):
        self.work, self.stage, self.value = work, stage, value

    def __setitem__(self, key, value):
        self.work.setitem(self.stage, value, self.value, key)

    def __delitem__(self, key):
        self.work.consume(self.stage + '.delitem')
        del self.value[key]


def _literal_tree(node):
    if isinstance(node, ast.Constant):
        return True
    if isinstance(node, ast.BinOp):
        return _literal_tree(node.left) and _literal_tree(node.right)
    if isinstance(node, ast.UnaryOp):
        return _literal_tree(node.operand)
    if isinstance(node, ast.Tuple):
        return all(_literal_tree(item) for item in node.elts)
    return False


@functools.lru_cache(maxsize=2048)
def _compiled_literal(expression):
    # Ask the active interpreter; evaluating or predicting result values would
    # confuse retained arithmetic with literal loads, for example 1*1.
    code = compile(expression, '<operation-fold-control>', 'eval', dont_inherit=True)
    if sys.implementation.name == 'graalpy':
        import __graalpython__
        instructions = re.findall(r'^\s*\d+ \[[a-f0-9]+\] [a-f0-9]+ (\S+)',
                                  __graalpython__.dis(code), re.MULTILINE)
        if not instructions:
            raise ValueError('unrecognized historical GraalPy disassembly')
        return instructions == ['c.EnterCalleeContext', 'load.constant', 'c.EpilogForReturn', 'return']
    return all(step.opname in ('RESUME', 'LOAD_CONST', 'RETURN_CONST', 'RETURN_VALUE')
               for step in dis.get_instructions(code))


class OperationTransformer(ast.NodeTransformer):
    def __init__(self, module):
        self.module = module
        self.scopes = []

    def stage(self):
        return self.module + '.' + '.'.join(self.scopes)

    def invoke(self, name, values, original):
        call = ast.Call(ast.Attribute(ast.Name('_regelsuche_operation_work', ast.Load()), name, ast.Load()),
                        [ast.Constant(self.stage()), *values], [])
        return ast.copy_location(call, original)

    def visit_FunctionDef(self, node):
        self.scopes.append(node.name)
        node.body = [part for item in node.body for part in self._statements(self.visit(item))]
        self.scopes.pop()
        return node

    visit_AsyncFunctionDef = visit_FunctionDef

    def visit_ClassDef(self, node):
        self.scopes.append(node.name)
        node.body = [part for item in node.body for part in self._statements(self.visit(item))]
        self.scopes.pop()
        return node

    @staticmethod
    def _statements(value):
        return value if isinstance(value, list) else [value]

    def visit_BinOp(self, node):
        if _literal_tree(node) and _compiled_literal(ast.unparse(node)):
            return node
        node = self.generic_visit(node)
        return self.invoke('binary', [ast.Constant(_BINARY_AST[type(node.op)]), node.left, node.right], node)

    def visit_UnaryOp(self, node):
        if _literal_tree(node) and _compiled_literal(ast.unparse(node)):
            return node
        node = self.generic_visit(node)
        return self.invoke('unary', [ast.Constant(_UNARY_AST[type(node.op)]), node.operand], node)

    def visit_Compare(self, node):
        node = self.generic_visit(node)
        operations = [_COMPARE_AST[type(op)] for op in node.ops]
        if len(operations) == 1:
            return self.invoke('compare', [ast.Constant(operations[0]), node.left, node.comparators[0]], node)
        if any(isinstance(child, (ast.NamedExpr, ast.Yield, ast.YieldFrom, ast.Await))
               for operand in node.comparators for child in ast.walk(operand)):
            raise ValueError('effectful binding in chained comparison requires explicit instrumentation')
        args = ast.arguments(posonlyargs=[], args=[], kwonlyargs=[], kw_defaults=[], defaults=[])
        thunks = [ast.Lambda(args, operand) for operand in node.comparators]
        return self.invoke('compare_chain', [node.left, ast.Tuple([ast.Constant(op) for op in operations], ast.Load()),
                                            ast.Tuple(thunks, ast.Load())], node)

    def slice_value(self, node):
        if isinstance(node, ast.Slice):
            return ast.Call(ast.Name('slice', ast.Load()),
                            [self.visit(value) if value is not None else ast.Constant(None)
                             for value in (node.lower, node.upper, node.step)], [])
        return self.visit(node)

    def visit_Subscript(self, node):
        if not isinstance(node.ctx, ast.Load):
            node = self.generic_visit(node)
            node.value = self.invoke('access', [node.value], node.value)
            return node
        return self.invoke('getitem', [self.visit(node.value), self.slice_value(node.slice)], node)

    def visit_Assign(self, node):
        if len(node.targets) == 1 and isinstance(node.targets[0], ast.Subscript):
            target = node.targets[0]
            # Python evaluates the RHS first, then the container and index.
            call = self.invoke('setitem', [self.visit(node.value), self.visit(target.value),
                                           self.slice_value(target.slice)], node)
            return ast.copy_location(ast.Expr(call), node)
        return self.generic_visit(node)

    def visit_AugAssign(self, node):
        operation = 'i' + _BINARY_AST[type(node.op)]
        if isinstance(node.target, ast.Name):
            result = self.invoke('binary', [ast.Constant(operation),
                ast.copy_location(ast.Name(node.target.id, ast.Load()), node.target), self.visit(node.value)], node)
            return ast.copy_location(ast.Assign([node.target], result), node)
        if isinstance(node.target, ast.Subscript):
            target = self.invoke('target', [self.visit(node.target.value), self.slice_value(node.target.slice)], node)
            call = ast.Call(ast.Attribute(target, 'apply', ast.Load()), [ast.Constant(operation), self.visit(node.value)], [])
            return ast.copy_location(ast.Expr(call), node)
        if isinstance(node.target, ast.Attribute):
            target = self.invoke('attribute_target', [self.visit(node.target.value), ast.Constant(node.target.attr)], node)
            call = ast.Call(ast.Attribute(target, 'apply', ast.Load()), [ast.Constant(operation), self.visit(node.value)], [])
            return ast.copy_location(ast.Expr(call), node)
        raise ValueError('unsupported augmented assignment target')

    def visit_For(self, node):
        node = self.generic_visit(node)
        node.iter = self.invoke('iterate', [node.iter], node.iter)
        return node

    def visit_comprehension(self, node):
        node = self.generic_visit(node)
        node.iter = self.invoke('iterate', [node.iter], node.iter)
        return node

    def visit_While(self, node):
        node = self.generic_visit(node)
        node.test = self.invoke('while_test', [node.test], node.test)
        return node

    def visit_Call(self, node):
        node = self.generic_visit(node)
        # These builtins observe their immediate Python frame.
        if isinstance(node.func, ast.Name) and node.func.id in ('super', 'locals', 'globals', 'vars', 'dir', 'eval', 'exec'):
            return node
        call = self.invoke('call', [node.func, *node.args], node)
        call.keywords = node.keywords
        return call


def instrument_source(source, module):
    return ast.fix_missing_locations(OperationTransformer(module).visit(ast.parse(source)))
