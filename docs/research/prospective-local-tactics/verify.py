"""Verify two fresh JVM ledgers and the independently generated inputs of the local study."""
import argparse
import json
from pathlib import Path
import re
import runpy
import sys

from local_cases import expected_cases, validate_split

HERE = Path(__file__).resolve().parent
OLDER = HERE.parent / "prospective-retained-tactics"
sys.path.insert(0, str(OLDER))
retained = runpy.run_path(str(OLDER / "verify.py"))
parent = retained["parent"]
require, read, sha = (parent[k] for k in ("require", "read", "sha"))
DEADLINE = 524288
PRIOR_SEED = "27bb13a8c861578912ef968ab73b2d06cb95fce7"
PROTOCOL = "ca9732802d94cbf8f9b650e5e554e13e166e82b185dbb71aa31676b85755b4ee"
ARMS = ("SHARED", "PRIOR", "LOCAL_OLD", "LOCAL", "DROP")


def check_setup(arms, bill):
    reference = json.loads(read(OLDER / "results" / "C", "summary.json"))["arms"]["RETAINED"]["setup"]
    require(set(arms) == set(ARMS), "unexpected arms")
    require(arms["PRIOR"]["setup"] == reference, "old control changed")
    require(arms["LOCAL"]["setup"] == arms["DROP"]["setup"], "proposal ablation changed setup")
    require(arms["SHARED"]["setup"] == dict.fromkeys(reference, 0), "unexpected shared-primitive setup")
    for arm in ("LOCAL", "LOCAL_OLD"):
        setup = arms[arm]["setup"]
        require(set(setup) == set(reference) and all(type(v) is int and v >= 0 for v in setup.values()), "invalid setup")
        lib = bill["old" if arm == "LOCAL_OLD" else "union"]
        require(setup["load"] == lib["load"] and setup["acquisition"] == lib["acquisition"]
                and setup["calibration"] == 0, "incorrect costs for frozen library")
        require(setup["preparation"] >= (0 if arm == "LOCAL_OLD" else bill["composition"]), "unpaid composition")


def panel(first, second, name, seed, cases, models, bill):
    def raw(filename):
        data = read(first / name, filename)
        require(data == read(second / name, filename), "opposite rotations differ: " + name + "/" + filename)
        return data

    def lines(filename):
        return [json.loads(s) for s in raw(filename).splitlines()]

    report = json.loads(raw("summary.json"))
    require((report["family"], report["freeze"], report["deadline"]) == (name,seed,DEADLINE), "wrong panel or budget")
    check_setup(report["arms"],bill)
    paths = json.loads(raw("paths.json"))
    parent["verify_paths"](paths,dict(models.values()))
    used, rows, timely, stats = set(), {}, {}, {}
    admitted = json.loads(raw("admissions.json"))
    require(set(admitted) == set(ARMS), "missing admission flags")
    for arm in ARMS:
        records = rows[arm] = lines(arm+".jsonl")
        rule, patterns = models["old" if arm == "LOCAL_OLD" else "union"]
        allowed = set() if arm in ("DROP","SHARED") else patterns.keys()
        decisions = admitted[arm]
        for row, case, flag in zip(records,cases,decisions,strict=True):
            parent["check_row"](arm,row,case,paths,used,allowed,rule)
            require(type(flag) is bool and (flag or not row["learnedSchemas"]), "declined offer used")
        summ = report["arms"][arm]
        setup = sum(summ["setup"].values())
        total, timely[arm] = parent["clock_rows"](records,setup,DEADLINE)
        solved = sum(r["solved"] for r in records)
        cold = sum(timely[arm])
        require((total,solved,cold) == (summ["fullCost"],summ["totalSolved"],summ["byDeadline"]), "wrong summary")
        stats[arm] = {"cold":cold,"local":solved,"work":total,"setup":setup,
                      "postSetup":sum(r["solved"] and r["completedAt"]-setup <= DEADLINE for r in records),
                      "admitted":sum(decisions),
                      "learnedSteps":sum(s["schema"] is not None for r in records for s in paths[r["pathHash"]])}
        stats[arm].update({k:sum(r[k] for r in records) for k in ("selection","query","verificationWithinQuery","replay","audit")})
        if name in ("C","D") and arm == "PRIOR":
            require(raw(arm+".jsonl") == read(OLDER/"results"/name,"RETAINED.jsonl"), "historical router drift")
        if name in ("C","D") and arm == "SHARED":
            known = json.loads(read(OLDER/"results","standalone.json"))[name]["SHARED"]
            require(sha(raw(arm+".jsonl")) == known["rowSha256"], "optimized primitive control drift")
    eligible = {(r["index"],schema) for r in rows["LOCAL"] if r["solved"] and
                all(not rows[k][r["index"]]["solved"] for k in ARMS if k != "LOCAL") for schema in r["learnedSchemas"]}
    tested, causal = set(), []
    rule, patterns = models["union"]
    for experiment in lines("removals.jsonl"):
        i, schema, row = (experiment[k] for k in ("index","schema","row"))
        require((i,schema) in eligible - tested, "ineligible/duplicate knockout")
        parent["check_row"]("removal",row,cases[i],paths,used,patterns.keys()-{schema},rule)
        parent["clock_rows"]([row],stats["LOCAL"]["setup"],DEADLINE)
        tested.add((i,schema))
        if timely["LOCAL"][i] and not row["solved"]:
            causal.append({"index":i,"schema":schema,"source":cases[i]["source"]})
    require(tested == eligible and used == paths.keys(), "incomplete removal/path ledger")
    for arm in ARMS:
        for key, target, control in (("cold",timely["LOCAL"],timely[arm]),
                ("local",[r["solved"] for r in rows["LOCAL"]],[r["solved"] for r in rows[arm]])):
            stats[arm][key+"Wins"] = sum(x and not y for x,y in zip(target,control,strict=True))
            stats[arm][key+"Losses"] = sum(y and not x for x,y in zip(target,control,strict=True))
    margin = parent["compare_completions"](stats["LOCAL"]["cold"],{k:v["cold"] for k,v in stats.items() if k!="LOCAL"})
    return {"arms":stats,"margin":margin,"timelySingleRuleWitnesses":causal,"singleRemovals":len(tested),
            "paths":len(paths),"rows":len(cases)*len(ARMS),"passed":bool(margin["passed"] and causal)}


def verify(first, second, phase, seed):
    require(first.resolve()!=second.resolve(), "need distinct runs")
    require(sha((HERE/"protocol.md").read_bytes()) == PROTOCOL, "changed protocol")
    models, bill, sources = retained["materials"]()
    if phase == "development":
        panels = {n:list(retained["expected_cases"](PRIOR_SEED,n)) for n in ("C","D")}
        seed = PRIOR_SEED
    else:
        require(seed and re.fullmatch("[0-9a-f]{40}", seed), "expected full freeze seed")
        panels = {"E":list(expected_cases(seed))}
        validate_split(panels["E"],sources)
    return {n:panel(first,second,n,seed,cases,models,bill) for n,cases in panels.items()}


if __name__ == "__main__":
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("first",type=Path)
    args.add_argument("second",type=Path)
    args.add_argument("phase",choices=("development","final"))
    args.add_argument("freeze",nargs="?")
    parsed = args.parse_args()
    print(json.dumps(verify(parsed.first,parsed.second,parsed.phase,parsed.freeze),indent=2))
