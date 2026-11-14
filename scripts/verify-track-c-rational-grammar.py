#!/usr/bin/env python3
"""Independently reconstruct the bounded rational grammar experiment on the public corpus."""
from collections import Counter, deque
from fractions import Fraction
import gzip
import hashlib
import json
from pathlib import Path
import runpy
import sys

TOOLS = Path(__file__).resolve().parent
R = runpy.run_path(str(TOOLS / "run-candidate-independent-rational-assumption-verifier.py"))["VERIFIER"]
DATA = TOOLS.parent / "research/benchmarks/candidate-independent"
LANE = DATA / "track-c-rational-grammar"
PLAN_DIGEST = "cb4aeb6cc607f7b42da33000a493f1402e6534aee5513c1feec68836146dd124"
GRAMMAR = ("CANCELLATION", "EQUAL_DENOMINATOR", "COMBINED")
ARMS = ("PRODUCTION_FULL_OPERATOR", "ENUMERATE_OPERATOR_GRAMMAR", "SEEDED_RANDOM_OPERATOR_GRAMMAR")


def check(condition, context):
    if not condition:
        raise ValueError(context)


def schedules(seed):
    state = (int(seed[7:23], 16) ^ 0x5DEECE66D) & ((1 << 48) - 1)
    random = []
    while len(random) < 3:
        state = (state * 0x5DEECE66D + 11) & ((1 << 48) - 1)
        bits = state >> 17
        # Java nextInt rejects the tail of the finite 31-bit range.
        if bits - bits % 3 + 2 < (1 << 31):
            random.append(GRAMMAR[bits % 3])
    return [[GRAMMAR[2]], list(GRAMMAR), random]


def operator_forms(value, program):
    if program in ("CANCELLATION", "COMBINED"):
        result = R.cancel_root(value)
        if result is not None and result != value:
            yield result
    if program in ("EQUAL_DENOMINATOR", "COMBINED") and value[0] in ("+", "-"):
        left, right = value[1:]
        if left[0] == right[0] == "/" and left[2] == right[2] and left[2] != ("num", Fraction(0)):
            yield ("/", (value[0], left[1], right[1]), left[2])


def canonical_cost(value):
    # The frozen witnesses have one cost-changing canonicalization: folding a numeric quotient.
    if value[0] == "/" and value[1][0] == value[2][0] == "num" and value[2][1]:
        return 1
    return R.node_count(value)


def resource(row, limit_states, limit_checks, states, checks):
    check(row == {"configuredStates": limit_states, "executedStates": states,
                  "remainingStates": limit_states - states, "configuredCandidateEvaluations": limit_checks,
                  "executedCandidateEvaluations": checks, "remainingCandidateEvaluations": limit_checks - checks},
          f"resource discrepancy: {row}")


def validate_edge(row, left, right, premises, generated_field, conditional):
    normal_left, normal_right, required, provided = R.confirm_equivalence(left, right, premises)
    check([row[k] for k in ("leftCrossNormalForm", "rightCrossNormalForm", "requiredNonZeroFactors",
                             "providedNonZeroFactors")] == [normal_left, normal_right, required, provided],
          f"arithmetic certificate: {left} -> {right}")
    generated = row[generated_field]
    obligations, _, unsupported = R.audit_assumptions(generated, [])
    check(not unsupported and set(obligations) <= set(provided), "generated guard not supplied")
    check((bool(generated) == conditional) and (not conditional or set(required) == set(obligations)),
          "lost or invented guard")


def check_proposal(row, seeds, limit_states, limit_checks):
    program = row["program"]
    accepted = []
    states = checks = 0
    for seed in seeds:
        states += 1
        source = R.parse_expression(seed["expression"])
        found = None
        for candidate in operator_forms(source, program):
            checks += 1
            try:
                R.confirm_equivalence_ast(source, candidate, seed["assumptions"])
            except R.VerificationError:
                continue
            required = R.required_factor_keys(source, candidate)
            if required and canonical_cost(candidate) < canonical_cost(source):
                found = candidate
                break
        if found is None:
            break
        accepted.append((seed, found))
    resource(row["resourceUse"], limit_states, limit_checks, states, checks)
    check(len(accepted) == len(row["evidence"]), "formation evidence count")
    for (seed, expected), item in zip(accepted, row["evidence"], strict=True):
        R.require_hash(item, "formation witness")
        source, result, premises = item["inputExpression"], item["selectedExpression"], item["declaredAssumptions"]
        check((item["seedId"], item["sourceReference"], source, premises) ==
              (seed["seedId"], seed["sourceReference"], seed["expression"], seed["assumptions"]), "TRAIN projection")
        check(R.parse_expression(result) == expected and not item["evaluationInputRead"] and not item["targetVisible"],
              "selected grammar witness")
        check([item["inputAstNodes"], item["selectedAstNodes"]] ==
              [canonical_cost(R.parse_expression(source)), canonical_cost(expected)], "canonical AST cost")
        validate_edge(item, source, result, premises, "candidateAssumptions", True)
    complete = len(accepted) == len(seeds)
    check(row["status"] == ("SELECTED" if complete else "NO_CANDIDATE"), "formation outcome")
    if complete:
        candidate = row["candidate"]
        R.require_hash(candidate, "candidate")
        candidate_id = R.CANDIDATE_FORM + ("" if program == "COMBINED" else "/" + program)
        check(candidate["candidateFormId"] == candidate_id and candidate["operatorId"] == R.OPERATOR_ID
              and candidate["implementationClass"] == "de.regelsuche.transform.RationalNormalizationHypothesisOperator"
              and candidate["supportSeedIds"] == [s["seedId"] for s in seeds]
              and candidate["frozenPrimitiveRuleIds"] == ["ast_divide_one", "ast_square_difference_factor"],
              "frozen candidate identity or primitive inventory")
    else:
        check(row["candidate"] is None, "rejected proposal retained as candidate")
    return states, checks, complete


def transitions(node, program):
    # For this panel, primitive and hypothesis results are syntactically unique at each expansion.
    edges = {(e.rule_id, e.target) for e in R.base_rewrites_everywhere(node)}
    edges.update((R.OPERATOR_ID, child) for child in operator_forms(node, program))
    return sorted(edges, key=lambda edge: (edge[0], R.ast_key(edge[1])))


def replay(task, program):
    if program is None:
        return "CANDIDATE_NOT_FORMED", [], 0, 0
    start, target = (R.parse_expression(task[k]) for k in ("source", "target"))
    seen = {start}
    queue = deque([(start, [])])
    states = checks = 0
    while queue and states < 245:
        current, path = queue.popleft()
        states += 1
        if current == target:
            R.confirm_equivalence_ast(start, target, task["assumptions"])
            return "REACHED_AND_CONFIRMED", path, states, checks
        if len(path) == 5:
            continue
        for rule, child in transitions(current, program):
            if checks == 45:
                return "BUDGET_EXHAUSTED", [], states, checks
            checks += 1
            try:
                R.confirm_equivalence_ast(current, child, task["assumptions"])
            except R.VerificationError:
                continue
            if child not in seen:
                seen.add(child)
                queue.append((child, path + [(rule, current, child)]))
    return ("BUDGET_EXHAUSTED" if queue else "NO_RESULT"), [], states, checks


def validate_task(row, case, task, program):
    R.require_hash(row, "evaluation")
    check(row["caseId"] == case["caseId"] and row["caseContentHash"] == case["contentHash"]
          and row["split"] == case["split"] and row["structuralCluster"] == case["structuralCluster"]
          and all(row[key] == task[key] for key in ("taskId", "source", "target", "assumptions")), "changed task")
    outcome, path, states, checks = replay(task, program)
    check(row["outcome"] == outcome and len(path) == len(row["steps"]), "search outcome or path length")
    resource(row["resourceUse"], 245, 45, states, checks)
    for index, ((rule, before, after), edge) in enumerate(zip(path, row["steps"], strict=True), start=1):
        R.require_hash(edge, "search edge")
        check((index, rule, before, after) == (edge["sequence"], edge["ruleId"], R.parse_expression(edge["source"]),
              R.parse_expression(edge["target"])), "search edge")
        validate_edge(edge, edge["source"], edge["target"], task["assumptions"], "generatedAssumptions",
                      rule == R.OPERATOR_ID)
    return states, checks


def verify(result):
    plan_file = LANE / "comparison-plan.json"
    check(hashlib.sha256(plan_file.read_bytes()).hexdigest() == PLAN_DIGEST, "protocol bytes")
    plan = R.load_json(plan_file)
    check(result["schema"] == "regelsuche.track-c-rational-grammar/v1"
          and result["planBytes"] == "sha256:" + PLAN_DIGEST, "run identity")
    check(R.exact_hash(DATA / "benchmark-source.json") == plan["benchmarkBytes"], "benchmark bytes")
    corpus = R.load_json(DATA / "case-corpus.json")
    for document, field in [(corpus, "caseCorpusContentHash"),
            (R.load_json(DATA / "corpus-freeze-receipt.json"), "freezeReceiptContentHash"),
            (R.load_json(DATA / "rational-assumption-primitives.json"), "primitiveProfileContentHash")]:
        R.require_hash(document, field)
        check(document["contentHash"] == plan[field], field)
    benchmark_id = R.load_json(DATA / "benchmark-source.json")["benchmarkId"]
    cases = [c for c in corpus["cases"] if c["challengeId"] == R.CHALLENGE]
    seeds = []
    tasks = []
    for case in cases:
        R.require_hash(case, "case")
        if case["split"] == "TRAIN":
            for seed in case["formationInput"]["seedExpressions"]:
                label = f"input-{len(seeds) + 1}"
                seeds.append(dict(seedId=label, sourceReference=label, expression=seed["expression"],
                                  assumptions=seed["assumptions"]))
        tasks.extend((case, task) for task in case["evaluationInput"]["tasks"])
    check(len(seeds) == 4 and result["formationInputs"] == seeds and len(tasks) == 12, "public projection")
    freezes = result["frozenArms"]
    check(result["formationFreezeHash"] == R.semantic_hash(freezes) and len(freezes) == len(result["evaluations"]) == 12,
          "freeze commitment")
    outcomes = Counter()
    for i, (freeze, panel) in enumerate(zip(freezes, result["evaluations"], strict=True)):
        index, arm_index = divmod(i, 3)
        campaign_id = f"{R.CHALLENGE}-campaign-{index + 1:02d}"
        digest = R.configured_seed(benchmark_id, campaign_id, index + 1)
        check((freeze["arm"], freeze["campaignId"], freeze["configuredSeed"]) == (ARMS[arm_index], campaign_id, digest),
              "campaign identity")
        states = checks = 0
        selected = None
        for program, attempt in zip(schedules(digest)[arm_index], freeze["proposals"], strict=True):
            check(attempt["program"] == program, "proposal order")
            n, k, supported = check_proposal(attempt, seeds, 60 - states, 60 - checks)
            states += n
            checks += k
            if selected is None and supported:
                selected = program
        check((selected, states, checks) == (freeze["selected"], freeze["executedFormationStates"],
              freeze["executedFormationCandidates"]), "formation ledger or selection")
        check(panel["frozenArmHash"] == R.semantic_hash(freeze), "evaluation bound to wrong freeze")
        for row, (case, task) in zip(panel["tasks"], tasks, strict=True):
            n, k = validate_task(row, case, task, selected)
            states += n
            checks += k
            outcomes[(freeze["arm"], case["split"], row["outcome"])] += 1
        check(states <= 3000 and checks <= 600, "campaign exceeded budget")
    return outcomes


if __name__ == "__main__":
    artifact = Path(sys.argv[1]) if len(sys.argv) > 1 else LANE / "comparison-result.json.gz"
    payload = gzip.decompress(artifact.read_bytes()) if artifact.suffix == ".gz" else artifact.read_bytes()
    for fields, count in sorted(verify(json.loads(payload, object_pairs_hook=R.unique_object)).items()):
        print(*fields, count)
