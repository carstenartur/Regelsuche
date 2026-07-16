package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousCandidateLifecycleV2.LifecycleDecision;
import de.regelsuche.experiments.autopilot.AutonomousCandidateLifecycleV2.LifecycleOutcome;
import de.regelsuche.experiments.autopilot.AutonomousProductionMiningRunner.MiningRun;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.experiments.autopilot.AutonomousStageResourceLedger.StageResourceLedger;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.OpenTargetConjectureEvaluator;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationPlan;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.NegativeHoldout;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.PositiveHoldout;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyReport;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofReport;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofStatus;
import de.regelsuche.mining.OpenTargetHypothesisCandidateAdapter;
import de.regelsuche.validation.CounterexampleSearchService.CounterexampleBudget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Executes all downstream production gates after aggregate candidate formation. */
public final class AutonomousProductionLifecycleRunner {
    public static final String SCHEMA =
        "regelsuche.autonomous-production-lifecycle/v2";
    private static final String VALIDATION_REVISION =
        "production-validation-holdouts-348/v1";
    private static final int RANDOM_COUNTEREXAMPLE_SAMPLES = 64;
    private static final Instant LIFECYCLE_CREATED_AT =
        Instant.parse("2026-07-15T00:00:00Z");

    public LifecycleRun runPinned(int parallelism) {
        return run(new AutonomousProductionMiningRunner().runPinned(parallelism));
    }

    LifecycleRun run(MiningRun mining) {
        Objects.requireNonNull(mining, "mining");
        var brief = mining.generation().brief();
        OpenTargetConjecture conjecture = retainedConjecture(mining);
        String candidateBranchId = retainedCandidateBranchId(mining, conjecture);

        EvaluationPlan plan = evaluationPlan(brief.deterministicSeed());
        EvaluationReport evaluation = new OpenTargetConjectureEvaluator()
            .evaluate(conjecture, plan);
        String validationJson = AutonomousLifecycleEvidenceJson.validation(evaluation);
        String validationHash = AutonomousResearchBriefV2.hash(validationJson);
        String counterexampleJson = AutonomousLifecycleEvidenceJson.counterexample(
            conjecture.conjectureId(), evaluation);
        String counterexampleHash = AutonomousResearchBriefV2.hash(counterexampleJson);

        NoveltyReport novelty = new OpenTargetConjectureNoveltyChecker().check(
            conjecture, new KnownRuleRepository(), List.of());
        String noveltyJson = AutonomousLifecycleEvidenceJson.novelty(novelty);
        String noveltyHash = AutonomousResearchBriefV2.hash(noveltyJson);

        ProofReport proof = new OpenTargetConjectureProofGate().evaluate(
            conjecture, evaluation);
        HypothesisCandidate lifecycleCandidate =
            new OpenTargetHypothesisCandidateAdapter().adapt(
                conjecture, evaluation, LIFECYCLE_CREATED_AT);
        String lifecycleCandidateJson =
            AutonomousLifecycleEvidenceJson.lifecycleCandidate(lifecycleCandidate);
        String lifecycleCandidateHash = AutonomousResearchBriefV2.hash(
            lifecycleCandidateJson);
        LifecycleDecision lifecycleDecision = AutonomousCandidateLifecycleV2.decide(
            candidateBranchId, novelty, proof, lifecycleCandidate);

        validateSuccessfulLifecycle(
            evaluation, novelty, proof, lifecycleCandidate, lifecycleDecision);
        StageResourceLedger stageLedger = stageLedger(
            brief,
            evaluation,
            validationHash,
            plan.counterexampleBudget(),
            counterexampleHash,
            novelty,
            noveltyHash,
            proof,
            lifecycleDecision);
        String contentHash = AutonomousResearchBriefV2.hash(
            SCHEMA
                + "\nmining=" + mining.contentHash()
                + "\ncandidateBranch=" + candidateBranchId
                + "\nconjecture=" + conjecture.conjectureId()
                + "\nvalidation=" + validationHash
                + "\ncounterexample=" + counterexampleHash
                + "\nnovelty=" + noveltyHash
                + "\nproof=" + proof.evidenceHash()
                + "\nproofObligation=" + proof.obligation().obligationHash()
                + "\nlifecycleCandidate=" + lifecycleCandidateHash
                + "\nlifecycleDecision=" + lifecycleDecision.contentHash()
                + "\nstageLedger=" + stageLedger.contentHash());
        return new LifecycleRun(
            SCHEMA,
            mining,
            candidateBranchId,
            conjecture,
            evaluation,
            validationJson,
            validationHash,
            counterexampleJson,
            counterexampleHash,
            novelty,
            noveltyJson,
            noveltyHash,
            proof,
            lifecycleCandidate,
            lifecycleCandidateJson,
            lifecycleCandidateHash,
            lifecycleDecision,
            stageLedger,
            false,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public void write(Path outputDirectory, LifecycleRun run) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(run, "run");
        try {
            Files.createDirectories(outputDirectory);
            new AutonomousProductionMiningRunner().write(outputDirectory, run.mining());
            write(outputDirectory.resolve("validation-report.json"), run.validationJson());
            write(outputDirectory.resolve("counterexample-report.json"), run.counterexampleJson());
            write(outputDirectory.resolve("project-novelty-report.json"), run.noveltyJson());
            write(outputDirectory.resolve("proof-report.json"), run.proof().toCanonicalJson());
            write(outputDirectory.resolve("proof-obligation.json"),
                AutonomousLifecycleEvidenceJson.proofObligation(run.proof().obligation()));
            write(outputDirectory.resolve("lifecycle-candidate.json"),
                run.lifecycleCandidateJson());
            write(outputDirectory.resolve("lifecycle-decision.json"),
                run.lifecycleDecision().toCanonicalJson());
            write(outputDirectory.resolve("stage-resource-ledger.json"),
                run.stageLedger().toCanonicalJson());
            write(outputDirectory.resolve("production-lifecycle-run.json"),
                run.toCanonicalJson());
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write production lifecycle evidence", exception);
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static EvaluationPlan evaluationPlan(long deterministicSeed) {
        return new EvaluationPlan(
            VALIDATION_REVISION,
            List.of(
                new PositiveHoldout(
                    "positive-gap-53",
                    "(53 + 2) * q + 53 * q",
                    "(2 * 53 + 2) * q"),
                new PositiveHoldout(
                    "positive-gap-67",
                    "(67 + 2) * r + 67 * r",
                    "(2 * 67 + 2) * r"),
                new PositiveHoldout(
                    "positive-expression-71",
                    "(71 + 2) * (u + v) + 71 * (u + v)",
                    "(2 * 71 + 2) * (u + v)")),
            List.of(
                new NegativeHoldout(
                    "negative-gap-three",
                    "(53 + 3) * q + 53 * q"),
                new NegativeHoldout(
                    "negative-second-coefficient",
                    "(67 + 2) * r + 68 * r"),
                new NegativeHoldout(
                    "negative-distinct-factor",
                    "(71 + 2) * (u + v) + 71 * (u - v)")),
            new CounterexampleBudget(
                RANDOM_COUNTEREXAMPLE_SAMPLES,
                true,
                false,
                deterministicSeed,
                true,
                true,
                0,
                0L));
    }

    private static OpenTargetConjecture retainedConjecture(MiningRun mining) {
        List<OpenTargetConjecture> conjectures = mining.fullBatch()
            .evidence().report().conjectures();
        if (conjectures.size() != 1) {
            throw new IllegalStateException(
                "pinned lifecycle slice requires exactly one retained conjecture");
        }
        return conjectures.getFirst();
    }

    private static String retainedCandidateBranchId(
        MiningRun mining,
        OpenTargetConjecture conjecture
    ) {
        var outputs = mining.fullBatch().binding().receipt().outputs();
        if (outputs.size() != 1
                || !conjecture.conjectureId().equals(outputs.getFirst().conjectureId())) {
            throw new IllegalStateException(
                "pinned lifecycle slice requires one hash-linked candidate branch");
        }
        return outputs.getFirst().outputBranchId();
    }

    private static void validateSuccessfulLifecycle(
        EvaluationReport evaluation,
        NoveltyReport novelty,
        ProofReport proof,
        HypothesisCandidate lifecycleCandidate,
        LifecycleDecision lifecycleDecision
    ) {
        if (evaluation.status() != EvaluationStatus.ACCEPTED_FOR_PROOF
                || !evaluation.holdoutsComplete()
                || !evaluation.allHoldoutsPassed()
                || !"NO_COUNTEREXAMPLE_FOUND".equals(
                    evaluation.counterexample().status())
                || evaluation.counterexample().attemptedSources().isEmpty()
                || !evaluation.counterexample().inferredAssumptions().isEmpty()
                || !evaluation.counterexample().assignments().isEmpty()) {
            throw new IllegalStateException(
                "production candidate did not pass validation: " + evaluation);
        }
        if (novelty.status() != NoveltyStatus.NOVEL_WITHIN_PROJECT
                || !novelty.matches().isEmpty()
                || !"NOT_EVALUATED".equals(novelty.externalNoveltyStatus())) {
            throw new IllegalStateException(
                "production candidate did not pass project novelty: " + novelty);
        }
        if (proof.proofStatus() != ProofStatus.SYMBOLICALLY_VERIFIED
                || !proof.proofObligationEmitted()
                || proof.obligation().targetProvided()
                || !proof.blockers().isEmpty()
                || !"NOT_EVALUATED".equals(proof.formalProofStatus())) {
            throw new IllegalStateException(
                "production proof gate did not verify the candidate: " + proof);
        }
        if (!lifecycleCandidate.id().equals(evaluation.conjectureId())
                || lifecycleDecision.outcome() != LifecycleOutcome.COMPLETED
                || lifecycleDecision.terminal()
                || lifecycleDecision.promotionAttempted()
                || lifecycleDecision.publicationAttempted()
                || !lifecycleDecision.blockers().isEmpty()) {
            throw new IllegalStateException(
                "production candidate did not reach conservative lifecycle completion");
        }
    }

    private static StageResourceLedger stageLedger(
        AutonomousResearchBriefV2 brief,
        EvaluationReport evaluation,
        String validationHash,
        CounterexampleBudget counterexampleBudget,
        String counterexampleHash,
        NoveltyReport novelty,
        String noveltyHash,
        ProofReport proof,
        LifecycleDecision lifecycleDecision
    ) {
        return AutonomousStageResourceLedger.create(brief, List.of(
            AutonomousStageResourceLedger.completed(
                brief,
                EvidenceStage.VALIDATION,
                ResourceKind.VALIDATION_CHECKS,
                Math.addExact(
                    evaluation.executedPositiveHoldouts(),
                    evaluation.executedNegativeHoldouts()),
                validationHash),
            AutonomousStageResourceLedger.completed(
                brief,
                EvidenceStage.COUNTEREXAMPLE_SEARCH,
                ResourceKind.COUNTEREXAMPLE_ATTEMPTS,
                executedCounterexampleAttempts(
                    counterexampleBudget,
                    evaluation.counterexample().attemptedSources()),
                counterexampleHash),
            AutonomousStageResourceLedger.completed(
                brief,
                EvidenceStage.PROJECT_NOVELTY,
                ResourceKind.NOVELTY_COMPARISONS,
                Math.addExact(
                    novelty.checkedActiveRules(),
                    novelty.checkedPriorCandidates()),
                noveltyHash),
            AutonomousStageResourceLedger.completed(
                brief,
                EvidenceStage.PROOF,
                ResourceKind.PROOF_ATTEMPTS,
                proof.proofObligationEmitted() ? 1L : 0L,
                proof.evidenceHash()),
            AutonomousStageResourceLedger.completed(
                brief,
                EvidenceStage.LIFECYCLE_HANDOFF,
                ResourceKind.LIFECYCLE_HANDOFFS,
                lifecycleDecision.outcome() == LifecycleOutcome.COMPLETED ? 1L : 0L,
                lifecycleDecision.contentHash())));
    }

    private static long executedCounterexampleAttempts(
        CounterexampleBudget budget,
        List<String> attemptedSources
    ) {
        Set<String> sources = new TreeSet<>(attemptedSources);
        Set<String> expected = Set.of(
            "numeric-boundary-values",
            "rational-samples",
            "numeric-random",
            "complex-samples");
        if (!sources.equals(expected)) {
            throw new IllegalStateException(
                "production counterexample sources differ from the pinned budget: "
                    + sources);
        }
        return 5L + 4L + budget.maxNumericSamples() + 4L;
    }

    public record LifecycleRun(
        String schema,
        MiningRun mining,
        String candidateBranchId,
        OpenTargetConjecture conjecture,
        EvaluationReport evaluation,
        String validationJson,
        String validationHash,
        String counterexampleJson,
        String counterexampleHash,
        NoveltyReport novelty,
        String noveltyJson,
        String noveltyHash,
        ProofReport proof,
        HypothesisCandidate lifecycleCandidate,
        String lifecycleCandidateJson,
        String lifecycleCandidateHash,
        LifecycleDecision lifecycleDecision,
        StageResourceLedger stageLedger,
        boolean targetProvided,
        boolean lifecycleRunIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public LifecycleRun {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported production lifecycle schema");
            }
            mining = Objects.requireNonNull(mining, "mining");
            requireText(candidateBranchId, "candidateBranchId");
            conjecture = Objects.requireNonNull(conjecture, "conjecture");
            evaluation = Objects.requireNonNull(evaluation, "evaluation");
            requireText(validationJson, "validationJson");
            requireSha256(validationHash, "validationHash");
            requireText(counterexampleJson, "counterexampleJson");
            requireSha256(counterexampleHash, "counterexampleHash");
            novelty = Objects.requireNonNull(novelty, "novelty");
            requireText(noveltyJson, "noveltyJson");
            requireSha256(noveltyHash, "noveltyHash");
            proof = Objects.requireNonNull(proof, "proof");
            lifecycleCandidate = Objects.requireNonNull(
                lifecycleCandidate, "lifecycleCandidate");
            requireText(lifecycleCandidateJson, "lifecycleCandidateJson");
            requireSha256(lifecycleCandidateHash, "lifecycleCandidateHash");
            lifecycleDecision = Objects.requireNonNull(
                lifecycleDecision, "lifecycleDecision");
            stageLedger = Objects.requireNonNull(stageLedger, "stageLedger");
            if (!conjecture.conjectureId().equals(evaluation.conjectureId())
                    || !conjecture.conjectureId().equals(novelty.conjectureId())
                    || !conjecture.conjectureId().equals(proof.conjectureId())
                    || !conjecture.conjectureId().equals(lifecycleCandidate.id())
                    || !candidateBranchId.equals(lifecycleDecision.candidateBranchId())
                    || !mining.generation().brief().contentHash().equals(
                        stageLedger.briefHash())
                    || targetProvided
                    || lifecycleRunIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "production lifecycle evidence is not consistently linked");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", mining.generation().brief().contentHash())
                .property("miningRunHash", mining.contentHash())
                .property("candidateBranchId", candidateBranchId)
                .property("conjectureId", conjecture.conjectureId())
                .property("validationHash", validationHash)
                .property("counterexampleHash", counterexampleHash)
                .property("projectNoveltyHash", noveltyHash)
                .property("projectNoveltyStatus", novelty.status().name())
                .property("externalNoveltyStatus", novelty.externalNoveltyStatus())
                .property("proofEvidenceHash", proof.evidenceHash())
                .property("proofObligationHash",
                    proof.obligation().obligationHash())
                .property("proofStatus", proof.proofStatus().name())
                .property("formalProofStatus", proof.formalProofStatus())
                .property("lifecycleCandidateHash", lifecycleCandidateHash)
                .property("lifecycleDecisionHash", lifecycleDecision.contentHash())
                .property("lifecycleOutcome", lifecycleDecision.outcome().name())
                .property("stageResourceLedgerHash", stageLedger.contentHash())
                .property("targetProvided", targetProvided)
                .property("lifecycleRunIsMathematicalEvidence",
                    lifecycleRunIsMathematicalEvidence)
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
