"""Independently reconcile two amortization runs against the published protocol."""

import argparse
import ast
import collections
import functools
import gzip
import hashlib
import itertools
import json
from pathlib import Path
import re


ARMS = ("BASE", "PRIOR", "REGEN", "DROP")
TOPOLOGIES = ("H", "D")
DEADLINE = 524_288
PROTOCOL = "sha256:2f6d5c3be42d8f291f3facac132cd749c9b5b246a257af32aafad52840c81702"
MODEL = "sha256:9e56f3640b85520b8b4090f64aa96c14bb703d3d218ab6fd60c2cdb47e74e223"
# Acquisition from the original freeze, load and compile receipts from the archived
# first Horner run. The follow-up freezes the same solver, inventory, and snapshot.
SETUP = {"BASE": (0, 0, 0), "PRIOR": (9130, 1620, 2055),
         "REGEN": (9130, 1620, 2078), "DROP": (9130, 1620, 2078)}


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(directory, name):
    path = directory / name
    if path.is_file():
        return path.read_bytes()
    return gzip.decompress((directory / (name + ".gz")).read_bytes())


def expected_cases(seed):
    """Reconstruct the registered order, expansion positions and source syntax."""
    for block in range(128):
        def key(pair):
            topology, count = pair
            return sha(f"{seed}/order/{block}/{topology}/{count}".encode()), topology, count
        for slot, (topology, count) in enumerate(sorted(itertools.product(TOPOLOGIES, range(4)), key=key)):
            index = 8 * block + slot
            positions = sorted(range(3), key=lambda i: (sha(f"{seed}/position/{index}/{i}".encode()), i))
            coefficients = []
            for pos, (u, v, p, q) in enumerate([
                (f"(a+{10001 + index})", "b", "c", "d"), ("e", "f", "g", "h"), ("i", "j", "k", "l")
            ]):
                if pos in positions[:count]:
                    expr = f"({u}*(({v}-{p})-{q})+{u}*{q})+{u}*{p}"
                elif count == 0 and pos == positions[0]:
                    expr = f"({u}*{v})+0"
                else:
                    expr = f"{u}*{v}"
                coefficients.append(f"({expr})")
            a, b, c = coefficients
            source = f"({a}*r+{b})*s+{c}" if topology == "H" else f"({a}*r+{b}*s)+{c}*t"
            yield topology, count, source


def multiply(left, right):
    result = collections.Counter()
    for a, ca in left.items():
        for b, cb in right.items():
            result[tuple(sorted(a + b))] += ca * cb
    return {term: c for term, c in result.items() if c}


def polynomial(node):
    """Return exact integer polynomial, expression nodes, and Java's projection work."""
    if isinstance(node, ast.Name):
        require(re.fullmatch("[a-z]", node.id), "unexpected variable")
        return {(node.id,): 1}, 1, 2
    if isinstance(node, ast.Constant) and type(node.value) is int:
        result = {(): node.value} if node.value else {}
        return result, 1, 1 + len(result)
    require(isinstance(node, ast.BinOp), f"unsupported expression node {node}")
    left, size, work = polynomial(node.left)
    if isinstance(node.op, ast.Pow):
        require(isinstance(node.right, ast.Constant) and type(node.right.value) is int
                and 0 <= node.right.value <= 32, "unsupported exponent")
        result, power = {(): 1}, node.right.value
        work += 3  # one binary node, exponent node, initial constant term
        factor = left
        while power:
            if power & 1:
                products = len(result) * len(factor)
                result = multiply(result, factor)
                work += products + len(result)
            power >>= 1
            if power:
                products = len(factor) ** 2
                factor = multiply(factor, factor)
                work += products + len(factor)
        return result, size + 2, work
    right, rsize, rwork = polynomial(node.right)
    if isinstance(node.op, ast.Mult):
        result = multiply(left, right)
        work += len(left) * len(right)
    else:
        require(isinstance(node.op, (ast.Add, ast.Sub)), "operator outside the fixed inventory")
        sign = -1 if isinstance(node.op, ast.Sub) else 1
        result = left.copy()
        for term, coeff in right.items():
            result[term] = result.get(term, 0) + sign * coeff
            if result[term] == 0:
                del result[term]
    return result, size + rsize + 1, work + rwork + 1 + len(result)


@functools.lru_cache(maxsize=32768)
def analyze(expression):
    return polynomial(ast.parse(expression.replace("^", "**"), mode="eval").body)


def check_paths(paths):
    for fingerprint, steps in paths.items():
        content = json.dumps(steps, ensure_ascii=False, separators=(",", ":")).encode()
        require(fingerprint == "sha256:" + sha(content), f"incorrect path hash {fingerprint}")
        for i, step in enumerate(steps):
            require(0 <= step["verification"] and step["rule"], f"{fingerprint}: verification receipt")
            if step["learnedEvidence"] is not None:
                require(re.fullmatch(r"sha256:[0-9a-f]{64}", step["learnedEvidence"]), "bad learned evidence hash")
            require(step["rule"].startswith("checked-learned:") == (step["learnedEvidence"] is not None),
                    f"{fingerprint}: incoherent learned evidence")
            if i:
                require(steps[i - 1]["target"] == step["source"], f"{fingerprint}: broken chain")
            require(analyze(step["source"])[0] == analyze(step["target"])[0], f"{fingerprint}: unequal polynomials")


def check_arm(arm, record, rows, expected, paths, used):
    setup = tuple(record[key] for key in ("acquisition", "load", "preparation"))
    require(setup == SETUP[arm], f"{arm}: setup receipt {setup}")
    clock = sum(setup)
    require(len(rows) == len(expected) == 1024, f"{arm}: incomplete ledger")
    totals = {top: collections.Counter() for top in TOPOLOGIES}
    earned = set()
    for i, (row, (top, count, source)) in enumerate(zip(rows, expected, strict=True)):
        label = f"{arm} row {i}"
        require((row["index"], row["distributed"], row["source"]) == (i, count, source), label + ": wrong source")
        require(row["pathHash"] in paths, label + ": missing witness")
        used.add(row["pathHash"])
        steps = paths[row["pathHash"]]
        previous, input_size, input_audit = analyze(source)
        output, score, output_audit = analyze(row["output"])
        require(previous == output, label + ": output polynomial")
        require(row["audit"] == input_audit + output_audit and row["score"] == score,
                f"{label}: node or exact-product audit count")
        if steps:
            start, start_size, _ = analyze(steps[0]["source"])
            require(start == previous and start_size == input_size and steps[-1]["target"] == row["output"],
                    label + ": witness endpoints")
        else:
            require(input_size == score, label + ": empty witness changed the expression")
        learned = sum(step["learnedEvidence"] is not None for step in steps)
        require(row["learnedSteps"] == learned and (arm in ("PRIOR", "REGEN") or learned == 0),
                label + ": learned witness")
        replay = sum(step["verification"] for step in steps)
        require(row["replay"] == replay and row["query"] >= row["verificationWithinQuery"] >= replay,
                label + ": replay or verification ledger")
        require(row["withinQueryBudget"] == (row["query"] + replay <= 8192), label + ": budget")
        solved = row["withinQueryBudget"] and row["outcome"] == "QUALITY_REACHED" and score <= {"H": 17, "D": 19}[top]
        require(row["solved"] == solved, label + ": solver completion")
        clock += row["query"] + replay + row["audit"]
        require(row["completedAt"] == clock, label + ": cumulative clock")
        on_time = clock <= DEADLINE
        if solved and on_time:
            earned.add(i)
        totals[top].update(attempts=1, fullSolved=int(solved), completedAtDeadline=int(on_time),
                           solvedAtDeadline=int(solved and on_time), query=row["query"],
                           verificationWithinQuery=row["verificationWithinQuery"], replay=replay,
                           audit=row["audit"], learnedSteps=learned, overBudget=int(not row["withinQueryBudget"]))
    for part in totals.values():
        require(part["attempts"] == 512, f"{arm}: unbalanced topology")
        part["streamWork"] = part["query"] + part["replay"] + part["audit"]
    require(record["fullCost"] == clock and record["totalSolved"] == sum(p["fullSolved"] for p in totals.values()),
            f"{arm}: full stream result")
    require(record["checkpoints"] == [{"budget": DEADLINE, "completed": sum(p["completedAtDeadline"] for p in totals.values()),
                                       "solved": len(earned)}], f"{arm}: primary checkpoint")
    return totals, earned


def verify(first, second, seed):
    require(re.fullmatch("[0-9a-f]{40}", seed), "provide the full first published freeze SHA")
    data = {}
    for name in ("summary.json", "topologies.json", "paths.json", *(arm + ".jsonl" for arm in ARMS)):
        raw = read(first, name)
        require(raw == read(second, name), f"independent JVM outputs differ in {name}")
        data[name] = [json.loads(line) for line in raw.splitlines()] if name.endswith(".jsonl") else json.loads(raw)
    summary = data["summary.json"]
    require((summary["freeze"], summary["primaryBudget"], summary["protocol"], summary["model"])
            == (seed, DEADLINE, PROTOCOL, MODEL), "incorrect freeze or protocol identity")
    require(set(summary["arms"]) == set(ARMS), "incomplete arm set")
    paths = data["paths.json"]
    check_paths(paths)
    expected = list(expected_cases(seed))
    # Each exact coefficient multiset identifies this new stream member. Normal
    # forms of all training, gate and previous Horner cases have no coefficient
    # in this new range (the first stream's constants were 101..228; TRAIN/GATE <= 11).
    for i, (_, _, source) in enumerate(expected):
        coeffs = sorted(analyze(source)[0].values())
        require(coeffs == [1, 1, 1, 10001 + i], f"source {i}: split signature")
    used, per_arm, earned = set(), {}, {}
    for arm in ARMS:
        per_arm[arm], earned[arm] = check_arm(arm, summary["arms"][arm], data[arm + ".jsonl"], expected, paths, used)
    require(used == set(paths), "unreferenced paths")
    reported = {(entry["arm"], entry["topology"]): entry for entry in data["topologies.json"]}
    require(len(data["topologies.json"]) == len(reported) == 8, "missing/duplicate topology records")
    for arm in ARMS:
        for top in TOPOLOGIES:
            indices = {i for i, (t, _, _) in enumerate(expected) if t == top}
            part = per_arm[arm][top]
            part["regenWins"] = len(indices & (earned["REGEN"] - earned[arm]))
            part["regenLosses"] = len(indices & (earned[arm] - earned["REGEN"]))
            keys = ("attempts", "fullSolved", "solvedAtDeadline", "streamWork", "regenWins", "regenLosses")
            require(reported[arm, top] == {"topology": top, "arm": arm, **{key: part[key] for key in keys}},
                    f"{arm}/{top}: topology comparison")
    counts = {arm: len(earned[arm]) for arm in ARMS}
    passed = counts["REGEN"] > max(counts["BASE"], counts["DROP"])
    improved = counts["REGEN"] > counts["PRIOR"]
    require((summary["passed"], summary["improvedOnPriorAtPrimary"]) == (passed, improved), "incorrect primary decisions")
    times = [json.loads(read(directory, "elapsed-nanos.json")) for directory in (first, second)]
    require(all(set(t) == set(ARMS) and all(type(v) is int and v > 0 for v in t.values()) for t in times), "timing receipt")
    return {"freeze": seed, "deadline": DEADLINE, "witnesses": len(paths), "primaryCounts": counts, "passed": passed,
            "improvedOnPriorAtPrimary": improved, "full": {a: summary["arms"][a] for a in ARMS},
            "topologies": per_arm, "elapsedNanos": times}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("forward", type=Path)
    parser.add_argument("reverse", type=Path)
    parser.add_argument("freeze_sha")
    args = parser.parse_args()
    print(json.dumps(verify(args.forward, args.reverse, args.freeze_sha), indent=2))
