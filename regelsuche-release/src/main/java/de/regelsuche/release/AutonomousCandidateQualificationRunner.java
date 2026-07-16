package de.regelsuche.release;

import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.CandidateOutput;
import de.regelsuche.experiments.autopilot.AutonomousProductionCampaignRunner.CampaignRun;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.OpenTargetConjectureEvaluator;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationPlan;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.release.ProductionCandidateQualificationSplitAudit.SplitAudit;
import de.regelsuche.release.ProductionCandidateUtilityEvaluator.UtilityReport;
import de.regelsuche.validation.CounterexampleSearchService.CounterexampleBudget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Executes the independent release qualification of the retained candidate. */
public final class AutonomousCandidateQualificationRunner {
    public static final String SCHEMA =
        "regelsuche.autonomous-candidate-qualification-run/v1";
    private static final int COUNTEREXAMPLE_SAMPLES = 256;

    public QualificationRun run(CampaignRun campaign) {
        Objects.requireNonNull(campaign, "campaign");
        var lifecycle = campaign.lifecycle();
        var mining = lifecycle.mining();
        var brief = mining.generation().brief();
        OpenTargetConjecture conjecture = lifecycle.conjecture();
        CandidateOutput output = retainedOutput(campaign, conjecture);
        validateLineage(conjecture, output);

        String suiteJson = suiteJson();
        String suiteHash = AutonomousResearchBriefV2.hash(suiteJson);
        SplitAudit split = new ProductionCandidateQualificationSplitAudit()
            .audit(campaign);
        EvaluationPlan plan = new EvaluationPlan(
            ProductionCandidateQualificationCatalog.REVISION,
            ProductionCandidateQualificationCatalog.positives().stream()
                .map(ProductionCandidateQualificationCatalog.PositiveCase::asHoldout)
                .toList(),
            ProductionCandidateQualificationCatalog.negatives(),
            new CounterexampleBudget(
                COUNTEREXAMPLE_SAMPLES,
                true,
                false,
                brief.deterministicSeed() ^ 359L,
                true,
                true,
                0,
                0L));
        EvaluationReport evaluation = new OpenTargetConjectureEvaluator()
            .evaluate(conjecture, plan);
        String evaluationJson = evaluationJson(evaluation);
        String evaluationHash = AutonomousResearchBriefV2.hash(evaluationJson);
        UtilityReport utility = new ProductionCandidateUtilityEvaluator()
            .evaluate(conjecture);

        int refuting = Math.addExact(
            (int) evaluation.positiveResults().stream()
                .filter(result -> !result.passed()).count(),
            (int) evaluation.negativeResults().stream()
                .filter(result -> !result.passed()).count());
        int counterexamples = "COUNTEREXAMPLE_FOUND".equals(
            evaluation.counterexample().status()) ? 1 : 0;
        int skipped = Math.addExact(
            evaluation.skippedPositiveHoldouts(),
            evaluation.skippedNegativeHoldouts());
        List<String> assumptions = conjecture.evidence().stream()
            .flatMap(evidence -> evidence.paths().stream())
            .flatMap(path -> path.assumptions().stream())
            .filter(value -> value != null && !value.isBlank())
            .distinct().sorted().toList();
        List<String> branchHashes = output.sources().stream()
            .map(source -> source.observationBranchHash())
            .sorted().toList();
        AutonomousCandidateQualificationEvidence evidence =
            AutonomousCandidateQualificationEvidence.create(
                campaign.contentHash(),
                brief.contentHash(),
                brief.inventoryHash(),
                brief.modelHash(),
                conjecture.conjectureId(),
                output.outputBranchId(),
                mining.fullBatch().evidence().contentHash(),
                mining.fullBatch().lineage().contentHash(),
                conjecture.supportingObservationIds(),
                branchHashes,
                conjecture.leftPattern(),
                conjecture.rightPattern(),
                conjecture.parameterRelations(),
                assumptions,
                ProductionCandidateQualificationCatalog.REVISION,
                suiteHash,
                split.contentHash(),
                evaluationHash,
                utility.contentHash(),
                split.heldOutFamilyOrClusterCount(),
                evaluation.configuredPositiveHoldouts(),
                evaluation.executedPositiveHoldouts(),
                evaluation.configuredNegativeHoldouts(),
                evaluation.executedNegativeHoldouts(),
                skipped,
                refuting,
                counterexamples,
                utility.pairedUtilityEvaluated(),
                utility.gainPermille(),
                utility.correctnessRegressionCount());
        validateSuccessfulQualification(split, evaluation, utility, evidence);
        String contentHash = AutonomousResearchBriefV2.hash(
            SCHEMA
                + "\ncampaign=" + campaign.contentHash()
                + "\nsuite=" + suiteHash
                + "\nsplit=" + split.contentHash()
                + "\nevaluation=" + evaluationHash
                + "\nutility=" + utility.contentHash()
                + "\nevidence=" + evidence.contentHash());
        return new QualificationRun(
            SCHEMA,
            campaign,
            suiteJson,
            suiteHash,
            split,
            evaluation,
            evaluationJson,
            evaluationHash,
            utility,
            evidence,
            contentHash);
    }

    public void write(Path outputDirectory, QualificationRun run) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(run, "run");
        try {
            Files.createDirectories(outputDirectory);
            write(outputDirectory.resolve("qualification-suite.json"),
                run.suiteJson());
            write(outputDirectory.resolve("qualification-split-audit.json"),
                run.split().toCanonicalJson());
            write(outputDirectory.resolve("qualification-evaluation.json"),
                run.evaluationJson());
            write(outputDirectory.resolve("qualification-utility.json"),
                run.utility().toCanonicalJson());
            write(outputDirectory.resolve("candidate-qualification-evidence.json"),
                run.evidence().toCanonicalJson());
            write(outputDirectory.resolve("candidate-qualification-run.json"),
                run.toCanonicalJson());
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write candidate qualification evidence", exception);
        }
    }

    private static CandidateOutput retainedOutput(
        CampaignRun campaign,
        OpenTargetConjecture conjecture
    ) {
        List<CandidateOutput> outputs = campaign.lifecycle().mining().fullBatch()
            .binding().receipt().outputs();
        if (outputs.size() != 1
                || !outputs.getFirst().conjectureId()
                    .equals(conjecture.conjectureId())) {
            throw new IllegalArgumentException(
                "qualification requires the exact retained production output");
        }
        return outputs.getFirst();
    }

    private static void validateLineage(
        OpenTargetConjecture conjecture,
        CandidateOutput output
    ) {
        TreeSet<String> declared = new TreeSet<>(
            conjecture.supportingObservationIds());
        TreeSet<String> linked = output.sources().stream()
            .map(source -> source.observationId())
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (!declared.equals(linked)
                || linked.size() != conjecture.supportCount()
                || output.sources().stream().anyMatch(source ->
                    !isSha(source.snapshotHash())
                        || !isSha(source.evidenceHash())
                        || !isSha(source.observationBranchHash()))) {
            throw new IllegalArgumentException(
                "qualification candidate lineage differs from production evidence");
        }
    }

    private static void validateSuccessfulQualification(
        SplitAudit split,
        EvaluationReport evaluation,
        UtilityReport utility,
        AutonomousCandidateQualificationEvidence evidence
    ) {
        if (!split.passed()
                || evaluation.status() != EvaluationStatus.ACCEPTED_FOR_PROOF
                || !evaluation.holdoutsComplete()
                || !evaluation.allHoldoutsPassed()
                || !"NO_COUNTEREXAMPLE_FOUND".equals(
                    evaluation.counterexample().status())
                || !evaluation.counterexample().inferredAssumptions().isEmpty()
                || !evaluation.counterexample().assignments().isEmpty()
                || !utility.beneficial()
                || !evidence.qualified()) {
            throw new IllegalStateException(
                "retained production candidate did not pass release qualification");
        }
    }

    private static String suiteJson() {
        List<ProductionCandidateQualificationCatalog.PositiveCase> positives =
            ProductionCandidateQualificationCatalog.positives().stream()
                .sorted(Comparator.comparing(
                    ProductionCandidateQualificationCatalog.PositiveCase::id))
                .toList();
        var negatives = ProductionCandidateQualificationCatalog.negatives().stream()
            .sorted(Comparator.comparing(item -> item.id())).toList();
        return new JsonWriter().beginObject()
            .property("schema",
                "regelsuche.autonomous-candidate-qualification-suite/v1")
            .property("revision", ProductionCandidateQualificationCatalog.REVISION)
            .property("heldOutClusterId",
                ProductionCandidateQualificationCatalog.HELD_OUT_CLUSTER_ID)
            .array("positives", array -> positives.forEach(item ->
                array.objectValue(object -> object
                    .property("id", item.id())
                    .property("factorExpression", item.factorExpression())
                    .property("inputExpression", item.inputExpression())
                    .property("targetExpression", item.targetExpression()))))
            .array("negatives", array -> negatives.forEach(item ->
                array.objectValue(object -> object
                    .property("id", item.id())
                    .property("inputExpression", item.inputExpression()))))
            .endObject().toString();
    }

    private static String evaluationJson(EvaluationReport report) {
        return new JsonWriter().beginObject()
            .property("schema", report.schema())
            .property("conjectureId", report.conjectureId())
            .property("status", report.status().name())
            .property("compilationStatus", report.compilationStatus())
            .property("dynamicRuleId", report.dynamicRuleId())
            .property("provenanceHash", report.provenanceHash())
            .property("configuredPositiveHoldouts",
                report.configuredPositiveHoldouts())
            .property("executedPositiveHoldouts",
                report.executedPositiveHoldouts())
            .property("skippedPositiveHoldouts",
                report.skippedPositiveHoldouts())
            .property("configuredNegativeHoldouts",
                report.configuredNegativeHoldouts())
            .property("executedNegativeHoldouts",
                report.executedNegativeHoldouts())
            .property("skippedNegativeHoldouts",
                report.skippedNegativeHoldouts())
            .array("positiveResults", array -> report.positiveResults().forEach(item ->
                array.objectValue(object -> object
                    .property("id", item.id())
                    .property("candidateCount", item.candidateCount())
                    .property("passed", item.passed())
                    .stringArray("candidateExpressions",
                        item.candidateExpressions()))))
            .array("negativeResults", array -> report.negativeResults().forEach(item ->
                array.objectValue(object -> object
                    .property("id", item.id())
                    .property("candidateCount", item.candidateCount())
                    .property("passed", item.passed())
                    .stringArray("candidateExpressions",
                        item.candidateExpressions()))))
            .property("counterexampleStatus", report.counterexample().status())
            .stringArray("counterexampleAttemptedSources",
                report.counterexample().attemptedSources())
            .stringArray("inferredAssumptions",
                report.counterexample().inferredAssumptions())
            .stringArray("counterexampleAssignments",
                report.counterexample().assignments())
            .stringArray("blockers", report.blockers())
            .property("promotionStatus", report.promotionStatus())
            .property("publicEvidenceStatus", report.publicEvidenceStatus())
            .endObject().toString();
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static boolean isSha(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    public record QualificationRun(
        String schema,
        CampaignRun campaign,
        String suiteJson,
        String suiteHash,
        SplitAudit split,
        EvaluationReport evaluation,
        String evaluationJson,
        String evaluationHash,
        UtilityReport utility,
        AutonomousCandidateQualificationEvidence evidence,
        String contentHash
    ) {
        public QualificationRun {
            if (!SCHEMA.equals(schema)
                    || !campaign.contentHash()
                        .equals(evidence.campaignManifestHash())
                    || !suiteHash.equals(evidence.suiteHash())
                    || !split.contentHash().equals(evidence.splitAuditHash())
                    || !evaluationHash.equals(evidence.evaluationHash())
                    || !utility.contentHash().equals(evidence.utilityHash())) {
                throw new IllegalArgumentException(
                    "candidate qualification run is not hash-linked");
            }
            if (!isSha(contentHash)) {
                throw new IllegalArgumentException("contentHash must be SHA-256");
            }
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("campaignManifestHash", campaign.contentHash())
                .property("suiteHash", suiteHash)
                .property("splitAuditHash", split.contentHash())
                .property("evaluationHash", evaluationHash)
                .property("utilityHash", utility.contentHash())
                .property("qualificationEvidenceHash", evidence.contentHash())
                .property("qualified", evidence.qualified())
                .property("promotionStatus", "NOT_EVALUATED")
                .property("publicEvidenceStatus", "NOT_EVALUATED")
                .property("contentHash", contentHash)
                .endObject().toString();
        }
    }
}
