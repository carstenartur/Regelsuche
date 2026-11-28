"""Verify two opposite-order retained-tactic runs from their raw ledgers and proof paths."""
import argparse
import json
from itertools import islice
from pathlib import Path
import re
import runpy

from admission_cases import expected_cases, validate_split
import verify_acquisition


STUDY = Path(__file__).resolve().parent
RESEARCH = STUDY.parent
parent = runpy.run_path(str(RESEARCH / "prospective-tactic-transfer/verify.py"))
require, read, sha = (parent[k] for k in ("require", "read", "sha"))
DEADLINE = 524_288
PROTOCOL = "65d509412987427192fb04af5b2af726eaad9888f5c29451a894aed432ea4ed4"
ARMS = ("BASE", "AUTO_OLD", "CHECKED_OLD", "PREVIOUS_POLICY", "FRESH_ONLY", "OLD_ONLY",
        "PRIOR_UNION", "RETAINED_FORCED", "RETAINED", "DROP_ALL")
HISTORICAL = ("BASE", "AUTO_OLD", "CHECKED_OLD", "PREVIOUS_POLICY")
MODEL_FOR = {"AUTO_OLD": "old", "CHECKED_OLD": "old", "PREVIOUS_POLICY": "fresh",
             "OLD_ONLY": "old", "FRESH_ONLY": "fresh", "PRIOR_UNION": "priorUnion",
             "RETAINED_FORCED": "union", "RETAINED": "union", "DROP_ALL": "union"}


def verify_calibration(bill, old_study):
    manifest = json.loads(read(old_study / "training", "training.json"))
    require(len(manifest["calibration"]) == 46, "changed calibration source panel")
    selected = list(manifest["calibration"][::4])
    source_work = sum(1 + len(task["source"]) for task in selected)
    for family in ("A", "B"):
        prefix = read(old_study / "results" / family, "BASE.jsonl").splitlines(keepends=True)[:20]
        require(len(prefix) == 20 and all(line.endswith(b"\n") and not line.endswith(b"\r\n") for line in prefix),
                "unrecognized source ledger encoding")
        source_work += sum(map(len, prefix))
        for i, line in enumerate(prefix[16:20], start=16):
            row = json.loads(line)
            require(row["index"] == i, "unordered calibration source ledger")
            selected.append(dict(source=row["source"], depth=32, cap=row["cap"], target=row["target"]))
    seed = "efb9439d38f8f0fa4e20ce5ffbedc34fa5248519"
    block = list(islice(parent["previous"]["expected_cases"](seed),32,40))
    require(len(block) == 8, "incomplete mixed calibration block")
    source_work += sum(1 + len(source) for _,_,source in block)
    for topology, _, source in block[:4]:
        selected.append(dict(source=source, depth=32, cap=8192, target=17 if topology == "H" else 19))
    require(len(selected) == len(bill["calibrationSources"]) == 24, "wrong calibration cohort size")
    for task, stored in zip(selected, bill["calibrationSources"], strict=True):
        require(set(stored) == {"source","depth","cap","target"} and parent["tree"](task["source"]) == parent["tree"](stored["source"])
                and all(task[k] == stored[k] for k in ("depth","cap","target")), "wrong calibration task or budget")
    require(bill["calibrationSourceWork"] == source_work, "incorrect calibration source charge")


def materials():
    old_study = RESEARCH / "prospective-tactic-transfer"
    (old, fresh), charges, sources = parent["materials"](old_study)
    train = STUDY / "training"
    receipt = json.loads(read(train, "receipt.json"))
    sources.extend(verify_acquisition.verify(STUDY, json.loads(read(train, "acquired.json"))))
    sources.extend(t["source"] for t in receipt["calibrationSources"])
    verify_calibration(receipt, old_study)
    libraries = {"old": old, "fresh": fresh}
    for label, name in (("acquired", "acquired.json"), ("priorUnion", "prior-union.json"), ("union", "union.json")):
        raw = read(train, name).decode()
        libraries[label] = parent["schema_inventory"](json.dumps({"model": raw}).encode())
    for child, parents in (("priorUnion", ("old", "fresh")), ("union", ("priorUnion", "acquired"))):
        allowed = {}
        for p in parents:
            for key, pattern in libraries[p][1].items():
                require(key not in allowed or allowed[key] == pattern, "parent schema ID collision")
                allowed[key] = pattern
        actual = libraries[child][1]
        require(all(allowed.get(key) == pattern for key, pattern in actual.items()), "unsupported retained rule")
        require(libraries[parents[0]][1].keys() <= actual.keys(), "previous proof lost")
    for arm in ("old", "fresh", "priorUnion", "union"):
        rule, rules = libraries[arm]
        bill = receipt[arm]
        require(sorted(rules) == bill["schemas"] and rule == "checked-learned:" + bill["model"], "incorrect model receipt")
        require(all(type(bill[k]) is int and bill[k] >= 0 for k in ("acquisition", "load", "calibration")),
                "invalid cost")
        require(re.fullmatch("sha256:[0-9a-f]{64}", bill["admission"]) is not None, "unbound gate")
        trials = bill["trials"]
        require([t["index"] for t in trials] == list(range(len(receipt["calibrationSources"]))), "missing trial")
        require(all(type(t[k]) is int and t[k] >= 0 for t in trials for k in ("primitiveWork","learnedWork","learnedSteps")),
                "invalid calibration trial")
        require(bill["calibration"] >= sum(t["primitiveWork"]+t["learnedWork"] for t in trials),
                "unpaid calibration attempts")
    require(receipt["old"]["acquisition"] == 9130 and receipt["fresh"]["acquisition"] == charges["acquisition"],
            "historical acquisition mismatch")
    for charge, extra in (("acquisition", "acquiredAcquisition"), ("load", "acquiredLoad")):
        prior = receipt["old"][charge] + receipt["fresh"][charge]
        require(prior == receipt["priorUnion"][charge] and prior + receipt[extra] == receipt["union"][charge],
                "parent charge lost")
    require(0 < receipt["priorComposition"] <= receipt["composition"], "invalid composition charge")
    return libraries, receipt, sources


def verify_setups(arms, bills):
    old = json.loads(read(RESEARCH / "prospective-tactic-transfer/results/A", "summary.json"))["arms"]
    for key in ARMS:
        setup = arms[key]["setup"]
        require(set(setup) == {"acquisition", "calibration", "load", "preparation"}
                and all(type(v) is int and v >= 0 for v in setup.values()), "invalid setup")
        if key in ("BASE", "AUTO_OLD", "PREVIOUS_POLICY"):
            original = "POLICY" if key == "PREVIOUS_POLICY" else key
            require(setup == old[original]["setup"], "historical control changed")
            continue
        lib = MODEL_FOR[key]
        receipt = bills[lib]
        expected_cal = 0 if key in ("CHECKED_OLD","RETAINED_FORCED") else receipt["calibration"]+bills["calibrationSourceWork"]
        require((setup["acquisition"],setup["load"],setup["calibration"]) ==
                (receipt["acquisition"],receipt["load"],expected_cal), "wrong setup for " + key)
        if lib in ("union", "priorUnion"):
            require(setup["preparation"] >= bills["composition" if lib == "union" else "priorComposition"],
                    "composition was free")
    require(arms["RETAINED"]["setup"] == arms["DROP_ALL"]["setup"], "ablation setup differs")


def verify_family(first, second, family, seed, cases, known, bills):
    def same(name):
        a, b = (read(directory / family, name) for directory in (first, second))
        require(a == b, "different rotations " + family + "/" + name)
        return a

    def lines(name):
        return list(map(json.loads,same(name).splitlines()))

    summary = json.loads(same("summary.json"))
    arms = summary["arms"]
    require((summary["family"],summary["freeze"],summary["deadline"]) == (family,seed,DEADLINE) and set(arms) == set(ARMS),
            "incorrect experiment identity")
    verify_setups(arms,bills)
    paths = json.loads(same("paths.json"))
    parent["verify_paths"](paths,dict(known.values()))
    seen, records, timed, stats = set(), {}, {}, {}
    for arm in ARMS:
        records[arm] = rows = lines(arm + ".jsonl")
        rule, patterns = known.get(MODEL_FOR.get(arm), (None, {}))
        ids = set(patterns) if arm != "DROP_ALL" else set()
        for row, case in zip(rows,cases,strict=True):
            parent["check_row"](arm,row,case,paths,seen,ids,rule)
        setup = sum(arms[arm]["setup"].values())
        total, timed[arm] = parent["clock_rows"](rows,setup,DEADLINE)
        by_deadline, solved = sum(timed[arm]), sum(r["solved"] for r in rows)
        require((total,by_deadline,solved) == (arms[arm]["fullCost"],arms[arm]["byDeadline"],arms[arm]["totalSolved"]),
                "inconsistent ledger summary " + arm)
        stats[arm] = {"cold":by_deadline,"postSetup":sum(r["solved"] and r["completedAt"]-setup <= DEADLINE for r in rows),
                      "local":solved,"work":total,"setup":setup,"selection":sum(r["selection"] for r in rows),
                      "search":sum(r["query"] for r in rows),"onlineVerification":sum(r["verificationWithinQuery"] for r in rows),
                      "replay":sum(r["replay"] for r in rows),"audit":sum(r["audit"] for r in rows),
                      "learnedSteps":sum(sum(s["schema"] is not None for s in paths[r["pathHash"]]) for r in rows)}
    gated = {"FRESH_ONLY","OLD_ONLY","PRIOR_UNION","RETAINED","DROP_ALL"}
    admissions = json.loads(same("admissions.json"))
    require(set(admissions) == gated and admissions["RETAINED"] == admissions["DROP_ALL"], "different ablation routing")
    for arm, decisions in admissions.items():
        require(len(decisions) == len(cases) and all(type(x) is bool for x in decisions), "missing admission decision")
        require(all(flag or not row["learnedSchemas"] for flag,row in zip(decisions,records[arm],strict=True)), "declined rule used")
        stats[arm]["admitted"] = sum(decisions)
    eligible = {(i,rule) for i,row in enumerate(records["RETAINED"]) if row["solved"]
                and all(not records[k][i]["solved"] for k in (*HISTORICAL,"DROP_ALL")) for rule in row["learnedSchemas"]}
    tested, causal = set(), []
    candidate_rule, available = known["union"]
    for trial in lines("removals.jsonl"):
        i, rule, row = trial["index"], trial["disabled"], trial["row"]
        key = i,rule
        require(key in eligible and key not in tested, "extra or duplicated removal")
        parent["check_row"]("removal",row,cases[i],paths,seen,available.keys()-{rule},candidate_rule)
        parent["clock_rows"]([row],stats["RETAINED"]["setup"],DEADLINE)
        tested.add(key)
        if timed["RETAINED"][i] and not row["solved"]:
            causal.append({"index":i,"schema":rule,"alsoHistorical":rule in known["old"][1],
                           "alsoFresh":rule in known["fresh"][1],"alsoAcquired":rule in known["acquired"][1]})
    require(tested == eligible and seen == paths.keys(), "missing removals or unreferenced proof")
    controls = {a:v["cold"] for a,v in stats.items() if a != "RETAINED"}
    margin = parent["compare_completions"](stats["RETAINED"]["cold"],controls)
    for arm in ARMS:
        stats[arm]["newVsControl"] = sum(p and not c for p,c in zip(timed["RETAINED"],timed[arm],strict=True))
        stats[arm]["lostVsControl"] = sum(c and not p for p,c in zip(timed["RETAINED"],timed[arm],strict=True))
    elapsed = [json.loads(read(d/family,"elapsed.json")) for d in (first,second)]
    require(all(set(e) == set(ARMS) and all(type(t) is int and t > 0 for t in e.values()) for e in elapsed),
            "missing timing observations")
    return {"arms":stats,"margin":margin,"timelySingleRemovals":causal,"individualRemovals":len(eligible),
            "paths":len(seen),"rows":len(cases)*len(ARMS),"elapsedNanos":elapsed,"passed":bool(margin["passed"] and causal)}


def verify(first, second, seed):
    require(first.resolve() != second.resolve() and re.fullmatch("[0-9a-f]{40}",seed), "unique freeze and runs required")
    require(sha((STUDY/"protocol.md").read_bytes()) == PROTOCOL, "registered protocol changed")
    known, receipts, training_sources = materials()
    cases = {family:list(expected_cases(seed,family)) for family in ("C","D")}
    validate_split(cases,training_sources)
    results = {f:verify_family(first,second,f,seed,inputs,known,receipts) for f,inputs in cases.items()}
    return {"freeze":seed,"families":results,"passed":all(v["passed"] for v in results.values())}


if __name__ == "__main__":
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("forward",type=Path)
    args.add_argument("reverse",type=Path)
    args.add_argument("freeze")
    options = args.parse_args()
    print(json.dumps(verify(options.forward,options.reverse,options.freeze),indent=2))
