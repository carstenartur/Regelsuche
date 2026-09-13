#!/usr/bin/env python3
"""Checkout-owned acceptance of retained observations, never mathematical authority."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

PROFILES = ("DIRECT_ONLY", "THEORY_MATCHING", "SAFE_EXACT_PREPARATION", "SAFE_PREPARATION_PLUS_LOCAL_BRIDGE")
OPERATIONS = ("TRIGSIMP", "FU", "FACTOR", "CANCEL", "TOGETHER", "APART")
FILES = ("plan.json", "sources.json", "qualification.json", "candidate-freeze.json", "qualification-report.json")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def digest(raw):
    return "sha256:" + hashlib.sha256(raw).hexdigest()


def pairs(values):
    result = {}
    for key, value in values:
        require(key not in result, "duplicate JSON field")
        result[key] = value
    return result


def opaque(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 8_000_000,
            "missing/unsafe/oversized required file: " + path.name)
    return path.read_bytes()


def read(path):
    raw = opaque(path)
    value = json.loads(raw, object_pairs_hook=pairs)
    require(canonical(value) == raw, "noncanonical required file: " + path.name)
    return raw, value


def native_unavailability(evidence):
    """Project only the Java authority's recorded stage decisions, never source text."""
    result = None
    if isinstance(evidence, dict):
        for key in ("status", "matchStatus", "outcome"):
            status = evidence.get(key)
            if status == "TECHNICAL_FAILURE":
                return status
            if status in ("BUDGET_INCONCLUSIVE", "INCONCLUSIVE"):
                result = "BUDGET_INCONCLUSIVE"
        children = evidence.values()
    elif isinstance(evidence, list):
        children = evidence
    else:
        return result
    for child in children:
        nested = native_unavailability(child)
        if nested == "TECHNICAL_FAILURE":
            return nested
        result = nested or result
    return result


def bundle(root):
    require(root.is_dir() and not root.is_symlink(), "bundle directory missing/unsafe")
    files = {name: read(root / name) for name in FILES}
    _, receipt = read(root / "execution-receipt.json")
    require(receipt["schema"] == "regelsuche.amplification-execution-receipt/v1", "receipt schema differs")
    require(receipt["files"] == {name: digest(raw) for name, (raw, _) in files.items()}, "receipt file bindings differ")
    plan_raw, plan = files["plan.json"]
    require(plan["schema"] == "regelsuche.amplification-plan/v1", "plan schema differs")
    require(plan["repositoryRevision"] == receipt["repositoryRevision"], "subject revision differs")
    require([row["profile"] for row in plan["nativeProfiles"]] == list(PROFILES), "profile boundary/order differs")
    require([row["operation"] for row in plan["externalOperations"]] == list(OPERATIONS), "named operation boundary/order differs")
    require(plan["sourceHash"] == digest(files["sources.json"][0]) and
            plan["qualificationHash"] == digest(files["qualification.json"][0]), "sealed input binding differs")
    freeze_raw, freeze = files["candidate-freeze.json"]
    require(freeze["schema"] == "regelsuche.amplification-candidate-freeze/v1" and
            freeze["status"] == "ALL_PLANNED_CANDIDATE_ROWS_FROZEN" and
            freeze["qualificationExposure"] == "HASH_ONLY_NOT_OPENED", "incomplete candidate freeze")
    require(freeze["planHash"] == digest(plan_raw) and freeze["sourceHash"] == plan["sourceHash"] and
            freeze["qualificationHash"] == plan["qualificationHash"], "freeze input identities differ")
    sources = files["sources.json"][1]["sources"]
    expected_native = [(i, profile) for i in range(len(sources)) for profile in PROFILES]
    expected_external = [(i, operation) for i in range(len(sources)) for operation in OPERATIONS]
    require([(row["sourceIndex"], row["run"]["profile"]) for row in freeze["native"]] == expected_native,
            "native rows missing, duplicated, or reordered")
    require([(row["sourceIndex"], row["operation"]) for row in freeze["external"]] == expected_external,
            "external rows missing, duplicated, or reordered")
    for row in freeze["native"]:
        run = row["run"]
        observed = json.loads(run["canonicalJson"], object_pairs_hook=pairs)
        require(canonical(observed).decode() == run["canonicalJson"] and digest(canonical(observed)) == run["contentHash"], "native observation hash differs")
        configuration = plan["nativeProfiles"][PROFILES.index(run["profile"])]
        require(observed["configuration"] == configuration and run["configurationHash"] == digest(canonical(configuration)) and
                observed["configurationHash"] == run["configurationHash"], "native configuration differs")
        require(run["source"] == observed["source"] == sources[row["sourceIndex"]] and
                run["candidates"] == observed["candidates"], "native source/candidate projection differs")
        for candidate in run["candidates"]:
            require(digest(candidate["certificateJson"].encode()) == candidate["certificateHash"], "primitive certificate hash differs")
        work = observed["work"]
        require(sum(work["counters"].values()) == work["chargedUnits"] and all(value >= 0 for value in work["counters"].values()), "logical work ledger differs")
        exact = observed["exactPreparationWork"]
        require(sum(exact["counters"].values()) == exact["chargedUnits"] and
                all(value >= 0 for value in exact["counters"].values()) and 0 <= exact["chargedUnits"] <= exact["limit"], "exact work ledger differs")
    for row in freeze["external"]:
        outcome = row["outcome"]
        require(digest(outcome["canonicalJson"].encode()) == outcome["contentHash"], "external observation hash differs")
        observed = json.loads(outcome["canonicalJson"], object_pairs_hook=pairs)
        require(canonical(observed).decode() == outcome["canonicalJson"], "external observation is not canonical")
        configuration = plan["externalOperations"][OPERATIONS.index(row["operation"])]
        require(observed["configuration"] == configuration and
                observed["configurationHash"] == outcome["configurationHash"] == digest(canonical(configuration)),
                "external operation configuration differs")
        source_input = observed["input"]
        source = sources[row["sourceIndex"]]
        require(outcome["status"] == observed["status"] and outcome["output"] == observed["output"] and
                source_input["sourceExpression"] == source["expression"] and source_input["declaredAssumptions"] == source["assumptions"] and
                source_input["operation"] == row["operation"] and digest(canonical(source_input)) == observed["inputHash"],
                "external source/outcome projection differs")
    _, report = files["qualification-report.json"]
    require(report["schema"] == "regelsuche.amplification-qualification/v1" and report["planHash"] == digest(plan_raw) and
            report["candidateFreezeHash"] == digest(freeze_raw) and report["qualificationHash"] == plan["qualificationHash"],
            "qualification report binding differs")
    require([(row["sourceIndex"], row["profile"]) for row in report["independentReplay"]] == expected_native and
            all(row["verified"] for row in report["independentReplay"]), "native replay missing/duplicated/invalid")
    for retained, replay in zip(freeze["native"], report["independentReplay"]):
        run = retained["run"]
        require(replay["replayHash"] == run["contentHash"] and
                replay["verificationWork"] == json.loads(run["canonicalJson"])["work"] and
                replay["verificationExactPreparationWork"] == json.loads(run["canonicalJson"])["exactPreparationWork"], "independent replay observation differs")
    labels = files["qualification.json"][1]["rows"]
    require(len(labels) == len(sources) and sources, "nonempty source/qualification cardinality differs")
    require([(row["sourceIndex"], row["caseId"], row["profile"], row["runHash"]) for row in report["nativeRows"]] ==
            [(row["sourceIndex"], labels[row["sourceIndex"]]["caseId"], row["run"]["profile"], row["run"]["contentHash"]) for row in freeze["native"]],
            "native qualification row binding differs")
    native_statuses = Counter()
    for retained, row in zip(freeze["native"], report["nativeRows"]):
        # Bind the existing Java v1 availability projection; do not rejudge
        # mathematical reference reach or introduce another numeric work gate.
        observed = json.loads(retained["run"]["canonicalJson"])
        require(isinstance(observed["stages"], list), "native stage decisions missing")
        unavailable = native_unavailability(observed["stages"])
        status = row["status"]
        require(status in ("FALSE_POSITIVE", "REFERENCE_REACHED", "NEGATIVE_NO_CANDIDATE", "REFERENCE_NOT_REACHED",
                           "TECHNICAL_FAILURE", "BUDGET_INCONCLUSIVE"), "unknown native qualification status")
        require(status == unavailable if unavailable else status not in ("TECHNICAL_FAILURE", "BUDGET_INCONCLUSIVE"),
                "native qualification status differs from frozen availability")
        native_statuses[status] += 1
    require(report["statuses"] == dict(native_statuses), "native status summary differs from complete bound rows")
    require([(row["sourceIndex"], row["caseId"], row["operation"], row["outcomeHash"]) for row in report["externalRows"]] ==
            [(row["sourceIndex"], labels[row["sourceIndex"]]["caseId"], row["operation"], row["outcome"]["contentHash"]) for row in freeze["external"]],
            "external qualification row binding differs")
    require([row["status"] for row in report["externalRows"]] == [row["outcome"]["status"] for row in freeze["external"]],
            "external qualification outcome differs")
    require(report["falsePositives"] == 0 and report["invalidReplays"] == 0 and report["semanticEvidence"] != "FAILED", "semantic controls failed")
    require(report["comparativeGainClaim"] == "NOT_AUTHORIZED" and
            report["comparativeMatchedWorkGate"] == "BLOCKED_UNAVAILABLE_INTERNAL_WORK", "unmeasured comparative claim")
    return files, receipt, report


def verify(roots):
    require(len(roots) == 3, "two clean hosts and one pinned container required")
    checked = [bundle(Path(root)) for root in roots]
    receipts = [item[1] for item in checked]
    hosts = [receipt for receipt in receipts if receipt["kind"] == "host"]
    containers = [receipt for receipt in receipts if receipt["kind"] == "container"]
    require(len(hosts) == 2 and len(containers) == 1, "exactly two hosts and one container required")
    require(hosts[0]["observedMachineHash"] and hosts[0]["observedMachineHash"] != hosts[1]["observedMachineHash"],
            "two process runs on one observed host are not two hosts")
    require(all(receipt["cleanCheckout"] for receipt in receipts), "clean source authority missing")
    image = containers[0]["imageInspection"]
    require(image["Id"] == containers[0]["pinnedImageId"] and image["Id"].startswith("sha256:") and len(image["Id"]) == 71,
            "actual immutable image inspection missing/mismatched")
    require(image["Config"]["Labels"]["org.opencontainers.image.revision"] == containers[0]["repositoryRevision"], "container subject label differs")
    require(len({receipt["implementationHash"] for receipt in receipts}) == 1, "compiled authority classes differ")
    for name in FILES:
        require(len({raws[name][0] for raws, _, _ in checked}) == 1, "canonical disagreement: " + name)
    external_complete = all(row["status"] in ("COMPLETED", "UNSUPPORTED") for row in checked[0][2]["externalRows"])
    inconclusive = checked[0][2]["statuses"].get("BUDGET_INCONCLUSIVE", 0) + checked[0][2]["statuses"].get("TECHNICAL_FAILURE", 0)
    return {"schema": "regelsuche.amplification-reproduction/v1", "canonicalAgreement": True,
            "status": "REPRODUCED" if external_complete and inconclusive == 0 else "REPRODUCED_INCONCLUSIVE",
            "files": receipts[0]["files"], "implementationHash": receipts[0]["implementationHash"],
            "matchedWorkComparison": "BLOCKED_UNAVAILABLE_INTERNAL_WORK", "comparativeGainClaim": "NOT_AUTHORIZED",
            "independenceEvidence": "DISTINCT_OBSERVED_MACHINE_IDS_AND_DOCKER_IMAGE_INSPECTION; not remote attestation",
            "downloadedArchiveVerification": "NOT_PERFORMED_OR_CLAIMED"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("roots", nargs=3, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--require-conclusive", action="store_true")
    args = parser.parse_args()
    result = verify(args.roots)
    require(not args.output.exists(), "output must be fresh")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(canonical(result))
    if args.require_conclusive:
        require(result["status"] == "REPRODUCED", "required conclusive reproduction unavailable")
    print(result["status"] + "; matched-work comparison remains blocked")
