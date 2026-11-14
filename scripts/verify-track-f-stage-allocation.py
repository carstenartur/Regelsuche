#!/usr/bin/env python3
"""Replay the prospective dispatch policies and recompute their accounting from native receipts."""
import argparse
from collections import Counter
import gzip
import hashlib
import json
from pathlib import Path

PUBLIC = Path(__file__).resolve().parents[1] / "research/benchmarks/track-f-stage-allocation"
PLAN_HASH = "sha256:0b67a54b17f48304e38226579ff0f013180d4400fc679185a360ff023c981eb9"
GEN, FORM, VAL, CX, NOV, PROOF, LIFE = STAGES = (
    "GENERATION", "CANDIDATE_FORMATION", "VALIDATION", "COUNTEREXAMPLE_SEARCH",
    "PROJECT_NOVELTY", "PROOF", "LIFECYCLE_HANDOFF")
UNIFORM, STATIC, NATIVE = ARMS = ("UNIFORM_ALL_STAGES", "STATIC_NO_FEEDBACK", "NATIVE_RECEIPT_DRIVEN")
SUCCESSOR = {VAL: NOV, NOV: PROOF, PROOF: LIFE, LIFE: None}
PASS = {VAL: ("status", "ACCEPTED_FOR_PROOF"), NOV: ("status", "NOVEL_WITHIN_PROJECT"),
        PROOF: ("proofStatus", "SYMBOLICALLY_VERIFIED"), LIFE: ("outcome", "COMPLETED")}


def check(ok, message):
    if not ok:
        raise ValueError(message)


def digest(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


def json_hash(obj):
    return digest(json.dumps(obj, separators=(",", ":"), ensure_ascii=False).encode())


def amounts(entries):
    return {entry["resource"]: entry["amount"] for entry in entries}


def verify_lane(lane, panel, reference):
    arm = lane["arm"]
    brief, generation, seeds, gen = (lane[k] for k in ("brief", "generation", "seedCatalog", "generationReceipt"))
    check(generation["contentHash"] == reference["generationRunHash"], "original generator identity")
    check(generation["seedCatalogHash"] == seeds["contentHash"] and generation["generationReceiptHash"] == gen["contentHash"],
          "generation artifact linkage")
    check(all(x["briefHash"] == brief["contentHash"] for x in (generation, seeds, gen, lane["formationPlan"])), "brief linkage")
    check(generation["targetProvided"] is False and seeds["targetProvided"] is False
          and gen["targetProvided"] is False and reference["targetProvided"] is False, "target-free")
    work = Counter({r["resource"]: r["executed"] for r in gen["resources"]})
    skipped = Counter({r["resource"]: r["skipped"] for r in gen["resources"]})
    catalog = {s["seedId"]: s for s in seeds["seeds"]}
    check(len(catalog) == generation["seedCount"] == 12, "registered inventory")
    candidates = {(c["batch"], c["id"]): c for c in lane["candidates"]}
    check(len(candidates) == len(lane["candidates"]), "duplicate lineage")
    events = []

    def executed(stage, batch, branch, receipt):
        events.append(dict(stage=stage, batch=batch, status="EXECUTED", lineage=branch, receiptHash=receipt))

    def skip(stage, batch, reason="NO_ELIGIBLE_RESERVED_LINEAGE"):
        events.append(dict(stage=stage, batch=batch, status="SKIPPED", reason=reason))

    executed(GEN, -1, "", gen["contentHash"])
    if arm == UNIFORM:
        skip(GEN, -1, "SINGLE_REGISTERED_CATALOG")
    outputs = set()
    check(len(lane["batches"]) == len(panel["batches"]) == 2, "panel batch cardinality")
    for index, (batch, selector) in enumerate(zip(lane["batches"], panel["batches"], strict=True)):
        decision, receipt, mined, lineage = (batch[k] for k in ("decision", "receipt", "mining", "lineage"))
        observed = batch["inputSeeds"]
        check(sorted(observed) == sorted(catalog if selector == "ALL" else selector), "registered seed selection")
        check(sorted(observed) == sorted(s["seedId"] for s in mined["campaign"]["seeds"]), "miner inputs")
        check(sorted("autopilot/observation/" + seed for seed in observed)
              == sorted(b["branchId"] for b in decision["inputs"]), "decision inputs")
        check(all(b["goalStatus"] == "UNTARGETED" for b in decision["inputs"]), "target-free decision inputs")
        check(mined["targetProvided"] is False and mined["campaign"]["ruleInventoryHash"] == brief["inventoryHash"], "miner authority")
        check(amounts(decision["plannedResources"]) == {"MINING_BATCHES": 1, "CANDIDATES": 6}, "equal formation slots")
        check(receipt["decisionHash"] == lineage["decisionHash"] == decision["contentHash"], "decision linkage")
        check(receipt["miningEvidenceHash"] == lineage["miningEvidenceHash"] == mined["contentHash"], "mining linkage")
        check(lineage["aggregateReceiptHash"] == receipt["contentHash"], "lineage linkage")
        check(len(mined["rejectedClusters"]) == len(receipt["rejectedClusters"]), "rejection retention")
        check({c["conjectureId"] for c in mined["conjectures"]} == {o["conjectureId"] for o in receipt["outputs"]}, "mined outputs")
        check(len(receipt["outputs"]) == len(lineage["candidates"]), "lineage cardinality")
        check(amounts(receipt["executedResources"]).get("CANDIDATES", 0) == len(receipt["outputs"])
              and amounts(receipt["skippedResources"]).get("CANDIDATES", 0) == 6 - len(receipt["outputs"]), "formation execution")
        for out, link in zip(receipt["outputs"], lineage["candidates"], strict=True):
            key = index, out["outputBranchId"]
            check(key in candidates and candidates[key]["conjectureId"] == out["conjectureId"] == link["candidateId"], "branch identity")
            check(candidates[key]["lineageHash"] == link["lineageHash"] == out["lineageHash"], "branch evidence")
            check(link["sources"] == out["sources"] and bool(link["sources"]), "candidate source identity")
            outputs.add(key)
        work.update(amounts(receipt["executedResources"]))
        skipped.update(amounts(receipt["skippedResources"]))
        executed(FORM, index, "", receipt["contentHash"])
    check(outputs == set(candidates), "dropped or extra branches")
    states = {key: VAL for key in candidates}
    status = dict.fromkeys(candidates, "FORMED")
    read = {key: set() for key in candidates}

    def dispatch(stage, key, reserved):
        if key is None or states[key] != stage:
            skip(stage, reserved)
            if stage == VAL:
                skip(CX, reserved)
            return
        case = candidates[key]
        receipts = case["receipts"]
        report = receipts[stage]
        read[key].add(stage)
        executed(stage, *key, json_hash(report))
        if stage == VAL:
            check(report["conjectureId"] == case["conjectureId"], "validation identity")
            for side in ("Positive", "Negative"):
                count = report["executed" + side + "Holdouts"]
                check(count == len(report[side.lower() + "Results"]), "holdout attempts")
                work["VALIDATION_CHECKS"] += count
            cx = receipts[CX]
            counts = receipts["evaluatedAssignmentsBySource"]
            check(cx["conjectureId"] == case["conjectureId"] and set(counts) <= set(cx["attemptedSources"]), "sample source")
            check(all(isinstance(n, int) and n > 0 for n in counts.values()), "sample counts")
            executed(CX, *key, json_hash(cx))
            work["COUNTEREXAMPLE_ATTEMPTS"] += sum(counts.values())
            read[key].update((CX, "evaluatedAssignmentsBySource"))
        elif stage == NOV:
            check(report["conjectureId"] == case["conjectureId"] and report["externalNoveltyStatus"] == "NOT_EVALUATED", "novelty scope")
            work["NOVELTY_COMPARISONS"] += report["checkedActiveRules"] + report["checkedPriorCandidates"]
        elif stage == PROOF:
            check(report["conjectureId"] == case["conjectureId"] and report["formalProofStatus"] == "NOT_EVALUATED", "proof scope")
            work["PROOF_ATTEMPTS"] += int(report["proofObligationEmitted"])
            for name, field in (("solverObligation", "solverObligationHash"), ("solverResult", "solverResultHash")):
                if name in receipts:
                    check(receipts[name]["contentHash"] == report[field], "solver artifact")
                    read[key].add(name)
            if "solverResult" in receipts:
                check(receipts["solverResult"]["obligationHash"] == report["solverObligationHash"], "solver obligation identity")
        else:
            check(report["candidateBranchId"] == key[1] and report["candidateId"] == case["conjectureId"], "handoff identity")
            check(not report["publicationAttempted"] and not report["promotionAttempted"], "claim boundary")
            check(receipts["lifecycleCandidate"]["id"] == case["conjectureId"], "lifecycle hypothesis identity")
            read[key].add("lifecycleCandidate")
            work["LIFECYCLE_HANDOFFS"] += int(report["outcome"] == "COMPLETED")
        field, passing = PASS[stage]
        status[key] = report[field]
        states[key] = SUCCESSOR[stage] if status[key] == passing else None

    if arm == UNIFORM:
        for stage in SUCCESSOR:
            eligible = sorted(k for k in candidates if states[k] == stage)
            for index in range(2):
                dispatch(stage, eligible[index] if index < len(eligible) else None, -1)
    elif arm == STATIC:
        first = sorted(k for k in candidates if k[0] == 0)
        for index in range(2):
            for stage in SUCCESSOR:
                dispatch(stage, first[index] if index < len(first) else None, 0)
        dispatch(PROOF, next((k for k in first if states[k] == PROOF), None), 0)
    else:
        while len(events) < 14:
            eligible = [k for k in states if states[k] and (states[k] != VAL or len(events) <= 12)]
            if not eligible:
                break
            key = min(eligible, key=lambda k: (-STAGES.index(states[k]), k))
            dispatch(states[key], key, key[0])
    check(lane["dispatch"] == events, "dispatch differed from registered policy")
    check(lane["configuredTickets"] == 14 and lane["remainingTickets"] == 14 - len(events), "ticket balance")
    for key, candidate in candidates.items():
        check(candidate["nextStage"] == states[key] and candidate["status"] == status[key], "candidate disposition")
        check(set(candidate["receipts"]) == read[key], "extra or missing native receipt")
    ledger = {}
    for budget in brief["stageBudgets"]:
        stage = ledger[budget["stage"]] = {}
        for r in budget["resources"]:
            kind, limit = r["resource"], r["configured"]
            left = limit - work[kind] - skipped[kind]
            check(left >= 0, "native budget overrun")
            stage[kind] = dict(configured=limit, executed=work[kind], skipped=skipped[kind], remaining=left)
    check(ledger == lane["resourceLedger"], "native resource reconstruction")
    return len(candidates), len({c["conjectureId"] for c in candidates.values()}), sum(s == "COMPLETED" for s in status.values())


def verify(plan_path, report_path):
    plan_bytes = plan_path.read_bytes()
    plan = json.loads(plan_bytes)
    raw = report_path.read_bytes()
    raw = gzip.decompress(raw) if raw.startswith(b"\x1f\x8b") else raw
    data = json.loads(raw)
    check(data["schema"] == "regelsuche.track-f-stage-allocation/v1", "report schema")
    check(digest(plan_bytes) == data["planSha256"] == PLAN_HASH, "registered bytes")
    ref, feedback = data["originalCampaign"], data["originalTerminalFeedback"]
    original = json.loads(gzip.decompress((PUBLIC.parents[2] / "docs/evidence/autopilot-controller-comparison-v1.json.gz").read_bytes()))
    control = next(lane for lane in original["lanes"] if lane["policy"] == "PRODUCTION_FEEDBACK")
    check(ref["contentHash"] == control["productionCampaignHash"] and feedback == control["feedback"], "historical reference replay")
    check(ref["feedbackReallocationHash"] == feedback["contentHash"] and feedback["disposition"] == "CAMPAIGN_COMPLETE"
          and not feedback["eligibleBranchIds"] and feedback["nextDecisionCount"] == 0, "original terminal-only reference")
    lanes = {(l["panel"], l["arm"]): l for l in data["lanes"]}
    expected = {(p["id"], a) for p in plan["panels"] for a in ARMS}
    check(set(lanes) == expected and len(lanes) == len(data["lanes"]), "paired lane coverage")
    summaries = []
    for panel in plan["panels"]:
        paired = [lanes[panel["id"], arm] for arm in ARMS]
        for lane in paired:
            for key in ("brief", "generation", "seedCatalog", "generationReceipt", "formationPlan", "batches"):
                check(lane[key] == paired[0][key], "pre-scheduling parity " + key)
            check(lane["brief"] == original["brief"] and lane["generation"] == control["generation"]
                  and lane["seedCatalog"] == control["seedCatalog"] and lane["generationReceipt"] == control["generationReceipt"],
                  "original #355 authority parity")
            counts = verify_lane(lane, panel, ref)
            summaries.append((panel["id"], lane["arm"], *counts))
    return summaries


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, default=PUBLIC / "plan.json")
    parser.add_argument("--report", type=Path, default=PUBLIC / "measured.json.gz")
    args = parser.parse_args()
    for panel, arm, candidate, distinct, completed in verify(args.plan, args.report):
        print(f"{panel} {arm}: {candidate} lineages, {distinct} distinct candidate IDs, {completed} completed")
