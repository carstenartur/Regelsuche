"""Individually metered native collection semantics used by the frozen Python code.

A charged read or write denotes one logical container entry. Private allocator
capacity and memory copies are not inferred from size, duration or source nodes.
"""

import _random
import builtins
import sys
import operator
import time
import types


class UnmeasuredOperation(BaseException):
    """An executable path lacks an authorized operation counter."""


NOT_HANDLED = object()


def is_native(function):
    # Every callable whose body is not a Python function or bound Python
    # method needs a named intrinsic, including partials and cache wrappers.
    return not isinstance(function, (types.FunctionType, types.MethodType))


def qualified(function):
    return str(getattr(function, '__module__', None)) + ':' + getattr(
        function, '__qualname__', type(function).__qualname__)


def _items(work, stage, iterable):
    for value in work.iterate(stage, iterable):
        work.consume(stage + '.write')
        yield value


def _predicate(work, stage, function, iterable):
    stop = function is any
    for value in work.iterate(stage, iterable):
        work.consume(stage + '.truth')
        if bool(value) == stop:
            return stop
    return not stop


def _mapping(work, stage, function, args, keywords):
    receiver = getattr(function, '__self__', None)
    if not isinstance(receiver, dict) or keywords:
        return NOT_HANDLED
    name = function.__name__
    if getattr(type(receiver), name, None) is not getattr(dict, name, None):
        return NOT_HANDLED
    # Each invocation accesses a single logical mapping entry. In particular
    # setdefault performs one conditional update, without a second key lookup.
    if name in ('get', 'setdefault', 'pop') and 1 <= len(args) <= 2:
        work.consume(stage + '.mapping.' + name)
        return function(*args)
    if name in ('items', 'keys', 'values') and not args:
        return function()
    return NOT_HANDLED


def _lazy_input(work, stage, function, args, keywords):
    if function is zip and not keywords.keys() - {'strict'}:
        inputs = (work.iterate(stage + '.zip.input', iter(value)) for value in args)
        return zip(*inputs, **keywords)
    if function is map and len(args) >= 2 and not keywords:
        callback, *iterables = args
        inputs = (work.iterate(stage + '.map.input', iter(value)) for value in iterables)
        return map(lambda *row: work.call(stage + '.map.apply', callback, *row), *inputs)
    if function is enumerate and 1 <= len(args) <= 2 and not keywords:
        return enumerate(work.iterate(stage + '.enumerate.input', iter(args[0])), *args[1:])
    return NOT_HANDLED


def _text(work, stage, function, args, keywords):
    source = getattr(function, '__self__', None)
    if type(source) is not str or keywords:
        return NOT_HANDLED
    name = function.__name__
    if name == 'endswith' and len(args) == 1 and type(args[0]) is str:
        suffix = args[0]
        if len(source) < len(suffix):
            return False
        start = len(source) - len(suffix)
        for index in work.iterate(stage + '.suffix', range(len(suffix))):
            work.consume(stage + '.suffix.read')
            value = source[start + index]
            work.consume(stage + '.suffix.read')
            expected = suffix[index]
            work.consume(stage + '.suffix.compare')
            if value != expected:
                return False
        return True
    if name == 'strip' and not args:
        start, end = 0, len(source)
        while start < end:
            work.consume(stage + '.strip.read')
            if not source[start].isspace():
                break
            start += 1
        while end > start:
            work.consume(stage + '.strip.read')
            if not source[end - 1].isspace():
                break
            end -= 1
        if start == 0 and end == len(source):
            return source
        out = []
        for index in work.iterate(stage + '.strip.copy', range(start, end)):
            work.consume(stage + '.strip.copy.read')
            value = source[index]
            work.consume(stage + '.strip.copy.write')
            out.append(value)
        return ''.join(out)
    return NOT_HANDLED


def native_call(work, stage, function, args, keywords):
    receiver = getattr(function, '__self__', None)
    if (sys.implementation.name == 'graalpy' and isinstance(receiver, _random.Random)
            and getattr(function, '__name__', '') == 'random'
            and getattr(type(receiver), 'random') is _random.Random.random and not args and not keywords):
        from frozen_graalpy_random_work import checked_random
        return checked_random(work, receiver)
    if function is time.perf_counter_ns and not args and not keywords:
        # Timing metadata is excluded from the mathematical ledger.
        return function()
    if function is type and len(args) == 1 and not keywords:
        return type(args[0])
    if function is range and 1 <= len(args) <= 3 and not keywords and all(type(x) is int for x in args):
        return range(*args)
    if function is iter and len(args) == 1 and not keywords:
        return iter(args[0])
    if any(function is constructor for constructor in (int, bool, float, str)) and len(args) == 1 and not keywords:
        source = args[0]
        simple = type(source) in (int, str, float, bool)
        if simple:
            work.consume(stage + '.convert.' + function.__name__)
            return function(source)
    text = _text(work, stage, function, args, keywords)
    if text is not NOT_HANDLED:
        return text
    mapping = _mapping(work, stage, function, args, keywords)
    if mapping is not NOT_HANDLED:
        return mapping
    lazy = _lazy_input(work, stage, function, args, keywords)
    if lazy is not NOT_HANDLED:
        return lazy
    if function is any or function is all:
        if len(args) == 1 and not keywords:
            return _predicate(work, stage + '.' + function.__name__, function, args[0])
    if function is tuple or function is list:
        if not keywords and len(args) <= 1:
            if not args:
                return function()
            source = args[0]
            if function is tuple and type(source) is tuple:
                return source
            return function(_items(work, stage + '.' + function.__name__, source))
    receiver = getattr(function, '__self__', None)
    name = getattr(function, '__name__', '')
    if type(receiver) is list and not keywords:
        if name == 'append' and len(args) == 1:
            work.consume(stage + '.append.write')
            return function(*args)
        if name == 'extend' and len(args) == 1:
            source = args[0]
            if source is receiver:
                # Visit the original prefix, without revisiting entries appended during self-extension.
                size = len(source)
                source = (work.getitem(stage + '.self-extend', receiver, i) for i in range(size))
            for value in _items(work, stage + '.extend', source):
                receiver.append(value)
            return None
    if function is len and len(args) == 1 and not keywords:
        work.consume(stage + '.length')
        return len(args[0])
    if any(function is f for f in (builtins.isinstance, builtins.issubclass, builtins.getattr, builtins.hasattr)) and not keywords:
        return function(*args)
    if function is operator.index and len(args) == 1 and not keywords:
        work.consume(stage + '.integer-index')
        return function(*args)
    return NOT_HANDLED
