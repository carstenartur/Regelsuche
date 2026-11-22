"""Independent order and budget controls for source operation instrumentation."""

import ast
from collections import Counter
import unittest

from frozen_sympy_operation_work import OperationWork, OperationLimit, instrument_source


class OperationInstrumentationTest(unittest.TestCase):
    def execute(self, source, work, bindings=None):
        scope = dict(bindings or {})
        scope['_regelsuche_operation_work'] = work
        exec(compile(instrument_source(source, 'control'), '<control>', 'exec'), scope)
        return scope

    def test_arithmetic_and_every_budget_prefix(self):
        source = 'def compute(a, b):\n    return (a + b) * (a - b)\n'
        complete = OperationWork(3)
        fn = self.execute(source, complete)['compute']
        self.assertEqual(21, fn(5, 2))
        expected = ['control.compute.add', 'control.compute.sub', 'control.compute.mul']
        self.assertEqual(expected, complete.events)
        for cap in range(3):
            work = OperationWork(cap)
            fn = self.execute(source, work)['compute']
            with self.assertRaises(OperationLimit) as failure:
                fn(5, 2)
            self.assertEqual(expected[cap], str(failure.exception))
            self.assertEqual(expected[:cap], work.events)
            self.assertEqual(cap, work.used)

    def test_chain_compares_lazily_and_once(self):
        seen = []
        def value(n):
            seen.append(n)
            return n
        work = OperationWork(20)
        fn = self.execute('def compute():\n    return value(3) < value(2) < value(1)\n',
                          work, {'value': value})['compute']
        self.assertFalse(fn())
        self.assertEqual([3, 2], seen)
        self.assertEqual(['control.compute.lt'], work.events)

    def test_chain_returns_last_rich_result_without_truth_conversion(self):
        class Last:
            def __bool__(self):
                raise AssertionError('final rich comparison was truth-tested')
        final = Last()
        class Compared:
            def __lt__(self, other):
                return final
        fn = self.execute('def compute(a):\n    return 0 < 1 < a\n', OperationWork(20))['compute']
        class Right:
            def __gt__(self, other):
                return final
        self.assertIs(final, fn(Right()))

    def test_subscript_update_preserves_operand_evaluation_order(self):
        seen = []
        data = [3]
        def target():
            seen.append('target')
            return data
        def index():
            seen.append('index')
            return 0
        def value():
            seen.append('value')
            return 4
        work = OperationWork(20)
        fn = self.execute('def compute():\n    target()[index()] += value()\n', work,
                          {'target': target, 'index': index, 'value': value})['compute']
        fn()
        self.assertEqual([7], data)
        self.assertEqual(['target', 'index', 'value'], seen)
        self.assertEqual(['control.compute.getitem', 'control.compute.iadd', 'control.compute.setitem'], work.events)

    def test_refused_access_does_not_read_user_object(self):
        class Forbidden:
            def __getitem__(self, key):
                self.fail = True
                raise AssertionError('refused read executed')
        fn = self.execute('def compute(a):\n    return a[0]\n', OperationWork(0))['compute']
        with self.assertRaisesRegex(OperationLimit, 'getitem'):
            fn(Forbidden())

    def test_for_loop_retains_real_next_attempts(self):
        work = OperationWork(50)
        fn = self.execute('def compute(xs):\n    total = 0\n    for x in xs:\n        total += x\n    return total\n', work)['compute']
        self.assertEqual(6, fn([1, 2, 3]))
        self.assertEqual(Counter({'control.compute.iter-next': 4, 'control.compute.iadd': 3}), work.stages)

    def test_short_circuit_and_local_frame_builtins(self):
        work = OperationWork(20)
        fn = self.execute('def compute(x):\n    return (x and 5 / x), locals()["x"]\n', work)['compute']
        self.assertEqual((0, 0), fn(0))
        self.assertNotIn('control.compute.truediv', work.stages)

    def test_native_sum_and_extrema_count_the_actual_coefficient_operations(self):
        work = OperationWork(100)
        fn = self.execute('def compute(xs):\n    return sum(xs), min(xs), max(xs)\n', work)['compute']
        self.assertEqual((7, 1, 4), fn([2, 1, 4]))
        self.assertEqual(3, work.stages['control.compute.sum.add'])
        self.assertEqual(2, work.stages['control.compute.min.lt'])
        self.assertEqual(2, work.stages['control.compute.max.gt'])

    def test_compiler_folded_constants_emit_no_invented_runtime_work(self):
        import sys
        work = OperationWork(8)
        fn = self.execute('def compute():\n    return -5, 2*3\n', work)['compute']
        self.assertEqual((-5, 6), fn())
        self.assertNotIn('control.compute.neg', work.stages)
        # The pinned GraalPy emits PyNumberMultiply. CPython 3.12 returns 6
        # directly. Both compile -5 to a literal.
        self.assertEqual(int(sys.implementation.name == 'graalpy'), work.stages['control.compute.mul'])

    def test_native_scalar_gcd_admission_precedes_the_operation(self):
        import math
        work = OperationWork(0)
        fn = self.execute('def compute():\n    return gcd(15, 10)\n', work, {'gcd': math.gcd})['compute']
        with self.assertRaisesRegex(OperationLimit, 'math.gcd'):
            fn()


class NativeCollectionWorkTest(unittest.TestCase):
    def test_short_circuit_native_predicates_skip_the_unvisited_suffix(self):
        work=OperationWork(64)
        def values():
            yield 0
            yield 2
            raise AssertionError('unvisited suffix was evaluated')
        self.assertTrue(work.call('probe',any,values()))
        self.assertEqual(2,work.stages['probe.any.truth'])
        self.assertEqual(2,work.stages['probe.any.iter-next'])

    def test_bulk_extend_preserves_partial_mutation_on_budget_stop(self):
        out=[1]
        work=OperationWork(3)
        with self.assertRaises(OperationLimit):
            work.call('probe',out.extend,[2,3])
        self.assertEqual([1,2],out)
        self.assertEqual(1,work.stages['probe.extend.write'])

    def test_copy_counts_individual_entries_and_tuple_identity(self):
        original=(1,2,3)
        work=OperationWork(10)
        self.assertIs(original,work.call('probe',tuple,original))
        self.assertEqual([1,2,3],work.call('probe',list,original))
        self.assertEqual(3,work.stages['probe.list.write'])

    def test_unknown_native_bulk_operation_fails_before_executing_in_strict_mode(self):
        from frozen_sympy_source_patch import UnmeasuredOperation
        from collections import deque
        out=deque([1,2,3]); work=OperationWork(100,require_native_coverage=True)
        with self.assertRaisesRegex(UnmeasuredOperation,'rotate'):
            work.call('probe',out.rotate,1)
        self.assertEqual([1,2,3],list(out))

class BulkSequenceTest(unittest.TestCase):
    def test_repeat_reads_and_writes_each_entry(self):
        work = OperationWork(64)
        value = object()
        result = work.binary('probe', 'mul', [value, 7], 2)
        self.assertEqual([value, 7, value, 7], result)
        self.assertIs(value, result[2])
        self.assertEqual(4, work.stages['probe.repeat.read'])
        self.assertEqual(4, work.stages['probe.repeat.write'])

    def test_slice_reading_and_stride(self):
        work = OperationWork(64)
        self.assertEqual((5, 3, 1), work.getitem('probe', tuple(range(7)), slice(5, 0, -2)))
        self.assertEqual(3, work.stages['probe.slice.read'])
        self.assertEqual(3, work.stages['probe.slice.write'])

    def test_membership_and_comparison_stop_at_first_difference(self):
        work = OperationWork(64)
        class Unreachable:
            def __eq__(self, other):
                raise AssertionError('compared an unvisited suffix')
        self.assertTrue(work.compare('probe', 'in', 7, [1, 7, Unreachable()]))
        self.assertTrue(work.compare('probe', 'lt', [1, 4, Unreachable()], [1, 5, Unreachable()]))
        self.assertEqual(2, work.stages['probe.contains.read'])
        self.assertEqual(4, work.stages['probe.sequence.read'])

    def test_hash_map_read_before_access_and_mutation_before_update(self):
        calls = []
        class Key:
            def __hash__(self):
                calls.append(1)
                return 7
        values = {}
        key = Key()
        with self.assertRaises(OperationLimit):
            OperationWork(0, require_native_coverage=True).call('probe', values.get, key)
        self.assertEqual([], calls)
        work = OperationWork(2, require_native_coverage=True)
        self.assertEqual('fallback', work.call('probe', values.get, key, 'fallback'))
        self.assertEqual(9, work.call('probe', values.setdefault, key, 9))
        self.assertEqual(2, len(calls))
        self.assertEqual(9, values[key])

    def test_lazy_zip_consumes_no_unvisited_inputs_and_charges_each_attempt(self):
        seen = []
        def items():
            seen.append(1)
            yield 7
            seen.append(2)
            yield 8
        work = OperationWork(64, require_native_coverage=True)
        zipped = work.call('probe', zip, items(), [2])
        self.assertEqual([], seen)
        self.assertEqual([(7, 2)], list(zipped))
        self.assertEqual([1, 2], seen)
        self.assertEqual(4, work.stages['probe.zip.input.iter-next'])

    def test_text_strip_visits_only_the_scanned_prefix_and_suffix(self):
        work = OperationWork(50, require_native_coverage=True)
        self.assertEqual('xx', work.call('probe', ' xx '.strip))
        self.assertEqual(4, work.stages['probe.strip.read'])
        self.assertEqual(2, work.stages['probe.strip.copy.write'])
        self.assertEqual('', work.call('probe', ' '.strip))
        self.assertEqual(5, work.stages['probe.strip.read'])

    def test_complete_work_stops_before_native_json_instead_of_borrowing_the_diagnostic_prefix(self):
        import json
        from frozen_sympy_native_work import UnmeasuredOperation
        work = OperationWork(20, require_native_coverage=True, require_complete_work=True)
        # Malformed input would throw JSONDecodeError if the unmeasured parser ran.
        with self.assertRaisesRegex(UnmeasuredOperation, 'json.loads'):
            work.call('bridge', json.loads, '{')
        self.assertEqual(0, work.used)
        self.assertEqual(1, work.boundary_gaps['transport.json.loads'])

    def test_cache_wrapper_is_recognized_before_implicit_native_use(self):
        from functools import lru_cache
        from frozen_sympy_native_work import UnmeasuredOperation
        seen = []
        @lru_cache
        def cached():
            seen.append(1)
            return 7
        with self.assertRaises(UnmeasuredOperation):
            OperationWork(10, require_native_coverage=True).call('probe', cached)
        self.assertEqual([], seen)

    def test_unhandled_native_or_limit_cannot_be_suppressed_by_original_code(self):
        from collections import deque
        from frozen_sympy_native_work import UnmeasuredOperation
        with self.assertRaises(OperationLimit):
            work = OperationWork(0)
            try:
                work.binary('probe', 'add', 1, 2)
            except BaseException:
                pass
            work.require_uninterrupted()
        with self.assertRaises(UnmeasuredOperation):
            work = OperationWork(20, require_native_coverage=True)
            try:
                work.call('probe', deque().rotate, 1)
            except BaseException:
                pass
            work.require_uninterrupted()


class SourceCoverageTest(unittest.TestCase):
    def fake(self, module, text):
        scope = {'__name__':module}
        exec(compile(text, '<fixture>', 'exec'),scope)
        return scope['call']

    def test_guard_rejects_uncounted_sympy_and_mpmath_before_body_even_if_caught(self):
        import sys
        from frozen_sympy_source_patch import guard_math_calls, UnmeasuredOperation
        for name in ['sympy.fixture', 'mpmath.libmp.fixture']:
            touched = []
            foreign = self.fake(name, 'def call(touched): touched.append(1)')
            with self.assertRaisesRegex(UnmeasuredOperation, name):
                with guard_math_calls(()) as guard:
                    try:
                        foreign(touched)
                    except BaseException:
                        pass
            self.assertEqual([], touched)
            self.assertIsNone(sys.getprofile())

    def test_foreign_callback_below_measured_function_is_rejected_before_math(self):
        from frozen_sympy_source_patch import guard_math_calls, UnmeasuredOperation
        touched = []
        foreign = self.fake('outside_polynomial', 'def call(values): values.append(2 * 3)')
        measured = self.fake('sympy.fixture', 'def call(callback, values): return callback(values)')
        with self.assertRaisesRegex(UnmeasuredOperation, 'outside_polynomial'):
            with guard_math_calls([measured.__code__], strict_foreign=True):
                measured(foreign, touched)
        self.assertEqual([], touched)

    def test_guard_only_admits_actual_installed_code_and_restores_prior_profiler(self):
        import sys
        from frozen_sympy_source_patch import guard_math_calls
        measured = self.fake('sympy.fixture', 'def call(): return 42')
        with guard_math_calls([measured.__code__]) as guard:
            self.assertEqual(42,measured())
        self.assertEqual(1, guard.visits)
        self.assertIsNone(sys.getprofile())


if __name__ == '__main__':
    unittest.main()
