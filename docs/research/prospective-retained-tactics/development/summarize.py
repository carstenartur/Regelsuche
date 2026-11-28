"""Reconcile case, proof, budget and cold/paid-premises comparisons on the three public panels."""
import argparse
import json
from pathlib import Path
import runpy

RESEARCH = Path(__file__).resolve().parents[2]
prior = runpy.run_path(str(RESEARCH / "prospective-tactic-transfer/verify.py"))
old = prior["previous"]
require, sha = old["require"], old["sha"]
MIXED_SEED = "efb9439d38f8f0fa4e20ce5ffbedc34fa5248519"
TACTIC_SEED = "c7141702e091ff94b5ed2e3c5af5b5d2d66136dd"


def inventories(training):
    snapshots = [RESEARCH / "prospective-distributive-coverage/snapshot.json",
                 RESEARCH / "prospective-tactic-transfer/training/snapshot.json"]
    models = dict(prior["schema_inventory"](path.read_bytes()) for path in snapshots)
    for path in training.glob("*.json"):
        text = path.read_text()
        model = json.loads(text)
        if "schemas" in model:
            key = "checked-learned:sha256:" + sha(text.encode())
            models[key] = {s["id"]: (prior["freeze_tree"](s["source"]), prior["freeze_tree"](s["target"]))
                           for s in model["schemas"]}
    return models


def panel(name):
    if name != "MIXED":
        return list(prior["expected_cases"](TACTIC_SEED, name))
    cases = []
    for i, (topology, degree, source) in enumerate(old["expected_cases"](MIXED_SEED)):
        cases.append({"index": i, "m": degree, "orientation": int(topology == "D"), "cap": 8192,
                      "bound": 19 if topology == "D" else 17, "source": source,
                      "polynomial": old["analyze"](source)[0]})
    return cases


def verify(root, repeat, name, models):
    def read(name_):
        data = (root / name / name_).read_bytes()
        require((repeat / name / name_).read_bytes() == data, "different rotations: " + name + "/" + name_)
        return data

    sums = json.loads(read("summary.json"))
    deadline = 524_288 if name == "MIXED" else 1_048_576
    require(sums["deadline"] == deadline, "wrong budget")
    cases = panel(name)
    paths = json.loads(read("paths.json"))
    prior["verify_paths"](paths, models)
    seen, arms, cold, local = set(), {}, {}, {}
    for arm, s in sums["arms"].items():
        raw = read(arm + ".jsonl")
        rows = [json.loads(line) for line in raw.splitlines()]
        require(len(rows) == len(cases), "wrong panel size")
        for row, case in zip(rows, cases, strict=True):
            learned = {t["rule"] for t in paths[row["pathHash"]] if t["schema"] is not None}
            require(len(learned) <= 1, "mixed witness model within arm")
            rule = next(iter(learned), None)
            prior["check_row"](arm, row, case, paths, seen, set(models.get(rule, {})), rule)
        setup = sum(s["setup"].values())
        cost, timely = prior["clock_rows"](rows, setup, deadline)
        cold[arm], local[arm] = timely, [r["solved"] for r in rows]
        counts = sum(timely), sum(local[arm]), cost
        require(counts == (s["byDeadline"], s["totalSolved"], s["fullCost"]), "summary mismatch")
        arms[arm] = {"setup": setup, "byDeadline": counts[0], "totalSolved": counts[1], "fullCost": cost,
                     "withoutSetupByDeadline": sum(r["solved"] and r["completedAt"] - setup <= deadline for r in rows),
                     "localAfterFirst32": sum(local[arm][32:]), "coldAfterFirst32": sum(timely[32:]),
                     "selection": sum(r["selection"] for r in rows), "search": sum(r["query"] for r in rows),
                     "replay": sum(r["replay"] for r in rows), "audit": sum(r["audit"] for r in rows),
                     "learnedSteps": sum(t["schema"] is not None for r in rows for t in paths[r["pathHash"]]),
                     "ledgerSha256": sha(raw)}
    require(seen == set(paths), "unused proof paths")
    require(sums["arms"]["RETAINED"]["setup"] == sums["arms"]["DROP_ALL"]["setup"], "ablation setup")
    require(arms["DROP_ALL"]["learnedSteps"] == 0, "not all proposals were disabled")
    if name == "MIXED":
        require((arms["CHECKED_OLD"]["byDeadline"], arms["CHECKED_OLD"]["fullCost"]) == (720, 738848),
                "historical seven-schema checker drifted")
    else:
        previous = RESEARCH / "prospective-tactic-transfer/results" / name
        for current, former in (("BASE", "BASE"), ("AUTO_OLD", "AUTO_OLD"), ("PREVIOUS_POLICY", "POLICY")):
            require(read(current + ".jsonl") == old["read"](previous, former + ".jsonl"), "historical arm drift")
    pairs = {}
    for arm in arms:
        def difference(values):
            pairs = list(zip(values["RETAINED"], values[arm], strict=True))
            return {"new": sum(a and not b for a, b in pairs), "lost": sum(b and not a for a, b in pairs)}
        pairs[arm] = {"cold": difference(cold), "local": difference(local)}
    return {"deadline": deadline, "tasks": len(cases), "distinctPaths": len(paths),
            "arms": arms, "pairedAgainstRetained": pairs}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("first", type=Path)
    parser.add_argument("opposite", type=Path)
    parser.add_argument("--training", type=Path, default=RESEARCH / "prospective-retained-tactics/training")
    args = parser.parse_args()
    require(args.first.resolve() != args.opposite.resolve(), "two distinct JVM result directories required")
    models = inventories(args.training)
    print(json.dumps({name: verify(args.first, args.opposite, name, models) for name in ("MIXED", "A", "B")}, indent=2))
