"""Source-pinned installation of operation counters into existing functions.

Code objects are replaced on the original function objects, so imported aliases
remain measured. This installer does not assert that its module inventory is a
complete factorization work boundary; the call-coverage guard is mandatory for
any caller making that separate claim.
"""

from contextlib import contextmanager, ExitStack
import hashlib
import importlib
import functools
from pathlib import Path
import types
import sys

from frozen_sympy_operation_work import instrument_source
from frozen_sympy_native_work import UnmeasuredOperation


def _codes(code):
    result = {}
    pending = [code]
    while pending:
        current = pending.pop()
        key = (current.co_qualname, current.co_firstlineno)
        result.setdefault(key, []).append(current)
        pending.extend(value for value in current.co_consts if isinstance(value, types.CodeType))
    return result


def _functions(modules):
    pending = [value for module in modules for value in vars(module).values()]
    visited = set()
    while pending:
        value = pending.pop()
        if id(value) in visited:
            continue
        visited.add(id(value))
        if isinstance(value, types.FunctionType):
            yield value
            wrapped = getattr(value, '__wrapped__', None)
            if wrapped is not None:
                pending.append(wrapped)
            for cell in value.__closure__ or ():
                try:
                    member = cell.cell_contents
                except ValueError:
                    continue
                if isinstance(member, types.FunctionType):
                    pending.append(member)
        elif isinstance(value, type) and value.__module__.split('.')[0] in ('sympy','mpmath','random'):
            pending.extend(vars(value).values())
        elif isinstance(value, (staticmethod, classmethod)):
            pending.append(value.__func__)
        elif isinstance(value, property):
            pending.extend(member for member in (value.fget, value.fset, value.fdel) if member is not None)
        elif isinstance(value, functools._lru_cache_wrapper):
            pending.append(value.__wrapped__)


@contextmanager
def patch_sources(work, pins):
    """Pins map importable module names to hashes of their exact .py bytes."""
    modules, replacements = [], {}
    for name, expected in sorted(pins.items()):
        module = importlib.import_module(name)
        path = Path(module.__file__)
        source = path.read_bytes()
        if hashlib.sha256(source).hexdigest() != expected:
            raise ValueError('source pin differs for ' + name)
        tree = instrument_source(source.decode('utf-8'), name)
        replacements[str(path)] = _codes(compile(tree, str(path), 'exec', dont_inherit=True))
        modules.append(module)
    functions = list(_functions(modules))
    with ExitStack() as restore:
        for module in modules:
            if '_regelsuche_operation_work' in vars(module):
                raise RuntimeError('operation meter already installed')
            vars(module)['_regelsuche_operation_work'] = work
            restore.callback(vars(module).pop, '_regelsuche_operation_work')
        installed = {}
        for function in functions:
            original = function.__code__
            table = replacements.get(original.co_filename)
            if table is None:
                continue
            choices = table.get((original.co_qualname, original.co_firstlineno), [])
            if len(choices) != 1:
                raise ValueError('ambiguous original function: ' + original.co_qualname)
            replacement = choices[0]
            if original.co_freevars != replacement.co_freevars:
                raise ValueError('changed lexical closure: ' + original.co_qualname)
            restore.callback(setattr, function, '__code__', original)
            function.__code__ = replacement
            for codes in _codes(replacement).values():
                installed.update((id(code), code) for code in codes)
        yield tuple(installed.values())


def _administrative_codes():
    """Exact code identities for host metering, transport serialization and reflection."""
    import abc
    import collections
    import inspect
    import json
    import json.encoder
    import json.decoder
    import json.scanner
    from importlib import _bootstrap

    allowed = []
    for name in ('frozen_sympy_operation_work', 'frozen_sympy_native_work', 'frozen_sympy_sequence_work',
                 'frozen_graalpy_random_work'):
        module = sys.modules.get(name)
        if module is None:
            continue
        for member in vars(module).values():
            if getattr(member, '__module__', None) != name:
                continue
            if isinstance(member, type):
                allowed.extend(vars(member).values())
            else:
                allowed.append(member)
    # GraalPy implements operator.add/etc. as thin Python wrappers around
    # the already-admitted scalar operation; CPython uses builtins here.
    operators = sys.modules['frozen_sympy_operation_work']
    allowed.extend((*operators._BINARY.values(), *operators._UNARY.values(), *operators._COMPARE.values()))
    import operator
    allowed.append(operator.contains)
    allowed.extend((collections.Counter.__missing__, inspect.getmro, _bootstrap._handle_fromlist,
                    abc.ABCMeta.__instancecheck__, abc.ABCMeta.__subclasscheck__,
                    json.dumps, json.loads, json.encoder.JSONEncoder.__init__, json.encoder.JSONEncoder.encode,
                    json.encoder.JSONEncoder.iterencode, json.encoder._make_iterencode,
                    json.decoder.JSONDecoder.__init__, json.decoder.JSONDecoder.decode,
                    json.decoder.JSONDecoder.raw_decode, json.scanner.py_make_scanner))
    codes = {}
    for member in allowed:
        if isinstance(member, types.FunctionType):
            for group in _codes(member.__code__).values():
                codes.update((id(code), code) for code in group)
    return codes


class MathCallCoverage:
    """One isolated invocation; indexed by object identity, including nested comprehensions."""
    def __init__(self, installed, packages, strict_foreign):
        self.codes = {id(code): code for code in installed}
        self.packages = frozenset(packages)
        self.strict_foreign = strict_foreign
        self.administrative = _administrative_codes()
        self.foreign_calls = {}
        self.visits = 0
        self.failure = None

    def profile(self, frame, event, arg):
        if event != 'call':
            return
        code = frame.f_code
        module = frame.f_globals.get('__name__', '')
        owner = module.partition('.')[0]
        if owner not in self.packages:
            if self.administrative.get(id(code)) is code:
                return
            parent = frame.f_back
            while parent is not None:
                if self.codes.get(id(parent.f_code)) is parent.f_code:
                    name = f'{module}.{code.co_qualname}:{code.co_firstlineno}'
                    self.foreign_calls[name] = self.foreign_calls.get(name, 0) + 1
                    if self.strict_foreign:
                        self.failure = name
                        raise UnmeasuredOperation(name)
                    break
                parent = parent.f_back
            return
        if self.codes.get(id(code)) is not code:
            self.failure = f'{module}.{code.co_qualname}:{code.co_firstlineno}'
            raise UnmeasuredOperation(self.failure)
        self.visits += 1


@contextmanager
def guard_math_calls(installed, packages=('sympy', 'mpmath'), *, strict_foreign=False):
    """Reject unpatched package code at entry, and refuse a suppressed failure at exit.

    This guard attests calls in the stated Python packages. Collection intrinsics,
    foreign package callbacks and the original bridge require separate evidence.
    """
    if sys.getprofile() is not None:
        raise RuntimeError('operation coverage requires an unoccupied per-thread profiler')
    guard = MathCallCoverage(installed, packages, strict_foreign)
    hook = guard.profile
    sys.setprofile(hook)
    try:
        yield guard
    finally:
        active = sys.getprofile()
        sys.setprofile(None)
        if guard.failure is not None:
            raise UnmeasuredOperation(guard.failure)
        if active is not hook:
            raise UnmeasuredOperation('operation profiler removed during measured execution')
