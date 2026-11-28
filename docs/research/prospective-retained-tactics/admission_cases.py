"""Independent C/D source, polynomial oracle, and split validation for the prospective study."""
from hashlib import sha256
from itertools import product
import json
from pathlib import Path
import re
import runpy


RESEARCH = Path(__file__).resolve().parents[1]
QUADRATURE = runpy.run_path(str(RESEARCH / "prospective-interval-transfer" / "verify.py"))
analyze, read, require = (QUADRATURE[name] for name in ("analyze", "read", "require"))


def digest(text):
    return sha256(text.encode("utf-8")).hexdigest()


def cascade(index, count, orientation, order):
    factors, collected = [], []
    for rank, j in enumerate(order):
        gain = "abc"[j] if rank else f"({'abc'[j]}+{70_001 + index})"
        total, first, second = "efg"[j], "jkl"[j], "nop"[j]
        if orientation:
            stage = f"({gain}*(({total}-{first})-{second})+{gain}*{second})+{gain}*{first}"
        else:
            stage = f"{gain}*({total}-{first})+{gain}*{first}"
        factors.append(f"({stage})")
        collected.append(f"({gain}*{total})")
    return "*".join(factors), "*".join(collected), 4 * count + 1


def area(index, count, orientation, order):
    slots, collected = list("pqrs"), list("pqrs")
    for rank, j in enumerate(order[:count]):
        gain = "abcd"[j] if rank else f"({'abcd'[j]}+{90_001 + index})"
        t, u = "efgh"[j], "jklm"[j]
        if orientation and rank == 0:
            slots[j] = f"({gain}*({t}-{u})+{gain}*{u})"
            collected[j] = f"({gain}*{t})"
        else:
            slots[j] = f"({t}*{gain}+{u}*{gain})"
            collected[j] = f"(({t}+{u})*{gain})"

    def det(coords):
        return "({}*{})-({}*{})".format(*coords)

    return det(slots), det(collected), 9 + 4 * count - 2 * orientation


def expected_cases(seed, family):
    require(re.fullmatch(r"[a-f0-9]{40}", seed) and family in ("C", "D"), "invalid seed or family")
    strata = tuple(product((2, 3), (0, 1), (256, 768, 2048, 8192)))
    for block in range(32):
        def block_key(s):
            m, o, cap = s
            return digest(f"{seed}/order/{family}/{block}/{m}/{o}/{cap}"), s

        for slot, (m, o, cap) in enumerate(sorted(strata, key=block_key)):
            i = 16 * block + slot
            order = sorted(range(m if family == "C" else 4),
                           key=lambda j: (digest(f"{seed}/vars/{family}/{i}/{j}"), j))
            make = cascade if family == "C" else area
            source, collected, bound = make(i, m, o, order)
            polynomial, nodes, _ = analyze(collected)
            require(nodes == bound and analyze(source)[0] == polynomial, "invalid fixture identity or node goal")
            yield dict(index=i, source=source, m=m, orientation=o, cap=cap, bound=bound, polynomial=polynomial)


def validate_split(expected, training_sources):
    """Distinct coefficient multisets suffice to rule out equality up to variable renaming."""
    hist = RESEARCH / "prospective-distributive-coverage" / "freeze.json"
    signatures = QUADRATURE["prior_signatures"](json.loads(hist.read_bytes()))
    paired = RESEARCH / "prospective-tactic-transfer"
    for archive, size in [(RESEARCH / "prospective-interval-transfer" / "results", 1024),
                          (paired / "results/A", 512), (paired / "results/B", 512)]:
        rows = read(archive, "BASE.jsonl").splitlines()
        require(len(rows) == size, f"missing earlier full stream in {archive.name}")
        signatures.update(QUADRATURE["signature"](json.loads(row)["source"]) for row in rows)
    manifest = json.loads((paired / "training/training.json").read_bytes())
    known = [row["task"]["source"] for k in ("training", "gate") for row in manifest[k]]
    known.extend(row["source"] for row in manifest["calibration"])
    signatures.update(QUADRATURE["signature"](s) for s in (*known, *training_sources))
    require(set(expected) == {"C", "D"}, "missing registered family")
    for family, cases in expected.items():
        require(len(cases) == 512, "incorrect size of family " + family)
        for case in cases:
            signature = tuple(sorted(case["polynomial"].values()))
            require(signature not in signatures, f"overlap in {family} at {case['index']}")
            signatures.add(signature)
