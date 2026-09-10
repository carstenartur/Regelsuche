package de.regelsuche.benchmark;

import de.regelsuche.assumption.AssumptionSignature;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * Frozen information-parity qualification for the safe preparation product
 * profile from issue #745.
 *
 * <p>DIRECT_V1 and SAFE_PREPARATION_V2 see the same concrete rewrite
 * inventory and the same target. Both searches receive the same primitive,
 * candidate, state and total-work budgets. SAFE may additionally use the
 * explicitly versioned preparation authority, but every composed candidate
 * keeps its complete primitive lineage and therefore consumes its real
 * primitive path cost.</p>
 *
 * <p>The report deliberately separates a successful bounded qualification
 * from the product-default decision. A green experiment is not sufficient to
 * switch the Workbench/CLI default while known coverage or runtime-integration
 * blockers remain.</p>
 */
public final class SafePreparationProductQualificationExperiment {
    public static final String SCHEMA =
        "regelsuche.safe-preparation-product-qualification/v1";
    public static final String CONFIGURATION_ID =
        "direct-v1-vs-safe-preparation-v2-matched-work-v1";
    public static final String DIRECT_PROFILE_ID = "DIRECT_V1";
    public static final String SAFE_PROFILE_ID = "SAFE_PREPARATION_V2";

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
        requireRevision(repositoryRevision);
        List<PatternRewriteRule> principals = principals();
        List<RewriteApplicabilitySchema> schemas = principals.stream()
            .map(RewriteApplicabilitySchema::fromPatternRule)
            .toList();
        List<RewriteRule> preparationRules = cancellationRules();
        List<RewriteRule> visibleRules = visibleRules(principals, preparationRules);
        String visibleInventory = RuleInventoryFingerprint.contentHash(visibleRules);
        SharedUnifiedRulePreparationCoordinator identityCoordinator =
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
            visibleInventory,
            identityCoordinator.principalInventoryFingerprint(),
            identityCoordinator.preparationInventoryFingerprint(),
            identityCoordinator.exactRegistryFingerprint(),
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
        RouteOutcome directOutcome = route(
            DIRECT_PROFILE_ID,
            experimentCase,
            search(direct, source, target),
            SafeTelemetry.none(),
            visibleInventory);

        SafePreparationMeasuredEngine safe =
            new SafePreparationMeasuredEngine(
                schemas,
                preparationRules,
                visibleRules,
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
            new ExperimentCase(
                "pythagorean-direct-canonical",
                "trigonometry",
                "sin(x)^2 + cos(x)^2",
                "1",
                List.of(),
                List.of(),
                true,
                true),
            new ExperimentCase(
                "pythagorean-direct-ac-reordered",
                "trigonometry",
                "cos(x)^2 + sin(x)^2",
                "1",
                List.of(),
                List.of(),
                true,
                true),
            new ExperimentCase(
                "pythagorean-one-hidden-cancellation",
                "trigonometry",
                "((sin(x) * a) / a)^2 + cos(x)^2",
                "1",
                List.of(),
                List.of("a != 0"),
                true,
                true),
            new ExperimentCase(
                "pythagorean-two-hidden-cancellations",
                "trigonometry",
                "((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2",
                "1",
                List.of(),
                List.of("a != 0", "b != 0"),
                true,
                true),
            new ExperimentCase(
                "pythagorean-different-argument-near-miss",
                "trigonometry-negative",
                "((sin(x) * a) / a)^2 + ((cos(y) * b) / b)^2",
                "1",
                List.of(),
                List.of(),
                false,
                false),
            new ExperimentCase(
                "difference-squares-direct",
                "polynomial",
                "x^2 - y^2",
                "(x - y) * (x + y)",
                List.of(),
                List.of(),
                true,
                true),
            new ExperimentCase(
                "difference-squares-two-hidden-cancellations",
                "polynomial",
                "((x^2 * a) / a) - ((y^2 * b) / b)",
                "(x - y) * (x + y)",
                List.of(),
                List.of("a != 0", "b != 0"),
                true,
                true),
            new ExperimentCase(
                "difference-squares-sum-near-miss",
                "polynomial-negative",
                "x^2 + y^2",
                "(x - y) * (x + y)",
                List.of(),
                List.of(),
                false,
                false),
            new ExperimentCase(
                "telescoping-direct",
                "rational",
                "1 / (n * (n + 1))",
                "1 / n - 1 / (n + 1)",
                List.of("n != 0", "n + 1 != 0"),
                List.of("n != 0", "n + 1 != 0"),
                true,
                true),
            new ExperimentCase(
                "telescoping-two-hidden-cancellations",
                "rational",
                "1 / (((n * a) / a) * (((n + 1) * b) / b))",
                "1 / n - 1 / (n + 1)",
                List.of("n != 0", "n + 1 != 0"),
                List.of(
                    "a != 0", "b != 0",
                    "n != 0", "n + 1 != 0"),
                true,
                true),
            new ExperimentCase(
                "telescoping-step-two-near-miss",
                "rational-negative",
                "1 / (n * (n + 2))",
                "1 / n - 1 / (n + 1)",
                List.of("n != 0", "n + 2 != 0"),
                List.of(),
                false,
                false),
            new ExperimentCase(
                "telescoping-missing-guard-control",
                "guard-negative",
                "1 / (n * (n + 1))",
                "1 / n - 1 / (n + 1)",
                List.of(),
                List.of("n != 0", "n + 1 != 0"),
                false,
                false),
            new ExperimentCase(
                "perfect-square-native-exact-preparation",
                "polynomial-exact-preparation",
                "4 * x^4 * y^2 - 9 * z^2",
                "(2 * x^2 * y - 3 * z) * (2 * x^2 * y + 3 * z)",
                List.of(),
                List.of(),
                false,
                true));
    }

    public enum ProductDecision {
        SELECT_SAFE_PREPARATION_V2,
        KEEP_OPT_IN_SAFETY_FAILURE,
        KEEP_OPT_IN_PENDING_PRODUCT_COVERAGE
    }

    public record ExperimentCase(
        String id,
        String family,
        String sourceExpression,
        String targetExpression,
        List<String> sourceAssumptions,
        List<String> requiredAssumptions,
        boolean expectDirectSemanticReachability,
        boolean expectSafeSemanticReachability
    ) {
        public ExperimentCase {
            id = requiredText(id, "id");
            family = requiredText(family, "family");
            sourceExpression = requiredText(
                sourceExpression, "sourceExpression");
            targetExpression = requiredText(
                targetExpression, "targetExpression");
            sourceAssumptions = AssumptionSignature.ofExpressions(
                Objects.requireNonNull(
                    sourceAssumptions, "sourceAssumptions"))
                .normalizedAssumptions();
            requiredAssumptions = AssumptionSignature.ofExpressions(
                Objects.requireNonNull(
                    requiredAssumptions, "requiredAssumptions"))
                .normalizedAssumptions();
        }
    }

    public record SafeTelemetry(
        long coordinatorCalls,
        long verificationFailures,
        long directPrincipalCandidates,
        long preparedPrincipalCandidates,
        long coordinatorMechanicalWork,
        long fallbackExecutions,
        long fallbackGeneratedTransitions,
        long fallbackUniqueExpansions
    ) {
        public SafeTelemetry {
            if (coordinatorCalls < 0 || verificationFailures < 0
                    || directPrincipalCandidates < 0
                    || preparedPrincipalCandidates < 0
                    || coordinatorMechanicalWork < 0
                    || fallbackExecutions < 0
                    || fallbackGeneratedTransitions < 0
                    || fallbackUniqueExpansions < 0
                    || verificationFailures > coordinatorCalls) {
                throw new IllegalArgumentException(
                    "safe preparation telemetry is invalid");
            }
        }

        static SafeTelemetry none() {
            return new SafeTelemetry(0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public record RouteOutcome(
        String profileId,
        String visibleInventoryFingerprint,
        String searchStatus,
        boolean syntacticallyReached,
        boolean semanticReached,
        boolean constructionSafe,
        List<String> effectiveAssumptions,
        List<String> missingAssumptions,
        int edgeDepth,
        int primitiveDepth,
        List<String> appliedRuleIds,
        List<String> primitiveRuleIds,
        long exploredStates,
        long generatedCandidates,
        long chargedTotalWork,
        Budget configuredBudget,
        String technicalFailure,
        SafeTelemetry safeTelemetry
    ) {
        public RouteOutcome {
            profileId = requiredText(profileId, "profileId");
            visibleInventoryFingerprint = requiredHash(
                visibleInventoryFingerprint,
                "visibleInventoryFingerprint");
            searchStatus = requiredText(searchStatus, "searchStatus");
            effectiveAssumptions = List.copyOf(effectiveAssumptions);
            missingAssumptions = List.copyOf(missingAssumptions);
            appliedRuleIds = List.copyOf(appliedRuleIds);
            primitiveRuleIds = List.copyOf(primitiveRuleIds);
            configuredBudget = Objects.requireNonNull(
                configuredBudget, "configuredBudget");
            technicalFailure = technicalFailure == null
                ? "" : technicalFailure;
            safeTelemetry = Objects.requireNonNull(
                safeTelemetry, "safeTelemetry");
            if (edgeDepth < -1 || primitiveDepth < 0
                    || exploredStates < 0 || generatedCandidates < 0
                    || chargedTotalWork < 0
                    || semanticReached && !syntacticallyReached
                    || semanticReached && !constructionSafe
                    || semanticReached && !missingAssumptions.isEmpty()) {
                throw new IllegalArgumentException(
                    "route outcome is inconsistent");
            }
        }

        static RouteOutcome technicalFailure(
            String profileId,
            String visibleInventoryFingerprint,
            Budget budget,
            String failure,
            SafeTelemetry telemetry
        ) {
            return new RouteOutcome(
                profileId,
                visibleInventoryFingerprint,
                "TECHNICAL_FAILURE",
                false,
                false,
                false,
                List.of(),
                List.of(),
                -1,
                0,
                List.of(),
                List.of(),
                0,
                0,
                0,
                budget,
                requiredText(failure, "failure"),
                telemetry);
        }

        public boolean technicalFailurePresent() {
            return !technicalFailure.isEmpty();
        }
    }

    public record CaseResult(
        ExperimentCase experimentCase,
        RouteOutcome direct,
        RouteOutcome safe,
        boolean expectationsSatisfied,
        boolean reachabilityRegression,
        boolean correctnessRegression,
        boolean assumptionRegression,
        boolean newlyReachedBySafe
    ) {
        public CaseResult {
            experimentCase = Objects.requireNonNull(
                experimentCase, "experimentCase");
            direct = Objects.requireNonNull(direct, "direct");
            safe = Objects.requireNonNull(safe, "safe");
            if (!direct.configuredBudget().equals(safe.configuredBudget())) {
                throw new IllegalArgumentException(
                    "paired routes must use the same search budget");
            }
            if (!direct.visibleInventoryFingerprint().equals(
                    safe.visibleInventoryFingerprint())) {
                throw new IllegalArgumentException(
                    "paired routes must use the same visible inventory");
            }
            if (reachabilityRegression
                    != (direct.semanticReached()
                        && !safe.semanticReached())) {
                throw new IllegalArgumentException(
                    "reachability regression flag is inconsistent");
            }
        }
    }

    public record Report(
        String schema,
        String configurationId,
        String repositoryRevision,
        String directProfileId,
        String safeProfileId,
        String visibleInventoryFingerprint,
        String principalInventoryFingerprint,
        String preparationInventoryFingerprint,
        String exactRegistryFingerprint,
        PatternTargetedLocalBridgeSearch.Budget preparationBudget,
        Budget searchBudget,
        boolean directRouteIsNoPreparationAblation,
        List<CaseResult> cases,
        List<String> productBlockers
    ) {
        public Report {
            if (!SCHEMA.equals(schema)
                    || !CONFIGURATION_ID.equals(configurationId)
                    || !DIRECT_PROFILE_ID.equals(directProfileId)
                    || !SAFE_PROFILE_ID.equals(safeProfileId)) {
                throw new IllegalArgumentException(
                    "qualification identity is invalid");
            }
            requireRevision(repositoryRevision);
            visibleInventoryFingerprint = requiredHash(
                visibleInventoryFingerprint,
                "visibleInventoryFingerprint");
            principalInventoryFingerprint = requiredHash(
                principalInventoryFingerprint,
                "principalInventoryFingerprint");
            preparationInventoryFingerprint = requiredHash(
                preparationInventoryFingerprint,
                "preparationInventoryFingerprint");
            exactRegistryFingerprint = requiredHash(
                exactRegistryFingerprint,
                "exactRegistryFingerprint");
            preparationBudget = Objects.requireNonNull(
                preparationBudget, "preparationBudget");
            searchBudget = Objects.requireNonNull(searchBudget, "searchBudget");
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            productBlockers = List.copyOf(Objects.requireNonNull(
                productBlockers, "productBlockers"));
            if (!directRouteIsNoPreparationAblation
                    || cases.size()
                        != SafePreparationProductQualificationExperiment
                            .cases().size()) {
                throw new IllegalArgumentException(
                    "qualification must retain the frozen no-preparation matrix");
            }
        }

        public boolean evidenceQualified() {
            return cases.stream().allMatch(CaseResult::expectationsSatisfied)
                && cases.stream().noneMatch(CaseResult::reachabilityRegression)
                && cases.stream().noneMatch(CaseResult::correctnessRegression)
                && cases.stream().noneMatch(CaseResult::assumptionRegression)
                && cases.stream().noneMatch(result ->
                    result.safe().technicalFailurePresent())
                && cases.stream().allMatch(result ->
                    result.safe().safeTelemetry().verificationFailures() == 0)
                && newlyReachedCases() >= 1;
        }

        public long newlyReachedCases() {
            return cases.stream()
                .filter(CaseResult::newlyReachedBySafe)
                .count();
        }

        public long commonSolvedCases() {
            return cases.stream()
                .filter(result -> result.direct().semanticReached())
                .filter(result -> result.safe().semanticReached())
                .count();
        }

        public long directSyntacticButSemanticallyRejectedCases() {
            return cases.stream()
                .filter(result -> result.direct().syntacticallyReached())
                .filter(result -> !result.direct().semanticReached())
                .count();
        }

        public long safeSyntacticButSemanticallyRejectedCases() {
            return cases.stream()
                .filter(result -> result.safe().syntacticallyReached())
                .filter(result -> !result.safe().semanticReached())
                .count();
        }

        public long commonDirectWork() {
            return cases.stream()
                .filter(result -> result.direct().semanticReached()
                    && result.safe().semanticReached())
                .mapToLong(result -> result.direct().chargedTotalWork())
                .sum();
        }

        public long commonSafeWork() {
            return cases.stream()
                .filter(result -> result.direct().semanticReached()
                    && result.safe().semanticReached())
                .mapToLong(result -> result.safe().chargedTotalWork())
                .sum();
        }

        public ProductDecision productDecision() {
            if (!evidenceQualified()) {
                return ProductDecision.KEEP_OPT_IN_SAFETY_FAILURE;
            }
            return productBlockers.isEmpty()
                ? ProductDecision.SELECT_SAFE_PREPARATION_V2
                : ProductDecision.KEEP_OPT_IN_PENDING_PRODUCT_COVERAGE;
        }

        public void write(Path outputDirectory) throws IOException {
            Objects.requireNonNull(outputDirectory, "outputDirectory");
            Files.createDirectories(outputDirectory);
            Files.writeString(
                outputDirectory.resolve("qualification.json"),
                toJson(),
                StandardCharsets.UTF_8);
            Files.writeString(
                outputDirectory.resolve("qualification.md"),
                toMarkdown(),
                StandardCharsets.UTF_8);
        }

        public String toJson() {
            StringBuilder json = new StringBuilder();
            json.append("{\n")
                .append("  \"schema\": \"").append(escape(schema)).append("\",\n")
                .append("  \"configurationId\": \"").append(escape(configurationId)).append("\",\n")
                .append("  \"repositoryRevision\": \"").append(repositoryRevision).append("\",\n")
                .append("  \"directProfileId\": \"").append(directProfileId).append("\",\n")
                .append("  \"safeProfileId\": \"").append(safeProfileId).append("\",\n")
                .append("  \"visibleInventoryFingerprint\": \"").append(visibleInventoryFingerprint).append("\",\n")
                .append("  \"principalInventoryFingerprint\": \"").append(principalInventoryFingerprint).append("\",\n")
                .append("  \"preparationInventoryFingerprint\": \"").append(preparationInventoryFingerprint).append("\",\n")
                .append("  \"exactRegistryFingerprint\": \"").append(exactRegistryFingerprint).append("\",\n")
                .append("  \"directRouteIsNoPreparationAblation\": ").append(directRouteIsNoPreparationAblation).append(",\n")
                .append("  \"searchBudget\": ").append(searchBudgetJson(searchBudget)).append(",\n")
                .append("  \"preparationBudgetIdentity\": \"").append(preparationBudget.identity()).append("\",\n")
                .append("  \"evidenceQualified\": ").append(evidenceQualified()).append(",\n")
                .append("  \"productDecision\": \"").append(productDecision()).append("\",\n")
                .append("  \"newlyReachedCases\": ").append(newlyReachedCases()).append(",\n")
                .append("  \"commonSolvedCases\": ").append(commonSolvedCases()).append(",\n")
                .append("  \"commonDirectWork\": ").append(commonDirectWork()).append(",\n")
                .append("  \"commonSafeWork\": ").append(commonSafeWork()).append(",\n")
                .append("  \"directSyntacticButSemanticallyRejectedCases\": ").append(directSyntacticButSemanticallyRejectedCases()).append(",\n")
                .append("  \"safeSyntacticButSemanticallyRejectedCases\": ").append(safeSyntacticButSemanticallyRejectedCases()).append(",\n")
                .append("  \"productBlockers\": ").append(stringArray(productBlockers)).append(",\n")
                .append("  \"cases\": [\n");
            for (int index = 0; index < cases.size(); index++) {
                if (index > 0) {
                    json.append(",\n");
                }
                appendCaseJson(json, cases.get(index));
            }
            return json.append("\n  ]\n}\n").toString();
        }

        public String toMarkdown() {
            StringBuilder out = new StringBuilder();
            out.append("# Safe preparation product qualification\n\n")
                .append("Configuration: `").append(configurationId).append("`  \n")
                .append("Repository revision: `").append(repositoryRevision).append("`  \n")
                .append("Matched search budget: `").append(searchBudget).append("`  \n")
                .append("Evidence qualified: **").append(evidenceQualified()).append("**  \n")
                .append("Product decision: **").append(productDecision()).append("**\n\n")
                .append("The DIRECT route is the explicit no-preparation ablation. Both routes see the same declared concrete rule inventory and the same search budget. SAFE receives only the additional versioned preparation authority; composed candidates retain their complete primitive lineage.\n\n")
                .append("| Case | DIRECT semantic | SAFE semantic | DIRECT primitives | SAFE primitives | DIRECT work | SAFE work | New by SAFE |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
            for (CaseResult result : cases) {
                out.append("| ").append(result.experimentCase().id())
                    .append(" | ").append(result.direct().semanticReached())
                    .append(" | ").append(result.safe().semanticReached())
                    .append(" | ").append(result.direct().primitiveDepth())
                    .append(" | ").append(result.safe().primitiveDepth())
                    .append(" | ").append(result.direct().chargedTotalWork())
                    .append(" | ").append(result.safe().chargedTotalWork())
                    .append(" | ").append(result.newlyReachedBySafe())
                    .append(" |\n");
            }
            out.append("\nCommon solved cases: `").append(commonSolvedCases())
                .append("`; DIRECT work: `").append(commonDirectWork())
                .append("`; SAFE work: `").append(commonSafeWork()).append("`.  \n")
                .append("New semantic reachability under the matched budget: `")
                .append(newlyReachedCases()).append("` case(s).\n\n")
                .append("## Product-default decision\n\n");
            if (productBlockers.isEmpty()) {
                out.append("No retained product blockers remain in this frozen qualification.\n");
            } else {
                out.append("The bounded evidence can be green while the profile remains opt-in. The retained blockers are:\n\n");
                for (String blocker : productBlockers) {
                    out.append("- `").append(blocker).append("`\n");
                }
            }
            out.append("\n## Claim boundary\n\n")
                .append("This qualification is a frozen, matched-budget comparison for the retained case matrix. It does not establish wall-clock or asymptotic superiority, universal preparation coverage, global rewrite completeness, or safety for algorithmic/typed principals not represented by an explicit applicability schema.\n");
            return out.toString();
        }

        private static void appendCaseJson(
            StringBuilder json,
            CaseResult result
        ) {
            ExperimentCase c = result.experimentCase();
            json.append("    {\n")
                .append("      \"id\": \"").append(escape(c.id())).append("\",\n")
                .append("      \"family\": \"").append(escape(c.family())).append("\",\n")
                .append("      \"sourceExpression\": \"").append(escape(c.sourceExpression())).append("\",\n")
                .append("      \"targetExpression\": \"").append(escape(c.targetExpression())).append("\",\n")
                .append("      \"sourceAssumptions\": ").append(stringArray(c.sourceAssumptions())).append(",\n")
                .append("      \"requiredAssumptions\": ").append(stringArray(c.requiredAssumptions())).append(",\n")
                .append("      \"expectDirectSemanticReachability\": ").append(c.expectDirectSemanticReachability()).append(",\n")
                .append("      \"expectSafeSemanticReachability\": ").append(c.expectSafeSemanticReachability()).append(",\n")
                .append("      \"expectationsSatisfied\": ").append(result.expectationsSatisfied()).append(",\n")
                .append("      \"reachabilityRegression\": ").append(result.reachabilityRegression()).append(",\n")
                .append("      \"correctnessRegression\": ").append(result.correctnessRegression()).append(",\n")
                .append("      \"assumptionRegression\": ").append(result.assumptionRegression()).append(",\n")
                .append("      \"newlyReachedBySafe\": ").append(result.newlyReachedBySafe()).append(",\n")
                .append("      \"direct\": ");
            appendRouteJson(json, result.direct(), 6);
            json.append(",\n      \"safe\": ");
            appendRouteJson(json, result.safe(), 6);
            json.append("\n    }");
        }

        private static void appendRouteJson(
            StringBuilder json,
            RouteOutcome route,
            int indent
        ) {
            String p = " ".repeat(indent);
            json.append("{\n")
                .append(p).append("  \"profileId\": \"").append(route.profileId()).append("\",\n")
                .append(p).append("  \"searchStatus\": \"").append(escape(route.searchStatus())).append("\",\n")
                .append(p).append("  \"syntacticallyReached\": ").append(route.syntacticallyReached()).append(",\n")
                .append(p).append("  \"semanticReached\": ").append(route.semanticReached()).append(",\n")
                .append(p).append("  \"constructionSafe\": ").append(route.constructionSafe()).append(",\n")
                .append(p).append("  \"effectiveAssumptions\": ").append(stringArray(route.effectiveAssumptions())).append(",\n")
                .append(p).append("  \"missingAssumptions\": ").append(stringArray(route.missingAssumptions())).append(",\n")
                .append(p).append("  \"edgeDepth\": ").append(route.edgeDepth()).append(",\n")
                .append(p).append("  \"primitiveDepth\": ").append(route.primitiveDepth()).append(",\n")
                .append(p).append("  \"appliedRuleIds\": ").append(stringArray(route.appliedRuleIds())).append(",\n")
                .append(p).append("  \"primitiveRuleIds\": ").append(stringArray(route.primitiveRuleIds())).append(",\n")
                .append(p).append("  \"exploredStates\": ").append(route.exploredStates()).append(",\n")
                .append(p).append("  \"generatedCandidates\": ").append(route.generatedCandidates()).append(",\n")
                .append(p).append("  \"chargedTotalWork\": ").append(route.chargedTotalWork()).append(",\n")
                .append(p).append("  \"technicalFailure\": \"").append(escape(route.technicalFailure())).append("\",\n")
                .append(p).append("  \"safeTelemetry\": ").append(telemetryJson(route.safeTelemetry(), p + "  ")).append("\n")
                .append(p).append("}");
        }

        private static String telemetryJson(
            SafeTelemetry value,
            String indent
        ) {
            return "{\n"
                + indent + "  \"coordinatorCalls\": " + value.coordinatorCalls() + ",\n"
                + indent + "  \"verificationFailures\": " + value.verificationFailures() + ",\n"
                + indent + "  \"directPrincipalCandidates\": " + value.directPrincipalCandidates() + ",\n"
                + indent + "  \"preparedPrincipalCandidates\": " + value.preparedPrincipalCandidates() + ",\n"
                + indent + "  \"coordinatorMechanicalWork\": " + value.coordinatorMechanicalWork() + ",\n"
                + indent + "  \"fallbackExecutions\": " + value.fallbackExecutions() + ",\n"
                + indent + "  \"fallbackGeneratedTransitions\": " + value.fallbackGeneratedTransitions() + ",\n"
                + indent + "  \"fallbackUniqueExpansions\": " + value.fallbackUniqueExpansions() + "\n"
                + indent + "}";
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

    /**
     * Experiment-only adapter. Principal outputs come exclusively from the
     * guard-aware shared coordinator; ordinary preparation rules remain visible
     * as direct search moves. This avoids bypassing applicability guards by
     * unioning an unguarded principal engine behind the safe profile.
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
        private long verificationFailures;
        private long directPrincipalCandidates;
        private long preparedPrincipalCandidates;
        private long coordinatorMechanicalWork;
        private long fallbackExecutions;
        private long fallbackGeneratedTransitions;
        private long fallbackUniqueExpansions;

        private SafePreparationMeasuredEngine(
            List<RewriteApplicabilitySchema> schemas,
            List<RewriteRule> preparationRules,
            List<RewriteRule> visibleRules,
            String repositoryRevision,
            List<String> sourceAssumptions
        ) {
            Objects.requireNonNull(visibleRules, "visibleRules");
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
            TransformationBatch support = supportEngine.transformMeasured(expression);
            SharedUnifiedRulePreparationCoordinator.Evaluation evaluation =
                coordinator.analyze(expression, sourceAssumptions);
            coordinatorCalls++;
            if (!coordinator.verify(evaluation).valid()) {
                verificationFailures++;
                throw new IllegalStateException(
                    "shared safe-preparation evaluation did not replay");
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
            SharedUnifiedRulePreparationCoordinator.SharedExecutionWork shared =
                evaluation.sharedExecutionWork();
            if (shared.fallbackExecuted()) {
                fallbackExecutions++;
            }
            fallbackGeneratedTransitions += shared.fallbackWork()
                .generatedTransitions();
            fallbackUniqueExpansions += shared.fallbackPhysicalWork()
                .uniqueExpansions();
            long delegated = coordinatorMechanicalWork(evaluation);
            coordinatorMechanicalWork = add(
                coordinatorMechanicalWork, delegated);

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
                verificationFailures,
                directPrincipalCandidates,
                preparedPrincipalCandidates,
                coordinatorMechanicalWork,
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

    private static String searchBudgetJson(Budget budget) {
        return "{"
            + "\"maxPrimitiveSteps\":" + budget.maxPrimitiveSteps() + ","
            + "\"maxExactTheoryWorkUnits\":" + budget.maxExactTheoryWorkUnits() + ","
            + "\"maxExploredStates\":" + budget.maxExploredStates() + ","
            + "\"maxCandidatesPerState\":" + budget.maxCandidatesPerState() + ","
            + "\"maxExpandingSteps\":" + budget.maxExpandingSteps() + ","
            + "\"maxWorkUnits\":" + budget.maxWorkUnits()
            + "}";
    }

    private static String stringArray(List<String> values) {
        return values.stream()
            .map(value -> "\"" + escape(value) + "\"")
            .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String requiredHash(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 identity");
        }
        return value;
    }

    private static void requireRevision(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a lowercase commit SHA");
        }
    }

    private static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static String stableFailure(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
            + (message == null || message.isBlank()
                ? ""
                : ":" + message.replaceAll("\\s+", " ").trim());
    }
}
