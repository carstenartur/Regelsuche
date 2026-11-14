#!/usr/bin/env python3
"""Check open macro input projection, selection schedules, freeze hashes and paired outcomes."""

import ast
import gzip
import hashlib
import json
from pathlib import Path

DATA = Path(__file__).resolve().parents[1] / "research/benchmarks/candidate-independent"
DIR = DATA / "track-c-open-macros"
PLAN_HASH = "sha256:b5780d13f2278b3eb91b94e4166f312f2ecb71acadda96aba49087cb7d0c7694"
COMMIT = "1fa021ac7f17bcf7886f761b0cd2c37bce9fd26f"


def canonical(val):
    return json.dumps(val, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def digest(raw):
    return "sha256:" + hashlib.sha256(raw).hexdigest()


def check(condition, label):
    if not condition:
        raise ValueError(label)


def verify_hashes(value):
    if isinstance(value, list):
        for row in value:
            verify_hashes(row)
    if isinstance(value, dict):
        if "contentHash" in value:
            check(value["contentHash"] == digest(canonical({k: v for k, v in value.items() if k != "contentHash"})),
                  "incorrect content commitment")
        for entry in value.values():
            verify_hashes(entry)


def read_frozen(name, expected):
    doc = json.loads((DATA / (name + ".json")).read_bytes())
    check(doc["contentHash"] == expected, name)
    verify_hashes(doc)
    return doc


class JavaRandom:
    def __init__(self, seed):
        self.state = (int(seed[7:23], 16) ^ 0x5DEECE66D) % 2**48

    def index(self, size):
        while True:
            self.state = (self.state * 0x5DEECE66D + 11) % 2**48
            bits = self.state >> 17
            if size & (size - 1) == 0:
                return (size * bits) >> 31
            index = bits % size
            if bits - index + size - 1 < 2**31:
                return index


def choose(arm, choices, rng):
    if not choices:
        return None
    if arm == "PRODUCTION_SCORE":
        return min(choices, key=lambda row: (row["endpointScore"], row["key"]))
    if arm == "ENUMERATE_PRIMITIVE_PATHS":
        return choices[0]
    check(arm == "SEEDED_RANDOM_PRIMITIVE_PATHS", "unknown selection policy")
    return choices[rng.index(len(choices))]


def scalar_syntax(text):
    """Normalize commutative child order for the public replay expressions."""
    def node(expr):
        if isinstance(expr, ast.Name):
            return ("symbol", expr.id)
        if isinstance(expr, ast.Constant) and type(expr.value) is int:
            return ("integer", expr.value)
        if isinstance(expr, ast.BinOp):
            left, right = node(expr.left), node(expr.right)
            operator = type(expr.op).__name__
            if isinstance(expr.op, (ast.Add, ast.Mult)):
                left, right = sorted((left, right))
            return (operator, left, right)
        raise ValueError("unsupported expression in public scalar syntax replay")
    return node(ast.parse(text.replace("^", "**"), mode="eval").body)


def verify_formation(run, challenge, universes):
    idx, arm, formation = run["index"], run["arm"], run["formation"]
    seed = digest(canonical(dict(campaignId=f"{challenge}-campaign-{idx:02}", challengeId=challenge, index=idx)))
    check(seed == run["seed"] and digest(canonical(formation)) == run["formationHash"], "seed or formation root")
    random = JavaRandom(seed)
    groups = {}
    selections = []
    selected = {}
    for i, inv in enumerate(universes):
        chosen = choose(arm, inv["choices"], random)
        selections.append(dict(startIndex=i, chosenKey=chosen["key"] if chosen else None))
        if chosen:
            trace = f"open-path-{i+1}"
            groups.setdefault(tuple(chosen["operations"]), []).append(trace)
            selected[trace] = (inv["start"]["source"], chosen["endpoints"][-1])
    check(formation["selections"] == selections, "replay of schedule")
    check([tuple(group["operations"]) for group in formation["groups"]] == list(groups), "group order")
    accepted = {}
    for group in formation["groups"]:
        operations, result = tuple(group["operations"]), group["formation"]
        replays = result["replayEvidence"]
        check(sorted(groups[operations]) == [r["traceId"] for r in replays], "source commitments")
        for row in replays:
            source, endpoint = selected[row["traceId"]]
            if row["reproduced"]:
                check(row["expressionPath"][0] == source and scalar_syntax(row["expressionPath"][-1]) == scalar_syntax(endpoint),
                      "replay did not end at selected open path")
                check(tuple(row["compressedOperationIds"]) == operations, "operation sequence")
        if result["status"] == "SELECTED":
            for macro in result["macros"]:
                check(tuple(macro["operationSequence"]) == operations, "generalizer operation binding")
                accepted.setdefault(macro["macroId"], macro)
    actual = {m["macroId"]: m for m in formation["accepted"]["macros"]}
    check(actual == accepted, "post-formation candidate substitution")
    return set(accepted)


def outcome(base, macro, accepted):
    if not accepted:
        return "CANDIDATE_NOT_FORMED"
    if base["success"] and not macro["success"]:
        return "CORRECTNESS_REGRESSION"
    if macro["success"] and not base["success"]:
        return "REACHABILITY_GAIN"
    if not base["success"]:
        return "NO_RESULT"
    if macro["expandedStates"] < base["expandedStates"] or len(macro["ruleIds"]) < len(base["ruleIds"]):
        return "IMPROVED"
    return "NO_IMPROVEMENT"


def main():
    plan_bytes = (DIR / "comparison-plan.json").read_bytes()
    check(digest(plan_bytes) == PLAN_HASH, "preregistered plan")
    plan = json.loads(plan_bytes)
    corpus = read_frozen("case-corpus", plan["caseCorpusContentHash"])
    profile = read_frozen("macro-primitives", plan["primitiveProfileContentHash"])
    read_frozen("corpus-freeze-receipt", plan["freezeReceiptContentHash"])
    cases = [r for r in corpus["cases"] if r["challengeId"] == plan["challengeId"]]
    starts = [dict(source=t["source"], assumptions=t["assumptions"])
              for r in cases if r["split"] == "TRAIN" for t in r["formationInput"]["replayTraces"]]
    raw = gzip.decompress((DIR / "comparison-result.json.gz").read_bytes())
    data = json.loads(raw)
    check(canonical(data)+b"\n" == raw, "canonical raw archive")
    verify_hashes(data)
    check((data["planCommit"], data["planByteHash"], data["corpusHash"]) ==
          (COMMIT, PLAN_HASH, corpus["contentHash"]), "plan/corpus binding")
    universes = data["pathUniverses"]
    check(len(starts) == 4 and starts == [u["start"] for u in universes], "unexpected formation input")
    reverse = {}
    for operation in sorted(profile["operations"], key=lambda op: op["operationId"]):
        for rule in operation["implementationRuleIds"]:
            reverse.setdefault(rule, operation["operationId"])
    for inv in universes:
        check(1 <= inv["expanded"] <= 13 and inv["generated"] >= len(inv["choices"]), "primitive-work budget")
        keys = []
        for choice in inv["choices"]:
            check(len(choice["rules"]) == len(choice["endpoints"]) == 2, "not two steps")
            ops = [reverse[rule] for rule in choice["rules"]]
            check(ops == choice["operations"] and ops[0] != ops[1], "shared grammar")
            key = canonical([choice["rules"], choice["endpoints"]]).decode()
            check(key == choice["key"], "wrong path identity")
            keys.append(key)
        check(sorted(set(keys)) == keys, "path enumeration order")

    matrix = [(index, a) for index in range(1, plan["campaigns"]+1) for a in plan["arms"]]
    check([(r["index"], r["arm"]) for r in data["campaigns"]] == matrix, "campaign matrix")
    tasks = {t["taskId"]: (r, t) for r in cases for t in r["evaluationInput"]["tasks"]}
    check(len(tasks) == 12, "downstream task count")
    baselines = {}
    for run in data["campaigns"]:
        accepted = verify_formation(run, plan["challengeId"], universes)
        rows = run["evaluations"]
        check([r["taskId"] for r in rows] == sorted(tasks), "incomplete evaluation")
        for row in rows:
            case, task = tasks[row["taskId"]]
            check(all(row[k] == task[k] for k in ["source", "target", "assumptions", "searchBudget"]), "task changed")
            check(row["caseId"] == case["caseId"] and row["split"] == case["split"], "case binding")
            base, enabled = row["baseline"], row["macroEnabled"]
            check(baselines.setdefault(row["taskId"], base) == base, "changed primitive baseline")
            for search, allowed in [(base, set(reverse)), (enabled, set(reverse) | accepted)]:
                check(set(search["ruleIds"]) <= allowed, "foreign path rule")
                check(0 <= search["expandedStates"] <= task["searchBudget"]["maxExpandedStates"], "state budget")
                check(len(search["ruleIds"]) <= task["searchBudget"]["maxDepth"], "depth budget")
            result = outcome(base, enabled, accepted)
            check(row["outcome"] == result and row["correctnessRegression"] == (result == "CORRECTNESS_REGRESSION"),
                  "paired utility classification")
    print("Verified all four TRAIN source projections, twelve freeze/schedule decisions and 144 paired task outcomes.")


if __name__ == "__main__":
    main()
