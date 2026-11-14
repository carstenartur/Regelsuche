#!/usr/bin/env python3
"""Independently replay ordered grammar draws, difference-table production and frozen evaluation."""

import hashlib
import itertools
import json
import math
from pathlib import Path

DATA = Path(__file__).resolve().parents[1] / "research/benchmarks/candidate-independent"
DIRECTORY = "track-c-finite-differences"
PLAN = "sha256:de9d3735d5e986bdf03087e8d5568a1cb30c1df3e8ebc5c7b87ecb84343d493c"
COMMIT = "85c2150586c68aa3436f6b17f84cb54bf5ff7eb9"


def require(condition, description):
    if not condition:
        raise ValueError(description)


def digest(value):
    return "sha256:" + hashlib.sha256(value).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()


def frozen(name, expected):
    data = json.loads((DATA / (name + ".json")).read_bytes())
    declared = data.pop("contentHash")
    require(expected == declared == digest(canonical(data)), name)
    return data


class CampaignRandom:
    def __init__(self, seed):
        self.state = (int(seed[7:23], 16) ^ 0x5DEECE66D) & (2**48 - 1)

    def draw(self, size):
        while True:
            self.state = (self.state * 0x5DEECE66D + 11) % 2**48
            bits = self.state >> 17
            if size & (size - 1) == 0:
                return size * bits >> 31
            index = bits % size
            if bits - index + size - 1 < 2**31:
                return index


def series(model, count):
    return [str(sum(math.comb(n, k) * c for k, c in enumerate(model) if k <= n)) for n in range(count)]


def proposals(arm, prefix, limit, grammar, cap, random):
    if arm == "ENUMERATE_VALID_GRAMMAR":
        return [], grammar[:cap]
    if arm == "SEEDED_RANDOM_VALID_GRAMMAR":
        return [], [grammar[random.draw(len(grammar))] for _ in range(cap)] if grammar else []
    require(arm == "PRODUCTION_DIFFERENCE_DOMAIN", "unknown arm")
    first = [prefix[0]]
    levels = []
    row = prefix
    for degree in range(1, limit + 1):
        row = [b - a for a, b in zip(row, row[1:])]
        levels.append(degree)
        first.append(row[0])
        if len(set(row)) == 1:
            return levels, [tuple(first)]
    return levels, []


def form(arm, seed, inputs, plan):
    random = CampaignRandom(seed)
    models, input_rows = [], []
    for visible in inputs:
        prefix, maximum = visible["observedPrefix"], visible["maximumOrder"]
        degree = min(maximum, 8, len(prefix) - 2)
        low, high = plan["minimumCoefficient"], plan["maximumCoefficient"]
        cap = plan["proposalsPerTrainPrefix"]
        grammar = [(prefix[0],) + free for d in range(1, degree + 1)
                   for free in itertools.product(range(low, high + 1), repeat=d) if free[-1] != 0]
        levels, candidates = proposals(arm, prefix, degree, grammar, cap, random)
        seen, attempts = set(), []
        for model in candidates:
            predicted = series(model, len(prefix))
            status = ("OUTSIDE_GRAMMAR" if model not in grammar else
                      "SUPPORTED" if predicted == list(map(str, prefix)) else "REJECTED")
            attempts.append(dict(coefficients=list(model), predictions=predicted,
                                 duplicate=model in seen or list(model) in models, disposition=status))
            seen.add(model)
            if status == "SUPPORTED" and list(model) not in models:
                models.append(list(model))
        input_rows.append(dict(prefix=prefix, maximumOrder=maximum, grammarSize=len(grammar),
                               domainLevels=levels, domainSubtractions=sum(len(prefix)-k for k in levels),
                               attempts=attempts, unusedSlots=cap-len(attempts), prefixChecks=len(attempts)*len(prefix)))
    return dict(inputs=input_rows, models=models)


def evaluate(models, case):
    observed = case["evaluationInput"]["observedPrefix"]
    future = case["evaluationInput"]["holdoutContinuation"]
    rows = []
    ranking = ["NO_OUTPUT", "PREFIX_MISMATCH", "REFUTED", "CONFIRMED"]
    best = 0
    for model in models:
        predicted = series(model, len(observed)+len(future))
        if predicted[:len(observed)] != list(map(str, observed)):
            level = 1
        else:
            level = 3 if predicted[len(observed):] == list(map(str, future)) else 2
        best = max(level, best)
        rows.append(dict(coefficients=model, predictions=predicted, outcome=ranking[level]))
    return dict(caseId=case["caseId"], split=case["split"], observed=observed, continuation=future,
                prefixChecks=len(models)*len(observed), continuationChecks=len(models)*len(future),
                outcome=ranking[best], candidates=rows)


def main():
    plan_bytes = (DATA / DIRECTORY / "comparison-plan.json").read_bytes()
    require(digest(plan_bytes) == PLAN, "changed preregistration")
    plan = json.loads(plan_bytes)
    corpus = frozen("case-corpus", plan["caseCorpusContentHash"])
    frozen("corpus-freeze-receipt", plan["freezeReceiptContentHash"])
    frozen("finite-sequence-candidate-forms", plan["formationProfileContentHash"])
    cases = [row for row in corpus["cases"] if row["challengeId"] == plan["challengeId"]]
    inputs = [row["formationInput"] for row in cases if row["split"] == "TRAIN"]
    require(len(inputs) == 2 and len(cases) == 6, "frozen sequence panel")
    runs = []
    for n in range(1, plan["campaignCount"] + 1):
        ch = plan["challengeId"]
        seed = digest(canonical(dict(benchmarkId=corpus["benchmarkId"], challengeId=ch,
                                     campaignId=f"{ch}-campaign-{n:02}", index=n)))
        for arm in plan["arms"]:
            formation = form(arm, seed, inputs, plan)
            runs.append(dict(campaign=n, arm=arm, seed=seed, formation=formation,
                             formationHash=digest(canonical(formation))))
    for run in runs:
        run["evaluations"] = [evaluate(run["formation"]["models"], row) for row in cases]
    expected = dict(schema="regelsuche.track-c-finite-difference-comparison/v1", planCommit=COMMIT,
                    planByteHash=PLAN, corpusContentHash=plan["caseCorpusContentHash"], campaigns=runs)
    require((DATA / DIRECTORY / "comparison-result.json").read_bytes() == canonical(expected) + b"\n",
            "raw evidence differs from independent replay")
    print("Verified all 12 frozen formations, every proposal, every work counter and 72 case rows.")


if __name__ == "__main__":
    main()
