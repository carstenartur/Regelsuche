"""Replay the fixed-basis exponent-31 arithmetic certificate with exact rationals.

Dusart's prime-interval theorem and the grid lemma are the external and written
steps, respectively. No integer of size q is allocated. Normalized scores use
logarithms to base 2 in place of natural logarithms (one common factor).
"""
from fractions import Fraction
from math import gcd


A = int("1556095533552627246390780335067207532624858656838080892018247476484757633546217236016582305898188818689508280367742001845993424030239264478911416750039480586003340464262294466538")
B = int("222659284728917207555836872298586172476537487047674653720926481551823532984942099300344580227088419773334749862651932293427135056390737286892772995102309860816945901600653842701")
EXPONENT = 31


def require(condition, message):
    if not condition:
        raise ValueError(message)


def log_from_atanh(z, terms):
    """Natural log((1+z)/(1-z)); bound the omitted positive series geometrically."""
    require(0 < z < 1 and terms > 0, "invalid log series arguments")
    term = z
    total = Fraction()
    for index in range(terms):
        total += 2 * term / (2 * index + 1)
        term *= z * z
    tail = 2 * term / ((2 * terms + 1) * (1 - z * z))
    return total, total + tail


def certify(a=A, b=B, n=EXPONENT):
    require(0 < a < 7*b and b > n > 1, "wrong grid size")
    require(gcd(a, b) == gcd(n, b) == 1, "grid residues fail")
    log_two_lo, log_two_hi = log_from_atanh(Fraction(1, 3), 400)
    gap_lo, gap_hi = log_from_atanh(Fraction(1, 255), 100)
    alpha_lo = 7 - gap_hi / log_two_lo
    alpha_hi = 7 - gap_lo / log_two_hi
    require(6 < alpha_lo < alpha_hi < 7 and log_two_lo > Fraction(1, 2),
            "failed base-log bounds")

    error_lo, error_hi = b*alpha_lo-a, b*alpha_hi-a
    require(error_lo*error_hi > 0, "precision does not resolve signed grid error")
    epsilon_lo = Fraction(1, 2952*b)
    epsilon_hi = Fraction(1, 2950*b)
    require(epsilon_lo < min(abs(error_lo), abs(error_hi))
            and max(abs(error_lo), abs(error_hi)) < epsilon_hi,
            "rational grid accuracy not certified")

    m = 7*n*b+1
    center = Fraction(m, n)
    require(center > 19 and 2**19 > 396738, "Dusart threshold not met")
    eta = Fraction(1, b*b)
    # log2(q/2^center) <= 1/(25*(ln 2)^3*center^2)
    require(Fraction(8, 25)/center**2 < eta, "prime window too large")
    upper = 7*b+2
    require(center+eta < upper, "log-prime upper bound too small")
    i_max = n*upper
    j_max = Fraction(i_max, 6)
    norm_max = i_max + j_max + n
    require(center >= max(a, b*alpha_hi), "old pair unavailable at activation")

    # e != 0 mod n gives a nonzero multiple of 1/(bn) before perturbation.
    lower_gap = Fraction(1, b*n) - j_max*epsilon_hi/b - n*eta
    require(lower_gap > 0 and norm_max/lower_gap < a/epsilon_hi,
            "a smaller exponent could match the certified old incumbent")

    # For differences of old endpoints, the grid value i+j*a/b can vanish.
    # Then (i,j) is a nonzero multiple of (-a,b) and the gap is >= epsilon.
    old_gap = min(epsilon_lo, Fraction(1, b) - j_max*epsilon_hi/b)
    other_gap = old_gap - n*eta
    require(other_gap > 0, "prime window overlaps another old endpoint")
    require(Fraction(m, n)/eta > norm_max/other_gap,
            "target does not separate from old pairs and other final partners")
    return {"old_basis": (2, 127), "first_exponent": n,
            "upward_prime_window": "Dusart (2010), Proposition 6.8",
            "first_upper_endpoint": "q^n", "unique_partner": "2^m",
            "denominator_digits": len(str(b))}


if __name__ == "__main__":
    import json
    print(json.dumps(certify(), indent=2, sort_keys=True))
