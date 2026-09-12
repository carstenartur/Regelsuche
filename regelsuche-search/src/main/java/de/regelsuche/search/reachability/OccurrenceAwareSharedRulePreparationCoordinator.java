package de.regelsuche.search.reachability;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.EquivalentExpressionProvider;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Versioned SAFE-preparation authority with occurrence-local direct guard binding.
 *
 * <p>The historical {@link SharedUnifiedRulePreparationCoordinator} remains the
 * complete v2 authority. This v3 authority changes only the direct stage: it
 * retains the exact AST occurrence selected by the concrete executor, analyzes
 * the applicability pattern against that occurrence, and instantiates typed
 * guards from those occurrence-local bindings. Principals without a direct
 * candidate are delegated to an unchanged v2 coordinator, preserving the
 * already-qualified exact and shared-fallback semantics.</p>
 */
public final class OccurrenceAwareSharedRulePreparationCoordinator {
    public static final String COORDINATOR_ID =
        "regelsuche.unified-safe-rule-preparation-coordinator/v3";
    public static final String OCCURRENCE_BINDING_REVISION =
        "regelsuche.direct-occurrence-guard-binding/v1";

    /** Versioned logical work units; not wall time or CPU instructions. */
    public static final String OCCURRENCE_WORK_REVISION =
        "regelsuche.occurrence-preparation-work/v2";

    private final List<RewriteApplicabilitySchema> principalSchemas;
    private final Map<String, String> admittedSchemaHashes;
    private final List<RewriteRule> preparationRules;
    private final String repositoryRevision;
    private final PatternTargetedLocalBridgeSearch.Budget bridgeBudget;
    private final String principalInventoryFingerprint;
    private final String preparationInventoryFingerprint;
    private final String exactRegistryFingerprint;
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final PatternMatchAnalyzer analyzer = new PatternMatchAnalyzer();

    public OccurrenceAwareSharedRulePreparationCoordinator(
        List<RewriteApplicabilitySchema> principalSchemas,
        List<? extends RewriteRule> preparationRules,
        String repositoryRevision,
        PatternTargetedLocalBridgeSearch.Budget bridgeBudget
    ) {
        SharedUnifiedRulePreparationCoordinator validated =
            new SharedUnifiedRulePreparationCoordinator(
                principalSchemas,
                preparationRules,
                repositoryRevision,
                bridgeBudget);
        this.principalSchemas = validated.principalSchemas();
        // Failure diagnostics must not call the failed executor's descriptor again.
        Map<String, String> hashes = new LinkedHashMap<>();
        this.principalSchemas.forEach(schema ->
            hashes.put(schema.ruleId(), schema.contentHash()));
        this.admittedSchemaHashes = Map.copyOf(hashes);
        this.preparationRules = List.copyOf(Objects.requireNonNull(
            preparationRules, "preparationRules"));
        this.repositoryRevision = requireRevision(repositoryRevision);
        this.bridgeBudget = Objects.requireNonNull(
            bridgeBudget, "bridgeBudget");
        this.principalInventoryFingerprint =
            validated.principalInventoryFingerprint();
        this.preparationInventoryFingerprint =
            validated.preparationInventoryFingerprint();
        this.exactRegistryFingerprint = validated.exactRegistryFingerprint();
    }

    public List<RewriteApplicabilitySchema> principalSchemas() {
        return principalSchemas;
    }

    public String principalInventoryFingerprint() {
        return principalInventoryFingerprint;
    }

    public String preparationInventoryFingerprint() {
        return preparationInventoryFingerprint;
    }

    public String exactRegistryFingerprint() {
        return exactRegistryFingerprint;
    }

    /**
     * Evaluates direct principals with retained occurrence-local bindings and
     * delegates only unresolved principals to the historical v2 authority.
     */
    public Evaluation analyze(
        String sourceExpression,
        AssumptionSignature initialAssumptions
    ) {
        String source = normalize(sourceExpression);
        AssumptionSignature assumptions = normalized(initialAssumptions);
        Expr root = parser.parseTerm(source);
        SharedPreparationGuardFacts guardFacts =
            new SharedPreparationGuardFacts();
        Map<String, RulePreparationCoordinator.Outcome> outcomes =
            new LinkedHashMap<>();
        List<DirectOccurrenceEvidence> occurrenceEvidence =
            new ArrayList<>();
        List<RewriteApplicabilitySchema> unresolved = new ArrayList<>();

        DirectTraversalWork traversalWork = new DirectTraversalWork();
        long directRuleExecutions = 0;
        long directCandidates = 0;
        long occurrenceCandidates = 0;
        long occurrenceAnalyses = 0;

        for (RewriteApplicabilitySchema schema : principalSchemas) {
            directRuleExecutions++;
            DirectResolution direct = resolveDirect(
                schema, root, source, assumptions, guardFacts, traversalWork);
            occurrenceCandidates = Math.addExact(
                occurrenceCandidates, direct.occurrenceCandidates());
            occurrenceAnalyses = Math.addExact(
                occurrenceAnalyses, direct.occurrenceAnalyses());
            if (!direct.handled()) {
                unresolved.add(schema);
                continue;
            }
            if (direct.candidateFound()) {
                directCandidates++;
            }
            outcomes.put(schema.ruleId(), direct.outcome());
            direct.evidence().ifPresent(occurrenceEvidence::add);
        }

        // V2 setup is repeated for this subset. Charge its input-sized construction
        // separately instead of silently treating it as precomputed/free work.
        long delegateSetupUnits = delegateSetupUnits(unresolved.size(), preparationRules.size());
        Optional<SharedUnifiedRulePreparationCoordinator.Evaluation> delegated = Optional.empty();
        if (!unresolved.isEmpty()) {
            try {
                delegated = Optional.of(new SharedUnifiedRulePreparationCoordinator(
                    unresolved, preparationRules, repositoryRevision, bridgeBudget)
                    .analyze(source, assumptions));
            } catch (RuntimeException exception) {
                // Setup can fail before V2's own execution boundary is entered.
                // Retain the attempted setup charge, but do not invent a completed
                // delegated-work receipt or discard independent direct candidates.
                for (RewriteApplicabilitySchema schema : unresolved) {
                    outcomes.put(schema.ruleId(), technicalOutcome(
                        schema, "UNIFIED_V3_DELEGATE_TECHNICAL_FAILURE"));
                }
            }
        }
        delegated.ifPresent(evaluation -> evaluation.outcomes().forEach(
            outcome -> outcomes.put(outcome.ruleId(), outcome)));

        List<RulePreparationCoordinator.Outcome> orderedOutcomes =
            principalSchemas.stream()
                .map(schema -> Objects.requireNonNull(
                    outcomes.get(schema.ruleId()),
                    "missing v3 principal outcome: " + schema.ruleId()))
                .toList();
        RulePreparationCoordinator.AggregateWork aggregateWork = delegated
            .map(SharedUnifiedRulePreparationCoordinator.Evaluation::aggregateWork)
            .orElseGet(OccurrenceAwareSharedRulePreparationCoordinator::emptyAggregateWork);
        SharedPreparationGuardFacts.Work guardWork = guardFacts.work();
        OccurrenceWork occurrenceWork = new OccurrenceWork(
            directRuleExecutions,
            directCandidates,
            occurrenceCandidates,
            occurrenceAnalyses,
            guardWork.requests(),
            guardWork.uniqueFacts(),
            guardWork.cacheHits(),
            traversalWork.matchAttempts,
            traversalWork.applyAttempts,
            traversalWork.assumptionRequests,
            delegateSetupUnits);

        return new Evaluation(
            COORDINATOR_ID,
            OCCURRENCE_BINDING_REVISION,
            repositoryRevision,
            principalInventoryFingerprint,
            preparationInventoryFingerprint,
            exactRegistryFingerprint,
            bridgeBudget,
            source,
            assumptions,
            orderedOutcomes,
            aggregateWork,
            occurrenceWork,
            occurrenceEvidence,
            unresolved.stream().map(RewriteApplicabilitySchema::ruleId).toList(),
            delegated.map(
                SharedUnifiedRulePreparationCoordinator.Evaluation::sharedExecutionWork));
    }

    /**
     * Recomputes occurrence selection, guards and delegated v2 work, then checks
     * direct candidates against the independent concrete AST replay path.
     * Verification work is separate from the retained analyze-work ledger.
     */
    public Verification verify(Evaluation evaluation) {
        if (evaluation == null) {
            return new Verification(false, "EVALUATION_MISSING");
        }
        if (!COORDINATOR_ID.equals(evaluation.coordinatorId())
                || !OCCURRENCE_BINDING_REVISION.equals(
                    evaluation.occurrenceBindingRevision())
                || !repositoryRevision.equals(evaluation.repositoryRevision())
                || !principalInventoryFingerprint.equals(
                    evaluation.principalInventoryFingerprint())
                || !preparationInventoryFingerprint.equals(
                    evaluation.preparationInventoryFingerprint())
                || !exactRegistryFingerprint.equals(
                    evaluation.exactRegistryFingerprint())
                || !bridgeBudget.equals(evaluation.bridgeBudget())) {
            return new Verification(
                false, "COORDINATOR_CONFIGURATION_MISMATCH");
        }
        final Evaluation recomputed;
        try {
            recomputed = analyze(evaluation.sourceExpression(), evaluation.sourceAssumptions());
        } catch (RuntimeException exception) {
            return new Verification(false, "EVALUATION_RECOMPUTATION_TECHNICAL_FAILURE");
        }
        if (!recomputed.equals(evaluation)) {
            for (RulePreparationCoordinator.Outcome outcome : recomputed.outcomes()) {
                if (outcome.status() == PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE
                        && !evaluation.outcome(outcome.ruleId()).filter(outcome::equals).isPresent()) {
                    return new Verification(false, outcome.detailCode());
                }
            }
            return new Verification(false, "EVALUATION_RECOMPUTATION_MISMATCH");
        }
        // Recomputing our own occurrence traversal is not independent replay.
        // Also require the real AST executor to reproduce every direct candidate.
        return OccurrencePreparationReplay.verify(evaluation, principalSchemas);
    }

    private DirectResolution resolveDirect(
        RewriteApplicabilitySchema schema,
        Expr root,
        String source,
        AssumptionSignature assumptions,
        SharedPreparationGuardFacts guardFacts,
        DirectTraversalWork traversalWork
    ) {
        final List<Occurrence> occurrences;
        try {
            verifyAdmittedSchemaHash(schema);
            occurrences = directOccurrences(schema.executor(), root, "$", traversalWork)
                .stream()
                .filter(occurrence -> !ExpressionFormatter.format(
                    occurrence.transformedRoot()).equals(source))
                .toList();
        } catch (RuntimeException exception) {
            return DirectResolution.technical(
                technicalOutcome(
                    schema,
                    "UNIFIED_V3_DIRECT_REPLAY_TECHNICAL_FAILURE"),
                0);
        }
        if (occurrences.isEmpty()) {
            return DirectResolution.absent();
        }

        Occurrence selected = occurrences.getFirst();
        PatternMatchAnalyzer.Analysis analysis;
        try {
            analysis = analyzeOccurrence(schema, selected.sourceSubtree());
        } catch (RuntimeException exception) {
            return DirectResolution.technical(
                technicalOutcome(
                    schema,
                    "UNIFIED_V3_OCCURRENCE_ANALYSIS_TECHNICAL_FAILURE"),
                occurrences.size());
        }

        SharedPreparationGuardFacts.Fact guards;
        try {
            // An empty guard list cannot turn an inconclusive match into success.
            guards = analysis.matched()
                ? guardFacts.evaluate(schema, analysis, assumptions)
                : SharedPreparationGuardFacts.Fact.unknown(
                    "REQUIRED_ASSUMPTION_BINDINGS_UNAVAILABLE");
        } catch (RuntimeException exception) {
            return DirectResolution.technical(
                technicalOutcome(
                    schema,
                    PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(analysis),
                    "UNIFIED_V3_DIRECT_GUARD_TECHNICAL_FAILURE"),
                occurrences.size(),
                1);
        }

        try {
            return resolveAnalyzedOccurrence(
                schema, source, assumptions, selected, occurrences.size(),
                analysis, guards);
        } catch (RuntimeException exception) {
            return DirectResolution.technical(
                technicalOutcome(
                    schema,
                    PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(analysis),
                    "UNIFIED_V3_DIRECT_METADATA_TECHNICAL_FAILURE"),
                occurrences.size(),
                1);
        }
    }

    private DirectResolution resolveAnalyzedOccurrence(
        RewriteApplicabilitySchema schema,
        String source,
        AssumptionSignature assumptions,
        Occurrence selected,
        int occurrenceCount,
        PatternMatchAnalyzer.Analysis analysis,
        SharedPreparationGuardFacts.Fact guards
    ) {
        String transformed = ExpressionFormatter.format(
            selected.transformedRoot());
        String occurrenceHash = occurrenceHash(
            schema, source, selected, transformed);
        // Keep the primitive identity understood by the concrete replay engine.
        // The separate occurrenceHash binds source, path, subtree and target.
        String applicationKey = schema.ruleId() + ":"
            + canonicalizer.stableHash(ExpressionFormatter.format(
                selected.sourceSubtree()));
        DirectOccurrenceEvidence evidence = new DirectOccurrenceEvidence(
            schema.ruleId(),
            selected.path(),
            ExpressionFormatter.format(selected.sourceSubtree()),
            occurrenceHash,
            applicationKey,
            PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(analysis),
            guards.status().name(),
            guards.detailCode(),
            guards.requiredAssumptions());

        RulePreparationCoordinator.Outcome outcome;
        if (guards.satisfied()) {
            Transformation candidate = directCandidate(
                schema.executor(),
                transformed,
                applicationKey,
                selected.emittedAssumptions(),
                assumptions);
            outcome = new RulePreparationCoordinator.Outcome(
                schema.ruleId(),
                verifyAdmittedSchemaHash(schema),
                PatternTargetedLocalBridgeSearch.Status.DIRECT_MATCH_AVAILABLE,
                Optional.of(candidate),
                true,
                PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(analysis),
                PatternTargetedLocalBridgeSearch.Work.empty(),
                Set.of(),
                "UNIFIED_V3_OCCURRENCE_DIRECT_REPLAYED",
                "");
        } else {
            outcome = new RulePreparationCoordinator.Outcome(
                schema.ruleId(),
                verifyAdmittedSchemaHash(schema),
                rejectedStatus(analysis, guards),
                Optional.empty(),
                false,
                PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(analysis),
                PatternTargetedLocalBridgeSearch.Work.empty(),
                Set.of(),
                guards.detailCode(),
                "");
        }
        return new DirectResolution(
            true,
            true,
            outcome,
            Optional.of(evidence),
            occurrenceCount,
            1);
    }

    private static PatternTargetedLocalBridgeSearch.Status rejectedStatus(
        PatternMatchAnalyzer.Analysis analysis,
        SharedPreparationGuardFacts.Fact guards
    ) {
        if (analysis.status() == PatternMatchAnalyzer.Status.INCONCLUSIVE) {
            return PatternTargetedLocalBridgeSearch.Status.BUDGET_INCONCLUSIVE;
        }
        return guards.status() == SharedPreparationGuardFacts.Status.INVALID
            ? PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE
            : PatternTargetedLocalBridgeSearch.Status.UNSUPPORTED;
    }

    private PatternMatchAnalyzer.Analysis analyzeOccurrence(
        RewriteApplicabilitySchema schema,
        Expr occurrence
    ) {
        return analyzer.analyze(
            schema.pattern(),
            occurrence,
            schema.recognitionProfile(),
            new ExprMatcher.MatchOptions(
                EquivalentExpressionProvider.identity(),
                bridgeBudget.maxMatchResults(),
                bridgeBudget.maxMatchSteps(),
                bridgeBudget.maxPatternBranches()));
    }

    private List<Occurrence> directOccurrences(
        RewriteRule rule,
        Expr subtree,
        String path,
        DirectTraversalWork work
    ) {
        List<Occurrence> result = new ArrayList<>();
        // Count before invocation: failed matches, no-op rewrites and exceptions
        // consume work too, even when no candidate/evidence can be retained.
        work.matchAttempts = Math.addExact(work.matchAttempts, 1);
        if (rule.matches(subtree)) {
            work.applyAttempts = Math.addExact(work.applyAttempts, 1);
            Expr rewritten = rule.apply(subtree);
            if (!rewritten.equals(subtree)) {
                work.assumptionRequests = Math.addExact(work.assumptionRequests, 1);
                List<Assumption> emitted = rule.assumptions(subtree);
                result.add(new Occurrence(
                    path,
                    subtree,
                    rewritten,
                    List.copyOf(Objects.requireNonNull(
                        emitted, "concrete rule assumptions"))));
            }
        }

        if (subtree instanceof BinaryExpr binary) {
            for (Occurrence child : directOccurrences(
                    rule, binary.left(), path + "L", work)) {
                result.add(child.withTransformedRoot(new BinaryExpr(
                    child.transformedRoot(),
                    binary.operator(),
                    binary.right())));
            }
            for (Occurrence child : directOccurrences(
                    rule, binary.right(), path + "R", work)) {
                result.add(child.withTransformedRoot(new BinaryExpr(
                    binary.left(),
                    binary.operator(),
                    child.transformedRoot())));
            }
        } else if (subtree instanceof FunctionExpr function) {
            List<Expr> arguments = function.arguments();
            for (int index = 0; index < arguments.size(); index++) {
                for (Occurrence child : directOccurrences(
                        rule,
                        arguments.get(index),
                        path + "A" + index, work)) {
                    List<Expr> replaced = new ArrayList<>(arguments);
                    replaced.set(index, child.transformedRoot());
                    result.add(child.withTransformedRoot(new FunctionExpr(
                        function.name(), replaced)));
                }
            }
        }
        return result;
    }

    private static final class DirectTraversalWork {
        private long matchAttempts;
        private long applyAttempts;
        private long assumptionRequests;
    }

    /**
     * Logical setup quanta for R unresolved principals and P preparation rules:
     * one construction, R+P inventory slots and R*(P+1) visible-rule slots.
     * A slot includes V2's nested validation/indexing of that input; this is an
     * input-sized composite unit, not a claim to count every internal operation.
     * The zero-principal path constructs no delegate and incurs zero setup.
     */
    private static long delegateSetupUnits(int principals, int preparationRules) {
        if (principals == 0) {
            return 0;
        }
        long inventorySlots = Math.addExact((long) principals, preparationRules);
        long visibleRuleSlots = Math.multiplyExact((long) principals,
            Math.addExact((long) preparationRules, 1));
        return Math.addExact(1, Math.addExact(inventorySlots, visibleRuleSlots));
    }

    private Transformation directCandidate(
        RewriteRule rule,
        String transformedExpression,
        String applicationKey,
        List<Assumption> emittedAssumptions,
        AssumptionSignature sourceAssumptions
    ) {
        AssumptionSignature emitted = AssumptionSignature.ofExpressions(
            emittedAssumptions.stream().map(Assumption::expression).toList());
        AssumptionSignature cumulative = AssumptionSignature.merge(
            sourceAssumptions, emitted);
        return new Transformation(
            rule.id(),
            transformedExpression,
            rule.kind(),
            rule.mayIncreaseComplexity(),
            rule.estimatedCostDelta(),
            rule.isEquivalencePreservingByConstruction(),
            applicationKey,
            cumulative.normalizedAssumptions(),
            rule.descriptor().packId(),
            rule.descriptor().license(),
            List.of(rule.id()));
    }

    private RulePreparationCoordinator.Outcome technicalOutcome(
        RewriteApplicabilitySchema schema,
        String detailCode
    ) {
        return technicalOutcome(
            schema,
            PatternTargetedLocalBridgeSearch.AnalysisSnapshot.unavailable(),
            detailCode);
    }

    private RulePreparationCoordinator.Outcome technicalOutcome(
        RewriteApplicabilitySchema schema,
        PatternTargetedLocalBridgeSearch.AnalysisSnapshot analysis,
        String detailCode
    ) {
        return new RulePreparationCoordinator.Outcome(
            schema.ruleId(),
            admittedSchemaHashes.get(schema.ruleId()),
            PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
            Optional.empty(),
            false,
            analysis,
            PatternTargetedLocalBridgeSearch.Work.empty(),
            Set.of(),
            detailCode,
            "");
    }

    /** Retain the admitted identity, but never authorize changed live metadata. */
    private String verifyAdmittedSchemaHash(RewriteApplicabilitySchema schema) {
        String admitted = admittedSchemaHashes.get(schema.ruleId());
        if (!Objects.equals(admitted, schema.contentHash())) {
            throw new IllegalStateException("principal schema changed after admission");
        }
        return admitted;
    }

    private String occurrenceHash(
        RewriteApplicabilitySchema schema,
        String source,
        Occurrence occurrence,
        String transformed
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, OCCURRENCE_BINDING_REVISION);
        append(descriptor, verifyAdmittedSchemaHash(schema));
        append(descriptor, source);
        append(descriptor, occurrence.path());
        append(descriptor, ExpressionFormatter.format(
            occurrence.sourceSubtree()));
        append(descriptor, transformed);
        return sha256(descriptor.toString());
    }

    private String normalize(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException(
                "sourceExpression must not be blank");
        }
        return ExpressionFormatter.format(parser.parseTerm(expression));
    }

    private static AssumptionSignature normalized(
        AssumptionSignature assumptions
    ) {
        AssumptionSignature supplied = Objects.requireNonNull(
            assumptions, "initialAssumptions");
        return AssumptionSignature.ofExpressions(
            supplied.normalizedAssumptions());
    }

    private static String requireRevision(String revision) {
        if (revision == null || !revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a lowercase commit SHA");
        }
        return revision;
    }

    private static RulePreparationCoordinator.AggregateWork emptyAggregateWork() {
        return new RulePreparationCoordinator.AggregateWork(
            0, 0, 0, 0, 0, 0, 1);
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.getBytes(StandardCharsets.UTF_8).length)
            .append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Occurrence(
        String path,
        Expr sourceSubtree,
        Expr transformedRoot,
        List<Assumption> emittedAssumptions
    ) {
        private Occurrence {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException(
                    "occurrence path must not be blank");
            }
            sourceSubtree = Objects.requireNonNull(
                sourceSubtree, "sourceSubtree");
            transformedRoot = Objects.requireNonNull(
                transformedRoot, "transformedRoot");
            emittedAssumptions = List.copyOf(Objects.requireNonNull(
                emittedAssumptions, "emittedAssumptions"));
        }

        private Occurrence withTransformedRoot(Expr root) {
            return new Occurrence(
                path, sourceSubtree, root, emittedAssumptions);
        }
    }

    private record DirectResolution(
        boolean handled,
        boolean candidateFound,
        RulePreparationCoordinator.Outcome outcome,
        Optional<DirectOccurrenceEvidence> evidence,
        long occurrenceCandidates,
        long occurrenceAnalyses
    ) {
        private DirectResolution {
            evidence = Objects.requireNonNull(evidence, "evidence");
            if (handled != (outcome != null)
                    || occurrenceCandidates < 0
                    || occurrenceAnalyses < 0) {
                throw new IllegalArgumentException(
                    "direct resolution is inconsistent");
            }
        }

        private static DirectResolution absent() {
            return new DirectResolution(
                false, false, null, Optional.empty(), 0, 0);
        }

        private static DirectResolution technical(
            RulePreparationCoordinator.Outcome outcome,
            long occurrenceCandidates
        ) {
            return technical(outcome, occurrenceCandidates, 0);
        }

        private static DirectResolution technical(
            RulePreparationCoordinator.Outcome outcome,
            long occurrenceCandidates,
            long occurrenceAnalyses
        ) {
            return new DirectResolution(
                true,
                occurrenceCandidates > 0,
                outcome,
                Optional.empty(),
                occurrenceCandidates,
                occurrenceAnalyses);
        }
    }

    public record DirectOccurrenceEvidence(
        String ruleId,
        String occurrencePath,
        String sourceSubtree,
        String occurrenceHash,
        String applicationKey,
        PatternTargetedLocalBridgeSearch.AnalysisSnapshot analysis,
        String guardStatus,
        String guardDetailCode,
        List<String> requiredAssumptions
    ) {
        public DirectOccurrenceEvidence {
            if (ruleId == null || ruleId.isBlank()
                    || occurrencePath == null || occurrencePath.isBlank()
                    || sourceSubtree == null || sourceSubtree.isBlank()
                    || occurrenceHash == null
                    || !occurrenceHash.matches("sha256:[0-9a-f]{64}")
                    || applicationKey == null || applicationKey.isBlank()
                    || guardStatus == null || guardStatus.isBlank()
                    || guardDetailCode == null || guardDetailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "direct occurrence evidence identity is invalid");
            }
            analysis = Objects.requireNonNull(analysis, "analysis");
            requiredAssumptions = List.copyOf(Objects.requireNonNull(
                requiredAssumptions, "requiredAssumptions"));
        }
    }

    /**
     * Additional V3 analyze work, separate from delegated V2 execution and verify.
     * Dispatches, retained candidates, analyses and guard requests retain their
     * original units. Every visited node adds one match attempt; every apply and
     * concrete-assumption call adds one unit, including no-ops and failed calls.
     * Delegate setup uses the input-sized composite quanta documented above.
     */
    public record OccurrenceWork(
        long directRuleExecutions,
        long directCandidates,
        long occurrenceCandidates,
        long occurrenceAnalyses,
        long guardRequests,
        long uniqueGuardFacts,
        long guardCacheHits,
        long directMatchAttempts,
        long directApplyAttempts,
        long directAssumptionRequests,
        long delegateSetupUnits
    ) {
        public OccurrenceWork {
            if (directRuleExecutions < 0 || directCandidates < 0
                    || occurrenceCandidates < 0 || occurrenceAnalyses < 0
                    || guardRequests < 0 || uniqueGuardFacts < 0
                    || guardCacheHits < 0
                    || directMatchAttempts < 0 || directApplyAttempts < 0
                    || directAssumptionRequests < 0 || delegateSetupUnits < 0
                    || directApplyAttempts > directMatchAttempts
                    || directAssumptionRequests > directApplyAttempts
                    || directCandidates > directRuleExecutions
                    || guardRequests != uniqueGuardFacts + guardCacheHits) {
                throw new IllegalArgumentException(
                    "occurrence work ledger is inconsistent");
            }
        }

        public String revision() {
            return OCCURRENCE_WORK_REVISION;
        }

        public long chargedUnits() {
            long retainedWork = Math.addExact(
                Math.addExact(directRuleExecutions, occurrenceCandidates),
                Math.addExact(occurrenceAnalyses, guardRequests));
            long traversalWork = Math.addExact(directMatchAttempts,
                Math.addExact(directApplyAttempts, directAssumptionRequests));
            return Math.addExact(retainedWork,
                Math.addExact(traversalWork, delegateSetupUnits));
        }
    }

    /**
     * Retained analysis. Delegated work is absent with nonempty delegate IDs only
     * when every attempted delegate has an explicit technical-failure outcome.
     * In that case the setup charge remains, but completed V2 execution work is
     * unavailable, not a zero-cost successful execution.
     */
    public record Evaluation(
        String coordinatorId,
        String occurrenceBindingRevision,
        String repositoryRevision,
        String principalInventoryFingerprint,
        String preparationInventoryFingerprint,
        String exactRegistryFingerprint,
        PatternTargetedLocalBridgeSearch.Budget bridgeBudget,
        String sourceExpression,
        AssumptionSignature sourceAssumptions,
        List<RulePreparationCoordinator.Outcome> outcomes,
        RulePreparationCoordinator.AggregateWork aggregateWork,
        OccurrenceWork occurrenceWork,
        List<DirectOccurrenceEvidence> directOccurrenceEvidence,
        List<String> delegatedV2PrincipalIds,
        Optional<SharedUnifiedRulePreparationCoordinator.SharedExecutionWork>
            delegatedSharedExecutionWork
    ) {
        public Evaluation {
            if (!COORDINATOR_ID.equals(coordinatorId)
                    || !OCCURRENCE_BINDING_REVISION.equals(
                        occurrenceBindingRevision)
                    || repositoryRevision == null
                    || !repositoryRevision.matches("[0-9a-f]{40}")
                    || !hash(principalInventoryFingerprint)
                    || !hash(preparationInventoryFingerprint)
                    || !hash(exactRegistryFingerprint)) {
                throw new IllegalArgumentException(
                    "occurrence-aware unified evaluation identity is invalid");
            }
            bridgeBudget = Objects.requireNonNull(
                bridgeBudget, "bridgeBudget");
            if (sourceExpression == null || sourceExpression.isBlank()) {
                throw new IllegalArgumentException(
                    "sourceExpression must not be blank");
            }
            sourceAssumptions = normalized(sourceAssumptions);
            outcomes = List.copyOf(Objects.requireNonNull(
                outcomes, "outcomes"));
            if (outcomes.isEmpty()) {
                throw new IllegalArgumentException(
                    "evaluation requires principal outcomes");
            }
            Set<String> outcomeIds = new LinkedHashSet<>();
            outcomes.forEach(outcome -> {
                if (!outcomeIds.add(outcome.ruleId())) {
                    throw new IllegalArgumentException(
                        "duplicate outcome rule ID: " + outcome.ruleId());
                }
            });
            aggregateWork = Objects.requireNonNull(
                aggregateWork, "aggregateWork");
            occurrenceWork = Objects.requireNonNull(
                occurrenceWork, "occurrenceWork");
            directOccurrenceEvidence = List.copyOf(Objects.requireNonNull(
                directOccurrenceEvidence, "directOccurrenceEvidence"));
            delegatedV2PrincipalIds = List.copyOf(Objects.requireNonNull(
                delegatedV2PrincipalIds, "delegatedV2PrincipalIds"));
            delegatedSharedExecutionWork = Objects.requireNonNull(
                delegatedSharedExecutionWork,
                "delegatedSharedExecutionWork");
            Set<String> evidenceIds = new LinkedHashSet<>();
            directOccurrenceEvidence.forEach(evidence -> {
                if (!evidenceIds.add(evidence.ruleId())) {
                    throw new IllegalArgumentException(
                        "duplicate occurrence evidence rule ID: "
                            + evidence.ruleId());
                }
            });
            if (delegatedV2PrincipalIds.isEmpty()
                    != delegatedSharedExecutionWork.isEmpty()
                    && !failedDelegation(delegatedV2PrincipalIds, outcomes, occurrenceWork)) {
                throw new IllegalArgumentException(
                    "delegated v2 work must match delegated principals or retained setup failure");
            }
        }

        public Optional<RulePreparationCoordinator.Outcome> outcome(
            String ruleId
        ) {
            return outcomes.stream()
                .filter(value -> value.ruleId().equals(ruleId))
                .findFirst();
        }

        public Optional<DirectOccurrenceEvidence> directOccurrence(
            String ruleId
        ) {
            return directOccurrenceEvidence.stream()
                .filter(value -> value.ruleId().equals(ruleId))
                .findFirst();
        }

        public List<Transformation> candidates() {
            return outcomes.stream()
                .flatMap(value -> value.candidate().stream())
                .toList();
        }

        private static boolean failedDelegation(
            List<String> principalIds,
            List<RulePreparationCoordinator.Outcome> outcomes,
            OccurrenceWork work
        ) {
            if (principalIds.isEmpty() || work.delegateSetupUnits() == 0
                    || new LinkedHashSet<>(principalIds).size() != principalIds.size()) {
                return false;
            }
            return principalIds.stream().allMatch(id -> outcomes.stream().anyMatch(outcome ->
                id.equals(outcome.ruleId())
                    && outcome.status() == PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE
                    && "UNIFIED_V3_DELEGATE_TECHNICAL_FAILURE".equals(outcome.detailCode())
                    && outcome.candidate().isEmpty()));
        }

        private static boolean hash(String value) {
            return value != null && value.matches("sha256:[0-9a-f]{64}");
        }
    }

    public record Verification(boolean valid, String detailCode) {
        public Verification {
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
        }
    }
}
