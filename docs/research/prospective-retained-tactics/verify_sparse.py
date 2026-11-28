"""Audit dense or sparse calibration attempts, their support and logical search costs."""
import json
from pathlib import Path
import runpy
import sys

historical = runpy.run_path(str(Path(__file__).resolve().parents[1] / "prospective-tactic-transfer/verify.py"))
require, tree = historical["require"], historical["tree"]


def validate_fit(bill, tasks):
    trials = bill["trials"]
    require([t["index"] for t in trials] == list(range(len(tasks))), "missing calibration comparison")
    for t in trials:
        require(all(type(t[k]) is int and t[k] >= 0 for k in ("primitiveWork","learnedWork","learnedSteps")) and
                all(type(t[k]) is bool for k in ("primitiveSolved","learnedSolved")), "invalid base trial")
    base_cost = sum(t["primitiveWork"] + t["learnedWork"] for t in trials)
    require(type(bill["calibration"]) is int and bill["calibration"] >= base_cost, "unpaid calibration attempts")
    require(("sparseTrials" in bill) == ("selectedSchemas" in bill), "partial sparse calibration receipt")
    if "sparseTrials" not in bill:
        return {"method":"dense", "fullMatcherTrials":len(trials), "baseCost":base_cost, "extraCost":0}
    sparse = bill["sparseTrials"]
    require([s["index"] for s in sparse] == list(range(len(tasks))), "missing sparse receipts")
    all_ids = set(bill["schemas"])

    def shallow_key(task):
        node = tree(task["source"])
        return (node[0], node[1][0], node[2][0]) if len(node) == 3 else (node[0],)

    keys = [shallow_key(t) for t in tasks]
    selected, support = set(), set()
    for trial, s, key in zip(trials,sparse,keys,strict=True):
        ids = s["pilotSchemas"]
        require(ids == sorted(set(ids)) and set(ids) <= all_ids, "wrong pilot tactic IDs")
        require(all(type(s[k]) is int and s[k] >= 0 for k in ("primitivePathLength","fullPathLength","learnedSteps","work"))
                and type(s["solved"]) is bool, "invalid sparse trial")
        require(len(ids) <= trial["learnedSteps"] <= s["fullPathLength"] and bool(ids) == bool(trial["learnedSteps"]),
                "wrong full witness count")
        qualifies = bool(trial["learnedSolved"] and ids and (not trial["primitiveSolved"]
                         or trial["learnedWork"] < trial["primitiveWork"] or s["fullPathLength"] < s["primitivePathLength"]))
        require(s["compressedOrCheaper"] is qualifies, "unsupported proof selection")
        if qualifies:
            selected.update(ids)
            support.add(key)
    require(bill["selectedSchemas"] == sorted(selected), "selected matcher differs from qualifying pilot proofs")
    measured, extra = 0, 0
    for trial, s, key in zip(trials,sparse,keys,strict=True):
        expected = ("OUTSIDE_SUPPORT" if key not in support else "PILOT_REUSED" if selected == all_ids else "MEASURED")
        require(s["status"] == expected, "wrong refinement status or support")
        used = s["usedSchemas"]
        require(used == sorted(set(used)) and set(used) <= selected and len(used) <= s["learnedSteps"]
                and bool(used) == bool(s["learnedSteps"]), "ineligible sparse witness")
        if expected == "OUTSIDE_SUPPORT":
            require(not s["solved"] and s["work"] == s["learnedSteps"] == 0, "unmeasured result")
        elif expected == "PILOT_REUSED":
            require((s["solved"],s["learnedSteps"],used,s["work"]) ==
                    (trial["learnedSolved"],trial["learnedSteps"],s["pilotSchemas"],0), "invalid pilot reuse")
        else:
            require(s["work"] > 0, "unpaid sparse matcher execution")
            measured += 1
            extra += s["work"]
    require(bill["calibration"] >= base_cost + extra, "second matcher trials omitted from fitting costs")
    return {"method":"sparse","fullMatcherTrials":len(trials),"measuredRefinements":measured,
            "selectedSchemas":len(selected),"baseCost":base_cost,"extraCost":extra}


if __name__ == "__main__":
    receipt = json.loads(Path(sys.argv[1]).read_text())
    print(json.dumps({k:validate_fit(receipt[k],receipt["calibrationSources"])
                      for k in ("old","fresh","priorUnion","union")},indent=2))
