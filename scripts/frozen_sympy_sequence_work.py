"""Logical item operations for ordinary Python list and tuple values."""

import operator
from frozen_sympy_native_work import NOT_HANDLED


def copy_into(work, stage, source, indices, target):
    for index in work.iterate(stage, indices):
        work.consume(stage + '.read')
        item = source[index]
        work.consume(stage + '.write')
        target.append(item)


def read_slice(work, stage, source, key):
    if type(source) not in (list, tuple) or type(key) is not slice:
        return NOT_HANDLED
    indices = range(*key.indices(len(source)))
    if type(source) is tuple and indices == range(len(source)):
        return source
    result = []
    copy_into(work, stage + '.slice', source, indices, result)
    return tuple(result) if type(source) is tuple else result


def repeat(work, stage, operation, source, count):
    if type(source) not in (tuple, list) or type(count) not in (int, bool):
        return NOT_HANDLED
    if type(source) is tuple and count == 1:
        return source
    if type(source) is list and operation == 'imul':
        if count <= 0:
            while source:
                work.consume(stage + '.repeat.delete')
                source.pop()
            return source
        output, copies = source, count - 1
    else:
        output, copies = [], count
    original_indices = range(len(source))
    if original_indices:
        for _ in work.iterate(stage + '.repeat-block', range(copies)):
            copy_into(work, stage + '.repeat', source, original_indices, output)
    return tuple(output) if type(source) is tuple else output


def binary_sequence(work, stage, operation, left, right):
    if operation in ('mul', 'imul'):
        if type(left) in (list, tuple):
            return repeat(work, stage, operation, left, right)
        if type(right) in (list, tuple):
            return repeat(work, stage, 'mul', right, left)
    if operation in ('add', 'iadd') and type(left) in (list, tuple) and type(right) is type(left):
        if type(left) is tuple and (not left or not right):
            return right if not left else left
        if operation == 'iadd' and type(left) is list:
            output = left
        else:
            output = []
            copy_into(work, stage + '.concat', left, range(len(left)), output)
        copy_into(work, stage + '.concat', right, range(len(right)), output)
        return tuple(output) if type(left) is tuple else output
    return NOT_HANDLED


def compare_sequence(work, stage, operation, left, right):
    if type(right) not in (list, tuple):
        return NOT_HANDLED
    if operation in ('in','notin'):
        for index in work.iterate(stage + '.contains', range(len(right))):
            work.consume(stage + '.contains.read')
            item = right[index]
            if item is left or work.compare(stage + '.element', 'eq', item, left):
                return operation == 'in'
        return operation == 'notin'
    if type(left) is not type(right) or operation in ('is','isnot'):
        return NOT_HANDLED
    if type(left) is list and operation in ('eq','ne') and len(left) != len(right):
        return operation == 'ne'
    for index in work.iterate(stage + '.sequence', range(min(len(left), len(right)))):
        work.consume(stage + '.sequence.read')
        first = left[index]
        work.consume(stage + '.sequence.read')
        second = right[index]
        if first is second or work.compare(stage + '.element', 'eq', first, second):
            continue
        if operation in ('eq','ne'):
            return operation == 'ne'
        return work.compare(stage + '.element', operation, first, second)
    return work.compare(stage + '.length', operation, len(left), len(right))
