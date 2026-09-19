"""Required retained structure, not the quality authorities' decision rules.

Field inventories follow the existing policy loaders and producers. No numeric
limit, benchmark identity, exception expiry or gate outcome is evaluated here.
"""

import math


def members(container, item, *, allow_empty=False):
    return container, item, allow_empty


REFERENCE = {"path": str, "sha256": str}
FLOORS = {"lineMinimumPercent": float, "branchMinimumPercent": float}
EXECUTION_POLICY = {"mode": str, "forks": int, "warmupIterations": int, "measurementIterations": int}
HOTSPOT = {"sourceFile": str, "signature": str, "cognitiveComplexity": int,
           "cyclomaticComplexity": int, "maxNestingDepth": int}

# These shapes only require the authorities' fields to be available for reading.
# In particular, empty exception/suppression lists and retained FAILED policies
# are legitimate evidence; their interpretation remains with the native gates.
POLICIES = {
    "config/quality/coverage-policy.json": ("regelsuche.quality.coverage-policy/v1", {
        "ratchetPolicy": str, "aggregate": FLOORS, "modules": members(dict, FLOORS)}),
    "config/quality/jmh-regression-policy-v2.json": ("regelsuche.quality.jmh-regression-policy/v2", {
        "baselineRevision": str, "baselineArtifactDigest": str,
        "execution": {**EXECUTION_POLICY, "jdkMajor": int, "jmhVersion": str},
        "benchmarks": members(list, {"benchmark": str, "family": str, "unit": str,
            "baselineScore": float, "baselineScoreError": float,
            "maximumAllowedScore": float, "maximumMultiplier": float}), "claimBoundary": str}),
    "config/quality/jmh-regression-decision-policy-v3.json": ("regelsuche.quality.jmh-regression-decision-policy/v3", {
        field: str for field in ("thresholdPolicyPath", "thresholdPolicySchema", "thresholdPolicyGitBlobSha1",
            "decisionStatistic", "failureCondition", "inconclusiveCondition", "boundaryPolicy",
            "lowPrecisionDiagnostic", "claimBoundary")}),
    "config/quality/jmh-baseline.json": ("regelsuche.quality.jmh-baseline/v2", {
        "baselineRevision": str,
        "measurementPolicy": {**EXECUTION_POLICY, "warmupTime": str, "measurementTime": str,
                              "materialRegressionRatio": float, "decisionRule": str},
        "benchmarks": members(dict, {"baselineScore": float, "scoreUnit": str})}),
    "config/quality/jmh-history-policy.json": ("regelsuche.quality.jmh-history-policy/v1", {
        "snapshots": members(list, REFERENCE), "lowerIsBetter": bool,
        "normalizedUnit": str, "claimBoundary": str}),
    "config/quality/complexity-hotspots.json": ("regelsuche.quality.complexity-hotspot-policy/v1", {
        "allowedCognitiveIncrease": int, "allowedCyclomaticIncrease": int,
        "baselineHotspots": members(list, HOTSPOT),
        "exceptions": members(list, {"id": str, "sourceFile": str, "signature": str,
            "maximumCognitiveComplexity": int, "maximumCyclomaticComplexity": int,
            "rationale": str, "expiresOn": str}, allow_empty=True)}),
    "ai-knowledge/complexity-baseline.json": (None, {
        "schemaVersion": int, "contextDebtModelVersion": str,
        **{field: float for field in ("estimatedContextTokens", "conceptRadius", "dependencyRadius",
            "knowledgeDensity", "contextLocality", "compressionRatio", "aiCognitiveComplexity",
            "aiCognitiveDebt", "aiContextDebt")}}),
    "app/src/e2eTest/resources/screenshots/visual-regression-policy.json": ("regelsuche.visual-regression-policy/v1", {
        "environment": {"containerImage": str, "playwrightVersion": str, "browser": str,
            "viewportWidth": int, "viewportHeight": int, "deviceScaleFactor": float,
            "locale": str, "timezoneId": str},
        "comparison": {"channelTolerance": int, "maxDiffRatio": float, "changedPixelArgb": str},
        "baselines": members(dict, {"gitBlobSha": str, "byteLength": int})}),
    "config/quality/supply-chain-policy.json": ("regelsuche.supply-chain-policy/v1", {
        "inventory": {"format": str, "specVersion": str, "generator": str, "generatorVersion": str,
            "includeBomSerialNumber": bool, "includeBuildSystem": bool, "rawTimestampTreatment": str},
        "vulnerabilityPolicy": {"status": str, "failOnCvssAtOrAbove": float,
            "unknownSeverity": str, "scannerFailure": str, "requiredDatabaseProperties": members(list, str),
            "suppressionRequirements": members(list, str)}, "claimBoundary": str}),
    "config/quality/supply-chain-vulnerability-policy.json": ("regelsuche.supply-chain-vulnerability-policy/v1", {
        "databaseManifest": REFERENCE, "scannerManifest": REFERENCE, "inventoryPolicy": str,
        "failOnCvssAtOrAbove": float, "scannerTimeoutSeconds": int, "suppressions": list,
        **{field: str for field in ("ambiguousSeverityGroup", "scannerFailure", "unknownSeverity",
                                    "suppressionPolicy", "claimBoundary")}}),
}

# The new production instances retain the native schemas and all historical
# decision fields. Keep the old instances available for historical collection.
for current, historical, metadata in (
    ("jmh-regression-policy-more-warmup-v1.json", "jmh-regression-policy-v2.json",
     {"executionRevision": str, "baselineExecution": {**EXECUTION_POLICY, "jdkMajor": int, "jmhVersion": str},
      "execution": {**EXECUTION_POLICY, "jdkMajor": int, "jmhVersion": str,
                    "warmupTime": str, "measurementTime": str}}),
    ("jmh-regression-decision-policy-more-warmup-v1.json", "jmh-regression-decision-policy-v3.json", {}),
    ("jmh-baseline-more-warmup-v1.json", "jmh-baseline.json",
     {"executionRevision": str,
      "baselineMeasurementPolicy": {**EXECUTION_POLICY, "warmupTime": str, "measurementTime": str,
                                    "materialRegressionRatio": float, "decisionRule": str}}),
):
    schema, fields = POLICIES["config/quality/" + historical]
    POLICIES["config/quality/" + current] = (schema, {**fields, **metadata})

DATABASE_MANIFEST = {
    "archive": REFERENCE, "metadata": REFERENCE, "upstreamRetention": REFERENCE,
    "licenses": members(list, REFERENCE),
    **{field: str for field in ("createdAt", "provider", "revision", "ecosystem", "url")},
}
SCANNER_MANIFEST = {
    **{field: REFERENCE for field in ("license", "release", "tag", "checksums", "provenance")},
    **{field: str for field in ("sha256", "provider", "revision", "version", "url")}, "bytes": int,
}
LATEST_SCAN = {"run": str, "decision": str, "evidenceHash": str}
SCANNER_INPUTS = {"components": members(list, {"purl": str, "source": str, "sha256": str})}
SCANNER_EXECUTION = {
    "outcome": str, "exitCode": (int, type(None)), "command": members(list, str),
    **{field + suffix: str for field in ("stdout", "stderr") for suffix in ("Path", "Hash")},
}


def require_structure(value, shape, field="document"):
    if isinstance(shape, dict):
        if not isinstance(value, dict):
            raise ValueError(field + " must be a JSON object")
        for name, member_shape in shape.items():
            if name not in value:
                raise ValueError(field + "." + name + " is required")
            require_structure(value[name], member_shape, field + "." + name)
    elif isinstance(shape, tuple) and len(shape) == 3 and shape[0] in (dict, list):
        container, item, allow_empty = shape
        if type(value) is not container or (not value and not allow_empty):
            raise ValueError(field + " must be a " + ("possibly empty " if allow_empty else "nonempty ")
                             + container.__name__ + " inventory")
        entries = value.items() if container is dict else enumerate(value)
        for key, member in entries:
            if container is dict:
                require_structure(key, str, field + " inventory key")
            require_structure(member, item, field + "[" + str(key) + "]")
    elif shape is float:
        if type(value) not in (int, float) or (type(value) is float and not math.isfinite(value)):
            raise ValueError(field + " must contain a finite number")
    elif type(value) not in (shape if isinstance(shape, tuple) else (shape,)):
        raise ValueError(field + " has the wrong JSON type")
    elif shape is str and not value.strip():
        raise ValueError(field + " must contain a nonempty string")
