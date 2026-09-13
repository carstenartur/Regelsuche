"""Derive bounded product decisions from independently retained observations."""
from __future__ import annotations

from copy import deepcopy
import hashlib
import json
import re
from typing import Any


PROFILES = ("DIRECT_V1", "SAFE_PREPARATION_V4")
EXCLUDED_GAIN_STATUSES = {"TECHNICAL_FAILURE", "BUDGET_INCONCLUSIVE", "UNSUPPORTED"}
REPORT_SCHEMA = "regelsuche.safe-runtime-product-qualification/v1"
CONFIGURATION = "direct-v1-vs-safe-preparation-v4-actual-runtime-v1"
ARTIFACT_SCHEMA = "regelsuche.safe-runtime-artifact/v1"
MANIFEST_HASH = "sha256:3915a0630ee9f2a58a2f17a282a60a0a43bbf8d66e668a5d6cb0389964bf86d9"
SURFACES = ("cli-analyze", "http-analyze", "cli-replay", "http-replay")


def load_manifest(raw: bytes) -> dict:
    require(content_hash(raw) == MANIFEST_HASH, "public v1 criteria differ from the premeasurement manifest")
    return read_json(raw)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def canonical(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                      allow_nan=False).encode("utf-8")


def content_hash(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def read_json(raw: bytes | str) -> Any:
    def pairs(entries):
        result = {}
        for key, value in entries:
            require(key not in result, f"duplicate JSON field {key}")
            result[key] = value
        return result
    def invalid(value):
        raise ValueError(f"non-finite JSON number {value}")
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=invalid)


def expected_request(case: dict, profile: str) -> dict:
    result = deepcopy(case["commonRequest"])
    result["profile"] = profile
    if "representation" in result:
        # The adapter materializes these declared MatrixPreparation v1 defaults.
        typed = {"equations": "", "unknowns": [], "catalog": [], "operatorExpressions": [],
                 "eigenvalueParameter": "", "nonZeroVector": False, "matrixExpression": "", "rightHandSide": []}
        typed.update(result["representation"])
        typed["profile"] = ("RECOGNITION_ONLY_V1" if profile == "DIRECT_V1"
                            else "SAFE_PREPARED_REPRESENTATION_V1")
        result["representation"] = typed
    return result


def validate_artifact(raw: bytes, case: dict, profile: str, implementation: dict) -> dict:
    """Check retained structure; fresh replay by the delivered app remains mandatory."""
    artifact = read_json(raw)
    require(set(artifact) == {"schema", "contentHash", "evidence"}
            and artifact["schema"] == ARTIFACT_SCHEMA, "invalid runtime artifact schema")
    require(canonical(artifact) + b"\n" == raw, "runtime artifact bytes are not canonical")
    evidence = artifact["evidence"]
    require(artifact["contentHash"] == content_hash(canonical(evidence)), "runtime artifact hash differs")
    require(set(evidence) == {"adapterId", "authority", "implementation", "inventory", "outcomes",
                             "request", "retainedAssumptions", "status", "work"}, "unknown runtime evidence field")
    require(evidence["adapterId"] == "regelsuche.safe-product-runtime/v1", "unknown adapter")
    request = evidence["request"]
    require(request == expected_request(case, profile), "runtime request differs from the frozen manifest")
    require(evidence["retainedAssumptions"] == request["assumptions"], "runtime assumptions changed")
    require(evidence["implementation"] == implementation
            and implementation["revision"] == "regelsuche.runtime-class-content/v1"
            and implementation["authorityRevisionKind"] == "RUNTIME_CLASS_SHA256_PREFIX_160"
            and re.fullmatch(r"sha256:[0-9a-f]{64}", implementation["contentHash"]), "runtime implementation differs")
    inventory = evidence["inventory"]
    visible = inventory["visible"]
    require(inventory["visibleFingerprint"] == content_hash(canonical({"entries": visible})),
            "visible inventory fingerprint differs")
    require(len({entry["id"] for entry in visible}) == len(visible), "duplicate inventory rule")
    require(set(request["ruleIds"]) <= {entry["id"] for entry in visible}, "selected inventory rule missing")
    for entry in visible:
        selected = (entry["id"] in request["ruleIds"] or not request["ruleIds"])
        if entry["id"] == "sympy":
            selected &= request["includeSymPy"]
        require(entry["selected"] == selected, "inventory selection differs from request")
        if "preparationSupport" in entry:
            require(entry["preparationSupport"] == (entry["id"] in request["preparationRuleIds"]),
                    "inventory preparation selection differs")
    charged_work(evidence)
    require(evidence["work"]["revision"] == "regelsuche.safe-product-logical-work/v1", "unknown work authority")
    authority = evidence["authority"]
    version = "v3" if profile == "DIRECT_V1" else "v4"
    require(authority["coordinatorId"] == "regelsuche.unified-safe-rule-preparation-coordinator/" + version,
            "runtime coordinator differs from requested profile")
    require(authority["occurrenceBindingRevision"] == "regelsuche.direct-occurrence-guard-binding/v1"
            and authority["occurrenceWorkRevision"] == "regelsuche.occurrence-preparation-work/v2",
            "unknown occurrence authority")
    if profile == "DIRECT_V1":
        require(not authority.get("delegatedV2PrincipalIds") and not authority.get("contextualSuccessors")
                and "baseV3Evaluation" not in authority, "DIRECT contains preparation authority")
        require(all(outcome["status"] != "PREPARED" for outcome in evidence["outcomes"]),
                "DIRECT contains a prepared candidate")
    elif any(key in authority for key in ("baseV3Evaluation", "contextualSuccessors", "successorIdentityRevision", "contextualWork")):
        require(authority.get("successorIdentityRevision") == "regelsuche.contextual-occurrence-successor/v1",
                "unknown V4 successor identity authority")
        require(authority.get("contextualWork", {}).get("receipt", {}).get("revision") == "regelsuche.contextual-occurrence-work/v2",
                "unknown V4 contextual work authority")
    for _, candidate in candidates(evidence):
        require(candidate["retainedAssumptions"] == request["assumptions"], "candidate assumptions differ")
        execution = read_json(candidate["execution"])
        require(execution["schema"] == "regelsuche.recorded-execution/v1"
                and execution["transformedExpression"] == candidate["expression"], "candidate execution differs")
        for key in ("primitiveRewrites", "exactTheorySteps", "exactTheoryWorkUnits"):
            require(type(execution[key]) is int and execution[key] >= 0
                    and execution[key] == candidate["executionWork"][key], "candidate execution work differs")
        require(execution["primitiveRewrites"] == len(candidate["primitiveRuleIds"]), "candidate primitive lineage differs")
        require(execution["primitiveRewrites"] <= request["maxPrimitiveRewrites"]
                and execution["exactTheoryWorkUnits"] <= request["maxTheoryWorkUnits"], "candidate execution exceeds path budget")
    return evidence


def common_request(request: dict) -> dict:
    result = deepcopy(request)
    result.pop("profile", None)
    if "representation" in result:
        result["representation"].pop("profile", None)
    return result


def candidates(evidence: dict) -> list[tuple[str, dict]]:
    return [(outcome["id"], outcome["candidate"]) for outcome in evidence["outcomes"]
            if "candidate" in outcome]


def candidate_keys(evidence: dict) -> set[bytes]:
    return {canonical([principal, candidate["expression"], candidate["retainedAssumptions"]])
            for principal, candidate in candidates(evidence)}


def charged_work(evidence: dict) -> int:
    work = evidence["work"]
    for key in ("setupUnits", "analysisUnits", "verificationUnits", "chargedUnits",
                "configuredWorkUnits", "refusedReservationUnits"):
        require(type(work.get(key)) is int and work[key] >= 0, f"invalid charged work field {key}")
    require(work["chargedUnits"] == work["setupUnits"] + work["analysisUnits"] + work["verificationUnits"],
            "charged work does not retain setup, analysis and verification")
    require(work["configuredWorkUnits"] == evidence["request"]["maxWorkUnits"],
            "charged work budget differs from request")
    return work["chargedUnits"]


def excluded_statuses(evidence: dict) -> set[str]:
    return ({evidence["status"]} | {outcome.get("status") for outcome in evidence["outcomes"]}) & EXCLUDED_GAIN_STATUSES


def complete(evidence: dict) -> bool:
    return (not excluded_statuses(evidence)
            and evidence["work"]["refusedReservationUnits"] == 0
            and evidence["work"]["chargedUnits"] <= evidence["work"]["configuredWorkUnits"])


def evaluate_pair(case: dict, direct: dict, safe: dict) -> dict:
    """Evaluate a matched pair after its raw artifact identities are verified."""
    require(common_request(direct["request"]) == common_request(safe["request"]),
            "paired request source, assumptions, selection or budgets differ")
    for key in ("inventory", "implementation"):
        require(direct[key] == safe[key], f"paired {key} differs")
    direct_work, safe_work = charged_work(direct), charged_work(safe)
    expected = case["expected"]
    regression = bool(candidate_keys(direct) - candidate_keys(safe))
    expectations = not regression
    statuses = expected.get("statuses", {})
    for profile, evidence in zip(PROFILES, (direct, safe)):
        if profile in statuses:
            expectations &= evidence["status"] == statuses[profile]
        if not complete(evidence):
            excluded = excluded_statuses(evidence)
            within_budget = (evidence["work"]["refusedReservationUnits"] == 0
                             and evidence["work"]["chargedUnits"] <= evidence["work"]["configuredWorkUnits"])
            explicit_control = (not case["gainEligible"]
                                and case["kind"] in {"EXPECTED_FAILURE_CONTROL", "UNSUPPORTED_CONTROL"}
                                and statuses.get(profile) in EXCLUDED_GAIN_STATUSES
                                and statuses[profile] == evidence["status"]
                                and excluded == {statuses[profile]}
                                and (statuses[profile] == "BUDGET_INCONCLUSIVE" or within_budget))
            guard_rejection = (case["kind"] == "GUARD_CONTROL"
                               and excluded == {"UNSUPPORTED"} and within_budget
                               and any(outcome.get("id") == expected.get("principalId")
                                       and outcome.get("status") == "UNSUPPORTED" for outcome in evidence["outcomes"])
                               and all(outcome.get("status") not in EXCLUDED_GAIN_STATUSES
                                       or (outcome.get("id") == expected.get("principalId")
                                           and outcome.get("status") == "UNSUPPORTED") for outcome in evidence["outcomes"]))
            expectations &= explicit_control or guard_rejection

    direct_present = safe_present = False
    if "principalId" in expected:
        principal = expected["principalId"]
        direct_candidates = [candidate for key, candidate in candidates(direct) if key == principal]
        safe_candidates = [candidate for key, candidate in candidates(safe) if key == principal]
        direct_present, safe_present = bool(direct_candidates), bool(safe_candidates)
        expectations &= direct_present == expected["directPresent"]
        expectations &= safe_present == expected["safePresent"]
        for candidate in direct_candidates + safe_candidates:
            expectations &= candidate["retainedAssumptions"] == expected["retainedAssumptions"]
        if "safePrimitiveRuleIds" in expected:
            expectations &= all(candidate["primitiveRuleIds"] == expected["safePrimitiveRuleIds"]
                                for candidate in safe_candidates)

    if "safeSolution" in expected:
        typed_direct = read_json(direct["authority"]["typedArtifact"])["evidence"]
        typed_safe = read_json(safe["authority"]["typedArtifact"])["evidence"]
        expectations &= typed_direct.get("formation") == typed_safe.get("formation")
        rref = typed_safe.get("solving", {}).get("rref", {})
        expectations &= rref.get("relation") == expected["relation"]
        expectations &= rref.get("classification") == expected["safeClassification"]
        expectations &= rref.get("particularSolution") == expected["safeSolution"]

    gain = (case["gainEligible"] and expectations and not direct_present and safe_present
            and complete(direct) and complete(safe))
    return {"id": case["id"], "expectationsSatisfied": bool(expectations),
            "commonCandidateRegression": regression, "newPrincipalAvailable": bool(gain),
            "directPrincipalPresent": direct_present, "safePrincipalPresent": safe_present,
            "directCandidateCount": len(candidates(direct)), "safeCandidateCount": len(candidates(safe)),
            "directChargedWork": direct_work, "safeChargedWork": safe_work}


def mutation_requests(observations: dict) -> dict:
    """Fixed negative controls, derived from actual retained successful exports."""
    native = observations["native-square-preparation"]["SAFE_PREPARATION_V4"]
    typed = observations["typed-system-replay"]["SAFE_PREPARATION_V4"]
    learned = observations["learned-program-contract"]["SAFE_PREPARATION_V4"]
    result = {}
    for name, source in (("profile", native), ("primitive-lineage", native), ("typed-relation", typed),
                         ("learned-receipt", learned), ("implementation", native), ("work", native)):
        artifact = read_json(source)
        e = artifact["evidence"]
        if name == "profile":
            e["request"]["profile"] = "DIRECT_V1"
        elif name == "primitive-lineage":
            e["outcomes"][0]["candidate"]["primitiveRuleIds"][0] = "invented_preparation"
        elif name == "typed-relation":
            inner = read_json(e["authority"]["typedArtifact"])
            inner["evidence"]["solving"]["rref"]["relation"] = "SCALAR_EQUALITY"
            # Recompute the inner writer's insertion-order digest as well.
            inner["contentHash"] = content_hash(json.dumps(inner["evidence"], ensure_ascii=False,
                                                          separators=(",", ":")).encode())
            e["authority"]["typedArtifact"] = json.dumps(inner, ensure_ascii=False, separators=(",", ":")) + "\n"
        elif name == "learned-receipt":
            e["authority"]["learnedAuthorization"]["programs"][0]["receiptHash"] = "sha256:" + "0" * 64
        elif name == "implementation":
            e["implementation"]["contentHash"] = "sha256:" + "0" * 64
        elif name == "work":
            e["work"]["chargedUnits"] = 0
        artifact["contentHash"] = content_hash(canonical(e))
        result[name] = canonical(artifact) + b"\n"
    return result


def derive_report(root, manifest: dict, revision: str, inputs: dict, physical: dict) -> dict:
    require(re.fullmatch(r"[0-9a-f]{40}", revision), "invalid repository revision")
    observations, comparisons, files = {}, [], {}
    base_inventory = None
    selected, executed = set(), set()
    learned_snapshot = None
    for case in manifest["cases"]:
        pair, observations[case["id"]] = [], {}
        for profile in PROFILES:
            prefix = f"artifacts/{case['id']}/{profile}"
            raw = None
            for surface in SURFACES:
                relative = f"{prefix}/{surface}.json"
                path = root / relative
                require(path.is_file() and not path.is_symlink(), f"missing regular observation {relative}")
                current = path.read_bytes()
                if raw is None:
                    raw = current
                require(current == raw, f"surface or fresh replay bytes differ: {relative}")
                files[relative] = content_hash(current)
            e = validate_artifact(raw, case, profile, physical["implementation"])
            inventory = [{key: value for key, value in entry.items() if key not in {"selected", "preparationSupport"}}
                         for entry in e["inventory"]["visible"]]
            if base_inventory is None:
                base_inventory = inventory
                learned_snapshot = e["authority"]["learnedAuthorization"]
            require(inventory == base_inventory, "a case silently changes the full visible inventory")
            require(e["authority"]["learnedAuthorization"] == learned_snapshot, "learned authority differs between cases")
            selected.update(entry["id"] for entry in e["inventory"]["visible"] if entry["selected"])
            executed.update(principal for principal, _ in candidates(e))
            pair.append(e)
            observations[case["id"]][profile] = raw
        comparisons.append(evaluate_pair(case, *pair))
    require(len(learned_snapshot["patterns"]) == 2 and len(learned_snapshot["programs"]) == 1,
            "public learned leaf/program authority is absent")
    require(learned_snapshot["repositoryRevision"] == inputs["manifest"]["subjectRevision"], "learned subject changed")
    for item in learned_snapshot["patterns"] + learned_snapshot["programs"]:
        require(item["validUntil"] == inputs["manifest"]["positiveExpiry"], "learned validity interval changed")
    plugin = next(entry for entry in base_inventory if entry["id"] == "qualification_context_add_zero")
    require(plugin["coverage"] == "EXPLICIT_CUSTOM_SCHEMA" and plugin["schemaHash"]
            and not plugin["runtimePreparationEligible"]
            and plugin["runtimeExclusionReason"] == "PLUGIN_CONTEXT_REQUIRES_ORIGINAL_EXECUTOR",
            "plugin context fallback hides schema coverage or replaces the original executor")
    nested = read_json(observations["nested-prepared-guarded"]["SAFE_PREPARATION_V4"])["evidence"]
    require(nested["authority"].get("contextualSuccessors"), "nested prepared case has no retained V4 occurrence authority")
    controls = []
    for name, request in mutation_requests(observations).items():
        prefix = f"artifacts/controls/{name}"
        require((root / f"{prefix}/request.json").read_bytes() == request, "negative replay control request differs")
        expected = {"kind": "FRESH_REPLAY_MUTATION", "cliExit": 1, "httpStatus": 409,
                    "reason": "RUNTIME_DIFFERS_FROM_FRESH_TRUSTED_EXECUTION"}
        raw = (root / f"{prefix}/result.json").read_bytes()
        require(raw == canonical(expected) + b"\n", "negative replay control was not rejected")
        for name_suffix in ("request", "result"):
            relative = f"{prefix}/{name_suffix}.json"
            files[relative] = content_hash((root / relative).read_bytes())
        controls.append({"id": name, **expected})
    for profile in PROFILES:
        relative = f"artifacts/controls/expired-{profile}.json"
        raw = (root / relative).read_bytes()
        expected = {"kind": "REAL_CLOCK_EXPIRED_AUTHORITY", "cliExit": 1, "httpStatus": 400,
                    "negativeExpiry": inputs["manifest"]["negativeExpiry"], "clock": "PRODUCT_SYSTEM_UTC"}
        require(raw == canonical(expected) + b"\n", "expired public receipt was not rejected")
        controls.append({"id": "expired-" + profile, **expected})
        files[relative] = content_hash(raw)
    actual_files = {p.relative_to(root).as_posix() for p in (root / "artifacts").rglob("*") if p.is_file()}
    require(actual_files == set(files), "raw artifact file set differs from the complete public protocol")
    require(all(not p.is_symlink() for p in (root / "artifacts").rglob("*")), "symlink in raw artifacts")
    gains = sum(result["newPrincipalAvailable"] for result in comparisons)
    passed = all(result["expectationsSatisfied"] for result in comparisons) and gains >= manifest["criteria"]["minimumGains"]
    visible = {entry["id"] for entry in base_inventory}
    coverage = {"visibleRuleCount": len(visible), "selectedRuleCount": len(selected), "candidateRuleCount": len(executed),
        "selectedRuleIds": sorted(selected), "unselectedRuleIds": sorted(visible - selected),
        "runtimePreparationEligibleCount": sum(entry["runtimePreparationEligible"] for entry in base_inventory),
        "baseInventoryFingerprint": content_hash(canonical(base_inventory)), "completeVisibleInventorySelected": selected == visible}
    evidence = {"configurationId": CONFIGURATION, "repositoryRevision": revision, "manifestHash": MANIFEST_HASH,
        "criteria": manifest["criteria"], "inputs": inputs, "physicalRuntime": physical, "artifacts": files,
        "comparisons": comparisons, "controls": controls, "coverage": coverage,
        "summary": {"caseCount": len(comparisons), "profileCount": 2, "surfaceObservations": len(comparisons)*8,
            "gains": gains, "commonCandidateRegressions": sum(result["commonCandidateRegression"] for result in comparisons),
            "expectationFailures": sum(not result["expectationsSatisfied"] for result in comparisons),
            "directChargedWork": sum(result["directChargedWork"] for result in comparisons),
            "safeChargedWork": sum(result["safeChargedWork"] for result in comparisons)},
        "status": "QUALIFIED_BOUNDED_PUBLIC_CASES" if passed else "QUALIFICATION_FAILED",
        "defaultDecision": "KEEP_OPT_IN_BOUNDED_PUBLIC_COVERAGE" if passed else "KEEP_OPT_IN_QUALIFICATION_FAILED",
        "defaultChanged": False}
    return {"schema": REPORT_SCHEMA, "contentHash": content_hash(canonical(evidence)), "evidence": evidence}
