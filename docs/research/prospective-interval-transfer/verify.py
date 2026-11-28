"""Check both fresh JVM ledgers against the published rectangle-rule protocol."""

import argparse
import ast
from collections import Counter
from fractions import Fraction
from functools import lru_cache
import itertools
import json
from pathlib import Path
import re
import runpy


RESEARCH = Path(__file__).resolve().parents[1]
previous = runpy.run_path(str(RESEARCH / "prospective-amortization/verify.py"))
analyze, check_paths, read, require, sha = (previous[f] for f in ("analyze", "check_paths", "read", "require", "sha"))
PROTOCOL = "sha256:e2ed7fb16070265c7a2e2e838ed0933448a14a0662be0f46640c854e8bacd6f4"
ARMS = ("BASE", "REGEN", "DROP", "FAST", "FAST_DROP")
STRATA = tuple(itertools.product((2, 3), ("L", "R")))
DEADLINE = 262_144
OUTCOMES = {"TARGET_REACHED", "BOUNDED_EXHAUSTED", "INCONCLUSIVE", "WORK_EXHAUSTED", "STATE_LIMIT", "QUALITY_REACHED"}
PRIMITIVES = {"evo_3601297f9f7d_" + name for name in (
    "difference-product", "cancel-addend", "square-product", "double-term", "factor-left",
    "factor-right", "add-zero", "multiply-one")}


def digest(data):
    return "sha256:" + sha(data)


def expected_cases(seed):
    for block in range(256):
        def stratum_key(pair):
            cells, orientation = pair
            return sha(f"{seed}/order/{block}/{cells}/{orientation}".encode()), cells, orientation

        for slot, (cells, orientation) in enumerate(sorted(STRATA, key=stratum_key)):
            index = 4 * block + slot
            order = sorted(range(cells + 1), key=lambda j: (sha(f"{seed}/names/{index}/{j}".encode()), j))
            endpoints = ["pqrs"[j] for j in order]
            height = f"(a+{30_001 + index})"
            widths = [f"({end}-{start})" for start, end in zip(endpoints, endpoints[1:])]
            terms = [f"{height}*{width}" if orientation == "L" else f"{width}*{height}" for width in widths]
            yield cells, orientation, "+".join(terms), height + "*" + f"({endpoints[-1]}-{endpoints[0]})"


def signature(source):
    return tuple(sorted(analyze(source)[0].values()))


def prior_signatures(receipt):
    historical = set()
    for group, count in (("training", 2), ("gate", 3)):
        require(len(receipt[group]) == count, "incomplete acquisition split")
        historical.update(signature(row["source"]) for row in receipt[group])
    for study, count in (("prospective-horner-transfer", 128), ("prospective-amortization", 1024)):
        rows = read(RESEARCH / study / "results", "BASE.jsonl").splitlines()
        require(len(rows) == count, "incomplete earlier panel " + study)
        historical.update(signature(json.loads(row)["source"]) for row in rows)
    # Exact normal forms for the six families in the original distributive study.
    # The mirrored case has a literal nonzero divisor. Its signature is rational.
    for n in (2, 3, 5, 7):
        for coefficients in ((1, n, -1), (2, 2*n), (1, 2*n, n*n), (Fraction(1, n), 1), (1, n), (1, n)):
            historical.add(tuple(sorted(coefficients)))
    return historical


def validate_split(expected, historical):
    seen = set()
    for i, (_, _, source, reduced) in enumerate(expected):
        poly = analyze(source)[0]
        require(poly == analyze(reduced)[0], f"source {i}: interval endpoints do not telescope")
        sig = tuple(sorted(poly.values()))
        n = 30_001 + i
        require(sig == (-n, -1, 1, n) and sig not in seen and sig not in historical, f"source {i}: invalid split")
        seen.add(sig)


@lru_cache(maxsize=16384)
def structure(source):
    return ast.dump(ast.parse(source.replace("^", "**"), mode="eval").body)


def integer(value):
    return type(value) is int and value >= 0


def check_arm(arm, report, rows, expected, paths, used):
    setup = tuple(report[key] for key in ("acquisition", "load", "preparation"))
    require(all(integer(v) for v in setup), f"{arm}: invalid setup work")
    if arm in ("BASE", "REGEN", "DROP"):
        require(setup == previous["SETUP"][arm], f"{arm}: frozen setup has changed")
    else:
        require(setup[:2] == previous["SETUP"]["REGEN"][:2] and setup[2] > 0, f"{arm}: missing learned setup")
    clock = sum(setup)
    require(len(rows) == len(expected) == 1024, f"{arm}: incomplete stream")
    totals = {s: Counter() for s in STRATA}
    earned = set()
    for i, (row, (cells, orientation, source, _)) in enumerate(zip(rows, expected, strict=True)):
        label = f"{arm} row {i}"
        target = 4*cells + 3
        require((row["index"], row["cells"], row["orientation"], row["nodeBound"], row["source"]) ==
                (i, cells, orientation, target, source), label + ": unregistered task")
        fingerprint = row["pathHash"]
        require(fingerprint in paths, label + ": missing path")
        used.add(fingerprint)
        steps = paths[fingerprint]
        output = row["output"]
        if steps:
            require(structure(source) == structure(steps[0]["source"]) and steps[-1]["target"] == output,
                    label + ": path endpoints")
        else:
            require(structure(source) == structure(output), label + ": empty path changed expression")
        source_poly, _, input_audit = analyze(source)
        output_poly, score, output_audit = analyze(output)
        audit = input_audit + output_audit
        require(source_poly == output_poly and score == row["score"] and audit == row["audit"],
                label + ": polynomial, node count or projection charge")
        learned = sum(step["learnedEvidence"] is not None for step in steps)
        require(row["learnedSteps"] == learned and (arm in ("REGEN", "FAST") or learned == 0),
                label + ": learned evidence in wrong arm or miscounted")
        replay = sum(step["verification"] for step in steps)
        query, verification = row["query"], row["verificationWithinQuery"]
        require(integer(query) and integer(verification) and query >= verification >= replay and
                replay == row["replay"], label + ": query/verification/replay charges")
        within = query + replay <= 8192
        require(row["outcome"] in OUTCOMES and row["withinQueryBudget"] is within, label + ": query eligibility")
        solved = within and row["outcome"] == "QUALITY_REACHED" and score <= target
        require(row["solved"] is solved, label + ": incorrect solver credit")
        clock += query + replay + audit  # failed and late attempts cost actual work as well
        require(clock == row["completedAt"], label + ": incorrect cumulative clock")
        on_time = clock <= DEADLINE
        if solved and on_time:
            earned.add(i)
        totals[cells, orientation].update(attempts=1, completedAtDeadline=int(on_time), solvedAtDeadline=int(solved and on_time),
            fullSolved=int(solved), learnedSteps=learned, query=query, verificationWithinQuery=verification,
            replay=replay, audit=audit, streamWork=query + replay + audit, overBudget=int(not within))
    completed = sum(s["completedAtDeadline"] for s in totals.values())
    require(all(s["attempts"] == 256 for s in totals.values()), f"{arm}: unbalanced stream")
    require(report["checkpoints"] == [{"budget": DEADLINE, "completed": completed, "solved": len(earned)}] and
            report["totalSolved"] == sum(s["fullSolved"] for s in totals.values()) and report["fullCost"] == clock,
            f"{arm}: unreconciled summary")
    return totals, earned


def verify(first, second, seed):
    require(first.resolve() != second.resolve(), "provide outputs from two fresh JVMs")
    require(re.fullmatch(r"[a-f0-9]{40}", seed), "expected the original full freeze SHA")
    require(digest((RESEARCH / "prospective-interval-transfer/protocol.md").read_bytes()) == PROTOCOL,
            "protocol differs from publication")
    prior = RESEARCH / "prospective-distributive-coverage"
    model = (prior / "snapshot.json").read_bytes()
    receipt_bytes = (prior / "freeze.json").read_bytes()
    require(digest(model) == previous["MODEL"] and digest(receipt_bytes) ==
            "sha256:6863b403ed13292dd4aefde8603ba83b1f7ff221ee5dab091a7f019108947930", "model/acquisition mismatch")
    receipt = json.loads(receipt_bytes)
    require(receipt["modelSnapshot"] == digest(model) and sum(receipt["acquisition"].values()) ==
            previous["SETUP"]["REGEN"][0], "incorrect acquisition receipt")
    allowed_rules = PRIMITIVES | {"checked-learned:" + digest(json.loads(model)["model"].encode())}
    expected = list(expected_cases(seed))
    validate_split(expected, prior_signatures(receipt))

    def identical(name):
        raw = read(first, name)
        require(raw == read(second, name), "independent JVMs differ in " + name)
        return raw

    summary = json.loads(identical("summary.json"))
    require((summary["freeze"], summary["protocol"], summary["model"], summary["primaryBudget"]) ==
            (seed, PROTOCOL, digest(model), DEADLINE) and set(summary["arms"]) == set(ARMS), "incorrect experiment")
    paths = json.loads(identical("paths.json"))
    check_paths(paths)
    require(all(integer(step["verification"]) and step["rule"] in allowed_rules
                for steps in paths.values() for step in steps), "path uses unknown rule or verification charge")
    used, strata, earned = set(), {}, {}
    for arm in ARMS:
        rows = [json.loads(line) for line in identical(arm + ".jsonl").splitlines()]
        strata[arm], earned[arm] = check_arm(arm, summary["arms"][arm], rows, expected, paths, used)
    require(used == set(paths), "unreferenced paths")
    require(summary["arms"]["FAST"]["preparation"] == summary["arms"]["FAST_DROP"]["preparation"],
            "ablation has different preparation")
    counts = {arm: len(earned[arm]) for arm in ARMS}
    decisions = {
        "transferPassed": counts["REGEN"] > max(counts["BASE"], counts["DROP"]),
        "optimizedTransferPassed": counts["FAST"] > max(counts["BASE"], counts["REGEN"], counts["FAST_DROP"]),
        "improvedOnRegenAtPrimary": counts["FAST"] > counts["REGEN"],
    }
    require(all(summary[name] is passed for name, passed in decisions.items()), "primary decision mismatch")
    for stratum in STRATA:
        indices = {i for i, case in enumerate(expected) if case[:2] == stratum}
        for arm in ARMS:
            for candidate in ("REGEN", "FAST"):
                strata[arm][stratum][candidate.lower() + "Wins"] = len(indices & (earned[candidate] - earned[arm]))
                strata[arm][stratum][candidate.lower() + "Losses"] = len(indices & (earned[arm] - earned[candidate]))
    elapsed = [json.loads(read(path, "elapsed-nanos.json")) for path in (first, second)]
    require(all(set(times) == set(ARMS) and all(integer(t) and t > 0 for t in times.values()) for times in elapsed),
            "missing elapsed observations")
    return {"freeze": seed, "deadline": DEADLINE, "uniquePaths": len(paths), "primaryCounts": counts, **decisions,
            "full": summary["arms"], "strata": {arm: {f"{m}{lr}": tally for (m, lr), tally in parts.items()}
                                                 for arm, parts in strata.items()}, "elapsedNanos": elapsed}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("forward", type=Path)
    parser.add_argument("reverse", type=Path)
    parser.add_argument("freeze_sha")
    args = parser.parse_args()
    print(json.dumps(verify(args.forward, args.reverse, args.freeze_sha), indent=2))
