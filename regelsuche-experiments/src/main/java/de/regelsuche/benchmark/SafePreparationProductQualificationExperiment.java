package de.regelsuche.benchmark;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.benchmark.SafePreparationProductQualificationReport.CaseResult;
import de.regelsuche.benchmark.SafePreparationProductQualificationReport.ExperimentCase;
import de.regelsuche.benchmark.SafePreparationProductQualificationReport.Report;
import de.regelsuche.benchmark.SafePreparationProductQualificationReport.RouteOutcome;
import de.regelsuche.benchmark.SafePreparationProductQualificationReport.SafeTelemetry;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.reachability.PatternTargetedLocalBridgeSearch;
import de.regelsuche.search.reachability.RulePreparationCoordinator;
import de.regelsuche.search.reachability.SharedUnifiedRulePreparationCoordinator;
import de.regelsuche.search.strategy.SearchExpansionSource;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Budget;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Result;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.State;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngines;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.PerfectSquareStructurePreparationSolver;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationBatch;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Frozen information-parity qualification for #745 section A.
 *
 * <p>DIRECT_V1 is the no-preparation ablation. SAFE_PREPARATION_V2 sees the
 * same concrete rule inventory and receives the same outer search budgets but
 * may additionally invoke the explicit shared preparation authority. Every
 * prepared search edge retains its full primitive lineage. The SAFE route also
 * pays for the deterministic full recomputation performed by
 * {@link SharedUnifiedRulePreparationCoordinator#verify}.</p>
 */
public final class SafePreparationProductQualificationExperiment {
    public static final String SCHEMA =
        SafePreparationProductQualificationReport.SCHEMA;
    public static final String CONFIGURATION_ID =
        SafePreparationProductQualificationReport.CONFIGURATION_ID;
    public static final String DIRECT_PROFILE_ID =
        SafePreparationProductQualificationReport.DIRECT_PROFILE_ID;
    public static final String SAFE_PROFILE_ID =
        SafePreparationProductQualificationReport.SAFE_PROFILE_ID;

    private static final String PYTHAGOREAN_RULE_ID =
        "sympy.trig.pythagorean";
    private static final String DIFFERENCE_OF_SQUARES_RULE_ID =
        "sympy.poly.factor.diff_squares";
    private static final String TELESCOPING_RULE_ID =
        "sympy.rational.partial_fraction.telescoping";
    private static final String CANCELLATION_RULE_ID =
        "ast_cancel_division_factor";
    private static final int SOURCE_CANDIDATE_LIMIT = 32;

    private static final PatternTargetedLocalBridgeSearch.Budget
        PREPARATION_BUDGET = new PatternTargetedLocalBridgeSearch.Budget(
            3, 128, 1_024, 8, 160, 128,
            32, 5_000, 2_500);
    private static final Budget SEARCH_BUDGET =
        Budget.primitive(4, 96, 32, 4, 200_000);

    private static final List<String> PRODUCT_BLOCKERS = List.of(
        "APPLICABILITY_SCHEMA_COVERAGE_INCOMPLETE",
        "TYPED_REPRESENTATION_PREPARATION_OUTSIDE_PROFILE",
        "OCCURRENCE_LOCAL_GUARD_BINDINGS_NOT_PRODUCT_QUALIFIED",
        "WORKBENCH_CLI_RUNTIME_ADAPTER_NOT_PRODUCT_QUALIFIED");

    private final ExpressionParser parser = new ExpressionParser();

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "expected repository revision and output directory");
        }
        Report report = new SafePreparationProductQualificationExperiment()
            .run(args[0]);
        report.write(Path.of(args[1]));
        if (!report.evidenceQualified()) {
            throw new IllegalStateException(
                "safe-preparation matched-work evidence did not satisfy its frozen contract");
        }
    }

    public Report run(String repositoryRevision) {
        SafePreparationProductQualificationReport.requireRevision(
            repositoryRevision);
        List<PatternRewriteRule> principals = principals();
        List<RewriteApplicabilitySchema> schemas = principals.stream()
            .map(RewriteApplicabilitySchema::fromPatternRule)
            .toList();
        List<RewriteRule> preparationRules = cancellationRules();
        List<RewriteRule> visibleRules = visibleRules(
            principals, preparationRules);
        String visibleInventory = RuleInventoryFingerprint.contentHash(
            visibleRules);
        SharedUnifiedRulePreparationCoordinator identity =
            new SharedUnifiedRulePreparationCoordinator(
                schemas,
                preparationRules,
                repositoryRevision,
                PREPARATION_BUDGET);

        List<CaseResult> results = cases().stream()
            .map(experimentCase -> evaluateCase(
                experimentCase,
                schemas,
                preparationRules,
                visibleRules,
                repositoryRevision,
                visibleInventory))
            .toList();

        return new Report(
            SCHEMA,
            CONFIGURATION_ID,
            repositoryRevision,
            DIRECT_PROFILE_ID,
            SAFE_PROFILE_ID,
            SafePreparationProductQualificationReport
                .SAFE_WORK_ACCOUNTING_REVISION,
            true,
            visibleInventory,
            identity.principalInventoryFingerprint(),
            identity.preparationInventoryFingerprint(),
            identity.exactRegistryFingerprint(),
            PREPARATION_BUDGET,
            SEARCH_BUDGET,
            true,
            results,
            PRODUCT_BLOCKERS);
    }

    private CaseResult evaluateCase(
        ExperimentCase experimentCase,
        List<RewriteApplicabilitySchema> schemas,
        List<RewriteRule> preparationRules,
        List<RewriteRule> visibleRules,
        String repositoryRevision,
        String visibleInventory
    ) {
        String source = normalize(experimentCase.sourceExpression());
        String target = normalize(experimentCase.targetExpression());

        MeasuredTransformationEngine direct =
            MeasuredTransformationEngines.counting(
                new AstRewriteTransformationEngine(
                    visibleRules,
                    Integer.MAX_VALUE,
                    SOURCE_CANDIDATE_LIMIT));
        RouteOutcome directOutcome;
        try {
            directOutcome = route(
                DIRECT_PROFILE_ID,
                experimentCase,
                search(direct, source, target),
                SafeTelemetry.none(),
                visibleInventory);
        } catch (RuntimeException exception) {
            directOutcome = RouteOutcome.technicalFailure(
                DIRECT_PROFILE_ID,
                visibleInventory,
                SEARCH_BUDGET,
                stableFailure(exception),
                SafeTelemetry.none());
        }

        SafePreparationMeasuredEngine safe =
            new SafePreparationMeasuredEngine(
                schemas,
                preparationRules,
                repositoryRevision,
                experimentCase.sourceAssumptions());
        RouteOutcome safeOutcome;
        try {
            Result safeResult = search(safe, source, target);
            safeOutcome = route(
                SAFE_PROFILE_ID,
                experimentCase,
                safeResult,
                safe.telemetry(),
                visibleInventory);
        } catch (RuntimeException exception) {
            safeOutcome = RouteOutcome.technicalFailure(
                SAFE_PROFILE_ID,
                visibleInventory,
                SEARCH_BUDGET,
                stableFailure(exception),
                safe.telemetry());
        }

        boolean reachabilityRegression = directOutcome.semanticReached()
            && !safeOutcome.semanticReached();
        boolean correctnessRegression = safeOutcome.syntacticallyReached()
            && !safeOutcome.semanticReached()
            && directOutcome.semanticReached();
        boolean assumptionRegression = directOutcome.semanticReached()
            && safeOutcome.semanticReached()
            && !safeOutcome.effectiveAssumptions().equals(
                directOutcome.effectiveAssumptions());
        boolean newlyReached = !directOutcome.semanticReached()
            && safeOutcome.semanticReached();
        boolean expectationsSatisfied =
            directOutcome.semanticReached()
                == experimentCase.expectDirectSemanticReachability()
            && safeOutcome.semanticReached()
                == experimentCase.expectSafeSemanticReachability();

        return new CaseResult(
            experimentCase,
            directOutcome,
            safeOutcome,
            expectationsSatisfied,
            reachabilityRegression,
            correctnessRegression,
            assumptionRegression,
            newlyReached);
    }

    private Result search(
        MeasuredTransformationEngine engine,
        String source,
        String target
    ) {
        return new WorkBudgetBestFirstSearchStrategy().search(
            new Problem(
                source,
                target,
                new SearchExpansionSource.Measured(engine),
                new ExpressionScorer(),
                new ExpressionCanonicalizer(),
                SEARCH_BUDGET));
    }

    private RouteOutcome route(
        String profileId,
        ExperimentCase experimentCase,
        Result result,
        SafeTelemetry telemetry,
        String visibleInventory
    ) {
        PathAudit audit = audit(experimentCase, result);
        State reached = result.reachedState();
        return new RouteOutcome(
            profileId,
            visibleInventory,
            result.status().name(),
            result.reached(),
            audit.confirmed(),
            audit.constructionSafe(),
            audit.effectiveAssumptions(),
            audit.missingAssumptions(),
            reached == null ? -1 : reached.edgeDepth(),
            reached == null ? 0 : reached.primitiveDepth(),
            reached == null ? List.of() : reached.appliedRuleIds(),
            reached == null ? List.of() : reached.primitiveRuleIds(),
            result.metrics().exploredStates(),
            result.metrics().generatedTransformations(),
            result.metrics().chargedSearchWorkUnits(),
            SEARCH_BUDGET,
            "",
            telemetry);
    }

    private PathAudit audit(
        ExperimentCase experimentCase,
        Result result
    ) {
        if (!result.reached()) {
            return new PathAudit(false, true, List.of(), List.of());
        }
        State reached = result.reachedState();
        boolean constructionSafe = reached.transformations().stream()
            .allMatch(Transformation::equivalencePreservingByConstruction);
        AssumptionSignature effective = AssumptionSignature.merge(
            AssumptionSignature.ofExpressions(
                experimentCase.sourceAssumptions()),
            AssumptionSignature.ofExpressions(reached.assumptions()));
        Set<String> required = new LinkedHashSet<>(
            AssumptionSignature.ofExpressions(
                experimentCase.requiredAssumptions())
                .normalizedAssumptions());
        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(effective.normalizedAssumptions());
        boolean confirmed = constructionSafe && missing.isEmpty();
        return new PathAudit(
            confirmed,
            constructionSafe,
            effective.normalizedAssumptions(),
            List.copyOf(missing));
    }

    private List<PatternRewriteRule> principals() {
        return List.of(
            imported("sympy-trigonometry", PYTHAGOREAN_RULE_ID),
            imported("sympy-polynomial", DIFFERENCE_OF_SQUARES_RULE_ID),
            imported("sympy-rational", TELESCOPING_RULE_ID),
            builtInPattern(
                PerfectSquareStructurePreparationSolver.PRINCIPAL_RULE_ID));
    }

    private PatternRewriteRule imported(String packId, String ruleId) {
        return new KnowledgePackRegistry().allPacks().stream()
            .filter(pack -> packId.equals(pack.packId()))
            .flatMap(pack -> pack.rules().stream())
            .filter(rule -> ruleId.equals(rule.id()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "missing imported rule: " + ruleId));
    }

    private static PatternRewriteRule builtInPattern(String ruleId) {
        RewriteRule rule = AstRewriteTransformationEngine.allBuiltInRules()
            .stream()
            .filter(candidate -> ruleId.equals(candidate.id()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "missing built-in rule: " + ruleId));
        if (!(rule instanceof PatternRewriteRule pattern)) {
            throw new IllegalStateException(
                "principal is not an explicitly schema-capable pattern rule: "
                    + ruleId);
        }
        return pattern;
    }

    private static List<RewriteRule> cancellationRules() {
        List<RewriteRule> rules = AstRewriteTransformationEngine
            .allBuiltInRules().stream()
            .filter(rule -> CANCELLATION_RULE_ID.equals(rule.id()))
            .toList();
        if (rules.size() != 1) {
            throw new IllegalStateException(
                "expected exactly one cancellation preparation rule");
        }
        return rules;
    }

    private static List<RewriteRule> visibleRules(
        List<PatternRewriteRule> principals,
        List<RewriteRule> preparationRules
    ) {
        List<RewriteRule> result = new ArrayList<>(principals);
        result.addAll(preparationRules);
        return List.copyOf(result);
    }

    private String normalize(String expression) {
        return ExpressionFormatter.format(parser.parseTerm(expression));
    }

    public static List<ExperimentCase> cases() {
        return List.of(
            c("pythagorean-direct-canonical", "trigonometry",
                "sin(x)^2 + cos(x)^2", "1",
                List.of(), List.of(), true, true),
            c("pythagorean-direct-ac-reordered", "trigonometry",
                "cos(x)^2 + sin(x)^2", "1",
                List.of(), List.of(), true, true),
            c("pythagorean-one-hidden-cancellation", "trigonometry",
                "((sin(x) * a) / a)^2 + cos(x)^2", "1",
                List.of(), List.of("a != 0"), true, true),
            c("pythagorean-two-hidden-cancellations", "trigonometry",
                "((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2", "1",
                List.of(), List.of("a != 0", "b != 0"), true, true),
            c("pythagorean-different-argument-near-miss",
                "trigonometry-negative",
                "((sin(x) * a) / a)^2 + ((cos(y) * b) / b)^2", "1",
                List.of(), List.of(), false, false),
            c("difference-squares-direct", "polynomial",
                "x^2 - y^2", "(x - y) * (x + y)",
                List.of(), List.of(), true, true),
            c("difference-squares-two-hidden-cancellations", "polynomial",
                "((x^2 * a) / a) - ((y^2 * b) / b)",
                "(x - y) * (x + y)",
                List.of(), List.of("a != 0", "b != 0"), true, true),
            c("difference-squares-sum-near-miss", "polynomial-negative",
                "x^2 + y^2", "(x - y) * (x + y)",
                List.of(), List.of(), false, false),
            c("telescoping-direct", "rational",
                "1 / (n * (n + 1))", "1 / n - 1 / (n + 1)",
                List.of("n != 0", "n + 1 != 0"),
                List.of("n != 0", "n + 1 != 0"), true, true),
            c("telescoping-two-hidden-cancellations", "rational",
                "1 / (((n * a) / a) * (((n + 1) * b) / b))",
                "1 / n - 1 / (n + 1)",
                List.of("n != 0", "n + 1 != 0"),
                List.of("a != 0", "b != 0", "n != 0", "n + 1 != 0"),
                true, true),
            c("telescoping-step-two-near-miss", "rational-negative",
                "1 / (n * (n + 2))", "1 / n - 1 / (n + 1)",
                List.of("n != 0", "n + 2 != 0"), List.of(), false, false),
            c("telescoping-missing-guard-control", "guard-negative",
                "1 / (n * (n + 1))", "1 / n - 1 / (n + 1)",
                List.of(), List.of("n != 0", "n + 1 != 0"), false, false),
            c("perfect-square-native-exact-preparation",
                "polynomial-exact-preparation",
                "4 * x^4 * y^2 - 9 * z^2",
                "(2 * x^2 * y - 3 * z) * (2 * x^2 * y + 3 * z)",
                List.of(), List.of(), false, true));
    }

    private static ExperimentCase c(
        String id,
        String family,
        String source,
        String target,
        List<String> sourceAssumptions,
        List<String> requiredAssumptions,
        boolean direct,
        boolean safe
    ) {
        return new ExperimentCase(
            id,
            family,
            source,
            target,
            AssumptionSignature.ofExpressions(sourceAssumptions)
                .normalizedAssumptions(),
            AssumptionSignature.ofExpressions(requiredAssumptions)
                .normalizedAssumptions(),
            direct,
            safe);
    }

    /**
     * Experiment-only search adapter. General cancellation stays visible as an
     * ordinary search move; principal outputs come only through the guarded v2
     * coordinator, so the SAFE route cannot bypass its applicability schemas.
     */
    private static final class SafePreparationMeasuredEngine
            implements MeasuredTransformationEngine {
        private static final Comparator<Transformation> ORDER = Comparator
            .comparing(Transformation::rule)
            .thenComparing(Transformation::transformedExpression)
            .thenComparing(Transformation::applicationKey);

        private final SharedUnifiedRulePreparationCoordinator coordinator;
        private final MeasuredTransformationEngine supportEngine;
        private final AssumptionSignature sourceAssumptions;
        private long coordinatorCalls;
        private long verificationCalls;
        private long verificationFailures;
        private long directPrincipalCandidates;
        private long preparedPrincipalCandidates;
        private long analyzeMechanicalWork;
        private long verificationReplayMechanicalWork;
        private long chargedCoordinatorMechanicalWork;
        private long fallbackExecutions;
        private long fallbackGeneratedTransitions;
        private long fallbackUniqueExpansions;

        private SafePreparationMeasuredEngine(
            List<RewriteApplicabilitySchema> schemas,
            List<RewriteRule> preparationRules,
            String repositoryRevision,
            List<String> sourceAssumptions
        ) {
            this.coordinator = new SharedUnifiedRulePreparationCoordinator(
                schemas,
                preparationRules,
                repositoryRevision,
                PREPARATION_BUDGET);
            this.supportEngine = MeasuredTransformationEngines.counting(
                new AstRewriteTransformationEngine(
                    preparationRules,
                    Integer.MAX_VALUE,
                    SOURCE_CANDIDATE_LIMIT));
            this.sourceAssumptions = AssumptionSignature.ofExpressions(
                sourceAssumptions);
        }

        @Override
        public TransformationBatch transformMeasured(String expression) {
            TransformationBatch support = supportEngine.transformMeasured(
                expression);
            SharedUnifiedRulePreparationCoordinator.Evaluation evaluation =
                coordinator.analyze(expression, sourceAssumptions);
            coordinatorCalls++;
            long analyzeWork = coordinatorMechanicalWork(evaluation);
            analyzeMechanicalWork = add(analyzeMechanicalWork, analyzeWork);

            verificationCalls++;
            var verification = coordinator.verify(evaluation);
            // verify(Evaluation) performs exactly one fresh analyze(...) and
            // equality check. The deterministic recomputation therefore pays
            // the same measured coordinator work again.
            long replayWork = analyzeWork;
            verificationReplayMechanicalWork = add(
                verificationReplayMechanicalWork, replayWork);
            long delegated = add(analyzeWork, replayWork);
            chargedCoordinatorMechanicalWork = add(
                chargedCoordinatorMechanicalWork, delegated);
            if (!verification.valid()) {
                verificationFailures++;
                throw new IllegalStateException(
                    "shared safe-preparation evaluation did not replay: "
                        + verification.detailCode());
            }

            List<Transformation> principal = evaluation.outcomes().stream()
                .filter(RulePreparationCoordinator.Outcome::positive)
                .flatMap(outcome -> outcome.candidate().stream())
                .toList();
            directPrincipalCandidates += evaluation.outcomes().stream()
                .filter(RulePreparationCoordinator.Outcome::direct)
                .count();
            preparedPrincipalCandidates += evaluation.outcomes().stream()
                .filter(RulePreparationCoordinator.Outcome::prepared)
                .count();

            var shared = evaluation.sharedExecutionWork();
            if (shared.fallbackExecuted()) {
                fallbackExecutions++;
            }
            fallbackGeneratedTransitions = add(
                fallbackGeneratedTransitions,
                shared.fallbackWork().generatedTransitions());
            fallbackUniqueExpansions = add(
                fallbackUniqueExpansions,
                shared.fallbackPhysicalWork().uniqueExpansions());

            List<Transformation> combined = new ArrayList<>(
                support.transformations());
            combined.addAll(principal);
            combined.sort(ORDER);
            Map<String, Transformation> distinct = new LinkedHashMap<>();
            for (Transformation transformation : combined) {
                distinct.putIfAbsent(key(transformation), transformation);
            }
            long duplicates = combined.size() - distinct.size();
            TransformationWorkMetrics principalWork =
                TransformationWorkMetrics.flatEngine(principal.size())
                    .withDelegatedMechanicalWork(delegated)
                    .withDuplicateCandidatesDropped(duplicates);
            return new TransformationBatch(
                List.copyOf(distinct.values()),
                support.workMetrics().plus(principalWork));
        }

        private SafeTelemetry telemetry() {
            return new SafeTelemetry(
                coordinatorCalls,
                verificationCalls,
                verificationFailures,
                directPrincipalCandidates,
                preparedPrincipalCandidates,
                analyzeMechanicalWork,
                verificationReplayMechanicalWork,
                chargedCoordinatorMechanicalWork,
                fallbackExecutions,
                fallbackGeneratedTransitions,
                fallbackUniqueExpansions);
        }

        private static long coordinatorMechanicalWork(
            SharedUnifiedRulePreparationCoordinator.Evaluation evaluation
        ) {
            var shared = evaluation.sharedExecutionWork();
            var source = shared.sourceAnalysisWork();
            var aggregate = evaluation.aggregateWork();
            long total = 0;
            total = add(total, source.uniqueParsedExpressions());
            total = add(total, source.uniqueAnalyses());
            total = add(total, aggregate.expandedStates());
            total = add(total, aggregate.generatedTransitions());
            total = add(total, aggregate.discoveredStates());
            total = add(total, aggregate.retainedTransitions());
            total = add(total, aggregate.duplicateTransitions());
            total = add(total, aggregate.analyzedCandidates());
            return add(total, shared.guardRequests());
        }

        private static String key(Transformation transformation) {
            return transformation.rule() + "\u0000"
                + transformation.transformedExpression() + "\u0000"
                + transformation.applicationKey() + "\u0000"
                + transformation.primitiveRuleIds();
        }
    }

    private record PathAudit(
        boolean confirmed,
        boolean constructionSafe,
        List<String> effectiveAssumptions,
        List<String> missingAssumptions
    ) {
        private PathAudit {
            effectiveAssumptions = List.copyOf(effectiveAssumptions);
            missingAssumptions = List.copyOf(missingAssumptions);
        }
    }

    private static long add(long left, long right) {
        return SafePreparationProductQualificationReport.saturatedAdd(
            left, right);
    }

    private static String stableFailure(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
            + (message == null || message.isBlank()
                ? ""
                : ":" + message.replaceAll("\\s+", " ").trim());
    }
}
