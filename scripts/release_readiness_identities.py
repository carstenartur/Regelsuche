"""Exact existing Java identities of direct release-root binding artifacts.

These projections check retained content, not mathematical execution or the full
campaign graph. Schema validation must precede their use.
"""

from __future__ import annotations

import hashlib


def sha256(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def java_string_key(value: str) -> bytes:
    return value.encode("utf-16-be", errors="surrogatepass")


def java_list(values) -> str:
    return "[" + ", ".join(values) + "]"


def java_hash(material: str) -> str:
    # Java strings contain UTF-16 units; its UTF-8 encoder replaces an unpaired
    # surrogate with '?', while a valid pair denotes one supplementary character.
    units = material.encode("utf-16-be", errors="surrogatepass")
    return sha256(units.decode("utf-16-be", errors="surrogatepass").encode("utf-8", errors="replace"))


def java_value(value) -> str:
    return str(value).lower() if isinstance(value, bool) else str(value)


def labeled_hash(document, fields, overrides=None):
    values = document | (overrides or {})
    return java_hash(document["schema"] + "".join(
        "\n" + label + "=" + java_value(values[field]) for label, field in fields))


# AutonomousCampaignReleaseEvidence.canonicalHash, in its declared v1 order.
SUMMARY_FIELDS = """
campaignManifestHash cleanRunManifestHashes cleanRunCount cleanRunsIdentical
targetFree seedFamilyCount observationCount candidateCount rejectedClusterCount
alphaDistinctSupport aggregateMiningComplete exactSupportingLineage
mandatorySkippedWorkCount heldOutFamilyOrClusterCount configuredPositiveHoldouts
executedPositiveHoldouts configuredNegativeHoldouts executedNegativeHoldouts
refutingHoldouts counterexampleStrategyCount counterexamplesFound
projectNoveltyStatus externalNoveltyStatus symbolicProofStatus formalProofStatus
unresolvedAssumptionCount lifecycleHandoffComplete pairedHeldOutUtilityEvaluated
pairedUtilityPermille hiddenReferenceIsolated hiddenRuleBenchmarkComplete
executableRediscoveryRetained publicEvidenceReviewed
""".split()


def summary_hash(document):
    return labeled_hash(document, [(name, name) for name in SUMMARY_FIELDS], {
        "cleanRunManifestHashes": java_list(sorted(document["cleanRunManifestHashes"], key=java_string_key))})


# HiddenRuleBenchmarkReleaseEvidence.canonicalHash, not the source report's hash.
HIDDEN_FIELDS = """
sourceSchema sourceReportHash cases families frozenCandidates materialAblations
acceptedCases rediscoveredCases configuredNegativeHoldouts executedNegativeHoldouts
skippedNegativeHoldouts falsePositiveHoldouts generatedValidationExamples
counterexampleSearches splitCollisionCount leakageViolationCount
acceptedIncompleteHoldoutCount executableRediscoveryCount hiddenReferenceIsolated
benchmarkComplete executableRediscoveryRetained
""".split()


def hidden_hash(document):
    return labeled_hash(document, [(name, name) for name in HIDDEN_FIELDS])


def campaign_hash(document):
    # AutonomousProductionCampaignRunner.run: v2 omits summary counts. Those
    # require separate cross-artifact checks; adding them here would change v2.
    artifacts = sorted(document["artifacts"], key=lambda row: java_string_key(row["artifactType"]))
    return labeled_hash(document, [
        ("brief", "briefHash"), ("lifecycle", "lifecycleRunHash"),
        ("nextPlan", "nextPlanHash"), ("round", "campaignRoundHash"),
        ("feedback", "feedbackReallocationHash"),
        ("resourceLedger", "campaignResourceLedgerHash"),
        ("artifacts", "artifacts"), ("status", "status"),
    ], {"artifacts": java_list(row["artifactType"] + "|" + row["contentHash"] for row in artifacts)})


def sorted_java_strings(values):
    # AutonomousCandidateQualificationEvidence.sorted uses String.isBlank,
    # distinct and String.compareTo. Python strip/isspace differs for NBSP/NEL.
    whitespace = "\t\n\v\f\r\x1c\x1d\x1e\x1f \u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2008\u2009\u200a\u2028\u2029\u205f\u3000"
    return sorted({value for value in values if value and any(char not in whitespace for char in value)},
                  key=java_string_key)


def qualification_hash(document):
    # AutonomousCandidateQualificationEvidence.hash, with its constructor's list normalization.
    overrides = {name: java_list(sorted_java_strings(document[name])) for name in (
        "supportingObservationIds", "sourceObservationBranchHashes", "parameterRelations", "assumptions")}
    overrides.update(relation=document["leftPattern"] + "->" + document["rightPattern"],
                     positive=str(document["executedPositiveHoldouts"]) + "/" + str(document["configuredPositiveHoldouts"]),
                     negative=str(document["executedNegativeHoldouts"]) + "/" + str(document["configuredNegativeHoldouts"]))
    return labeled_hash(document, [
        ("campaign", "campaignManifestHash"), ("brief", "briefHash"), ("inventory", "inventoryHash"),
        ("model", "modelHash"), ("conjecture", "conjectureId"), ("branch", "candidateBranchId"),
        ("mining", "miningEvidenceHash"), ("lineage", "lineageHash"),
        ("observations", "supportingObservationIds"), ("branchHashes", "sourceObservationBranchHashes"),
        ("relation", "relation"), ("parameterRelations", "parameterRelations"), ("assumptions", "assumptions"),
        ("revision", "suiteRevision"), ("suite", "suiteHash"), ("split", "splitAuditHash"),
        ("evaluation", "evaluationHash"), ("utility", "utilityHash"),
        ("heldOut", "heldOutFamilyOrClusterCount"), ("positive", "positive"), ("negative", "negative"),
        ("skipped", "mandatorySkippedWorkCount"), ("refuting", "refutingHoldouts"),
        ("counterexamples", "counterexamplesFound"), ("utilityEvaluated", "pairedHeldOutUtilityEvaluated"),
        ("utilityPermille", "pairedUtilityPermille"), ("regressions", "correctnessRegressionCount"),
        ("qualified", "qualified"),
    ], overrides)


def qualification_run_hash(document):
    # AutonomousCandidateQualificationRunner.run; public statuses are schema constants.
    return labeled_hash(document, [
        ("campaign", "campaignManifestHash"), ("suite", "suiteHash"),
        ("split", "splitAuditHash"), ("evaluation", "evaluationHash"),
        ("utility", "utilityHash"), ("evidence", "qualificationEvidenceHash"),
    ])


def verify_artifact_identities(campaign, evidence, hidden, qualification, qualification_run, require):
    for label, document, field, calculate in (
        ("campaign manifest", campaign, "contentHash", campaign_hash),
        ("campaign release evidence", evidence, "evidenceHash", summary_hash),
        ("hidden-rule evidence", hidden, "evidenceHash", hidden_hash),
        ("qualification evidence", qualification, "contentHash", qualification_hash),
        ("qualification run", qualification_run, "contentHash", qualification_run_hash),
    ):
        require(document[field] == calculate(document), label + " canonical identity differs")

    # These summaries are copied from CampaignRun by AutonomousCampaignReleaseEvidence.fromRuns.
    for field in ("seedFamilyCount", "observationCount", "candidateCount", "rejectedClusterCount"):
        require(campaign[field] == evidence[field], "campaign summary count differs: " + field)
    require(evidence["targetFree"] is (not campaign["targetProvided"]), "campaign target-free summary differs")
    for field in ("suiteHash", "splitAuditHash", "evaluationHash", "utilityHash", "qualified"):
        require(qualification_run[field] == qualification[field], "qualification run summary differs: " + field)
