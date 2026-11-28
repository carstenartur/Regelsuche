#!/usr/bin/env python3
"""Endliche Sättigungen und Zwangsaufnahmen in A135311, 28. November 2026.

Python 3.8+. Standardlauf: Inferenz aus den eingebetteten kleineren Regeln.
--quelle-pruefen: zusätzlich unabhängiger kanonischer Durchlauf bis 62 816.
"""
import argparse
from math import isqrt


def require(condition, message):
    if not condition:
        raise ValueError(message)


def primes_upto(limit):
    prime = bytearray([1]) * (limit + 1)
    prime[:2] = b"\0\0"
    for n in range(2, isqrt(limit) + 1):
        if prime[n]:
            for multiple in range(n * n, limit + 1, n):
                prime[multiple] = 0
    return [p for p in range(2, limit + 1) if prime[p]]


# (Primzahl, erster Sättigungsoffset, zuletzt fehlender Rest).
# Vollständige Liste aller bis 62 816 gesättigten Primzahlen unter 751.
SMALLER_RULES = [
    (2, 0, 1), (3, 2, 1), (5, 8, 4), (7, 18, 3),
    (11, 36, 5), (13, 50, 1), (17, 78, 7), (19, 168, 9),
    (23, 176, 11), (29, 266, 25), (31, 246, 15), (37, 282, 33),
    (41, 450, 13), (43, 452, 21), (47, 578, 23), (53, 758, 31),
    (59, 812, 29), (61, 1416, 52), (67, 996, 33), (71, 2366, 35),
    (73, 1026, 35), (79, 1488, 39), (83, 1122, 41), (89, 1370, 58),
    (97, 1686, 11), (101, 1538, 13), (103, 2210, 51), (107, 2430, 53),
    (109, 1610, 57), (113, 2072, 29), (127, 3528, 63), (131, 3626, 65),
    (137, 3282, 43), (139, 4160, 69), (149, 2532, 119), (151, 3506, 75),
    (157, 3620, 122), (163, 3780, 81), (167, 3672, 83), (173, 3086, 112),
    (179, 4088, 89), (181, 3648, 4), (191, 4898, 95), (193, 4188, 94),
    (197, 4298, 174), (199, 5322, 99), (211, 5982, 105), (223, 5558, 111),
    (227, 11118, 113), (229, 5952, 123), (233, 4982, 107), (239, 4872, 119),
    (241, 5672, 228), (251, 7442, 125), (257, 6090, 223), (263, 7040, 131),
    (269, 7938, 126), (271, 8742, 135), (277, 9252, 201), (281, 5910, 29),
    (283, 13140, 141), (293, 9840, 193), (307, 7170, 153), (311, 9042, 155),
    (313, 7646, 261), (317, 8952, 271), (331, 9260, 165), (337, 9746, 225),
    (347, 12716, 173), (349, 10112, 275), (353, 12686, 239), (359, 10656, 179),
    (367, 13740, 183), (373, 10688, 107), (379, 12738, 189), (383, 13676, 191),
    (389, 12998, 354), (397, 11906, 238), (401, 11186, 330), (409, 14790, 23),
    (419, 16442, 209), (421, 14628, 129), (431, 14286, 215), (433, 12048, 171),
    (439, 14268, 219), (443, 14736, 221), (449, 14826, 295), (457, 13622, 180),
    (461, 13026, 335), (463, 14768, 231), (467, 21986, 233), (479, 18128, 239),
    (487, 24410, 243), (491, 18960, 245), (499, 18662, 249), (503, 22050, 251),
    (509, 16592, 454), (521, 16112, 239), (523, 18512, 261), (541, 18356, 315),
    (547, 21740, 273), (557, 17562, 377), (563, 30666, 281), (569, 19196, 376),
    (571, 23568, 285), (577, 21006, 538), (587, 26018, 293), (593, 22392, 358),
    (599, 20616, 299), (601, 23652, 513), (607, 21740, 303), (613, 23850, 129),
    (617, 22908, 432), (619, 24438, 309), (631, 31638, 315), (641, 28736, 638),
    (643, 27740, 321), (647, 27918, 323), (653, 19286, 22), (659, 40740, 329),
    (661, 27290, 23), (673, 25256, 92), (677, 24438, 616), (683, 30030, 341),
    (691, 31572, 345), (701, 23046, 568), (709, 22520, 583), (719, 49550, 359),
    (727, 40506, 363), (733, 24618, 148), (739, 29096, 369), (743, 25166, 371),
]

COVER_751 = [25538, 30176, 33032, 47796]
COVER_1217 = [
    25296, 25766, 25940, 26100, 26228, 26250, 26432, 26472, 26616, 26712,
    26720, 26828, 26840, 27050, 27566, 27948, 28032, 28086, 28728, 28772,
    28986, 29006, 29150, 29268, 29442, 30062, 30132, 30248, 30258, 30582,
    31182, 32010, 33098, 33716, 33780, 34040, 34176, 35156, 35420, 35736,
    35982, 37098, 37188, 37622, 37808, 37902, 38220, 38502, 38616, 39590,
    39642, 39960, 39992, 41168, 42222, 42438, 44178, 48090, 53972, 59540,
    60530,
]
COVER_269 = [5160, 5258, 5322, 5486, 6276, 6302, 6446, 6846, 6998, 7358, 7938]


def generate_rules(limit, threshold):
    """Canonical replay only for the optional authentication of small rules."""
    primes = set(primes_upto(limit + 1))
    accepted = []
    active = {}
    saturated = []
    banned = bytearray(limit + 1)

    def finish(q, at, seen):
        missing = next(r for r in range(q) if r not in seen)
        for g in range(at + 1 + (missing - at - 1) % q, limit + 1, q):
            banned[g] = 1
        if q < threshold:
            saturated.append((q, at, missing))

    for g in range(limit + 1):
        if banned[g]:
            continue
        accepted.append(g)
        finished = []
        for q, seen in active.items():
            seen.add(g % q)
            if len(seen) == q - 1:
                finish(q, g, seen)
                finished.append(q)
        for q in finished:
            del active[q]

        # Only now are q-1 terms available for this new modulus.
        q = len(accepted) + 1
        if q in primes:
            seen = {h % q for h in accepted}
            if len(seen) == q - 1:
                finish(q, g, seen)
            else:
                active[q] = seen
    return sorted(saturated)


def upper_sieve(p, limit, rules):
    banned = bytearray(limit + 1)
    for q, at, missing in rules:
        require(q < p, "Die Inferenz erhielt eine zu große Quellprimzahl.")
        first = at + 1 + (missing - at - 1) % q
        for g in range(first, limit + 1, q):
            banned[g] = 1
    return [g for g, forbidden in enumerate(banned) if not forbidden]


def saturation(values, q):
    seen = set()
    for g in sorted(values):
        seen.add(g % q)
        if len(seen) == q - 1:
            return (g, next(r for r in range(q) if r not in seen))
    return None


class Window:
    def __init__(self, p, limit, horizon, smaller_rules):
        self.candidates = upper_sieve(p, limit, smaller_rules)
        self.upper = set(self.candidates)
        self.initial = {g for g in self.candidates if g <= horizon}
        self.profiles = {}
        for q in primes_upto(len(self.candidates) + 1):
            if q < p:
                continue
            # The two largest first appearances suffice for "all but r".
            # An absent class receives a timestamp strictly beyond the window.
            first = [limit + 1] * q
            for g in reversed(self.candidates):
                first[g % q] = g
            last = max(first)
            require(last > horizon, "Eine große Primzahl kann im Anfangsstück sperren.")
            residue = first.index(last)
            second = max(first[:residue] + first[residue + 1:])
            if second < limit:
                self.profiles[q] = (residue, last, second)

    def blockers(self, g, known):
        require(g in self.upper, "Der Kandidat scheitert bereits am kleineren Sieb.")
        out = []
        for q, (latest_residue, last, second) in self.profiles.items():
            residue = g % q
            latest_other = second if residue == latest_residue else last
            if latest_other >= g:
                continue
            if not any(h < g and h % q == residue for h in known):
                out.append(q)
        return out

    def includes(self, offsets):
        known = set(self.initial)
        for g in offsets:
            require(g not in known and not self.blockers(g, known),
                    f"Aufnahme {g} ist nicht erzwungen.")
            known.add(g)
        return known

    def activation(self, q, known, at, missing):
        # An upper sieve gives the lower time bound. At the same time the
        # known terms must occupy all q-1 classes, with exactly this gap.
        require(saturation(self.candidates, q)[0] == at, f"Untere Zeitschranke bei {q}")
        require(saturation(known, q) == (at, missing), f"Obere Zeitschranke bei {q}")


def certify_conflict(window, target, blocker, omitted, other_blocker, witness, known):
    require(target not in known, "Die Ableitung würde ihre Zielaufnahme benutzen.")
    require(window.blockers(target, window.initial) == [blocker],
            "Die Sperrprimzahl für die Zielaufnahme ist nicht eindeutig.")
    require(window.blockers(omitted, window.initial) == [other_blocker],
            "Die Sperrprimzahl für die Auslassung ist nicht eindeutig.")
    # The omission must hold without using the target.
    require({g % other_blocker for g in known if g < omitted}
            == set(range(other_blocker)) - {omitted % other_blocker},
            "Die getrennte Ausschlussurkunde ist unvollständig.")
    require(witness % blocker != target % blocker, "Falsche Pflichtklasse.")
    cell = [g for g in window.candidates if g < target and g % blocker == witness % blocker]
    require(cell == [witness], "Der Pflichtzeuge ist nicht eindeutig.")
    require(witness % other_blocker == omitted % other_blocker, "Kein Widerspruch.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quelle-pruefen", action="store_true")
    args = parser.parse_args()
    if args.quelle_pruefen:
        require(generate_rules(62816, 751) == SMALLER_RULES, "Falsche kleinere Regeln.")
        print("Quelle: Kanonischer Durchlauf bestätigt alle kleineren Regeln bis 62 816.")
    def window(p, limit, horizon):
        return Window(p, limit, horizon, [row for row in SMALLER_RULES if row[0] < p])

    large = window(751, 62816, 25160)
    large.activation(751, large.includes(COVER_751), 47796, 375)
    covered = large.includes(COVER_1217)
    large.activation(1217, covered, 60530, 749)
    require(62816 % 1217 == 749 and 62816 > 60530, "Ausschluss 62 816")
    print("T_751 = 47 796 (Rest 375), T_1217 = 60 530 (Rest 749), 62 816 ausgeschlossen.")
    small = window(103, 4248, 1610)
    small.activation(109, small.initial, 1610, 57)
    certify_conflict(small, 4248, 157, 3218, 109, 1692, small.initial)
    middle = window(227, 13038, 4982)
    middle_known = middle.includes(COVER_269)
    middle.activation(269, middle_known, 7938, 126)
    certify_conflict(middle, 8952, 317, 13038, 269, 8196, middle_known)
    print("Zertifizierte Aufnahmen: 4 248 und 8 952.")


if __name__ == "__main__":
    main()
