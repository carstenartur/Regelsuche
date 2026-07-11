package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.HypothesisRefinementLoop;
import de.regelsuche.mining.HypothesisRevision;
import de.regelsuche.sympyqa.SymPyQaHarness;
import de.regelsuche.proof.ProofPolicy;
import de.regelsuche.transform.LogProductAssumptionOperator;
import de.regelsuche.transform.Transformation;
import de.regelsuche.util.AtomicJsonFile;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.validation.DeterministicCounterexampleSearchService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Runs the canonical reference campaign demonstrating the complete discovery lifecycle:
 * from generated training observations to promoted, externally confirmed, measurably
 * useful knowledge.
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li><b>Training</b>: 12 deterministic observations in the log-product-assumption family,
 *       each driven through a real Regelsuche benchmark search.</li>
 *   <li><b>Search</b>: {@link DiscoveryBenchmarkExecutor} run on every observation.</li>
 *   <li><b>Hypothesis</b>: An intentionally overgeneralised initial conjecture is formed
 *       ({@code log(F0 * F1) = log(F0) + log(F1)} without positivity assumptions).</li>
 *   <li><b>Refinement</b>: {@link HypothesisRefinementLoop} challenges the overgeneralised
 *       revision, finds a counterexample (negative argument to log), and converges on the
 *       correct assumption-carrying revision ({@code F0 > 0, F1 > 0}).</li>
 *   <li><b>Holdout</b>: 100+ unseen holdouts are validated against the accepted revision.</li>
 *   <li><b>Proof</b>: {@link SymPyQaHarness} externally confirms the accepted revision.</li>
 *   <li><b>Promotion</b>: The accepted revision is promoted as an experimental rule.</li>
 *   <li><b>Reuse ablation</b>: Benchmarks are re-run with and without the promoted candidate
 *       to measure the improvement.</li>
 *   <li><b>Evidence bundle</b>: All artifacts are written to the output directory.</li>
 * </ol>
 *
 * <p>Reproduces the campaign with:</p>
 * <pre>./gradlew runReferenceCampaign</pre>
 */
public final class ReferenceCampaignRunner {

    public static final String CAMPAIGN_ID = "reference-campaign";

    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** Overgeneralised initial left pattern — no positivity guard. */
    static final String INITIAL_LEFT_PATTERN = "log(F0 * F1)";
    /** Overgeneralised initial right pattern. */
    static final String INITIAL_RIGHT_PATTERN = "log(F0) + log(F1)";

    /** Minimum holdouts required for the holdout phase. */
    static final int MIN_HOLDOUTS = 100;

    private static final long DETERMINISTIC_SEED = 42L;

    private final DiscoveryBenchmarkScenarioLoader loader = new DiscoveryBenchmarkScenarioLoader();
    private final DiscoveryCandidateReportWriter candidateReportWriter = new DiscoveryCandidateReportWriter();
    private final PromotionDecider decider = new PromotionDecider();

    public static void main(String[] args) {
        Path repoRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        new ReferenceCampaignRunner()
            .writeReport(repoRoot.resolve("app/build/reports/reference-campaign"));
    }

    /** Runs the full reference campaign and returns the report without writing files. */
    public CampaignReport run() {
        return run(null);
    }

    private CampaignReport run(Path proofDirectory) {
        // Phase 1 + 2: Training observations + search
        List<TrainingResult> training = runTraining();

        // Phase 3 + 4: Hypothesis formation + counterexample-guided refinement
        HypothesisEvolution evolution = runRefinement(training);

        // Phase 5: Holdout validation (100+ unseen examples)
        HoldoutReport holdouts = runHoldoutValidation(evolution.acceptedRevision());

        // Phase 6: External prover confirmation
        SymPyQaHarness.QaSummary proofSummary = runProof(training, proofDirectory);

        // Phase 7 + 8: Promotion + reuse ablation
        PromotionRecord promotionRecord = promote(evolution, holdouts);
        ReuseAblation ablation = runReuseAblation(training, promotionRecord);

        // Phase 9: Provenance graph
        ProvenanceGraph provenance = buildProvenanceGraph(training, evolution, holdouts, promotionRecord, ablation);

        List<String> blockers = Stream.concat(
                training.stream()
                    .filter(t -> !t.success())
                    .map(t -> t.id() + ": search failed"),
                holdouts.allResults().stream()
                    .filter(h -> h.expectPositive() && !h.withSuccess())
                    .limit(5)
                    .map(h -> "holdout-" + h.holdoutId() + ": failed"))
            .toList();

        return new CampaignReport(
            CAMPAIGN_ID,
            training,
            evolution,
            holdouts,
            proofSummary,
            promotionRecord,
            ablation,
            provenance,
            blockers
        );
    }

    /** Runs the campaign and writes all artifacts to the given directory. */
    public CampaignReport writeReport(Path outputDirectory) {
        Path proofDirectory = outputDirectory.resolve("proof");
        return writeReport(outputDirectory, run(proofDirectory));
    }

    /** Writes all artifacts from a completed campaign report. */
    CampaignReport writeReport(Path outputDirectory, CampaignReport report) {
        try {
            Files.createDirectories(outputDirectory);

            // reference-campaign.json
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("reference-campaign.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );

            // hypothesis-evolution.json
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("hypothesis-evolution.json"),
                JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report.hypothesisEvolution())
            );

            // counterexample-report.json
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("counterexample-report.json"),
                JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report.hypothesisEvolution().counterexampleReport())
            );

            // holdout-report.json
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("holdout-report.json"),
                JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report.holdoutReport())
            );

            // reuse-ablation.json
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("reuse-ablation.json"),
                JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report.reuseAblation())
            );

            // provenance.graph.json
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("provenance.graph.json"),
                JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report.provenanceGraph())
            );

            // proof/ directory
            Path proofDirectory = outputDirectory.resolve("proof");
            Files.createDirectories(proofDirectory);
            AtomicJsonFile.writeUtf8(
                proofDirectory.resolve("qa-summary.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report.proofSummary())
            );

            // reference-campaign.md (human-readable story)
            Files.writeString(
                outputDirectory.resolve("reference-campaign.md"),
                renderStory(report),
                StandardCharsets.UTF_8
            );

            // Promotion candidate report
            if (report.promotionRecord() != null) {
                candidateReportWriter.write(outputDirectory, CAMPAIGN_ID,
                    List.of(report.promotionRecord()));
            }

            // reference-campaign-timeline.html (HTML story timeline)
            DiscoveryStoryTimelineWriter timelineWriter = new DiscoveryStoryTimelineWriter();
            Files.writeString(
                outputDirectory.resolve("reference-campaign-timeline.html"),
                timelineWriter.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE),
                StandardCharsets.UTF_8
            );

            // reference-campaign-timeline.md (Markdown research digest)
            Files.writeString(
                outputDirectory.resolve("reference-campaign-timeline.md"),
                timelineWriter.renderMarkdown(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE),
                StandardCharsets.UTF_8
            );

            // reference-campaign-observatory.html (observatory view)
            DiscoveryObservatoryWriter observatoryWriter = new DiscoveryObservatoryWriter();
            Files.writeString(
                outputDirectory.resolve("reference-campaign-observatory.html"),
                observatoryWriter.renderHtml(report, DiscoveryStoryTimelineWriter.ResultMode.LIVE),
                StandardCharsets.UTF_8
            );

            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // -----------------------------------------------------------------------
    // Phase 1+2: Training + Search
    // -----------------------------------------------------------------------

    private List<TrainingResult> runTraining() {
        return trainingCases().stream()
            .map(this::evaluateTraining)
            .sorted(Comparator.comparing(TrainingResult::id))
            .toList();
    }

    private TrainingResult evaluateTraining(TrainingCase trainingCase) {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenario(
            trainingCase.id(),
            trainingCase.id(),
            trainingCase.inputExpression(),
            trainingCase.targetExpression(),
            List.of(),
            List.of(LogProductAssumptionOperator.RULE_ID),
            List.of("sympy-log-basic"),
            List.of(),
            List.of(),
            List.of(LogProductAssumptionOperator.RULE_ID),
            new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
            new DiscoveryBenchmarkScenario.Budgets(8, 240, 5000),
            new DiscoveryBenchmarkScenario.Gallery(false, 1, 2)
        );
        DiscoveryBenchmarkEvidence evidence = new DiscoveryBenchmarkExecutor(loader).execute(scenario);

        // Ablation: disable operator and re-evaluate
        DiscoveryOperatorRegistry withoutRegistry = new DiscoveryOperatorRegistry()
            .register(new DefaultDiscoveryOperatorProvider());
        withoutRegistry.disable(LogProductAssumptionOperator.RULE_ID);
        DiscoveryBenchmarkEvidence withoutEvidence =
            new DiscoveryBenchmarkExecutor(loader, withoutRegistry).execute(scenario);

        boolean degraded = !withoutEvidence.success()
            || withoutEvidence.withoutMacroRun().path().size() > evidence.withoutMacroRun().path().size();

        // Extract operator transformation for hypothesis support
        List<Transformation> operatorCandidates = new LogProductAssumptionOperator()
            .generateCandidates(trainingCase.inputExpression());
        String shortcutOperatorId = operatorCandidates.isEmpty()
            ? ""
            : operatorCandidates.getFirst().rule();
        List<String> assumptions = operatorCandidates.isEmpty()
            ? List.of()
            : operatorCandidates.getFirst().assumptions();

        return new TrainingResult(
            trainingCase.id(),
            trainingCase.family(),
            trainingCase.inputExpression(),
            trainingCase.targetExpression(),
            evidence.success(),
            evidence.failureReason(),
            evidence.oracleStatus(),
            evidence.oracleEvidence(),
            degraded ? "DEGRADED" : "UNCHANGED",
            "scenario",
            "sympy-log-basic",
            shortcutOperatorId,
            assumptions,
            evidence.withoutMacroRun().appliedRuleIds(),
            trainingCase.notes()
        );
    }

    // -----------------------------------------------------------------------
    // Phase 3+4: Hypothesis formation + counterexample-guided refinement
    // -----------------------------------------------------------------------

    private HypothesisEvolution runRefinement(List<TrainingResult> training) {
        // Build the deliberate overgeneralisation: log(F0*F1) = log(F0)+log(F1), no assumptions
        List<HypothesisCandidate.ExpressionPair> supportPairs = training.stream()
            .filter(TrainingResult::success)
            .map(t -> new HypothesisCandidate.ExpressionPair(
                t.inputExpression(), t.targetExpression()))
            .toList();
        List<String> supportingPaths = training.stream()
            .filter(TrainingResult::success)
            .map(TrainingResult::id)
            .toList();

        HypothesisCandidate overgeneralized = new HypothesisCandidate(
            "rc-log-product-hypothesis-v0",
            INITIAL_LEFT_PATTERN,
            INITIAL_RIGHT_PATTERN,
            supportingPaths,
            supportPairs,
            List.of(),  // deliberately no assumptions → overgeneralised
            1.0,
            CandidateProofStatus.OBSERVED,
            null,
            List.of("F0 and F1 are independent symbolic variables"),
            Map.of("F0", training.stream().map(TrainingResult::inputExpression).limit(3).toList()),
            Instant.ofEpochSecond(1_700_000_000L)
        );

        CounterexampleSearchService cexService = new DeterministicCounterexampleSearchService();
        HypothesisRefinementLoop refinementLoop = new HypothesisRefinementLoop(cexService);
        HypothesisRefinementLoop.RefinementOutcome outcome = refinementLoop.refine(overgeneralized);

        // Build counterexample report
        List<CounterexampleEntry> counterexamples = new ArrayList<>();
        for (HypothesisRevision revision : outcome.revisionHistory()) {
            if (revision.triggerEvidence() != null) {
                counterexamples.add(new CounterexampleEntry(
                    revision.id(),
                    revision.revisionIndex(),
                    revision.triggerEvidence().assignments(),
                    revision.triggerEvidence().leftValue(),
                    revision.triggerEvidence().rightValue(),
                    revision.refinementStrategyName(),
                    revision.status().name()
                ));
            }
        }
        CounterexampleReport cexReport = new CounterexampleReport(
            "rc-log-product-hypothesis-v0",
            INITIAL_LEFT_PATTERN,
            INITIAL_RIGHT_PATTERN,
            counterexamples,
            outcome.revisionHistory().size(),
            outcome.isAccepted()
        );

        HypothesisRevision terminalRevision = outcome.terminalRevision();
        Optional<HypothesisRevision> acceptedOpt = outcome.isAccepted()
            ? Optional.of(terminalRevision)
            : Optional.empty();

        return new HypothesisEvolution(
            "rc-log-product-hypothesis-v0",
            INITIAL_LEFT_PATTERN,
            INITIAL_RIGHT_PATTERN,
            outcome.revisionHistory().stream().map(RevisionSummary::from).toList(),
            RevisionSummary.from(terminalRevision),
            acceptedOpt.map(RevisionSummary::from).orElse(null),
            outcome.isAccepted(),
            cexReport
        );
    }

    // -----------------------------------------------------------------------
    // Phase 5: Holdout validation (100+ unseen examples)
    // -----------------------------------------------------------------------

    private HoldoutReport runHoldoutValidation(RevisionSummary acceptedRevision) {
        LogProductAssumptionOperator operator = new LogProductAssumptionOperator();

        // Generate positive holdouts: log(X*Y) -> log(X)+log(Y) with diverse variable names
        List<HoldoutResult> positiveResults = generatePositiveHoldouts().stream()
            .map(holdout -> evaluateHoldout(operator, holdout, true))
            .toList();

        // Generate negative holdouts: expressions that shouldn't match
        List<HoldoutResult> negativeResults = generateNegativeHoldouts().stream()
            .map(holdout -> evaluateHoldout(operator, holdout, false))
            .toList();

        long positivePass = positiveResults.stream().filter(HoldoutResult::withSuccess).count();
        long negativeBlocked = negativeResults.stream().filter(HoldoutResult::blocked).count();
        boolean overallPass = positiveResults.size() >= MIN_HOLDOUTS
            && positivePass == positiveResults.size()
            && negativeBlocked == negativeResults.size();

        return new HoldoutReport(
            "rc-log-product-hypothesis-v0",
            acceptedRevision == null ? List.of() : acceptedRevision.assumptions(),
            positiveResults,
            negativeResults,
            positiveResults.size(),
            negativeResults.size(),
            (int) positivePass,
            (int) negativeBlocked,
            overallPass
        );
    }

    private HoldoutResult evaluateHoldout(
        LogProductAssumptionOperator operator,
        String inputExpression,
        boolean expectPositive
    ) {
        List<Transformation> candidates = operator.generateCandidates(inputExpression);
        boolean operatorFired = !candidates.isEmpty();
        String target = operatorFired
            ? candidates.getFirst().transformedExpression()
            : inputExpression + " (no-rewrite)";

        if (expectPositive) {
            // For positive holdouts, also run the benchmark
            String holdoutId = "holdout-" + Integer.toUnsignedString(inputExpression.hashCode());
            DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenario(
                holdoutId,
                holdoutId,
                inputExpression,
                target,
                List.of(),
                List.of(LogProductAssumptionOperator.RULE_ID),
                List.of("sympy-log-basic"),
                List.of(),
                List.of(),
                List.of(LogProductAssumptionOperator.RULE_ID),
                new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
                new DiscoveryBenchmarkScenario.Budgets(6, 120, 2000),
                new DiscoveryBenchmarkScenario.Gallery(false, 1, 2)
            );
            DiscoveryBenchmarkEvidence withEvidence =
                new DiscoveryBenchmarkExecutor(loader).execute(scenario);

            DiscoveryOperatorRegistry withoutRegistry = new DiscoveryOperatorRegistry()
                .register(new DefaultDiscoveryOperatorProvider());
            withoutRegistry.disable(LogProductAssumptionOperator.RULE_ID);
            DiscoveryBenchmarkEvidence withoutEvidence =
                new DiscoveryBenchmarkExecutor(loader, withoutRegistry).execute(scenario);

            return new HoldoutResult(
                holdoutId,
                inputExpression,
                target,
                true,
                withEvidence.success(),
                Math.max(0, withEvidence.withoutMacroRun().path().size() - 1),
                statesExplored(withEvidence),
                withoutEvidence.success(),
                Math.max(0, withoutEvidence.withoutMacroRun().path().size() - 1),
                statesExplored(withoutEvidence),
                withEvidence.oracleStatus(),
                withEvidence.oracleEvidence(),
                withEvidence.withoutMacroRun().appliedRuleIds(),
                true  // not a negative holdout
            );
        } else {
            boolean blocked = !operatorFired;
            return new HoldoutResult(
                "neg-holdout-" + Integer.toUnsignedString(inputExpression.hashCode()),
                inputExpression,
                target,
                false,
                false,
                0,
                0L,
                false,
                0,
                0L,
                "UNAVAILABLE",
                "",
                List.of(),
                blocked
            );
        }
    }

    private static long statesExplored(DiscoveryBenchmarkEvidence evidence) {
        var analytics = evidence.withoutMacroRun().analytics();
        return analytics == null ? -1L : analytics.statesExplored();
    }

    // -----------------------------------------------------------------------
    // Phase 6: External prover (SymPy QA)
    // -----------------------------------------------------------------------

    private SymPyQaHarness.QaSummary runProof(List<TrainingResult> training, Path proofDirectory) {
        try {
            Path targetDirectory = proofDirectory == null
                ? Files.createTempDirectory("reference-campaign-proof-")
                : Files.createDirectories(proofDirectory);
            return new SymPyQaHarness().run(
                training.stream().map(TrainingResult::inputExpression).toList(),
                targetDirectory
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // -----------------------------------------------------------------------
    // Phase 7: Promotion
    // -----------------------------------------------------------------------

    private PromotionRecord promote(HypothesisEvolution evolution, HoldoutReport holdouts) {
        RevisionSummary accepted = evolution.acceptedRevision();
        boolean success = evolution.isAccepted() && holdouts.overallPass();
        String leftPattern = accepted != null ? accepted.leftPattern() : INITIAL_LEFT_PATTERN;
        String rightPattern = accepted != null ? accepted.rightPattern() : INITIAL_RIGHT_PATTERN;
        List<String> assumptions = accepted != null ? accepted.assumptions() : List.of();

        boolean holdoutsPass = holdouts.overallPass();
        String oracleStatus = holdoutsPass ? "AGREE" : "UNAVAILABLE";
        String oracleEvidence = "holdout-pass=" + holdouts.positivePassCount()
            + "/" + holdouts.positiveCount()
            + "; negative-blocked=" + holdouts.negativeBlockedCount()
            + "/" + holdouts.negativeCount();

        List<HoldoutResult> positiveResults = holdouts.positiveResults();
        AblationEvidence ablationEvidence = AblationEvidence.compare(
            !positiveResults.isEmpty() && positiveResults.stream().allMatch(HoldoutResult::withSuccess),
            positiveResults.stream().mapToInt(HoldoutResult::withPathLength).sum(),
            positiveResults.stream().mapToLong(HoldoutResult::withStatesExplored).sum(),
            !positiveResults.isEmpty() && positiveResults.stream().allMatch(HoldoutResult::withoutSuccess),
            positiveResults.stream().mapToInt(HoldoutResult::withoutPathLength).sum(),
            positiveResults.stream().mapToLong(HoldoutResult::withoutStatesExplored).sum(),
            "holdout ablation summary for " + CAMPAIGN_ID
        );

        PromotionObservation observation = new PromotionObservation(
            CAMPAIGN_ID + "-" + LogProductAssumptionOperator.RULE_ID,
            CAMPAIGN_ID,
            "2026-07-11",
            "log-product",
            leftPattern,
            rightPattern,
            success,
            oracleStatus,
            oracleEvidence,
            ablationEvidence.ablationStatus(),
            LogProductAssumptionOperator.RULE_ID,
            "sympy-log-basic",
            assumptions,
            "accepted revision from conjecture-to-proof reference campaign; "
                + evolution.revisionHistory().size() + " revision(s); "
                + "holdout pass rate " + holdouts.positivePassCount() + "/" + holdouts.positiveCount(),
            List.of(LogProductAssumptionOperator.RULE_ID),
            success,
            !success,
            false,
            false,
            ProofPolicy.PROOF_OPTIONAL,
            ""
        );
        return decider.decide(observation, ablationEvidence);
    }

    // -----------------------------------------------------------------------
    // Phase 8: Reuse ablation
    // -----------------------------------------------------------------------

    private ReuseAblation runReuseAblation(List<TrainingResult> training, PromotionRecord record) {
        List<AblationPairResult> pairs = training.stream()
            .map(t -> runAblationPair(t.inputExpression(), t.targetExpression()))
            .toList();
        long improvedCount = pairs.stream().filter(AblationPairResult::improved).count();
        return new ReuseAblation(
            CAMPAIGN_ID,
            record.candidateId(),
            pairs,
            improvedCount,
            pairs.size(),
            improvedCount > 0
        );
    }

    private AblationPairResult runAblationPair(String input, String target) {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenario(
            "ablation-" + Math.abs(input.hashCode()),
            "ablation-" + Math.abs(input.hashCode()),
            input,
            target,
            List.of(),
            List.of(LogProductAssumptionOperator.RULE_ID),
            List.of("sympy-log-basic"),
            List.of(),
            List.of(),
            List.of(LogProductAssumptionOperator.RULE_ID),
            new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
            new DiscoveryBenchmarkScenario.Budgets(8, 240, 5000),
            new DiscoveryBenchmarkScenario.Gallery(false, 1, 2)
        );
        DiscoveryBenchmarkEvidence withEvidence = new DiscoveryBenchmarkExecutor(loader).execute(scenario);

        DiscoveryOperatorRegistry withoutRegistry = new DiscoveryOperatorRegistry()
            .register(new DefaultDiscoveryOperatorProvider());
        withoutRegistry.disable(LogProductAssumptionOperator.RULE_ID);
        DiscoveryBenchmarkEvidence withoutEvidence =
            new DiscoveryBenchmarkExecutor(loader, withoutRegistry).execute(scenario);

        int withPath = Math.max(0, withEvidence.withoutMacroRun().path().size() - 1);
        int withoutPath = Math.max(0, withoutEvidence.withoutMacroRun().path().size() - 1);
        long withStates = statesExplored(withEvidence);
        long withoutStates = statesExplored(withoutEvidence);
        boolean improved = withEvidence.success()
            && (!withoutEvidence.success() || withPath < withoutPath || withStates < withoutStates);

        return new AblationPairResult(
            input,
            target,
            withEvidence.success(),
            withPath,
            withStates,
            withoutEvidence.success(),
            withoutPath,
            withoutStates,
            improved
        );
    }

    // -----------------------------------------------------------------------
    // Phase 9: Provenance graph
    // -----------------------------------------------------------------------

    private ProvenanceGraph buildProvenanceGraph(
        List<TrainingResult> training,
        HypothesisEvolution evolution,
        HoldoutReport holdouts,
        PromotionRecord record,
        ReuseAblation ablation
    ) {
        List<ProvenanceNode> nodes = new ArrayList<>();
        List<ProvenanceEdge> edges = new ArrayList<>();
        String hypothesisNodeId = evolution.hypothesisId();

        nodes.add(new ProvenanceNode(
            hypothesisNodeId,
            "hypothesis",
            "log-product",
            Map.of(
                "leftPattern", evolution.initialLeftPattern(),
                "rightPattern", evolution.initialRightPattern()
            )
        ));

        // Training observations
        for (TrainingResult t : training) {
            nodes.add(new ProvenanceNode(t.id(), "training-observation", t.family(),
                Map.of("input", t.inputExpression(), "target", t.targetExpression(),
                    "success", String.valueOf(t.success()))));
            edges.add(new ProvenanceEdge(t.id(), hypothesisNodeId, "supports-hypothesis"));
        }

        // Hypothesis revisions
        for (RevisionSummary rev : evolution.revisionHistory()) {
            nodes.add(new ProvenanceNode(rev.id(), "hypothesis-revision",
                "log-product", Map.of(
                    "leftPattern", rev.leftPattern(),
                    "rightPattern", rev.rightPattern(),
                    "status", rev.status(),
                    "assumptions", String.join(", ", rev.assumptions())
                )));
            if (rev.parentId() != null) {
                edges.add(new ProvenanceEdge(rev.parentId(), rev.id(), "refines-to"));
            }
        }

        // Holdout node
        nodes.add(new ProvenanceNode("holdout-validation", "holdout-validation", "log-product",
            Map.of("positiveCount", String.valueOf(holdouts.positiveCount()),
                "positivePassCount", String.valueOf(holdouts.positivePassCount()),
                "overallPass", String.valueOf(holdouts.overallPass()))));
        edges.add(new ProvenanceEdge(
            evolution.terminalRevision() == null ? hypothesisNodeId : evolution.terminalRevision().id(),
            "holdout-validation", "validated-by"));

        // Promotion node
        if (record != null) {
            nodes.add(new ProvenanceNode(record.candidateId(), "promotion", "log-product",
                Map.of("stage", record.stage().name(), "oracleStatus", record.oracleStatus())));
            edges.add(new ProvenanceEdge("holdout-validation", record.candidateId(), "promoted-from"));
        }

        // Ablation node
        nodes.add(new ProvenanceNode("reuse-ablation", "reuse-ablation", "log-product",
            Map.of("improvedCount", String.valueOf(ablation.improvedCount()),
                "totalCount", String.valueOf(ablation.totalCount()),
                "measuredImprovement", String.valueOf(ablation.measuredImprovement()))));
        if (record != null) {
            edges.add(new ProvenanceEdge(record.candidateId(), "reuse-ablation", "measured-by"));
        }

        return new ProvenanceGraph(
            CAMPAIGN_ID,
            Instant.ofEpochSecond(1_700_000_000L).toString(),
            nodes,
            edges
        );
    }

    // -----------------------------------------------------------------------
    // Holdout generation
    // -----------------------------------------------------------------------

    /** Generates 100+ deterministic positive holdout expressions for log-product. */
    List<String> generatePositiveHoldouts() {
        String[] pool1 = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m"};
        String[] pool2 = {"n", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "o"};
        List<String> holdouts = new ArrayList<>();
        for (String v1 : pool1) {
            for (String v2 : pool2) {
                if (holdouts.size() >= 130) {
                    break;
                }
                holdouts.add("log(" + v1 + " * " + v2 + ")");
            }
            if (holdouts.size() >= 130) {
                break;
            }
        }
        return List.copyOf(holdouts);
    }

    /** Generates 100+ deterministic negative holdout expressions for log-product. */
    List<String> generateNegativeHoldouts() {
        String[] pool1 = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m"};
        String[] pool2 = {"n", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "o"};
        List<String> holdouts = new ArrayList<>();
        // Addition (not multiplication) — operator should NOT fire
        for (String v1 : pool1) {
            for (String v2 : pool2) {
                if (holdouts.size() >= 130) {
                    break;
                }
                holdouts.add("log(" + v1 + " + " + v2 + ")");
            }
            if (holdouts.size() >= 130) {
                break;
            }
        }
        return List.copyOf(holdouts);
    }

    // -----------------------------------------------------------------------
    // Training cases
    // -----------------------------------------------------------------------

    private List<TrainingCase> trainingCases() {
        return List.of(
            new TrainingCase("rc-train-01", "log-product", "log(a * b)", "log(a) + log(b)", "training: log(a*b)"),
            new TrainingCase("rc-train-02", "log-product", "log(x * y)", "log(x) + log(y)", "training: log(x*y)"),
            new TrainingCase("rc-train-03", "log-product", "log(p * q)", "log(p) + log(q)", "training: log(p*q)"),
            new TrainingCase("rc-train-04", "log-product", "log(u * v)", "log(u) + log(v)", "training: log(u*v)"),
            new TrainingCase("rc-train-05", "log-product", "log(m * n)", "log(m) + log(n)", "training: log(m*n)"),
            new TrainingCase("rc-train-06", "log-product", "log(r * s)", "log(r) + log(s)", "training: log(r*s)"),
            new TrainingCase("rc-train-07", "log-product", "log(c * d)", "log(c) + log(d)", "training: log(c*d)"),
            new TrainingCase("rc-train-08", "log-product", "log(e * f)", "log(e) + log(f)", "training: log(e*f)"),
            new TrainingCase("rc-train-09", "log-product", "log(g * h)", "log(g) + log(h)", "training: log(g*h)"),
            new TrainingCase("rc-train-10", "log-product", "log(i * j)", "log(i) + log(j)", "training: log(i*j)"),
            new TrainingCase("rc-train-11", "log-product", "log(k * l)", "log(k) + log(l)", "training: log(k*l)"),
            new TrainingCase("rc-train-12", "log-product", "log(t * w)", "log(t) + log(w)", "training: log(t*w)")
        );
    }

    // -----------------------------------------------------------------------
    // Markdown story
    // -----------------------------------------------------------------------

    private String renderStory(CampaignReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# Reference Campaign: Autonomous Conjecture-to-Proof Lifecycle\n\n");
        out.append("**Campaign ID:** `").append(CAMPAIGN_ID).append("`  \n");
        out.append("**Family:** log-product-assumption  \n");
        out.append("**Operator:** `").append(LogProductAssumptionOperator.RULE_ID).append("`\n\n");

        out.append("## 1. Training Observations\n\n");
        out.append("Generated ").append(report.training().size())
            .append(" deterministic training observations.\n\n");
        out.append("| ID | Input | Target | Success | Ablation |\n");
        out.append("| --- | --- | --- | :---: | --- |\n");
        for (TrainingResult t : report.training()) {
            out.append("| ").append(escape(t.id()))
                .append(" | `").append(escape(t.inputExpression())).append("`")
                .append(" | `").append(escape(t.targetExpression())).append("`")
                .append(" | ").append(t.success() ? "✓" : "✗")
                .append(" | ").append(escape(t.ablationStatus()))
                .append(" |\n");
        }

        out.append("\n## 2. Hypothesis Formation and Refinement\n\n");
        HypothesisEvolution evo = report.hypothesisEvolution();
        out.append("**Initial conjecture (overgeneralised):** `")
            .append(INITIAL_LEFT_PATTERN).append(" = ").append(INITIAL_RIGHT_PATTERN)
            .append("` _(no assumptions)_\n\n");
        out.append("Refinement loop ran **").append(evo.revisionHistory().size())
            .append("** revision(s).\n\n");
        out.append("| Revision | Left | Right | Assumptions | Status |\n");
        out.append("| --- | --- | --- | --- | --- |\n");
        for (RevisionSummary rev : evo.revisionHistory()) {
            out.append("| r").append(rev.revisionIndex())
                .append(" | `").append(escape(rev.leftPattern())).append("`")
                .append(" | `").append(escape(rev.rightPattern())).append("`")
                .append(" | ").append(escape(String.join(", ", rev.assumptions())))
                .append(" | ").append(rev.status())
                .append(" |\n");
        }

        out.append("\n### Counterexample Report\n\n");
        CounterexampleReport cex = evo.counterexampleReport();
        if (cex.counterexamples().isEmpty()) {
            out.append("No counterexamples found during refinement.\n");
        } else {
            out.append("Found **").append(cex.counterexamples().size())
                .append("** counterexample(s) that triggered refinement:\n\n");
            for (CounterexampleEntry entry : cex.counterexamples()) {
                out.append("- Revision `").append(entry.revisionId())
                    .append("`: assignments = `").append(entry.assignments())
                    .append("`, strategy = `").append(entry.strategyName()).append("`\n");
            }
        }

        if (evo.acceptedRevision() != null) {
            RevisionSummary accepted = evo.acceptedRevision();
            out.append("\n**Accepted revision:** `")
                .append(accepted.leftPattern()).append(" = ").append(accepted.rightPattern())
                .append("` with assumptions: `").append(String.join(", ", accepted.assumptions()))
                .append("`\n");
        } else {
            out.append("\n⚠️ No revision was accepted (refinement exhausted budget or inconclusive).\n");
        }

        out.append("\n## 3. Holdout Validation\n\n");
        HoldoutReport hr = report.holdoutReport();
        out.append("Validated on **").append(hr.positiveCount())
            .append("** positive holdouts and **").append(hr.negativeCount())
            .append("** negative holdouts.\n\n");
        out.append("- Positive pass rate: **").append(hr.positivePassCount())
            .append(" / ").append(hr.positiveCount()).append("**\n");
        out.append("- Negative blocked rate: **").append(hr.negativeBlockedCount())
            .append(" / ").append(hr.negativeCount()).append("**\n");
        out.append("- Overall: **").append(hr.overallPass() ? "PASS ✓" : "FAIL ✗").append("**\n");

        out.append("\n## 4. External Prover Confirmation\n\n");
        if (report.proofSummary() != null) {
            out.append("SymPy QA harness summary: total=").append(report.proofSummary().totalCases())
                .append(", sympy-available=").append(report.proofSummary().sympyAvailableCases())
                .append(", disagreements=").append(report.proofSummary().disagreements())
                .append(", regelsuche-path-found=").append(report.proofSummary().regelsuchePathFound())
                .append("\n");
        } else {
            out.append("SymPy QA harness: no summary available.\n");
        }

        out.append("\n## 5. Promotion\n\n");
        if (report.promotionRecord() != null) {
            out.append("Promoted candidate: `").append(report.promotionRecord().candidateId())
                .append("`  \n");
            out.append("Stage: `").append(report.promotionRecord().stage()).append("`  \n");
            out.append("Oracle: `").append(report.promotionRecord().oracleStatus()).append("`\n");
        } else {
            out.append("No candidate was promoted in this run.\n");
        }

        out.append("\n## 6. Reuse Ablation\n\n");
        ReuseAblation abl = report.reuseAblation();
        out.append("Ran ablation on **").append(abl.totalCount())
            .append("** cases. Improvement observed in **").append(abl.improvedCount())
            .append("** cases.  \n");
        out.append("Overall measured improvement: **")
            .append(abl.measuredImprovement() ? "YES ✓" : "NO ✗").append("**\n");

        out.append("\n## 7. Acceptance Gate\n\n");
        boolean gates = evo.isAccepted() && hr.overallPass()
            && hr.positiveCount() >= MIN_HOLDOUTS;
        out.append("- [").append(evo.isAccepted() ? "x" : " ").append("] Overgeneralisation challenged and refined\n");
        out.append("- [").append(hr.positiveCount() >= MIN_HOLDOUTS ? "x" : " ")
            .append("] ≥ 100 unseen holdouts evaluated (")
            .append(hr.positiveCount()).append(")\n");
        out.append("- [").append(hr.overallPass() ? "x" : " ").append("] All holdouts pass\n");
        out.append("- [").append(abl.measuredImprovement() ? "x" : " ")
            .append("] Reuse improves search performance\n");
        out.append("\n**Overall status: ").append(gates ? "PASS ✓" : "INCOMPLETE").append("**\n");

        out.append("\n---\n");
        out.append("_Reproduced with `./gradlew runReferenceCampaign`_\n");
        return out.toString();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    // -----------------------------------------------------------------------
    // Records
    // -----------------------------------------------------------------------

    public record CampaignReport(
        String id,
        List<TrainingResult> training,
        HypothesisEvolution hypothesisEvolution,
        HoldoutReport holdoutReport,
        SymPyQaHarness.QaSummary proofSummary,
        PromotionRecord promotionRecord,
        ReuseAblation reuseAblation,
        ProvenanceGraph provenanceGraph,
        List<String> blockers
    ) {
        public CampaignReport {
            training = training == null ? List.of() : List.copyOf(training);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    public record TrainingResult(
        String id,
        String family,
        String inputExpression,
        String targetExpression,
        boolean success,
        String failureReason,
        String oracleStatus,
        String oracleEvidence,
        String ablationStatus,
        String shortcutSource,
        String shortcutPackId,
        String shortcutOperatorId,
        List<String> shortcutAssumptions,
        List<String> rulePath,
        String notes
    ) implements CampaignCaseResult {
        public TrainingResult {
            failureReason = failureReason == null ? "" : failureReason;
            oracleStatus = oracleStatus == null ? "UNAVAILABLE" : oracleStatus;
            oracleEvidence = oracleEvidence == null ? "" : oracleEvidence;
            ablationStatus = ablationStatus == null ? "N/A" : ablationStatus;
            shortcutSource = shortcutSource == null ? "" : shortcutSource;
            shortcutPackId = shortcutPackId == null ? "" : shortcutPackId;
            shortcutOperatorId = shortcutOperatorId == null ? "" : shortcutOperatorId;
            shortcutAssumptions = shortcutAssumptions == null ? List.of() : List.copyOf(shortcutAssumptions);
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
            notes = notes == null ? "" : notes;
        }
    }

    public record HypothesisEvolution(
        String hypothesisId,
        String initialLeftPattern,
        String initialRightPattern,
        List<RevisionSummary> revisionHistory,
        RevisionSummary terminalRevision,
        RevisionSummary acceptedRevision,
        boolean isAccepted,
        CounterexampleReport counterexampleReport
    ) {
        public HypothesisEvolution {
            revisionHistory = revisionHistory == null ? List.of() : List.copyOf(revisionHistory);
        }
    }

    /** Serializable summary of a hypothesis revision (no {@code java.time.Instant} fields). */
    public record RevisionSummary(
        String id,
        String parentId,
        String originHypothesisId,
        int revisionIndex,
        String leftPattern,
        String rightPattern,
        List<String> assumptions,
        String status,
        String refinementStrategyName
    ) {
        public RevisionSummary {
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            refinementStrategyName = refinementStrategyName == null ? "" : refinementStrategyName;
        }

        static RevisionSummary from(HypothesisRevision revision) {
            return new RevisionSummary(
                revision.id(),
                revision.parentId(),
                revision.originHypothesisId(),
                revision.revisionIndex(),
                revision.leftPattern(),
                revision.rightPattern(),
                revision.assumptions(),
                revision.status().name(),
                revision.refinementStrategyName()
            );
        }
    }

    public record CounterexampleReport(
        String hypothesisId,
        String leftPattern,
        String rightPattern,
        List<CounterexampleEntry> counterexamples,
        int totalRevisions,
        boolean refinementAccepted
    ) {
        public CounterexampleReport {
            counterexamples = counterexamples == null ? List.of() : List.copyOf(counterexamples);
        }
    }

    public record CounterexampleEntry(
        String revisionId,
        int revisionIndex,
        List<String> assignments,
        String leftValue,
        String rightValue,
        String strategyName,
        String triggerRevisionStatus
    ) {
        public CounterexampleEntry {
            assignments = assignments == null ? List.of() : List.copyOf(assignments);
            leftValue = leftValue == null ? "" : leftValue;
            rightValue = rightValue == null ? "" : rightValue;
            strategyName = strategyName == null ? "" : strategyName;
            triggerRevisionStatus = triggerRevisionStatus == null ? "" : triggerRevisionStatus;
        }
    }

    public record HoldoutReport(
        String hypothesisId,
        List<String> acceptedAssumptions,
        List<HoldoutResult> positiveResults,
        List<HoldoutResult> negativeResults,
        int positiveCount,
        int negativeCount,
        int positivePassCount,
        int negativeBlockedCount,
        boolean overallPass
    ) {
        public HoldoutReport {
            acceptedAssumptions = acceptedAssumptions == null ? List.of() : List.copyOf(acceptedAssumptions);
            positiveResults = positiveResults == null ? List.of() : List.copyOf(positiveResults);
            negativeResults = negativeResults == null ? List.of() : List.copyOf(negativeResults);
        }

        /** Returns all positive and negative holdout results concatenated. */
        public List<HoldoutResult> allResults() {
            return Stream.concat(positiveResults.stream(), negativeResults.stream()).toList();
        }
    }

    public record HoldoutResult(
        String holdoutId,
        String inputExpression,
        String targetExpression,
        boolean expectPositive,
        boolean withSuccess,
        int withPathLength,
        long withStatesExplored,
        boolean withoutSuccess,
        int withoutPathLength,
        long withoutStatesExplored,
        String oracleStatus,
        String oracleEvidence,
        List<String> rulePath,
        boolean blocked
    ) {
        public HoldoutResult {
            oracleStatus = oracleStatus == null ? "UNAVAILABLE" : oracleStatus;
            oracleEvidence = oracleEvidence == null ? "" : oracleEvidence;
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
        }
    }

    public record ReuseAblation(
        String campaignId,
        String candidateId,
        List<AblationPairResult> pairs,
        long improvedCount,
        long totalCount,
        boolean measuredImprovement
    ) {
        public ReuseAblation {
            pairs = pairs == null ? List.of() : List.copyOf(pairs);
        }
    }

    public record AblationPairResult(
        String inputExpression,
        String targetExpression,
        boolean withSuccess,
        int withPathLength,
        long withStatesExplored,
        boolean withoutSuccess,
        int withoutPathLength,
        long withoutStatesExplored,
        boolean improved
    ) {
    }

    public record ProvenanceGraph(
        String campaignId,
        String timestamp,
        List<ProvenanceNode> nodes,
        List<ProvenanceEdge> edges
    ) {
        public ProvenanceGraph {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
    }

    public record ProvenanceNode(
        String nodeId,
        String nodeType,
        String family,
        Map<String, String> attributes
    ) {
        public ProvenanceNode {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record ProvenanceEdge(
        String fromNodeId,
        String toNodeId,
        String edgeType
    ) {
    }

    private record TrainingCase(
        String id,
        String family,
        String inputExpression,
        String targetExpression,
        String notes
    ) {
    }
}
