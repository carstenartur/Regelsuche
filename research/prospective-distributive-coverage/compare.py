"""Reconcile both independent JVM exports and publish a compact, lossless row table."""
import argparse
import csv
import hashlib
import itertools
import json
from pathlib import Path

ARMS = ("BASE", "LEARNED", "DROP")
FAMILIES = ("parallel", "serial", "normalize", "mirrored", "neutral", "negative")
PARAMETERS = (2, 3, 5, 7)
BUDGETS = (64, 96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288)
REFERENCE = 200000


def read_rows(path):
    rows = {}
    for line in path.read_text().splitlines():
        r = json.loads(line)
        key = (r["arm"], r["family"], r["n"], r["budget"])
        assert key not in rows
        rows[key] = r
    assert set(rows) == set(itertools.product(ARMS, FAMILIES, PARAMETERS, (*BUDGETS, REFERENCE)))
    return rows


def read(path):
    return json.loads(path.read_text())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")


def summarize(a, b, out):
    rows = read_rows(a / "rows.jsonl")
    assert rows == read_rows(b / "rows.jsonl")
    paths = read(a / "paths.json")
    assert paths == read(b / "paths.json")
    splits = read(a / "split.json")
    assert splits == read(b / "split.json")
    times = [read(d / "times.json") for d in (a, b)]
    assert [[v["arm"] for v in rotation] for rotation in times] == [list(ARMS), list(reversed(ARMS))]
    for x, y in zip(times[0], reversed(times[1])):
        for column in ("arm", "acquisition", "loadAndPrepare"):
            assert x[column] == y[column]
    overlaps = {key: value for key, value in splits.items() if len({item.split("/")[0] for item in value}) > 1}
    for row in rows.values():
        steps = paths[row["path"]]
        assert row["complete"]
        assert row["withinBudget"] == (row["work"] <= row["budget"])
        assert row["solved"] == (row["withinBudget"] and row["outcome"] == "QUALITY_REACHED"
                                 and row["score"] <= row["goal"])
        assert row["learnedSteps"] == sum(step["schema"] is not None for step in steps)
        assert row["arm"] == "LEARNED" or not row["learnedSteps"]
        assert row["audit"] > 0
        for before, after in zip(steps, steps[1:]):
            assert before["target"] == after["source"]
        if steps:
            assert steps[-1]["target"] == row["output"]
        encoded = json.dumps(steps, separators=(",", ":"), ensure_ascii=False).encode()
        assert "sha256:" + hashlib.sha256(encoded).hexdigest() == row["path"]
    assert {r["path"] for r in rows.values()} == set(paths)
    out.mkdir(parents=True, exist_ok=True)
    ordered_paths = sorted(paths)
    outputs = sorted({r["output"] for r in rows.values()})
    with (out / "paths.jsonl").open("w") as stream:
        for i, key in enumerate(ordered_paths):
            stream.write(json.dumps({"id": i, "hash": key, "steps": paths[key]}, separators=(",", ":")) + "\n")
    write_json(out / "outputs.json", outputs)
    with (out / "rows.csv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=next(iter(rows.values())), lineterminator="\n")
        writer.writeheader()
        for key in itertools.product(ARMS, FAMILIES, PARAMETERS, (*BUDGETS, REFERENCE)):
            row = rows[key].copy()
            row["output"] = outputs.index(row["output"])
            row["path"] = ordered_paths.index(row["path"])
            writer.writerow(row)

    def solved(arm, family, n, budget):
        return rows[arm, family, n, budget]["solved"]

    def totals(budgets, families=FAMILIES):
        return {arm: sum(solved(arm, f, n, b) for f, n, b in itertools.product(families, PARAMETERS, budgets))
                for arm in ARMS}

    def gains(control, budgets):
        pairs = [(solved("LEARNED", f, n, b), solved(control, f, n, b))
                 for f, n, b in itertools.product(FAMILIES, PARAMETERS, budgets)]
        return {"wins": pairs.count((True, False)), "losses": pairs.count((False, True))}

    qualifying = {}
    for family in FAMILIES[:3]:
        qualifying[family] = [budget for budget in BUDGETS if all(
            solved("LEARNED", family, n, budget) and rows["LEARNED", family, n, budget]["learnedSteps"] > 0
            and not solved("BASE", family, n, budget) and not solved("DROP", family, n, budget)
            and solved("BASE", family, n, REFERENCE) for n in PARAMETERS)]
    overall = totals(BUDGETS)
    report = {
        "rowsPerRotation": len(rows),
        "witnesses": len(paths),
        "rawSha256": [{f: digest(d / f) for f in ("rows.jsonl", "paths.json", "split.json", "times.json")}
                      for d in (a, b)],
        "timings": times,
        "alphaOverlaps": splits,
        "crossSplitAlphaOverlaps": overlaps,
        "sweptCoverage": overall,
        "pairwiseCoverage": {control: gains(control, BUDGETS) for control in ("BASE", "DROP")},
        "qualifyingBudgets": qualifying,
        "primaryCriterion": (any(qualifying.values()) and all(overall["LEARNED"] > overall[c] for c in ("BASE", "DROP"))),
        "families": {f: {"swept": totals(BUDGETS, [f]), "reference": totals([REFERENCE], [f])} for f in FAMILIES},
        "referenceQueryWork": {
            arm: {f: [rows[arm, f, n, REFERENCE]["work"] for n in PARAMETERS] for f in FAMILIES} for arm in ARMS
        },
        "byBudget": {b: totals([b]) for b in (*BUDGETS, REFERENCE)},
        "overBudgetRows": {arm: sum(not r["withinBudget"] for r in rows.values() if r["arm"] == arm) for arm in ARMS},
    }
    write_json(out / "summary.json", report)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("forward", "reverse", "out"):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    report = summarize(args.forward, args.reverse, args.out)
    print(json.dumps({k: report[k] for k in ("sweptCoverage", "qualifyingBudgets", "primaryCriterion")}))
