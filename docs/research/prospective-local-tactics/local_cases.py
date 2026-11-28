"""Independent reconstruction, polynomial oracle and split validation for the local reuse study."""
from hashlib import sha256
from itertools import product
import json
from pathlib import Path
import re
import runpy


RESEARCH = Path(__file__).resolve().parents[1]
QUADRATURE = runpy.run_path(str(RESEARCH / "prospective-interval-transfer" / "verify.py"))
analyze, read, require = (QUADRATURE[k] for k in ("analyze", "read", "require"))


def residual(index, variant, order):
    source, reduced = [], []
    for position, j in enumerate(order):
        loading, gain, adjustment = "abc"[j], "pqr"[j], "stu"[j]
        if position == 0 and variant == 1:
            raw = f"({gain}+{adjustment})*({gain}-{adjustment})+{adjustment}*{adjustment}"
            term = f"{gain}^2"
        else:
            reading, increment = "efg"[j], "jkl"[j]
            raw = f"{reading}*{gain}+{increment}*{gain}"
            term = f"({reading}+{increment})*{gain}"
        source.append(f"{loading}*({raw})")
        reduced.append(f"{loading}*({term})")

    def square(terms):
        return f"((h+{110_001+index})-({'+'.join(terms)}))^2"

    return square(source), square(reduced)


def expected_cases(seed):
    require(re.fullmatch("[0-9a-f]{40}", seed), "expected complete public freeze SHA")
    strata = list(product((2, 3), (0, 1), (256, 768, 2048, 8192)))

    def digest(text):
        return sha256(text.encode("utf8")).hexdigest()

    for block in range(16):
        def key(s):
            m, v, cap = s
            return digest(f"{seed}/order/E/{block}/{m}/{v}/{cap}"), s

        for slot, (m, v, cap) in enumerate(sorted(strata, key=key)):
            index = 16*block + slot
            order = sorted(range(m), key=lambda j: (digest(f"{seed}/vars/E/{index}/{j}"), j))
            source, canonical = residual(index, v, order)
            polynomial, nodes, _ = analyze(canonical)
            require(nodes == 8*m + 5 - 2*v and analyze(source)[0] == polynomial, "inconsistent residual oracle")
            yield dict(index=index, source=source, m=m, orientation=v, cap=cap, bound=nodes, polynomial=polynomial)


def validate_split(cases, training_sources=()):
    """A distinct multiset of exact coefficients excludes any whole-input equality up to variable renaming."""
    freeze = RESEARCH / "prospective-distributive-coverage/freeze.json"
    seen = QUADRATURE["prior_signatures"](json.loads(freeze.read_bytes()))
    paired = RESEARCH / "prospective-tactic-transfer"
    retained = RESEARCH / "prospective-retained-tactics"
    archives = [(RESEARCH / "prospective-interval-transfer/results", 1024)]
    archives.extend((paired / "results" / f, 512) for f in ("A", "B"))
    archives.extend((retained / "results" / f, 512) for f in ("C", "D"))
    for archive, count in archives:
        rows = read(archive, "BASE.jsonl").splitlines()
        require(len(rows) == count, f"missing earlier panel from {archive}")
        seen.update(QUADRATURE["signature"](json.loads(r)["source"]) for r in rows)

    old = json.loads((paired / "training/training.json").read_bytes())
    prior = [r["task"]["source"] for k in ("training", "gate") for r in old[k]]
    prior.extend(r["source"] for r in old["calibration"])
    training = retained / "training"
    prior.extend(r["source"] for r in json.loads((training / "receipt.json").read_bytes())["calibrationSources"])
    for batch in json.loads((training / "trace-acquisition.json").read_bytes())["batches"]:
        prior.extend(r["source"] for k in ("training", "gates") for r in batch[k])
    seen.update(QUADRATURE["signature"](s) for s in (*prior, *training_sources))
    cases = list(cases)
    require(len(cases) == 256, "incomplete prospective panel")
    for case in cases:
        signature = tuple(sorted(case["polynomial"].values()))
        require(signature not in seen, f"overlap at E/{case['index']}")
        seen.add(signature)
