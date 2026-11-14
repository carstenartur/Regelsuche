#!/usr/bin/env python3
"""Independently reproduce the public #383 recurrence rows and both grammar schedules."""
import hashlib
import itertools
import json
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "research" / "benchmarks" / "candidate-independent"
PROFILE = DATA / "track-c-recurrence"


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()


def digest(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


def check(condition, label):
    if not condition:
        raise ValueError(label)


class JavaRandom:
    def __init__(self, seed):
        self.state = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def index(self, bound):
        while True:
            self.state = (self.state * 0x5DEECE66D + 11) & ((1 << 48) - 1)
            bits = self.state >> 17
            if bound & (bound - 1) == 0:
                return (bound * bits) >> 31
            remainder = bits % bound
            if bits - remainder + bound - 1 < (1 << 31):
                return remainder


def exact_fit(prefix, maximum):
    """Lowest order with a unique rational solution to every observed equation."""
    for order in range(1, min(maximum, len(prefix) // 2) + 1):
        rows = [[Fraction(prefix[i - lag]) for lag in range(1, order + 1)] + [Fraction(prefix[i])]
                for i in range(order, len(prefix))]
        for col in range(order):
            pivot = next((i for i in range(col, len(rows)) if rows[i][col]), None)
            if pivot is None:
                break
            rows[col], rows[pivot] = rows[pivot], rows[col]
            rows[col] = [v / rows[col][col] for v in rows[col]]
            for i, row in enumerate(rows):
                if i != col:
                    rows[i] = [a - row[col] * b for a, b in zip(row, rows[col])]
        else:
            if all(not row[-1] for row in rows[order:]):
                return [tuple(rows[i][-1] for i in range(order))]
    return []


def predict(terms, coefficients):
    return sum(c * terms[-i] for i, c in enumerate(coefficients, 1))


def replay(prefix, model):
    return [predict(prefix[:i], model) == prefix[i] for i in range(len(model), len(prefix))]


def proposal_list(arm, rng, prefix, order, plan):
    if arm == "PRODUCTION_EXACT_FIT":
        return exact_fit(prefix, order)
    span = range(plan["minimumCoefficient"], plan["maximumCoefficient"] + 1)
    grammar = [v for k in range(1, min(order, len(prefix) // 2) + 1)
               for v in itertools.product(span, repeat=k) if v[-1] != 0]
    limit = plan["proposalsPerTrainPrefix"]
    if arm == "ENUMERATE_VALID_GRAMMAR":
        return grammar[:limit]
    return [grammar[rng.index(len(grammar))] for _ in range(limit)]


def verify_formation(campaign, train, plan, seed):
    rng = JavaRandom(int(seed[7:23], 16))
    formation = campaign["formation"]
    check(digest(canonical(formation)) == campaign["formationHash"], "formation root")
    check(len(formation["inputs"]) == len(train), "missing TRAIN prefix")
    models = []
    for row, inputs in zip(formation["inputs"], train):
        prefix, order = inputs["observedPrefix"], inputs["maximumOrder"]
        check((row["prefix"], row["maximumOrder"]) == (prefix, order), "prefix projection")
        proposals = proposal_list(campaign["arm"], rng, prefix, order, plan)
        check(len(proposals) == len(row["attempts"]), "proposal count")
        seen = set()
        for attempt, model in zip(row["attempts"], proposals):
            checks = replay(prefix, model)
            allowed = model[-1] and all(Fraction(c).denominator == 1 and
                plan["minimumCoefficient"] <= c <= plan["maximumCoefficient"] for c in model)
            outcome = "OUTSIDE_GRAMMAR" if not allowed else "SUPPORTED" if all(checks) else "REJECTED"
            expected = {"coefficients": list(map(str, model)), "disposition": outcome,
                        "duplicate": model in seen or model in models, "prefixChecks": checks}
            check(attempt == expected, "exact proposal, duplicate or support mismatch")
            seen.add(model)
            if outcome == "SUPPORTED" and model not in models:
                models.append(model)
    check(formation["models"] == [list(map(str, m)) for m in models], "unfrozen candidate set")
    return models


def verify_evaluations(campaign, cases, models):
    check(len(campaign["evaluations"]) == len(cases), "incomplete case matrix")
    for row, case in zip(campaign["evaluations"], cases):
        inputs = case["evaluationInput"]
        observed, continuation = inputs["observedPrefix"], inputs["holdoutContinuation"]
        check((row["caseId"], row["split"], row["observed"], row["continuation"]) ==
              (case["caseId"], case["split"], observed, continuation), "substituted evaluation")
        expected = []
        for model in models:
            prefix = replay(observed, model)
            preds = [predict(observed + continuation[:i], model) for i in range(len(continuation))]
            outcome = "PREFIX_MISMATCH" if not all(prefix) else "CONFIRMED" if preds == continuation else "REFUTED"
            expected.append({"coefficients": list(map(str, model)), "outcome": outcome,
                             "prefixChecks": prefix, "predictions": list(map(str, preds))})
        check(expected == row["candidates"], "missing or incorrect candidate evidence")
        priority = ["CONFIRMED", "REFUTED", "PREFIX_MISMATCH"]
        status = next((s for s in priority if any(x["outcome"] == s for x in expected)), "NO_OUTPUT")
        check(row["outcome"] == status, "case outcome")


def main():
    report = json.loads((PROFILE / "comparison-result.json").read_bytes())
    plan_bytes = (PROFILE / "comparison-plan.json").read_bytes()
    plan = json.loads(plan_bytes)
    check(report["planByteHash"] == digest(plan_bytes), "plan root")
    corpus = json.loads((DATA / "case-corpus.json").read_bytes())
    root = corpus.pop("contentHash")
    check(root == plan["caseCorpusContentHash"] == report["corpusContentHash"] == digest(canonical(corpus)), "corpus root")
    cases = [c for c in corpus["cases"] if c["challengeId"] == plan["challengeId"]]
    train = [c["formationInput"] for c in cases if c["split"] == "TRAIN"]
    expected = list(itertools.product(range(1, plan["campaignCount"] + 1), plan["arms"]))
    check([(c["campaign"], c["arm"]) for c in report["campaigns"]] == expected, "campaign matrix")
    for campaign in report["campaigns"]:
        index = campaign["campaign"]
        seed = digest(canonical({"benchmarkId": corpus["benchmarkId"], "campaignId":
            f'{plan["challengeId"]}-campaign-{index:02}', "challengeId": plan["challengeId"], "index": index}))
        check(seed == campaign["seed"], "wrong campaign seed")
        models = verify_formation(campaign, train, plan, seed)
        verify_evaluations(campaign, cases, models)
    print(f"Verified {len(expected)} candidate sets, every proposal and {len(expected) * len(cases)} evaluated case rows.")


if __name__ == "__main__":
    main()
