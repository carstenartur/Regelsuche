"""Reconcile independent reproductions of the two-family tactic transfer experiment."""

import argparse
import ast
from collections import Counter
from functools import lru_cache
import itertools
import json
from pathlib import Path
import runpy
import re


RESEARCH = Path(__file__).resolve().parents[1]
previous = runpy.run_path(str(RESEARCH / "prospective-amortization/verify.py"))
require, sha, read = (previous[name] for name in ("require", "sha", "read"))


def compare_completions(candidate, controls):
    """Evaluate the preregistered integer margin against the strongest control."""
    require(bool(controls), "no controls for the primary comparison")
    best = max(controls.values())
    minimum = max(best + 1, (11 * best + 9) // 10)
    return {"bestControl": best, "bestArms": sorted(arm for arm, count in controls.items() if count == best),
            "required": minimum, "passed": best > 0 and candidate >= minimum, "zeroComparator": best == 0,
            "additionalSolved": candidate - best}


def clock_rows(rows, setup, deadline):
    """Charge the full stream, including late and failed attempts; credit only verified finishes."""
    require(type(setup) is int and setup >= 0, "invalid cold-start charge")
    clock, successes = setup, []
    for row in rows:
        for key in ("selection", "query", "replay", "audit"):
            require(type(row[key]) is int and row[key] >= 0, f"invalid {key} charge")
        clock += row["selection"] + row["query"] + row["replay"] + row["audit"]
        require(row["completedAt"] == clock, "unreconciled cumulative clock")
        successes.append(row["solved"] and clock <= deadline)
    return clock, successes


FAMILIES = ("A", "B")
ARMS = ("BASE", "AUTO_OLD", "FAST_OLD", "AUTO_FRESH", "FAST_FRESH", "SHARED_FRESH", "POLICY", "NO_TACTICS")
HISTORICAL = ("BASE", "AUTO_OLD", "FAST_OLD")
DEADLINE = 1_048_576
CAPS = (256, 768, 2048, 8192)


def expected_cases(seed, family):
    """Reconstruct the two published tasks and their splits independently of the evaluator."""
    for block in range(32):
        def sort_stratum(s):
            m, orientation, cap = s
            key = f"{seed}/order/{family}/{block}/{m}/{orientation}/{cap}"
            return sha(key.encode()), m, orientation, cap

        strata = sorted(itertools.product((2, 3), (0, 1), CAPS), key=sort_stratum)
        for slot, (m, orientation, cap) in enumerate(strata):
            index = block * 16 + slot
            order = sorted(range(m), key=lambda j: (sha(f"{seed}/vars/{family}/{index}/{j}".encode()), j))
            if family == "A":
                n = 50_001 + index
                source = f"(a+{n})"
                for j in order:
                    t = "pqr"[j] if orientation == 0 else f"({'pqr'[j]}+{'stu'[j]})"
                    source = f"({source}*(1-{t})+{source}*{t})"
                bound = 3
                normal_form = {("a",): 1, (): n}
            else:
                n = 60_001 + index
                terms = []
                for j in order:
                    radius, delta = "bcd"[j], "efg"[j]
                    if j == order[0]:
                        radius = f"({radius}+{n})"
                    terms.append(f"(({radius}+{delta})*({radius}-{delta})+{delta}*{delta})")
                source = "+".join(terms if orientation == 0 else reversed(terms))
                bound = 4*m + 1
                normal_form = {(v, v): 1 for v in "bcd"[:m]}
                normal_form[()] = n*n
                normal_form[("bcd"[order[0]],)] = 2*n
            yield {"index": index, "m": m, "orientation": orientation, "cap": cap, "source": source,
                   "bound": bound, "polynomial": normal_form}


# The ordinary solver inventory is fixed before development. Re-match each reported
# step at an actual occurrence; endpoint equality alone cannot identify a named rule.
@lru_cache(maxsize=32768)
def tree(source, variables="V"):
    ops = {ast.Add: "ADD", ast.Sub: "SUB", ast.Mult: "MUL", ast.Pow: "POW"}

    def term(node):
        if isinstance(node, ast.Name):
            return variables, node.id
        if isinstance(node, ast.Constant) and type(node.value) is int:
            return "N", str(node.value)
        require(isinstance(node, ast.BinOp) and type(node.op) in ops, f"unsupported token in {source}")
        return ops[type(node.op)], term(node.left), term(node.right)

    return term(ast.parse(source.replace("^", "**"), mode="eval").body)


def freeze_tree(value):
    return tuple(freeze_tree(child) for child in value) if isinstance(value, list) else value


def match(pattern, term, bindings):
    if pattern[0] == "P":
        return bindings.setdefault(pattern[1], term) == term
    if len(pattern) != len(term) or pattern[0] != term[0]:
        return False
    if len(term) == 2:
        return pattern == term
    return match(pattern[1], term[1], bindings) and match(pattern[2], term[2], bindings)


def rewrite_at_one_occurrence(source, target, lhs, rhs):
    bindings = {}
    if match(lhs, source, bindings):
        target_bindings = dict(bindings)
        if match(rhs, target, target_bindings) and target_bindings == bindings:
            return True
    if len(source) == len(target) == 3 and source[0] == target[0]:
        return ((source[1] == target[1] and rewrite_at_one_occurrence(source[2], target[2], lhs, rhs)) or
                (source[2] == target[2] and rewrite_at_one_occurrence(source[1], target[1], lhs, rhs)))
    return False


PRIMITIVE_RULES = {
    "difference-product": ("(a+b)*(a-b)", "a^2-b^2"), "cancel-addend": ("(a-b)+b", "a"),
    "square-product": ("a*a", "a^2"), "double-term": ("a+a", "2*a"),
    "factor-left": ("a*b+a*c", "a*(b+c)"), "factor-right": ("a*c+b*c", "(a+b)*c"),
    "add-zero": ("a+0", "a"), "multiply-one": ("a*1", "a"),
}


def schema_inventory(snapshot_bytes):
    snapshot = json.loads(snapshot_bytes)
    model_json = snapshot["model"]
    by_rule = "checked-learned:sha256:" + sha(model_json.encode())
    schemas = {s["id"]: (freeze_tree(s["source"]), freeze_tree(s["target"]))
               for s in json.loads(model_json)["schemas"]}
    return by_rule, schemas


def verify_paths(paths, learned):
    primitives = {"evo_3601297f9f7d_" + rule: (tree(left, "P"), tree(right, "P"))
                  for rule, (left, right) in PRIMITIVE_RULES.items()}
    for fingerprint, steps in paths.items():
        wire = json.dumps(steps, separators=(",", ":"), ensure_ascii=False).encode()
        require(fingerprint == "sha256:" + sha(wire), "path fingerprint mismatch")
        for i, step in enumerate(steps):
            rule, schema, evidence = step["rule"], step["schema"], step["evidence"]
            if schema is None:
                require(rule in primitives and evidence is None, "unknown primitive or stray evidence")
                pattern = primitives[rule]
            else:
                require(rule in learned and schema in learned[rule], "unbound learned tactic ID")
                require(isinstance(evidence, str) and re.fullmatch(r"sha256:[a-f0-9]{64}", evidence), "missing evidence")
                pattern = learned[rule][schema]
            source, target = step["source"], step["target"]
            require(type(step["verification"]) is int and step["verification"] >= 0, "invalid step work")
            require(i == 0 or steps[i - 1]["target"] == source, "broken witness chain")
            require(rewrite_at_one_occurrence(tree(source), tree(target), *pattern),
                    f"step does not instantiate {rule} / {schema} in {fingerprint}")
            require(previous["analyze"](source)[0] == previous["analyze"](target)[0], "unequal step polynomials")


def validate_split(expected, training_sources):
    """Coefficient multisets are invariant under every bijective variable rename."""
    quadrature = runpy.run_path(str(RESEARCH / "prospective-interval-transfer/verify.py"))
    prior = RESEARCH / "prospective-distributive-coverage"
    signatures = quadrature["prior_signatures"](json.loads((prior / "freeze.json").read_bytes()))
    interval_rows = read(RESEARCH / "prospective-interval-transfer/results", "BASE.jsonl").splitlines()
    require(len(interval_rows) == 1024, "missing published quadrature cases")
    signatures.update(quadrature["signature"](json.loads(r)["source"]) for r in interval_rows)
    signatures.update(quadrature["signature"](s) for s in training_sources)
    for cases in expected.values():
        for case in cases:
            form = previous["analyze"](case["source"])[0]
            require(form == case["polynomial"], "registered source violates its generating identity")
            signature = tuple(sorted(form.values()))
            require(signature not in signatures, "new case overlaps an earlier split or FINAL case")
            signatures.add(signature)


PROTOCOL = "sha256:03d061e16071d3333ba44bc260b53a904d619f7e6cf81e7ddcdc7c79c81d1120"
OUTCOMES = {"TARGET_REACHED", "BOUNDED_EXHAUSTED", "INCONCLUSIVE", "WORK_EXHAUSTED", "STATE_LIMIT", "QUALITY_REACHED"}


def check_row(arm, row, case, paths, seen, schema_ids, model_rule):
    label = f"{arm} row {case['index']}"
    for key in ("index", "m", "orientation", "cap", "source"):
        require(row[key] == case[key], label + ": incorrect task")
    require(row["target"] == case["bound"], label + ": wrong node bound")
    path = row["pathHash"]
    require(path in paths, label + ": missing path")
    seen.add(path)
    steps = paths[path]
    source, output = row["source"], row["output"]
    if steps:
        require(tree(source) == tree(steps[0]["source"]) and steps[-1]["target"] == output, label + ": endpoints")
    else:
        require(tree(source) == tree(output), label + ": empty path changed expression")
    s, _, source_work = previous["analyze"](source)
    t, score, output_work = previous["analyze"](output)
    require(t == s == case["polynomial"] and row["score"] == score, label + ": incorrect output/score")
    require(row["audit"] == source_work + output_work, label + ": incorrect exact-audit work")
    for key in ("selection", "query", "verificationWithinQuery", "replay", "audit", "completedAt"):
        require(type(row[key]) is int and row[key] >= 0, label + ": invalid charge " + key)
    replay = sum(step["verification"] for step in steps)
    require(row["replay"] == replay and row["query"] >= row["verificationWithinQuery"] >= replay,
            label + ": incorrect verification/replay charges")
    learned = [step for step in steps if step["schema"] is not None]
    used = {step["schema"] for step in learned}
    require(all(step["rule"] == model_rule for step in learned), label + ": wrong model")
    require(row["learnedSchemas"] == sorted(used) and used <= schema_ids, label + ": learned inventory")
    require(row["accountingComplete"] is True and row["outcome"] in OUTCOMES, label + ": incomplete outcome")
    within = row["selection"] + row["query"] + replay <= case["cap"]
    require(row["withinBudget"] is within, label + ": incorrect individual budget")
    solved = within and row["outcome"] == "QUALITY_REACHED" and score <= case["bound"]
    require(row["solved"] is solved, label + ": incorrect credit")


def verify_ablations(rows, timely, cases, choices, paths, seen, fresh_schema_ids, old_ids, setup, fresh_rule):
    # Try every acquired ID actually used on each eligible case, including the
    # diagnostic trials after the race deadline. A group knockout alone is weaker.
    eligible = {}
    for i, policy in enumerate(rows["POLICY"]):
        ids = sorted(fresh_schema_ids.intersection(policy["learnedSchemas"]))
        if (policy["solved"] and ids and
                all(not rows[a][i]["solved"] for a in (*HISTORICAL, "NO_TACTICS"))):
            for schema in ids:
                eligible[i, (schema,)] = None
            if len(ids) > 1:
                eligible[i, tuple(ids)] = None
    tested = set()
    for trial in choices:
        disabled = trial["disabled"]
        require(isinstance(disabled, list) and disabled == sorted(set(disabled)), "invalid ablation set")
        index = trial["index"]
        require(trial["alsoHistorical"] == sorted(old_ids.intersection(disabled)), "incorrect historical overlap")
        key = index, tuple(disabled)
        require(key in eligible and key not in tested, "duplicate or ineligible ablation")
        row = trial["row"]
        check_row("knockout", row, cases[index], paths, seen, fresh_schema_ids - set(disabled), fresh_rule)
        clock_rows([row], setup, DEADLINE)
        eligible[key] = row["solved"]
        tested.add(key)
    require(tested == set(eligible), "missing tactic removal")
    singles = {(i, ids[0]): solved for (i, ids), solved in eligible.items() if len(ids) == 1}
    groups = {(i, ids): solved for (i, ids), solved in eligible.items() if len(ids) > 1}
    winners = [{"index": i, "schema": schema, "alsoHistorical": schema in old_ids} for (i, schema), solved in sorted(singles.items())
               if not solved and timely[i]]
    group_only = [{"index": i, "removed": list(ids)} for (i, ids), solved in sorted(groups.items())
                  if not solved and all(singles[i, schema] for schema in ids)]
    return {"timelySingleTacticWitnesses": winners, "individualRemovals": len(singles),
            "solvedAfterSingleRemoval": sum(singles.values()), "unsolvedAfterSingleRemoval": len(singles) - sum(singles.values()),
            "groupRemovals": len(groups), "onlyGroupRemovalFails": group_only}



def same_bytes(first, second, name):
    raw = read(first, name)
    require(raw == read(second, name), "opposite JVM rotations differ in " + name)
    return raw


def verify_family(first, second, family, seed, cases, inventories, receipts):
    data = lambda name: same_bytes(first / family, second / family, name)
    summary = json.loads(data("summary.json"))
    require((summary["family"], summary["freeze"], summary["protocol"], summary["primaryBudget"]) ==
            (family, seed, PROTOCOL, DEADLINE) and set(summary["arms"]) == set(ARMS), "experiment identity")
    paths = json.loads(data("paths.json"))
    old, fresh = inventories
    registry = dict((old, fresh))
    verify_paths(paths, registry)
    old_ids, fresh_ids = set(old[1]), set(fresh[1])
    seen, rows, timely, full, setup = set(), {}, {}, {}, {}
    for arm in ARMS:
        rows[arm] = [json.loads(line) for line in data(arm + ".jsonl").splitlines()]
        require(len(rows[arm]) == len(cases) == 512, "incomplete " + arm)
        report = summary["arms"][arm]
        parts = report["setup"]
        keys = {"acquisition", "calibration", "load", "preparation"}
        require(set(parts) == keys and all(type(n) is int and n >= 0 for n in parts.values()), "invalid cold setup")
        setup[arm] = sum(parts.values())
        if arm == "BASE":
            require(setup[arm] == 0, "primitive arm charged learned setup")
            schema_ids = set()
        elif arm in HISTORICAL:
            require(parts["acquisition"] == 9130 and parts["calibration"] == 0 and
                    ((arm == "FAST_OLD" and (parts["load"], parts["preparation"]) == (1620, 2100)) or
                     (arm == "AUTO_OLD" and (parts["load"], parts["preparation"]) == (3720, 0))),
                    "historical acquisition/load receipt")
            schema_ids = old_ids
        else:
            require(parts["acquisition"] == receipts["acquisition"], "fresh acquisition receipt")
            expected_calibration = receipts["calibration"] if arm in ("POLICY", "NO_TACTICS") else 0
            require(parts["calibration"] == expected_calibration, "calibration charge")
            schema_ids = set() if arm == "NO_TACTICS" else fresh_ids
        for row, case in zip(rows[arm], cases, strict=True):
            check_row(arm, row, case, paths, seen, schema_ids, old[0] if arm in HISTORICAL else fresh[0])
        total, timely[arm] = clock_rows(rows[arm], setup[arm], DEADLINE)
        full[arm] = sum(row["solved"] for row in rows[arm])
        require(report["fullCost"] == total and report["byDeadline"] == sum(timely[arm]) and
                report["totalSolved"] == full[arm], "summary disagrees with raw rows for " + arm)
    fresh_setups = {arm: summary["arms"][arm]["setup"] for arm in ARMS if arm not in HISTORICAL}
    forced = fresh_setups["FAST_FRESH"]
    auto = fresh_setups["AUTO_FRESH"]
    require(forced["load"] > 0 and all(fresh_setups[a]["load"] == forced["load"] for a in
            ("SHARED_FRESH", "POLICY", "NO_TACTICS")), "same snapshot has different loading charges")
    require(auto["preparation"] == 0 and auto["load"] == forced["load"] + forced["preparation"],
            "automatic session bill differs from model loading plus FAST preparation")
    require(summary["arms"]["POLICY"]["setup"] == summary["arms"]["NO_TACTICS"]["setup"],
            "removal changed setup")
    choices = [json.loads(line) for line in data("knockouts.jsonl").splitlines()]
    ablation = verify_ablations(rows, timely["POLICY"], cases, choices, paths, seen, fresh_ids, old_ids,
                                setup["POLICY"], fresh[0])
    require(set(paths) == seen, "unreferenced paths")
    counts = {arm: sum(timely[arm]) for arm in ARMS}
    margin = compare_completions(counts["POLICY"], {arm: count for arm, count in counts.items() if arm != "POLICY"})
    paired = {}
    for arm in ARMS:
        paired[arm] = {"newByDeadline": sum(p and not c for p, c in zip(timely["POLICY"], timely[arm])),
                       "lostByDeadline": sum(c and not p for p, c in zip(timely["POLICY"], timely[arm])),
                       "newWithinCaseBudget": sum(p["solved"] and not c["solved"] for p, c in zip(rows["POLICY"], rows[arm])),
                       "lostWithinCaseBudget": sum(c["solved"] and not p["solved"] for p, c in zip(rows["POLICY"], rows[arm])),
                       "samePath": sum(p["pathHash"] == c["pathHash"] for p, c in zip(rows["POLICY"], rows[arm]))}
    times = [json.loads(read(directory / family, "elapsed-nanos.json")) for directory in (first, second)]
    require(all(set(t) == set(ARMS) and all(type(n) is int and n > 0 for n in t.values()) for t in times),
            "missing elapsed receipts")
    work, details = {}, {}
    for arm in ARMS:
        totals = {k: sum(r[k] for r in rows[arm]) for k in ("selection", "query", "verificationWithinQuery", "replay", "audit")}
        totals["learnedSteps"] = sum(step["schema"] is not None for r in rows[arm] for step in paths[r["pathHash"]])
        totals["casesUsingLearning"] = sum(bool(r["learnedSchemas"]) for r in rows[arm])
        totals["overCap"] = sum(not r["withinBudget"] for r in rows[arm])
        work[arm] = totals
        slices = {}
        for row, counted in zip(rows[arm], timely[arm], strict=True):
            key = f"{row['m']}/{row['orientation']}/{row['cap']}"
            part = slices.setdefault(key, Counter())
            part.update(attempts=1, byDeadline=int(counted), solved=int(row["solved"]),
                        work=row["selection"] + row["query"] + row["replay"] + row["audit"])
        details[arm] = slices
    return {"counts": counts, "work": work, "strata": details, "margin": margin, "primaryPassed": bool(margin["passed"] and ablation["timelySingleTacticWitnesses"]),
            "full": summary["arms"], "paired": paired, "ablation": ablation, "uniquePaths": len(seen), "elapsedNanos": times}


def check_provenance(schema, patterns, lessons):
    supported = set()
    for origin in schema["supportingObservationIds"]:
        parsed = re.fullmatch(r"task-(\d+)@sha256:[0-9a-f]{64}:(.*)", origin)
        require(parsed is not None, "unbound training origin")
        lesson, location = int(parsed[1]), parsed[2]
        require(0 <= lesson < len(lessons), "training lesson index")
        witness = lessons[lesson]["witness"]
        full = re.fullmatch(r"(\d+)-(\d+)", location)
        if full:
            lo, hi, path = int(full[1]), int(full[2]) - 1, ""
        else:
            window = re.fullmatch(r"([LR]*)\[((?:\d+,)+)\]", location)
            require(window is not None, "unexpected subpath origin")
            indices = list(map(int, window[2].strip(",").split(",")))
            require(indices == sorted(set(indices)), "unordered step origins")
            lo, hi, path = indices[0], indices[-1], window[1]
        require(0 <= lo <= hi < len(witness), "invalid training window")
        source, target = tree(witness[lo]["source"]), tree(witness[hi]["target"])
        for side in path:
            pos = 1 if side == "L" else 2
            require(len(source) == len(target) == 3, "unreachable origin")
            source, target = source[pos], target[pos]
        require(rewrite_at_one_occurrence(source, target, *patterns), "schema does not explain its training observation")
        supported.add(lesson)
    require(len(supported) >= 2, "independent training traces missing")


def materials(study):
    historical = RESEARCH / "prospective-distributive-coverage"
    earlier = (historical / "snapshot.json").read_bytes()
    require("sha256:" + sha(earlier) == previous["MODEL"], "incorrect historical model")
    old = schema_inventory(earlier)
    training = study / "training"
    manifest = json.loads((training / "training.json").read_bytes())
    snapshot_bytes = (training / "snapshot.json").read_bytes()
    snapshot = json.loads(snapshot_bytes)
    parsed_model = json.loads(snapshot["model"])
    require(manifest["protocol"] == PROTOCOL and manifest["snapshot"] == "sha256:" + sha(snapshot_bytes),
            "unbound acquisition")
    require(manifest["inventory"] == parsed_model["inventoryHash"] ==
            json.loads(json.loads(earlier)["model"])["inventoryHash"], "different primitive inventory")
    fresh = schema_inventory(snapshot_bytes)
    lessons, gates, calibration = (manifest[k] for k in ("training", "gate", "calibration"))
    sources = [t["task"]["source"] for t in lessons + gates] + [t["source"] for t in calibration]
    costs = manifest["acquisition"]
    require(snapshot["costs"] == costs and all(type(v) is int and v >= 0 for v in costs.values()),
            "acquisition bill mismatch")
    require(costs["lessons"] == sum(r["work"] for r in lessons) and
            costs["validation"] == sum(t[k] for t in gates for k in ("base", "previous", "candidate", "routed")),
            "training or gate attempt omitted")
    require(costs["formation"] == parsed_model["formationWork"], "formation charge mismatch")
    for lesson in lessons:
        witness = lesson["witness"]
        raw = json.dumps(witness, separators=(",", ":"), ensure_ascii=False).encode()
        verify_paths({"sha256:" + sha(raw): witness}, {})
        source, out = lesson["task"]["source"], lesson["output"]
        if witness:
            require(tree(source) == tree(witness[0]["source"]) and out == witness[-1]["target"], "wrong lesson endpoints")
        else:
            require(tree(source) == tree(out), "empty training path changes expression")
    for schema in parsed_model["schemas"]:
        check_provenance(schema, fresh[1][schema["id"]], lessons)
    fit = json.loads((training / "fit.json").read_bytes())
    require(re.fullmatch(r"sha256:[0-9a-f]{64}", fit["fingerprint"]), "missing policy fingerprint")
    groups = {}
    actions = set(fresh[1]) | {"all-proved-schemata"}
    trial_work = 0
    for trial in fit["trials"]:
        key = trial["lesson"], trial["suffix"]
        require(0 <= key[0] < len(calibration) and 0 <= key[1] <= 2, "invalid calibration origin")
        baseline = trial["baselineSolved"], trial["baselineWork"]
        if key not in groups:
            groups[key] = baseline, set()
            trial_work += trial["baselineWork"]
        known, seen = groups[key]
        require(known == baseline and trial["schemaId"] in actions and trial["schemaId"] not in seen,
                "inconsistent or duplicate calibration trial")
        seen.add(trial["schemaId"])
        require(type(trial["candidateWork"]) is int and trial["candidateWork"] >= 0, "invalid calibration work")
        trial_work += trial["candidateWork"]
    require(all((i, 0) in groups for i in range(len(calibration))) and
            all(ids == actions for _, ids in groups.values()), "incomplete calibration actions")
    require(type(fit["work"]) is int and fit["work"] >= trial_work, "calibration omitted paid trials")
    return (old, fresh), {"acquisition": sum(costs.values()), "calibration": fit["work"]}, sources


def verify(first, second, seed):
    require(first.resolve() != second.resolve(), "provide two fresh JVM outputs")
    require(re.fullmatch(r"[0-9a-f]{40}", seed), "expected the full published freeze SHA")
    study = RESEARCH / "prospective-tactic-transfer"
    require("sha256:" + sha((study / "protocol.md").read_bytes()) == PROTOCOL, "preregistration digest mismatch")
    inventories, receipts, training_sources = materials(study)
    expected = {family: list(expected_cases(seed, family)) for family in FAMILIES}
    validate_split(expected, training_sources)
    outcomes = {family: verify_family(first, second, family, seed, expected[family], inventories, receipts)
                for family in FAMILIES}
    for arm in ARMS:
        require(outcomes["A"]["full"][arm]["setup"] == outcomes["B"]["full"][arm]["setup"],
                "different cold startup across families")
    return {"freeze": seed, "protocol": PROTOCOL, "families": outcomes,
            "passed": all(s["primaryPassed"] for s in outcomes.values())}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("forward", type=Path)
    parser.add_argument("reverse", type=Path)
    parser.add_argument("freeze_sha")
    args = parser.parse_args()
    print(json.dumps(verify(args.forward, args.reverse, args.freeze_sha), indent=2))
