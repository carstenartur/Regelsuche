package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousCampaignArtifactsV2.CampaignPlan;
import de.regelsuche.experiments.autopilot.AutonomousCampaignArtifactsV2.CampaignRound;
import de.regelsuche.experiments.autopilot.AutonomousCampaignFeedback.FeedbackDecision;
import de.regelsuche.experiments.autopilot.AutonomousCampaignResourceLedger.CampaignResourceLedger;
import de.regelsuche.experiments.autopilot.AutonomousProductionLifecycleRunner.LifecycleRun;
import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Completes the pinned issue-348 campaign with feedback and a deterministic next plan. */
public final class AutonomousProductionCampaignRunner {
    public static final String SCHEMA =
        "regelsuche.autonomous-production-campaign/v2";

    public CampaignRun runPinned(int parallelism) {
        return run(new AutonomousProductionLifecycleRunner().runPinned(parallelism));
    }

    CampaignRun run(LifecycleRun lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        var mining = lifecycle.mining();
        AutonomousResearchBriefV2 brief = mining.generation().brief();
        CampaignPlan nextPlan = AutonomousCampaignArtifactsV2.plan(brief, List.of());
        FeedbackDecision feedback = AutonomousCampaignFeedback.complete(
            lifecycle, nextPlan);
        CampaignRound round = AutonomousCampaignArtifactsV2.round(
            mining.plan(),
            mining.fullBatch().execution(),
            mining.fullBatch().lineage(),
            List.of(lifecycle.lifecycleDecision()),
            nextPlan);
        CampaignResourceLedger resourceLedger =
            AutonomousCampaignResourceLedger.create(lifecycle);
        List<ArtifactReference> artifacts = artifactReferences(
            lifecycle, nextPlan, round, feedback, resourceLedger);
        String contentHash = AutonomousResearchBriefV2.hash(
            SCHEMA
                + "\nbrief=" + brief.contentHash()
                + "\nlifecycle=" + lifecycle.contentHash()
                + "\nnextPlan=" + nextPlan.contentHash()
                + "\nround=" + round.contentHash()
                + "\nfeedback=" + feedback.contentHash()
                + "\nresourceLedger=" + resourceLedger.contentHash()
                + "\nartifacts=" + artifacts.stream()
                    .map(ArtifactReference::canonicalMaterial).toList()
                + "\nstatus=COMPLETED");
        return new CampaignRun(
            SCHEMA,
            lifecycle,
            nextPlan,
            round,
            feedback,
            resourceLedger,
            artifacts,
            "COMPLETED",
            2,
            12,
            1,
            1,
            false,
            false,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public void write(Path outputDirectory, CampaignRun run) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(run, "run");
        try {
            Files.createDirectories(outputDirectory);
            new AutonomousProductionLifecycleRunner().write(
                outputDirectory, run.lifecycle());
            write(outputDirectory.resolve("next-plan-v2.json"),
                run.nextPlan().toCanonicalJson());
            write(outputDirectory.resolve("campaign-round-v2.json"),
                run.round().toCanonicalJson());
            write(outputDirectory.resolve("feedback-reallocation.json"),
                run.feedback().toCanonicalJson());
            write(outputDirectory.resolve("campaign-resource-ledger.json"),
                run.resourceLedger().toCanonicalJson());
            write(outputDirectory.resolve("production-campaign-manifest.json"),
                run.toCanonicalJson());
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write complete production campaign evidence", exception);
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static List<ArtifactReference> artifactReferences(
        LifecycleRun lifecycle,
        CampaignPlan nextPlan,
        CampaignRound round,
        FeedbackDecision feedback,
        CampaignResourceLedger resourceLedger
    ) {
        var mining = lifecycle.mining();
        var generation = mining.generation();
        var full = mining.fullBatch();
        var rejection = mining.rejectionBatch();
        return List.of(
            ref("research-brief", generation.brief().contentHash()),
            ref("seed-catalog", generation.seedCatalog().contentHash()),
            ref("observation-bundle", generation.observationBundle().contentHash()),
            ref("generation-receipt", generation.receipt().contentHash()),
            ref("discovery-report", generation.discoveryReportHash()),
            ref("generation-run", generation.contentHash()),
            ref("initial-plan", mining.plan().contentHash()),
            ref("full-decision", full.decision().contentHash()),
            ref("full-mining-evidence", full.evidence().contentHash()),
            ref("full-binding", full.binding().contentHash()),
            ref("full-aggregate-receipt", full.binding().receipt().contentHash()),
            ref("full-execution", full.execution().contentHash()),
            ref("full-lineage", full.lineage().contentHash()),
            ref("rejection-decision", rejection.decision().contentHash()),
            ref("rejection-mining-evidence", rejection.evidence().contentHash()),
            ref("rejection-binding", rejection.binding().contentHash()),
            ref("rejection-aggregate-receipt",
                rejection.binding().receipt().contentHash()),
            ref("rejection-execution", rejection.execution().contentHash()),
            ref("rejection-lineage", rejection.lineage().contentHash()),
            ref("candidate-formation-receipt",
                mining.formationReceipt().contentHash()),
            ref("evidence-dag", mining.dag().contentHash()),
            ref("mining-run", mining.contentHash()),
            ref("validation-report", lifecycle.validationHash()),
            ref("counterexample-report", lifecycle.counterexampleHash()),
            ref("project-novelty-report", lifecycle.noveltyHash()),
            ref("proof-report", lifecycle.proof().evidenceHash()),
            ref("proof-obligation",
                lifecycle.proof().obligation().obligationHash()),
            ref("lifecycle-candidate", lifecycle.lifecycleCandidateHash()),
            ref("lifecycle-decision",
                lifecycle.lifecycleDecision().contentHash()),
            ref("stage-resource-ledger", lifecycle.stageLedger().contentHash()),
            ref("lifecycle-run", lifecycle.contentHash()),
            ref("next-plan", nextPlan.contentHash()),
            ref("campaign-round", round.contentHash()),
            ref("feedback-reallocation", feedback.contentHash()),
            ref("campaign-resource-ledger", resourceLedger.contentHash()))
            .stream()
            .sorted(Comparator.comparing(ArtifactReference::artifactType))
            .toList();
    }

    private static ArtifactReference ref(String type, String hash) {
        return new ArtifactReference(type, hash);
    }

    public record ArtifactReference(String artifactType, String contentHash) {
        public ArtifactReference {
            requireText(artifactType, "artifactType");
            requireSha256(contentHash, "contentHash");
        }

        String canonicalMaterial() {
            return artifactType + '|' + contentHash;
        }
    }

    public record CampaignRun(
        String schema,
        LifecycleRun lifecycle,
        CampaignPlan nextPlan,
        CampaignRound round,
        FeedbackDecision feedback,
        CampaignResourceLedger resourceLedger,
        List<ArtifactReference> artifacts,
        String status,
        int seedFamilyCount,
        int observationCount,
        int candidateCount,
        int rejectedClusterCount,
        boolean targetProvided,
        boolean campaignCompletionIsMathematicalEvidence,
        boolean externalNoveltyEvaluated,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public CampaignRun {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported production campaign schema");
            }
            lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            nextPlan = Objects.requireNonNull(nextPlan, "nextPlan");
            round = Objects.requireNonNull(round, "round");
            feedback = Objects.requireNonNull(feedback, "feedback");
            resourceLedger = Objects.requireNonNull(
                resourceLedger, "resourceLedger");
            artifacts = artifacts == null
                ? List.of()
                : artifacts.stream()
                    .sorted(Comparator.comparing(ArtifactReference::artifactType))
                    .toList();
            Set<String> types = artifacts.stream()
                .map(ArtifactReference::artifactType)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if (types.size() != artifacts.size() || artifacts.size() < 30) {
                throw new IllegalArgumentException(
                    "campaign manifest requires unique complete artifact references");
            }
            if (!"COMPLETED".equals(status)
                    || seedFamilyCount < 2
                    || observationCount < 12
                    || candidateCount < 1
                    || rejectedClusterCount < 1
                    || !nextPlan.decisions().isEmpty()
                    || !round.nextPlanHash().equals(nextPlan.contentHash())
                    || !feedback.nextPlanHash().equals(nextPlan.contentHash())
                    || !lifecycle.mining().generation().brief().contentHash()
                        .equals(resourceLedger.briefHash())
                    || targetProvided
                    || campaignCompletionIsMathematicalEvidence
                    || externalNoveltyEvaluated) {
                throw new IllegalArgumentException(
                    "production campaign is incomplete or overclaims its evidence");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash",
                    lifecycle.mining().generation().brief().contentHash())
                .property("generationRunHash",
                    lifecycle.mining().generation().contentHash())
                .property("miningRunHash", lifecycle.mining().contentHash())
                .property("lifecycleRunHash", lifecycle.contentHash())
                .property("initialPlanHash", lifecycle.mining().plan().contentHash())
                .property("nextPlanHash", nextPlan.contentHash())
                .property("campaignRoundHash", round.contentHash())
                .property("feedbackReallocationHash", feedback.contentHash())
                .property("campaignResourceLedgerHash",
                    resourceLedger.contentHash())
                .property("status", status)
                .property("seedFamilyCount", seedFamilyCount)
                .property("observationCount", observationCount)
                .property("candidateCount", candidateCount)
                .property("rejectedClusterCount", rejectedClusterCount)
                .array("artifacts", array -> artifacts.forEach(reference ->
                    array.objectValue(object -> object
                        .property("artifactType", reference.artifactType())
                        .property("contentHash", reference.contentHash()))))
                .property("targetProvided", targetProvided)
                .property("campaignCompletionIsMathematicalEvidence",
                    campaignCompletionIsMathematicalEvidence)
                .property("externalNoveltyEvaluated", externalNoveltyEvaluated)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }

    private static void requireNotEvaluated(String value, String name) {
        if (!"NOT_EVALUATED".equals(value)) {
            throw new IllegalArgumentException(name + " must be NOT_EVALUATED");
        }
    }
}
