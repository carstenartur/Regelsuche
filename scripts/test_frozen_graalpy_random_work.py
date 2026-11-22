"""Independent state and prefix controls against the actual native RNG."""

import random
import unittest
from frozen_sympy_operation_work import OperationLimit, OperationWork
from frozen_graalpy_random_work import checked_random


class GraalRandomWorkTest(unittest.TestCase):
    def test_twist_checks_the_true_output_and_all_624_state_entries(self):
        for seed in (0, 17):
            expected = random.Random(seed)
            actual = random.Random(seed)
            work = OperationWork(100000, retain_events=False)
            self.assertEqual(expected.random(), checked_random(work, actual))
            self.assertEqual(expected.getstate(), actual.getstate())
            self.assertEqual(624, work.stages['graalpy.PRandom.nextInt.twist.state-write'])
            self.assertEqual(624, work.stages['graalpy.PRandom.nextInt.twist.mag01-read'])

    def test_no_twist_after_first_native_draw_and_every_short_budget_prefix(self):
        expected = random.Random(11)
        expected.random()
        frozen = expected.getstate()
        full = OperationWork(1000)
        result = checked_random(full, expected)
        self.assertGreater(result, 0)
        self.assertEqual(41, full.used)
        self.assertNotIn('graalpy.PRandom.nextInt.twist.state-write', full.stages)
        for limit, rejected in enumerate(full.events):
            rng = random.Random()
            rng.setstate(frozen)
            work = OperationWork(limit)
            with self.assertRaises(OperationLimit) as failure:
                checked_random(work, rng)
            self.assertEqual(rejected, str(failure.exception))
            self.assertEqual(full.events[:limit], work.events)
            self.assertEqual(frozen, rng.getstate())

    def test_twist_between_the_two_native_words(self):
        rng = random.Random(31)
        while rng.getstate()[1][-1] != 623:
            rng.getrandbits(1)
        copied = random.Random()
        copied.setstate(rng.getstate())
        work = OperationWork(100000)
        self.assertEqual(copied.random(), checked_random(work, rng))
        self.assertEqual(copied.getstate(), rng.getstate())
        self.assertEqual(624, work.stages['graalpy.PRandom.nextInt.twist.state-write'])


if __name__ == '__main__':
    unittest.main()
