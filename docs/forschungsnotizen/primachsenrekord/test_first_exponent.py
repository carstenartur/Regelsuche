"""Finite certificates for first gains of the fixed old basis {2,127}."""
import unittest

from fixed_basis_grid import A, B, certify


def powers_up_to(prime, limit):
    value, exponent = 1, 0
    while value <= limit:
        yield value, exponent
        value *= prime
        exponent += 1


class FirstExponentTests(unittest.TestCase):
    def test_interval_prime_first_gain_requires_exponent_31(self):
        result = certify()
        self.assertEqual(result["first_exponent"], 31)
        self.assertEqual(result["unique_partner"], "2^m")

    def test_interval_prime_certificate_rejects_wrong_inputs(self):
        for a, b, exponent, message in (
            (A + 1, B, 31, "grid accuracy"),
            (2*A, 2*B, 31, "grid residues"),
            (A, B, 127, "smaller exponent"),
        ):
            with self.subTest(case=message):
                with self.assertRaisesRegex(ValueError, message):
                    certify(a, b, exponent)

    def test_first_gain_requires_exponent_seven(self):
        q = 191
        maximum = q**7
        winner = (2**53, maximum)
        values = sorted((a * b * c, (i, j, k))
                        for a, i in powers_up_to(2, maximum)
                        for b, j in powers_up_to(127, maximum // a)
                        for c, k in powers_up_to(q, maximum // (a * b)))
        self.assertEqual(len(values), 706)
        self.assertEqual(len({value for value, _ in values}), len(values))

        early = rivals = winners = benchmarks = 0
        for index, (b, v) in enumerate(values):
            for a, u in values[:index]:
                norm_squared = sum((x - y)**2 for x, y in zip(u, v))
                gap_squared = (b - a)**2
                # 2(b-a)/(b+a) <= log(b/a) <= (b-a)/a.
                # Both score bounds are compared after squaring and clearing denominators.
                if (a, b) == (127, 128):
                    self.assertGreater(norm_squared * a**2, 898**2 * gap_squared)
                    benchmarks += 1
                if (a, b) == winner:
                    self.assertEqual(v[-1] - u[-1], 7)
                    self.assertGreater(norm_squared * a**2, 1809**2 * gap_squared)
                    winners += 1
                elif u[-1] != v[-1] and b < maximum:
                    self.assertLess(norm_squared * (a + b)**2, 4 * 739**2 * gap_squared)
                    early += 1
                else:
                    self.assertLess(norm_squared * (a + b)**2, 4 * 1260**2 * gap_squared)
                    rivals += 1
        self.assertEqual((benchmarks, winners, early, rivals), (1, 1, 192520, 56344))
        self.assertEqual(winners + early + rivals, len(values) * (len(values) - 1) // 2)


if __name__ == '__main__':
    unittest.main()
