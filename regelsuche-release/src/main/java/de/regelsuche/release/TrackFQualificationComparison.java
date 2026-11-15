package de.regelsuche.release;

import static de.regelsuche.release.ReleaseReadinessBindings.require;
import static de.regelsuche.release.ReleaseReadinessBindings.sha256;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.experiments.autopilot.AutonomousProductionCampaignRunner;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Applies the existing public qualification suite to the rule retained by Track F. */
public final class TrackFQualificationComparison {
    private static final String ALLOCATION_HASH =
        "sha256:5c3a00f1db80ae5632ffe3ce734badbcde93575be1786094eb443c76c7801df0";
    private static final int ALLOCATION_BYTES = 651_684;
    private static final ObjectMapper JSON = new ObjectMapper();

    private TrackFQualificationComparison() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("expected <measured.json[.gz]> <qualification.json>");
        }
        AtomicJsonFile.writeUtf8(Path.of(args[1]), run(Path.of(args[0])));
    }

    static String run(Path frozenAllocation) throws IOException {
        JsonNode allocation = readAllocation(frozenAllocation);
        var campaign = new AutonomousProductionCampaignRunner().runPinned(1);
        require(allocation.required("originalCampaign").equals(JSON.readTree(campaign.toCanonicalJson())),
            "the Track F reference campaign did not reproduce");
        var lifecycle = campaign.lifecycle();
        var generationHash = lifecycle.mining().generation().contentHash();
        JsonNode hypothesis = JSON.readTree(lifecycle.lifecycleCandidateJson());
        JsonNode obligation = JSON.readTree(lifecycle.proof().obligation().toCanonicalJson());
        var run = new AutonomousCandidateQualificationRunner().run(campaign);

        ObjectNode report = JSON.createObjectNode();
        report.put("schema", "regelsuche.track-f-qualification/v1");
        report.put("stageAllocationSha256", ALLOCATION_HASH);
        report.put("qualificationExecutions", 1);
        report.put("scope", "Post-handoff evaluation of one identical rewrite on the public composite-factor suite");
        ObjectNode qualification = report.putObject("qualification");
        qualification.set("suite", JSON.readTree(run.suiteJson()));
        qualification.set("splitAudit", JSON.readTree(run.split().toCanonicalJson()));
        qualification.set("evaluation", JSON.readTree(run.evaluationJson()));
        qualification.set("utility", JSON.readTree(run.utility().toCanonicalJson()));
        qualification.set("evidence", JSON.readTree(run.evidence().toCanonicalJson()));
        qualification.set("run", JSON.readTree(run.toCanonicalJson()));

        var lanes = report.putArray("lanes");
        for (JsonNode lane : allocation.required("lanes")) {
            require(generationHash.equals(lane.required("generation").required("contentHash").asText()),
                "qualification split audit belongs to different formation inputs");
            ObjectNode result = lanes.addObject()
                .put("panel", lane.required("panel").asText())
                .put("arm", lane.required("arm").asText());
            var lineages = result.putArray("lineages");
            int qualified = 0;
            for (JsonNode candidate : lane.required("candidates")) {
                ObjectNode lineage = lineages.addObject()
                    .put("batch", candidate.required("batch").asInt())
                    .put("id", candidate.required("id").asText())
                    .put("lineageHash", candidate.required("lineageHash").asText())
                    .put("conjectureId", candidate.required("conjectureId").asText())
                    .put("allocationStatus", candidate.required("status").asText());
                if ("COMPLETED".equals(candidate.required("status").asText())) {
                    bindQualification(candidate, hypothesis, obligation, lineage, run.contentHash());
                    qualified++;
                } else {
                    lineage.put("qualificationStatus", "NOT_EVALUATED")
                        .put("reason", "LIFECYCLE_HANDOFF_NOT_COMPLETED");
                }
            }
            result.put("qualifiedLineages", qualified)
                .put("distinctQualifiedCandidates", qualified == 0 ? 0 : 1);
        }
        return JSON.writeValueAsString(report) + "\n";
    }

    private static JsonNode readAllocation(Path path) throws IOException {
        try (var source = Files.newInputStream(path);
                var decoded = path.toString().endsWith(".gz") ? new GZIPInputStream(source) : source) {
            byte[] bytes = decoded.readNBytes(ALLOCATION_BYTES + 1);
            require(bytes.length == ALLOCATION_BYTES && ALLOCATION_HASH.equals(sha256(bytes)),
                "expected the frozen Track F allocation");
            return JSON.readTree(bytes);
        }
    }

    private static void bindQualification(JsonNode candidate, JsonNode referenceHypothesis,
            JsonNode referenceObligation, ObjectNode lineage, String qualificationRunHash) {
        JsonNode receipts = candidate.required("receipts");
        JsonNode hypothesis = receipts.required("lifecycleCandidate");
        for (String field : List.of("id", "leftPattern", "rightPattern", "assumptions",
                "parameterRelations", "expressionPlaceholders")) {
            require(hypothesis.required(field).equals(referenceHypothesis.required(field)),
                "completed Track F lineage contains another qualification subject");
        }
        JsonNode obligation = receipts.required("solverObligation");
        for (String field : List.of("declarations", "theories", "assumptions", "goal", "requestedEvidence")) {
            require(obligation.required(field).equals(referenceObligation.required(field)),
                "completed Track F lineage has a different proof obligation");
        }
        JsonNode handoff = receipts.required("LIFECYCLE_HANDOFF");
        JsonNode proof = receipts.required("PROOF");
        require("COMPLETED".equals(handoff.required("outcome").asText())
                && handoff.required("candidateBranchId").equals(candidate.required("id"))
                && handoff.required("candidateId").equals(hypothesis.required("id"))
                && candidate.required("conjectureId").equals(hypothesis.required("id"))
                && handoff.required("proofEvidenceHash").equals(proof.required("evidenceHash"))
                && proof.required("solverObligationHash").equals(obligation.required("contentHash"))
                && "SYMBOLICALLY_VERIFIED".equals(proof.required("proofStatus").asText()),
            "qualification requires the completed Track F proof and handoff");
        lineage.put("qualificationStatus", "QUALIFIED")
            .put("qualificationRunHash", qualificationRunHash)
            .put("handoffHash", handoff.required("contentHash").asText())
            .put("formalProofStatus", proof.required("formalProofStatus").asText());
    }
}
