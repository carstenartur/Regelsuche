"""Berechnet die Beispiele der Forschungsnotiz zu OEIS A135311 (Python 3)."""

from math import isqrt


def primes_up_to(limit):
    return [n for n in range(2, limit + 1)
            if all(n % divisor for divisor in range(2, isqrt(n) + 1))]


def greedy_offsets(limit):
    """A135311 bis einschließlich limit direkt aus der Zulässigkeit erzeugen."""
    offsets = []
    primes = primes_up_to(limit + 1)
    for candidate in range(limit + 1):
        trial = offsets + [candidate]
        # Größere Primzahlen haben mehr Restklassen als der Kandidatensatz Elemente.
        if all(len({g % p for g in trial}) < p for p in primes if p <= len(trial)):
            offsets.append(candidate)
    return offsets


def classify(offsets, prime):
    target = (prime - 1) // 2
    seen = set()
    for g in offsets:
        residue = g % prime
        if residue == target:
            return "Teiler", g
        seen.add(residue)
        if len(seen) == prime - 1:
            return "ausgeschlossen", g
    return "im Ausschnitt offen", None


if __name__ == "__main__":
    offsets = greedy_offsets(1000)
    print(f"{len(offsets)} Folgenglieder bis 1000, letztes Glied {offsets[-1]}.")
    print("p | Status | entscheidendes g")
    for prime in primes_up_to(71):
        if prime > 2:
            status, g = classify(offsets, prime)
            print(f"{prime:2} | {status} | {g if g is not None else '–'}")
