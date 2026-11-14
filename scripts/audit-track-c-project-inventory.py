#!/usr/bin/env python3
"""Compare the frozen public Track C candidate sets to byte-pinned historical project evidence."""
import argparse
import gzip
import hashlib
import json
import re
import subprocess
from collections import Counter
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "research/benchmarks/candidate-independent"
HERE = BASE / "track-c-project-inventory"
REVISION = "34eb0900f26f9791545e0201d2f5db2d4a2ee96f"
PLAN = "41216241bad21ed1de69c1a4fc0baeb7eaf00e16de4209dccc2a99b24b656c6e"
HISTORICAL = "a5436121ea5cabba21a685f95e02f82d17a7f820acfae0c37f44a259908da687"
INPUTS = {
    "recurrence": "af8c65da2906b0b249bac4d895999b38b633adcad3579fe5b84bf1fb79b8c27c",
    "finiteDifferences": "c12c85e3e67829ea467784613a811e3f381024c74738315e174f6e62cffed447",
    "macros": "ccbdc84f3958a491b01b1f8fc606b1c7e3989ba178e79f3207e392f3efd617bb",
    "rational": "98c0ea8940db1950a14ec81cef83902fa0df663ef481c20f2b8e75d1639e6dca",
}
ARTIFACT_COMMITS = {
    "recurrence": "03b58d1dfdd9c5076622f1f9bf35dd9c618bf897",
    "finiteDifferences": "dcc08f16005903e57802afc0ba3c20e180e39ae1",
    "macros": "5569fe5bcb2116dfb57eca76e31f061c0aba9b67",
    "rational": "738206949a188d5974b7761573e074dbec7370c1",
}
PROGRAMS = {"CANCELLATION": ["COMMON_FACTOR_CANCELLATION"],
            "EQUAL_DENOMINATOR": ["SAME_DENOMINATOR_ADDITION_SUBTRACTION"],
            "COMBINED": ["COMMON_FACTOR_CANCELLATION", "SAME_DENOMINATOR_ADDITION_SUBTRACTION"]}


def require(truth, detail):
    if not truth:
        raise ValueError(detail)


def read_pinned(path, sha):
    data = path.read_bytes()
    return unpack_pinned(data, path, sha)


def unpack_pinned(data, path, sha):
    if path.suffix == ".gz":
        data = gzip.decompress(data)
    require(hashlib.sha256(data).hexdigest() == sha, f"{path.name} bytes disagree with registered source")
    return json.loads(data)


def pattern(left, right):
    # RulePatternCanonicalizer restarts its variable numbering on each side.
    def normalize(term):
        term = re.sub(r"\s+", "", term).replace("**", "^").replace(")(", ")*(")
        names = {}
        return re.sub(r"\b[A-Za-z][A-Za-z0-9_]*\b",
                      lambda m: names.setdefault(m[0], f"p{len(names) + 1}"), term)
    return normalize(left) + "->" + normalize(right)


def vector(coefficients):
    return tuple(str(Fraction(value)) for value in coefficients)


def differences(observed, order):
    row = observed
    initial = [row[0]]
    for _ in range(order):
        row = [b - a for a, b in zip(row, row[1:])]
        if len(row) < 2:
            break
        initial.append(row[0])
        if len(set(row)) == 1:
            return vector(initial)
    raise ValueError("confirmed historical difference fit has no constant row")


def source_row(campaign, key, row):
    return {"campaign": campaign["campaignId"], "source": key, "case": row.get("caseId"),
            "status": row.get("productionOutcome"), "receipt": row["contentHash"]}


def history_models(form, run, cases):
    by_key = {}
    for campaign in run["campaigns"]:
        for collection in ("formationEvidence", "evaluations"):
            for row in campaign[collection]:
                if form == "recurrence":
                    if not row["coefficients"]:
                        continue
                    key = vector(row["coefficients"])
                    require(len(key) == row["recurrenceOrder"], "historical recurrence order")
                else:
                    if row["productionOutcome"] != "CONFIRMED":
                        continue
                    case = cases[row["caseId"]]
                    inputs = case["formationInput" if collection == "formationEvidence" else "evaluationInput"]
                    key = differences(inputs["observedPrefix"], inputs["maximumOrder"])
                by_key.setdefault(key, []).append(source_row(campaign, collection, row))
    return by_key


def split_outcomes(rows):
    return {split: dict(sorted(Counter(row["outcome"] for row in rows if row["split"] == split).items()))
            for split in ("TRAIN", "VALIDATION", "TEST")}


def support(case_ids, cases):
    return {"caseIds": sorted(set(case_ids)),
            "structuralClusters": sorted({cases[i]["structuralCluster"] for i in case_ids})}


def sequence_lanes(form, result, historical, cases):
    catalogue = history_models(form, historical, cases)
    prefixes = {tuple(c["formationInput"]["observedPrefix"]): c["caseId"] for c in cases.values()
                if c["split"] == "TRAIN" and "observedPrefix" in c["formationInput"]}
    lanes = []
    for campaign in result["campaigns"]:
        inputs = campaign["formation"]["inputs"]
        attempts = [attempt for item in inputs for attempt in item["attempts"]]
        candidates = []
        for model in campaign["formation"]["models"]:
            key = vector(model)
            witnesses = [prefixes[tuple(item["prefix"])] for item in inputs
                         if any(vector(a["coefficients"]) == key and a["disposition"] == "SUPPORTED"
                                for a in item["attempts"])]
            require(witnesses, "selected sequence candidate lacks TRAIN support")
            candidates.append({"semanticKey": list(key), "support": support(witnesses, cases),
                               "inventoryStatus": "EXACT_MODEL_MATCH" if key in catalogue else "NO_MATCH_IN_NAMED_INVENTORY",
                               "matches": catalogue.get(key, [])})
        evaluations = campaign["evaluations"]
        lanes.append({"campaign": campaign["campaign"], "arm": campaign["arm"], "frozenRoot": campaign["formationHash"],
                      "candidates": candidates, "proposalDispositions": dict(Counter(a["disposition"] for a in attempts)),
                      "duplicateDraws": sum(a["duplicate"] for a in attempts), "evaluationOutcomes": split_outcomes(evaluations),
                      "individualModelChecks": dict(Counter(c["outcome"] for e in evaluations for c in e["candidates"])),
                      "proofScope": "EXACT_FINITE_PREFIX_AND_CONTINUATION_CHECKS",
                      "utilityScope": "FROZEN_MODEL_REUSE_NO_PAIRED_SEARCH_ABLATION"})
    return lanes


def rational_lanes(result, historical, cases):
    historical_candidates = [campaign["candidate"] for campaign in historical["campaigns"]]
    require(all(c["implementationClass"] == "de.regelsuche.transform.RationalNormalizationHypothesisOperator"
                for c in historical_candidates), "historical operator identity changed")
    train = {s["expression"]: c["caseId"] for c in cases.values() if c["split"] == "TRAIN"
             for s in c["formationInput"].get("seedExpressions", [])}
    input_cases = {s["seedId"]: train[s["expression"]] for s in result["formationInputs"]}
    lanes = []
    for arm, evaluated in zip(result["frozenArms"], result["evaluations"], strict=True):
        props = arm["proposals"]
        accepted = next((p for p in props if p["program"] == arm["selected"] and p["status"] == "SELECTED"), None)
        candidates = []
        if accepted:
            operations = PROGRAMS[accepted["program"]]
            exact = operations == PROGRAMS["COMBINED"]
            cand = accepted["candidate"]
            require(all(c["operatorId"] == cand["operatorId"] and c["frozenPrimitiveRuleIds"] == cand["frozenPrimitiveRuleIds"]
                        for c in historical_candidates), "project operator context")
            candidates.append({"semanticKey": operations, "support": support([input_cases[i] for i in cand["supportSeedIds"]], cases),
                               "inventoryStatus": "EXACT_PROGRAM_MATCH" if exact else "PROPER_SUBSET_OF_HISTORICAL_PROGRAM",
                               "matches": [c["contentHash"] for c in historical_candidates],
                               "guardedWitnesses": [{"source": e["seedId"], "assumptions": e["declaredAssumptions"],
                                                     "requiredNonZero": e["requiredNonZeroFactors"], "receipt": e["contentHash"]}
                                                    for e in accepted["evidence"]]})
        tasks = evaluated["tasks"]
        lanes.append({"campaign": arm["campaignId"], "arm": arm["arm"], "frozenRoot": evaluated["frozenArmHash"],
                      "candidates": candidates, "proposalDispositions": dict(Counter(p["status"] for p in props)),
                      "duplicateDraws": len(props) - len({p["program"] for p in props}),
                      "evaluationOutcomes": split_outcomes(tasks), "taskReasons": dict(Counter(t["reasonCode"] for t in tasks)),
                      "proofScope": "EXACT_RATIONAL_FIELD_WITNESSES_WITH_DECLARED_PREMISES",
                      "utilityScope": "GUARDED_TARGET_REACHABILITY_NO_PAIRED_SEARCH_ABLATION",
                      "correctnessRegressions": sum(t["correctnessRegression"] for t in tasks)})
    return lanes


def macro_lanes(result, historical, cases, known_rules):
    history = [m for c in historical["campaigns"] for m in c["formation"]["macros"]]
    known = {pattern(a, b) for pair in known_rules for a, b in (pair, pair[::-1])}
    starts = {t["source"]: c["caseId"] for c in cases.values() if c["split"] == "TRAIN"
              for t in c["formationInput"].get("replayTraces", [])}
    lanes = []
    for c in result["campaigns"]:
        formed = c["formation"]
        candidates = []
        for macro in formed["accepted"]["macros"]:
            key = pattern(macro["leftPattern"], macro["rightPattern"])
            require(key == macro["canonicalHash"], "native directional macro pattern key")
            expected_status = "MATCHES_KNOWN_RULE" if key in known else "NEW"
            require(macro["knownRuleStatus"] == expected_status, "native active catalogue classification")
            support_ids = [starts[result["pathUniverses"][int(t.removeprefix("open-path-")) - 1]["start"]["source"]]
                           for t in macro["supportingTraceIds"]]
            matches = []
            for ref in history:
                if key == ref["canonicalHash"]:
                    matches.append({"receipt": ref["contentHash"], "operations": ref["operationSequence"],
                                    "sameOperations": macro["operationSequence"] == ref["operationSequence"],
                                    "sameAssumptions": sorted(macro["assumptions"]) == sorted(ref["assumptions"])})
            candidates.append({"semanticKey": [key, macro["operationSequence"], macro["assumptions"]],
                               "support": support(support_ids, cases), "supportingTraceIds": macro["supportingTraceIds"],
                               "activeCatalogueStatus": expected_status, "nativeProofStatus": macro["proofStatus"],
                               "inventoryStatus": ("EXACT_MACRO_MATCH" if any(m["sameOperations"] and m["sameAssumptions"] for m in matches)
                                                   else "PATTERN_MATCH_ONLY" if matches else "NO_MATCH_IN_NAMED_INVENTORY"),
                               "matches": matches})
        tasks = c["evaluations"]
        lanes.append({"campaign": c["index"], "arm": c["arm"], "frozenRoot": c["formationHash"], "candidates": candidates,
                      "groupDispositions": dict(Counter(g["formation"]["status"] for g in formed["groups"])),
                      "startsWithNoPath": sum(s["chosenKey"] is None for s in formed["selections"]),
                      "proofScope": "NATIVE_SYMBOLIC_POLYNOMIAL_NORMAL_FORM_AND_ATOMIC_PATH_WITNESSES",
                      "utilityScope": "PAIRED_WITH_AND_WITHOUT_MACRO", "evaluationOutcomes": split_outcomes(tasks),
                      "correctnessRegressions": sum(t["correctnessRegression"] for t in tasks)})
    return lanes


def audit(args):
    plan = read_pinned(HERE / "audit-plan.json", PLAN)
    historical = read_pinned(args.historical, HISTORICAL)
    known = read_pinned(HERE / "reference-catalogue.json", "88bfb20711f1ef36438bab483c1b68dfeb3342b6da99fc101ceca744809e0dd0")
    require(known["revision"] == plan["inventoryRevision"] == REVISION, "reference revision")
    corpus = json.loads((BASE / "case-corpus.json").read_bytes())
    material = dict(corpus)
    expected = material.pop("contentHash")
    serialized = json.dumps(material, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    require("sha256:" + hashlib.sha256(serialized).hexdigest() == expected, "frozen case corpus bytes")
    cases = {case["caseId"]: case for case in corpus["cases"]}
    report = {"schema": "regelsuche.track-c-project-inventory-audit/v1", "inventoryRevision": REVISION,
              "auditPlanSha256": PLAN, "historicalReportBundleSha256": HISTORICAL,
              "caseCorpusHash": corpus["contentHash"], "forms": {}}
    for form, path in plan["comparisonPaths"].items():
        if args.git_repository:
            relative = str(BASE.relative_to(ROOT) / path)
            artifact = subprocess.check_output(["git", "-C", args.git_repository, "show",
                                                ARTIFACT_COMMITS[form] + ":" + relative])
            raw = unpack_pinned(artifact, Path(path), INPUTS[form])
        else:
            raw = read_pinned(args.inputs / path, INPUTS[form])
        prior = historical[form]
        require(prior["repositoryRevision"] == REVISION and prior["caseCorpusContentHash"] == corpus["contentHash"],
                "historical adapter authority")
        if form == "macros":
            lanes = macro_lanes(raw, prior, cases, known["activeMacroPatterns"])
        elif form == "rational":
            require(known["historicalRationalOperations"] == PROGRAMS["COMBINED"], "historical rational program")
            lanes = rational_lanes(raw, prior, cases)
        else:
            lanes = sequence_lanes(form, raw, prior, cases)
        require(len(lanes) == 12 and len({(r["campaign"], r["arm"]) for r in lanes}) == 12, "arm/campaign coverage")
        emitted = [c for lane in lanes for c in lane["candidates"]]
        inventory = {(json.dumps(c["semanticKey"], sort_keys=True), c["inventoryStatus"]) for c in emitted}
        report["forms"][form] = {"rawSha256": INPUTS[form], "sourceRevision": ARTIFACT_COMMITS[form], "historicalRoot": prior["contentHash"],
            "campaignArmSlots": len(lanes), "zeroOutputSlots": sum(not row["candidates"] for row in lanes),
            "candidateOccurrences": len(emitted), "distinctSemanticKeys": len(inventory),
            "occurrenceMatchCounts": dict(Counter(c["inventoryStatus"] for c in emitted)),
            "distinctMatchCounts": dict(Counter(status for _, status in inventory)), "lanes": lanes}
    return (json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path, default=BASE, help="directory containing all four frozen comparison paths")
    parser.add_argument("--git-repository", type=Path, help="read exact result blobs from their pinned commits")
    parser.add_argument("--historical", type=Path, default=HERE / "historical-adapter-runs.json.gz")
    parser.add_argument("--write", type=Path, help="write raw canonical audit JSON, otherwise compare against retained bytes")
    options = parser.parse_args()
    report_bytes = audit(options)
    if options.write:
        options.write.write_bytes(report_bytes)
    else:
        require(report_bytes == gzip.decompress((HERE / "audit-result.json.gz").read_bytes()), "retained audit mismatch")
    for form, data in json.loads(report_bytes)["forms"].items():
        print(form, data["zeroOutputSlots"], "empty slots;", data["candidateOccurrences"], "emissions;", data["distinctMatchCounts"])
