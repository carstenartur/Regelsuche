package de.regelsuche.release;

import static de.regelsuche.release.ReleaseReadinessBindings.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only verification of retained qualified release evidence; no generation or qualification. */
public final class ReleaseReadinessEvidenceVerifier {
    static final List<String> ROOT_FILES = List.of("profiles.json", "evidence-summary.json", "hidden-rule-release-evidence.json",
        "release-readiness-report.json", "release-readiness-run.json");
    static final List<String> CAMPAIGN_FILES = List.of("production-campaign-manifest.json", "campaign-resource-ledger.json",
        "feedback-reallocation.json", "proof-report.json", "solver-obligation.json", "solver-result.json", "production-lifecycle-run.json");
    static final List<String> QUALIFICATION_FILES = List.of("qualification-suite.json", "qualification-split-audit.json",
        "qualification-evaluation.json", "qualification-utility.json", "candidate-qualification-evidence.json", "candidate-qualification-run.json");
    static final Map<String, String> SCHEMAS = Map.ofEntries(
        Map.entry("profiles.json", "regelsuche-release-evidence-profile-catalog-v1.schema.json"),
        Map.entry("evidence-summary.json", "regelsuche-autonomous-campaign-release-evidence-v1.schema.json"),
        Map.entry("hidden-rule-release-evidence.json", "regelsuche-hidden-rule-release-evidence-v1.schema.json"),
        Map.entry("campaign/production-campaign-manifest.json", "regelsuche-autonomous-production-campaign-v2.schema.json"),
        Map.entry("qualification/qualification-suite.json", "regelsuche-autonomous-candidate-qualification-suite-v1.schema.json"),
        Map.entry("qualification/qualification-split-audit.json", "regelsuche-autonomous-candidate-qualification-split-v1.schema.json"),
        Map.entry("qualification/qualification-evaluation.json", "regelsuche-open-target-conjecture-evaluation-v1.schema.json"),
        Map.entry("qualification/qualification-utility.json", "regelsuche-autonomous-candidate-qualified-utility-v1.schema.json"),
        Map.entry("qualification/candidate-qualification-evidence.json", "regelsuche-autonomous-candidate-qualification-v1.schema.json"),
        Map.entry("qualification/candidate-qualification-run.json", "regelsuche-autonomous-candidate-qualification-run-v1.schema.json"),
        Map.entry("release-readiness-report.json", "regelsuche-release-readiness-matrix-v1.schema.json"),
        Map.entry("release-readiness-run.json", "regelsuche-release-readiness-run-v1.schema.json"),
        Map.entry("campaign/solver-obligation.json", "regelsuche-solver-obligation-v1.schema.json"),
        Map.entry("campaign/solver-result.json", "regelsuche-solver-result-v1.schema.json"),
        Map.entry("campaign/proof-report.json", "regelsuche-open-target-conjecture-proof-v2.schema.json"),
        Map.entry("campaign/production-lifecycle-run.json", "regelsuche-autonomous-production-lifecycle-v3.schema.json"));

    private ReleaseReadinessEvidenceVerifier() { }

    public static void verify(Path root, Path schemaRoot) throws IOException {
        try (var files = new OwnedReleaseEvidenceFiles(root); var schemas = new OwnedReleaseEvidenceFiles(schemaRoot)) {
            var documents = new LinkedHashMap<String, JsonNode>();
            for (String relative : ROOT_FILES) load(files, documents, relative);
            for (String relative : CAMPAIGN_FILES) load(files, documents, "campaign/" + relative);
            for (String relative : QUALIFICATION_FILES) load(files, documents, "qualification/" + relative);
            require(!files.present(Path.of("campaign/proof-obligation.json")), "legacy proof-obligation.json must not be retained");
            var json = new ReleaseEvidenceJson();
            for (var pair : SCHEMAS.entrySet()) json.validate(documents.get(pair.getKey()),
                ReleaseEvidenceJson.parse(schemas.readBytes(Path.of(pair.getValue())), pair.getValue()), pair.getKey());
            ReleaseReadinessBindings.verify(documents, files.readBytes(Path.of("profiles.json")));
            verifyQualifiedSemantics(documents);
        }
    }

    private static void load(OwnedReleaseEvidenceFiles files, Map<String, JsonNode> documents, String relative) throws IOException {
        byte[] bytes = files.readBytes(Path.of(relative));
        require(bytes.length > 0, "empty file: " + relative);
        // The existing gate requires both resource ledgers to be nonempty, without
        // declaring a schema or content identity for their payloads in this verifier.
        if (SCHEMAS.containsKey(relative)) documents.put(relative, ReleaseEvidenceJson.parse(bytes, relative));
    }

    private static void verifyQualifiedSemantics(Map<String, JsonNode> documents) {
        JsonNode obligation = document(documents, "campaign/solver-obligation.json");
        JsonNode result = document(documents, "campaign/solver-result.json");
        JsonNode proof = document(documents, "campaign/proof-report.json");
        JsonNode lifecycle = document(documents, "campaign/production-lifecycle-run.json");
        same(result, "obligationHash", obligation, "contentHash", "solver result obligation hash mismatch");
        for (JsonNode dependent : List.of(proof, lifecycle)) {
            same(dependent, "solverObligationHash", obligation, "contentHash", "proof/lifecycle obligation hash mismatch");
            same(dependent, "solverResultHash", result, "contentHash", "proof/lifecycle result hash mismatch");
        }
        require(text(result, "status").equals("CONFIRMED"), "solver result is not CONFIRMED");
        require(text(result, "translationStatus").equals("LOSSLESS"), "solver result translation is not LOSSLESS");
        JsonNode matrix = document(documents, "release-readiness-report.json");
        JsonNode hidden = document(documents, "hidden-rule-release-evidence.json");
        JsonNode run = document(documents, "release-readiness-run.json");
        JsonNode qualification = document(documents, "qualification/candidate-qualification-evidence.json");
        JsonNode split = document(documents, "qualification/qualification-split-audit.json");
        JsonNode utility = document(documents, "qualification/qualification-utility.json");
        Map<String, String> expectedProfiles = Map.of("HIDDEN_RULE_REDISCOVERY", "READY", "OPEN_TARGET_DISCOVERY", "READY",
            "AUTONOMOUS_CAMPAIGN", "READY", "EXTERNAL_NOVELTY_REVIEW", "BLOCKED");
        for (JsonNode profile : array(matrix, "profiles")) {
            String expected = expectedProfiles.get(text(profile, "profile"));
            if (expected != null) require(text(profile, "status").equals(expected), "release profile status drift");
        }
        counts(hidden, Map.of("cases", 20, "families", 4, "configuredNegativeHoldouts", 40,
            "executedNegativeHoldouts", 38, "skippedNegativeHoldouts", 2, "falsePositiveHoldouts", 0));
        truths(hidden, "hiddenReferenceIsolated", "benchmarkComplete", "executableRediscoveryRetained");
        truths(split, "passed");
        counts(split, Map.of("heldOutFamilyOrClusterCount", 1));
        require(array(split, "upstreamCollisions").isEmpty() && array(split, "internalCollisions").isEmpty(), "qualification split collisions");
        counts(qualification, Map.of("configuredPositiveHoldouts", 12, "executedPositiveHoldouts", 12,
            "configuredNegativeHoldouts", 12, "executedNegativeHoldouts", 12, "mandatorySkippedWorkCount", 0,
            "refutingHoldouts", 0, "counterexamplesFound", 0, "correctnessRegressionCount", 0));
        truths(qualification, "pairedHeldOutUtilityEvaluated", "qualified");
        positive(qualification, "pairedUtilityPermille");
        truths(utility, "beneficial");
        positive(utility, "materialGainCount");
        counts(utility, Map.of("correctnessRegressionCount", 0));
        same(matrix, "hiddenRuleEvidenceHash", hidden, "evidenceHash", "matrix hidden-rule hash mismatch");
        require(text(run, "hiddenRuleEvidenceStatus").equals("BOUND"), "hidden-rule evidence not bound");
        same(run, "hiddenRuleEvidenceHash", hidden, "evidenceHash", "run hidden-rule hash mismatch");
        require(text(run, "qualificationEvidenceStatus").equals("BOUND"), "qualification evidence not bound");
        same(run, "qualificationEvidenceHash", qualification, "contentHash", "qualification evidence hash mismatch");
        for (JsonNode source : List.of(run, matrix)) {
            require(text(source, "autonomousCampaignStatus").equals("READY"), "autonomy status drift");
            truths(source, "autonomyClaimAuthorized");
        }
        require(text(run, "promotionStatus").equals("NOT_EVALUATED"), "promotion status drift");
        require(text(run, "publicEvidenceStatus").equals("NOT_EVALUATED"), "public evidence status drift");
    }

    private static void counts(JsonNode document, Map<String, Integer> expected) {
        expected.forEach((name, count) -> require(field(document, name).isIntegralNumber()
            && field(document, name).bigIntegerValue().equals(java.math.BigInteger.valueOf(count)), "count drift: " + name));
    }
    private static void truths(JsonNode document, String... fields) {
        for (String name : fields) require(bool(document, name), "required evidence absent: " + name);
    }
    private static void positive(JsonNode document, String name) {
        require(field(document, name).isNumber() && field(document, name).decimalValue().signum() > 0, "positive value required: " + name);
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of("regelsuche-release/build/reports/release-readiness-qualified");
        Path schemas = Path.of("docs/schemas");
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) throw new IllegalArgumentException("expected --root PATH or --schemas PATH");
            switch (args[i]) {
                case "--root" -> root = Path.of(args[i + 1]);
                case "--schemas" -> schemas = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown verifier option: " + args[i]);
            }
        }
        verify(root, schemas);
        System.out.println("releaseReadinessRoot=" + root);
        System.out.println("jsonSchemaValidator=" + ReleaseEvidenceJson.VALIDATOR_VERSION);
        System.out.println("release-readiness-contract=valid");
    }
}
