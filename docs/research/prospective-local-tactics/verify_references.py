"""Audit primitive reference ledgers against the official panel and its independent generator."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import re

from verify import DEADLINE, PRIOR_SEED, expected_cases, parent, read, require, retained, sha, validate_split


def audit_arm(name, ledger, cases, paths, setup, deadline):
    rows = [json.loads(line) for line in ledger.splitlines()]
    used = set()
    for row, case in zip(rows, cases, strict=True):
        parent["check_row"](name, row, case, paths, used, set(), None)
        require(row["selection"] == 0, "unexpected primitive selection charge")
    full, timely = parent["clock_rows"](rows, setup, deadline)
    return {"setup": setup, "totalSolved": sum(row["solved"] for row in rows),
            "byDeadline": sum(timely), "fullCost": full, "query": sum(row["query"] for row in rows),
            "verificationInSearch": sum(row["verificationWithinQuery"] for row in rows),
            "replay": sum(row["replay"] for row in rows), "audit": sum(row["audit"] for row in rows),
            "proofPaths": len(used), "rowSha256": sha(ledger)}, used


RESEARCH = Path(__file__).resolve().parents[1]
# Digest of the identical pre-freeze ordering receipts in both public development runs.
TRAINING = "7999119708608dcce7608fddbfec6e95dc4a103a093570df6cf223c1168cf42b"
MIXED_SEED = "efb9439d38f8f0fa4e20ce5ffbedc34fa5248519"
STOCK = RESEARCH / "prospective-tactic-transfer"
SUMMARY_KEYS = ("totalSolved", "byDeadline", "fullCost")


def official_panel(official, panel, seed):
    published = json.loads(read(RESEARCH / "prospective-retained-tactics" / "results", "standalone.json"))
    if panel == "MIXED":
        require(seed == MIXED_SEED, "incorrect historical mixed seed")
        mixed = parent["previous"]
        cases = [dict(index=i, m=n, orientation=int(t == "D"), cap=8192, source=src,
                      polynomial=mixed["analyze"](src)[0], bound=17 if t == "H" else 19)
                 for i, (t, n, src) in enumerate(mixed["expected_cases"](seed))]
        for case, raw in zip(cases, read(official, "BASE.jsonl").splitlines(), strict=True):
            row = json.loads(raw)
            require(row["source"] == case["source"] and row["index"] == case["index"]
                    and row["distributed"] == case["m"], "changed historical mixed input")
        return cases, published[panel]["SHARED"]

    require(panel == "E" or seed == PRIOR_SEED, "incorrect public development freeze")
    meta = json.loads(read(official, "summary.json"))
    require((meta["family"], meta["freeze"], meta["deadline"]) == (panel, seed, DEADLINE),
            "wrong official family, freeze or deadline")
    if panel == "E":
        cases = list(expected_cases(seed))
        validate_split(cases, retained["materials"]()[2])
    else:
        cases = list(retained["expected_cases"](seed, panel))

    shared = read(official, "SHARED.jsonl")
    official_paths = json.loads(read(official, "paths.json"))
    summary, used = audit_arm("SHARED", shared, cases, official_paths, 0, DEADLINE)
    parent["verify_paths"]({path: official_paths[path] for path in used}, {})
    registered = meta["arms"]["SHARED"]
    require(sum(registered["setup"].values()) == 0 and all(summary[k] == registered[k] for k in SUMMARY_KEYS),
            "SHARED aggregate differs from its row ledger")
    if panel != "E":
        require(summary == published[panel]["SHARED"], "SHARED differs from the published control")
    return cases, summary


def verify(official, first, second, panel, seed, reports=None):
    require(first.resolve() != second.resolve(), "two distinct reference outputs required")
    require(re.fullmatch(r"[a-f0-9]{40}", seed), "expected a full lowercase freeze SHA")
    cases, summary = official_panel(official, panel, seed)
    same = parent["same_bytes"]
    paths = json.loads(same(first, second, "paths.json"))
    parent["verify_paths"](paths, {})
    if reports:
        one, two = reports
        require(one.resolve() != two.resolve(), "two distinct reference summaries required")
        a, b = (read(path.parent, path.name.removesuffix(".gz")) for path in (one, two))
        require(a == b, "opposite rotations disagree on standalone summary")
        results = json.loads(a)[panel]
        require(set(results) == {"SHARED", "SUBTREE"} and results["SHARED"] == summary,
                "standalone reference failed to reproduce SHARED")
        arms = {"SUBTREE": 0}
    else:
        results, arms = ordering_setup(first, second, panel)

    checked, all_used = {"SHARED": summary}, set()
    for arm, setup in arms.items():
        raw = same(first, second, arm + ".jsonl")
        checked[arm], used = audit_arm(arm, raw, cases, paths, setup, DEADLINE)
        expected = checked[arm] if reports else {k: checked[arm][k] for k in SUMMARY_KEYS}
        recorded = results[arm] if reports else {k: results[arm][k] for k in SUMMARY_KEYS}
        require(expected == recorded, arm + " aggregate differs from its row ledger")
        all_used.update(used)
    require(all_used == paths.keys(), "unused supplementary paths")
    if not reports:
        for a, b in zip(read(first, "GREEDY.jsonl").splitlines(), read(first, "DROP_ORDER.jsonl").splitlines(), strict=True):
            free, paid = json.loads(a), json.loads(b)
            free["completedAt"] += arms["DROP_ORDER"]
            require(free == paid, "order ablation changed more than its setup")
    if panel != "E":
        previous = json.loads(read(Path(__file__).parent / "development", "subtree-counts.json"))
        require(checked["SUBTREE"] == previous[panel]["SUBTREE"], "changed public subtree implementation")
    return checked


def training_setup(receipt):
    prior = json.loads(read(STOCK / "training", "training.json"))
    ids = ["evo_3601297f9f7d_" + name for name in sorted(parent["PRIMITIVE_RULES"])]
    require(receipt["inventory"] == ids and receipt["inventoryHash"] == prior["inventory"], "wrong primitive inventory")
    lessons = receipt["training"]
    require(len(lessons) == 26 and len(prior["training"]) == 20, "wrong training cohort")
    cost = 0
    for lesson, known in zip(lessons[:20], prior["training"], strict=True):
        task = known["task"]
        require(lesson == dict(source=task["source"], target=task["target"], solved=known["solved"],
                               rules=[s["rule"] for s in known["witness"]], work=known["work"]),
                "changed prior primitive training receipt")
        cost += 2 + len(task["source"])
    for i, panel in enumerate(("A", "B")):
        lines = read(STOCK / "results" / panel, "BASE.jsonl").splitlines(keepends=True)[:3]
        for raw, lesson in zip(lines, lessons[20 + 3*i:23 + 3*i], strict=True):
            row = json.loads(raw)
            require(parent["tree"](row["source"]) == parent["tree"](lesson["source"])
                    and row["target"] == lesson["target"], "changed historical training prefix")
            cost += len(raw)
    require(cost == receipt["readWork"], "incorrect training read charge")
    counts = defaultdict(Counter)
    for lesson in lessons:
        require(type(lesson["work"]) is int and lesson["work"] >= 0
                and (lesson["solved"] or not lesson["rules"]), "invalid primitive lesson")
        previous = -1
        for rule in lesson["rules"]:
            successor = ids.index(rule)
            counts[previous][successor] += 1
            counts[-2][successor] += 1
            previous = successor
    orders = {str(k): sorted(range(len(ids)), key=lambda i: -row[i]) for k, row in counts.items()}
    require(receipt["orders"] == orders, "model does not rank actual training transitions")
    setup = receipt["setup"]
    require(set(setup) == {"acquisition","calibration","load","preparation"}
            and all(type(v) is int and v >= 0 for v in setup.values()), "incorrect primitive-order setup")
    require(setup["acquisition"] == cost + sum(t["work"] for t in lessons) and setup["calibration"] == 0
            and setup["load"] == 1 + len(receipt["inventoryHash"]) + len(orders) * (4 + 5*len(ids))
            and setup["preparation"] > 0, "incorrect training/load bill")
    return setup


def ordering_setup(first, second, panel):
    same = parent["same_bytes"]
    report = json.loads(same(first, second, "summary.json"))
    require((report["family"], report["deadline"]) == (panel, DEADLINE), "wrong ordering panel or budget")
    raw_training = same(first.parent, second.parent, "training.json")
    require(sha(raw_training) == TRAINING, "ordering training differs from its public development receipt")
    setup = training_setup(json.loads(raw_training))
    arms = {"ORDERED": setup, "DROP_ORDER": setup, "GREEDY": dict.fromkeys(setup, 0), "SUBTREE": dict.fromkeys(setup, 0)}
    require(set(report["arms"]) == set(arms) and all(report["arms"][a]["setup"] == s for a,s in arms.items()),
            "mismatched ordering arms or setup")
    return report["arms"], {a: sum(s.values()) for a,s in arms.items()}


if __name__ == "__main__":
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("panel", choices=("MIXED", "C", "D", "E"))
    args.add_argument("seed", help="official freeze SHA for the selected panel")
    args.add_argument("official", type=Path, help="official panel directory (MIXED uses prospective-amortization/results)")
    args.add_argument("first", type=Path, help="first reference panel directory")
    args.add_argument("second", type=Path, help="second reference panel directory")
    args.add_argument("--subtree-reports", nargs=2, type=Path, metavar=("FIRST", "SECOND"),
                      help="complete standalone subtree summary from each JVM; omit for ordering runs")
    p = args.parse_args()
    print(json.dumps(verify(p.official, p.first, p.second, p.panel, p.seed, p.subtree_reports), indent=2))
