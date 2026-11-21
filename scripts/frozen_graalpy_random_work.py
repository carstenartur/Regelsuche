"""Meter the typed operations of the historical PRandom.nextDouble.

Source: oracle/graalpython at a88321e8b4440a08abaa7b163a85e038a39ec9a0,
PRandom.java blob 81be875bb7909a389942a10691d21b56f23a76d6 and RandomBuiltins.java
blob 33b12a60408594b37f5c19cdbf851283a43831d5. See the source and license
provenance in docs/research/frozen-polynomial-work-2026-11-20.md.
"""

import _random
import operator

from frozen_sympy_native_work import UnmeasuredOperation

STATE_SIZE = 624
LAG = 397
MAG = (0, 0x9908b0df)
WORD = 'graalpy.PRandom.nextInt.'
TWIST = WORD + 'twist.'
DOUBLE = 'graalpy.PRandom.nextDouble.'


class SourceWord:
    """Source-level 32-bit operations; emulating word width is not an extra Java instruction."""

    def __init__(self, work, state, index):
        self.work = work
        self.state = state
        self.index = index

    def binary(self, stage, operation, left, right):
        self.work.consume(stage)
        return operation(left, right)

    def read(self, stage, words, index):
        self.work.consume(stage)
        return words[index]

    def twist_word(self, index, offset=None):
        op = self.binary
        upper = op(TWIST + 'and', operator.and_, self.read(TWIST + 'state-read', self.state, index), 0x80000000)
        next_index = 0 if offset is None else op(TWIST + 'add', operator.add, index, 1)
        lower = op(TWIST + 'and', operator.and_, self.read(TWIST + 'state-read', self.state, next_index), 0x7fffffff)
        mixed = op(TWIST + 'or', operator.or_, upper, lower)
        far_index = LAG - 1 if offset is None else op(TWIST + 'add', operator.add, index, offset)
        far = self.read(TWIST + 'state-read', self.state, far_index)
        shifted = op(TWIST + 'ushr', operator.rshift, mixed, 1)
        combined = op(TWIST + 'xor', operator.xor, far, shifted)
        self.work.consume(TWIST + 'mag01-field-read')
        parity = op(TWIST + 'and', operator.and_, mixed, 1)
        value = op(TWIST + 'xor', operator.xor, combined, self.read(TWIST + 'mag01-read', MAG, parity))
        self.work.consume(TWIST + 'state-write')
        self.state[index] = value

    def twist_loop(self, index, stop, offset):
        while self.binary(TWIST + 'loop-lt', operator.lt, index, stop):
            self.work.consume(TWIST + 'loop-visit')
            self.twist_word(index, offset)
            index = self.binary(TWIST + 'increment', operator.add, index, 1)
        return index

    def next_word(self):
        op = self.binary
        self.work.consume(WORD + 'state-field-read')
        self.work.consume(WORD + 'index-read')
        if op(WORD + 'ge', operator.ge, self.index, STATE_SIZE):
            index = self.twist_loop(0, STATE_SIZE - LAG, LAG)
            self.twist_loop(index, STATE_SIZE - 1, LAG - STATE_SIZE)
            self.twist_word(STATE_SIZE - 1)
            self.work.consume(WORD + 'index-write')
            self.index = 0
        self.work.consume(WORD + 'index-read')
        current = self.index
        advanced = op(WORD + 'increment', operator.add, current, 1)
        self.work.consume(WORD + 'index-write')
        self.index = advanced
        value = self.read(WORD + 'state-read', self.state, current)
        value = op(WORD + 'xor', operator.xor, value, op(WORD + 'ushr', operator.rshift, value, 11))
        for shift, mask in ((7, 0x9d2c5680), (15, 0xefc60000)):
            shifted = op(WORD + 'shl', operator.lshift, value, shift)
            value = op(WORD + 'xor', operator.xor, value, op(WORD + 'and', operator.and_, shifted, mask))
        return op(WORD + 'xor', operator.xor, value, op(WORD + 'ushr', operator.rshift, value, 18))

    def next_double(self):
        op = self.binary
        upper = op(DOUBLE + 'ushr', operator.rshift, self.next_word(), 5)
        lower = op(DOUBLE + 'ushr', operator.rshift, self.next_word(), 6)
        self.work.consume(DOUBLE + 'int-to-double')
        high = op(DOUBLE + 'mul', operator.mul, float(upper), 67108864.0)
        self.work.consume(DOUBLE + 'int-to-double')
        both = op(DOUBLE + 'add', operator.add, high, float(lower))
        # javac folds the reciprocal of the constant denominator.
        return op(DOUBLE + 'mul', operator.mul, both, 2.0 ** -53)


def checked_random(work, receiver):
    """Check the exact native result and all state entries for each admitted draw."""
    before = _random.Random.getstate(receiver)
    measured = SourceWord(work, list(before[:-1]), before[-1])
    expected = measured.next_double()
    actual = _random.Random.random(receiver)
    after = _random.Random.getstate(receiver)
    if actual != expected or after != (*measured.state, measured.index):
        work.halt(UnmeasuredOperation('historical PRandom state or emitted Float64 differs'))
    return actual
