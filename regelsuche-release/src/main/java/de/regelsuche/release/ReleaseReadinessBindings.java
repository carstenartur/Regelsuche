package de.regelsuche.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import de.regelsuche.experiments.autopilot.AutonomousProductionCampaignRunner;
import de.regelsuche.experiments.autopilot.AutonomousProductionCampaignRunner.ArtifactReference;
import de.regelsuche.release.ReleaseReadinessMatrix.MatrixReport;
import de.regelsuche.release.ReleaseReadinessMatrix.ProfileResult;
import de.regelsuche.release.ReleaseReadinessMatrix.ProfileStatus;
import de.regelsuche.release.ReleaseReadinessMatrix.RequirementCheck;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Verifies direct retained release bindings; it never executes a campaign. */
final class ReleaseReadinessBindings {
    private ReleaseReadinessBindings() { }
    static void verify(Map<String, JsonNode> documents, byte[] catalogBytes) {
        JsonNode run = document(documents, "release-readiness-run.json");
        JsonNode matrix = document(documents, "release-readiness-report.json");
        JsonNode campaign = document(documents, "campaign/production-campaign-manifest.json");
        JsonNode evidence = document(documents, "evidence-summary.json");
        JsonNode hidden = document(documents, "hidden-rule-release-evidence.json");
        JsonNode qualification = document(documents, "qualification/candidate-qualification-evidence.json");
        JsonNode qualificationRun = document(documents, "qualification/candidate-qualification-run.json");
        JsonNode catalog = document(documents, "profiles.json");

        // The original domain constructors own normalization, accounting and canonical identity.
        decode(evidence, AutonomousCampaignReleaseEvidence.class);
        decode(hidden, HiddenRuleBenchmarkReleaseEvidence.class);
        decode(qualification, AutonomousCandidateQualificationEvidence.class);
        require(text(campaign, "schema").equals(AutonomousProductionCampaignRunner.SCHEMA), "unsupported campaign schema");
        var artifacts = new ArrayList<ArtifactReference>();
        for (JsonNode artifact : array(campaign, "artifacts")) {
            artifacts.add(new ArtifactReference(text(artifact, "artifactType"), text(artifact, "contentHash")));
        }
        require(text(campaign, "contentHash").equals(AutonomousProductionCampaignRunner.manifestHash(
            text(campaign, "briefHash"), text(campaign, "lifecycleRunHash"), text(campaign, "nextPlanHash"),
            text(campaign, "campaignRoundHash"), text(campaign, "feedbackReallocationHash"),
            text(campaign, "campaignResourceLedgerHash"), artifacts, text(campaign, "status"))), "campaign canonical identity differs");
        require(text(qualificationRun, "schema").equals(AutonomousCandidateQualificationRunner.SCHEMA), "unsupported qualification run schema");
        require(text(qualificationRun, "contentHash").equals(AutonomousCandidateQualificationRunner.runHash(
            text(qualificationRun, "campaignManifestHash"), text(qualificationRun, "suiteHash"),
            text(qualificationRun, "splitAuditHash"), text(qualificationRun, "evaluationHash"),
            text(qualificationRun, "utilityHash"), text(qualificationRun, "qualificationEvidenceHash"))), "qualification run canonical identity differs");
        for (String field : List.of("seedFamilyCount", "observationCount", "candidateCount", "rejectedClusterCount")) {
            same(campaign, field, evidence, field, "campaign summary count differs: " + field);
        }
        require(bool(evidence, "targetFree") != bool(campaign, "targetProvided"), "campaign target-free summary differs");
        for (String field : List.of("suiteHash", "splitAuditHash", "evaluationHash", "utilityHash", "qualified")) {
            same(qualificationRun, field, qualification, field, "qualification run summary differs: " + field);
        }

        require(text(run, "schema").equals(ReleaseReadinessRunner.SCHEMA), "unsupported release run schema");
        Map<String, String> expected = Map.of("campaignManifestHash", text(campaign, "contentHash"),
            "profileCatalogHash", sha256(catalogBytes), "evidenceHash", text(evidence, "evidenceHash"),
            "hiddenRuleEvidenceHash", text(hidden, "evidenceHash"), "qualificationEvidenceHash", text(qualification, "contentHash"),
            "matrixHash", text(matrix, "contentHash"));
        expected.forEach((field, hash) -> require(text(run, field).equals(hash), "run " + field + " artifact binding differs"));
        for (JsonNode dependent : List.of(evidence, qualification, qualificationRun)) {
            same(dependent, "campaignManifestHash", run, "campaignManifestHash", "dependent evidence belongs to another campaign");
        }
        same(qualificationRun, "qualificationEvidenceHash", run, "qualificationEvidenceHash", "qualification run evidence binding differs");
        same(matrix, "evidenceHash", run, "evidenceHash", "matrix campaign evidence binding differs");
        same(matrix, "hiddenRuleEvidenceHash", run, "hiddenRuleEvidenceHash", "matrix hidden-rule evidence binding differs");
        same(run, "autonomousCampaignStatus", matrix, "autonomousCampaignStatus", "run autonomy summary differs");
        same(run, "autonomyClaimAuthorized", matrix, "autonomyClaimAuthorized", "run autonomy authority differs");
        verifyMatrix(matrix, catalog);
        require(text(run, "contentHash").equals(ReleaseReadinessRunner.runHash(text(run, "profileCatalogHash"),
            text(run, "evidenceHash"), text(run, "hiddenRuleEvidenceHash"), text(run, "qualificationEvidenceHash"),
            text(run, "matrixHash"), text(run, "campaignManifestHash"))), "run canonical content hash differs");
    }

    private static void verifyMatrix(JsonNode matrix, JsonNode catalog) {
        var profiles = profiles(matrix);
        var declared = profiles(catalog);
        require(text(catalog, "schema").equals(ReleaseEvidenceProfile.CATALOG_SCHEMA), "unsupported profile catalog schema");
        require(text(catalog, "autonomyClaimProfile").equals("AUTONOMOUS_CAMPAIGN")
            && text(catalog, "externalNoveltyProfile").equals("EXTERNAL_NOVELTY_REVIEW"), "catalog claim profile binding differs");
        var results = new ArrayList<ProfileResult>();
        profiles.forEach((name, profile) -> {
            JsonNode declaration = declared.get(name);
            require(bool(declaration, "authorizesAutonomyClaim") == name.authorizesAutonomyClaim(), "catalog profile authority differs");
            same(profile, "claim", declaration, "claim", "matrix profile claim differs from catalog");
            var checks = new ArrayList<RequirementCheck>();
            for (JsonNode check : array(profile, "checks")) checks.add(decode(check, RequirementCheck.class));
            var blockers = new ArrayList<String>();
            for (JsonNode blocker : array(profile, "blockers")) {
                require(blocker.isTextual(), "profile blocker must be text");
                blockers.add(blocker.textValue());
            }
            results.add(new ProfileResult(name, ProfileStatus.valueOf(text(profile, "status")),
                bool(profile, "authorizesAutonomyClaim"), checks, blockers));
        });
        new MatrixReport(text(matrix, "schema"), text(matrix, "evidenceHash"), text(matrix, "hiddenRuleEvidenceHash"),
            results, ProfileStatus.valueOf(text(matrix, "autonomousCampaignStatus")), bool(matrix, "autonomyClaimAuthorized"),
            text(matrix, "promotionStatus"), text(matrix, "publicEvidenceStatus"), text(matrix, "contentHash"));
    }

    private static Map<ReleaseEvidenceProfile, JsonNode> profiles(JsonNode document) {
        var result = new EnumMap<ReleaseEvidenceProfile, JsonNode>(ReleaseEvidenceProfile.class);
        for (JsonNode profile : array(document, "profiles")) {
            require(result.put(ReleaseEvidenceProfile.valueOf(text(profile, "profile")), profile) == null, "duplicate release profile");
        }
        require(result.size() == ReleaseEvidenceProfile.values().length, "missing release profile");
        return result;
    }

    private static <T> T decode(JsonNode node, Class<T> type) {
        try {
            return new ObjectMapper().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).treeToValue(node, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("retained " + type.getSimpleName() + " does not satisfy its original contract", error);
        }
    }

    static JsonNode document(Map<String, JsonNode> documents, String name) {
        JsonNode result = documents.get(name);
        require(result != null && result.isObject(), "missing object document: " + name);
        return result;
    }

    static JsonNode field(JsonNode object, String field) {
        JsonNode result = object.get(field);
        require(result != null, "missing field: " + field);
        return result;
    }
    static String text(JsonNode object, String name) {
        JsonNode value = field(object, name);
        require(value.isTextual(), "text field required: " + name);
        return value.textValue();
    }
    static boolean bool(JsonNode object, String name) {
        JsonNode value = field(object, name);
        require(value.isBoolean(), "boolean field required: " + name);
        return value.booleanValue();
    }
    static JsonNode array(JsonNode object, String name) {
        JsonNode value = field(object, name);
        require(value.isArray(), "array field required: " + name);
        return value;
    }
    static void same(JsonNode first, String firstField, JsonNode second, String secondField, String message) {
        require(field(first, firstField).equals(field(second, secondField)), message);
    }
    static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
    static String sha256(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
}
