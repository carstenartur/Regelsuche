"""Reconcile the five-arm rerun with the published Horner/dot-product study."""

import argparse
import importlib.util
import json
from pathlib import Path
from unittest.mock import patch


def verify(forward, reverse):
    prior = Path(__file__).resolve().parents[1] / "prospective-amortization"
    spec = importlib.util.spec_from_file_location("amortization", prior / "verify.py")
    check = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(check)

    def identical(name):
        raw = check.read(forward, name)
        check.require(raw == check.read(reverse, name), f"rotation mismatch in {name}")
        return raw

    check.require(forward.resolve() != reverse.resolve(), "provide two JVM outputs")
    seed = "efb9439d38f8f0fa4e20ce5ffbedc34fa5248519"
    old = prior / "results"
    baseline = json.loads(check.read(old, "summary.json"))["arms"]
    summary = json.loads(identical("summary.json"))
    check.require(summary["freeze"] == seed and summary["model"] == check.MODEL and
                  summary["primaryBudget"] == check.DEADLINE, "wrong development study")
    expected = list(check.expected_cases(seed))
    paths = json.loads(identical("paths.json"))
    old_paths = json.loads(check.read(old, "paths.json"))
    check.check_paths(paths)
    check.require(all(steps == old_paths.get(key) for key, steps in paths.items()),
                  "the development stream contains a different proof")
    used, counts = set(), {}
    aliases = {"BASE": "BASE", "REGEN": "REGEN", "DROP": "DROP", "FAST": "REGEN", "FAST_DROP": "DROP"}
    check.require(set(summary["arms"]) == set(aliases), "unexpected solver arms")
    for arm, alias in aliases.items():
        name = arm + ".jsonl"
        raw = identical(name)
        record = summary["arms"][arm]
        if arm == alias:
            check.require(raw == check.read(old, name) and record == baseline[arm],
                          f"{arm}: earlier control changed")
        setup = check.SETUP[arm] if arm == alias else (9130, 1620, 2100)
        rows = [json.loads(line) for line in raw.splitlines()]
        with patch.dict(check.SETUP, {alias: setup}):
            _, solved = check.check_arm(alias, record, rows, expected, paths, used)
        counts[arm] = len(solved)
    check.require(used == set(paths), "unreferenced witnesses")
    print(f"Checked {len(expected) * len(aliases)} rows and {len(used)} distinct paths. {counts}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("forward", type=Path)
    parser.add_argument("reverse", type=Path)
    args = parser.parse_args()
    verify(args.forward, args.reverse)
