package de.regelsuche.search.reachability;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One deterministic policy boundary for direct and bounded prepared
 * applicability of several explicitly schema-bearing rules.
 *
 * <p>The coordinator does not infer schemas. A principal must be a
 * {@link PatternRewriteRule} or an explicit schema-backed subtype. Direct
 * concrete replay remains the first stage. The bounded local bridge search is
 * invoked only when that principal does not already apply. Every positive
 * candidate is materialized from the concrete principal implementation and a
 * prepared candidate additionally requires independent bridge verification.</p>
 *
 * <p>This first coordinator unifies rule eligibility, deterministic ordering,
 * replay, evidence and aggregate work. Its v1 implementation still delegates
 * one bounded search session per principal; a shared multi-principal frontier
 * is a later optimization and must preserve byte-identical outcomes.</p>
 */
public final class RulePreparationCoordinator {
    public static final String COORDINATOR_ID =
        "regelsuche.safe-rule-preparation-coordinator/v1";

    private final List<PatternRewriteRule> principalRules;
    private final List<RewriteRule> preparationRules;
    private final Map<String, PatternTargetedLocalBridgeSearch> searches;
    private final String repositoryRevision;
    private final String principalInventoryFingerprint;
    private final String preparationInventoryFingerprint;
    private final PatternTargetedLocalBridgeSearch.Budget bridgeBudget;
    private final ExpressionParser parser = new ExpressionParser();

    public RulePreparationCoordinator(
        List<? extends PatternRewriteRule> principalRules,
        List<? extends RewriteRule> preparationRules,
        String repositoryRevision,
        PatternTargetedLocalBridgeSearch.Budget bridgeBudget
    ) {
        this.principalRules = validatePrincipalRules(principalRules);
        this.preparationRules = validatePreparationRules(preparationRules);
        this.repositoryRevision = requireRevision(repositoryRevision);
        this.bridgeBudget = Objects.requireNonNull(
            bridgeBudget, "bridgeBudget");
        this.principalInventoryFingerprint =
            RuleInventoryFingerprint.contentHash(this.principalRules);
        this.preparationInventoryFingerprint =
            RuleInventoryFingerprint.contentHash(this.preparationRules);
        Map<String, PatternTargetedLocalBridgeSearch> indexed =
            new LinkedHashMap<>();
        for (PatternRewriteRule principal : this.principalRules) {
            indexed.put(
                principal.id(),
                new PatternTargetedLocalBridgeSearch(
                    principal,
                    this.preparationRules,
                    this.repositoryRevision,
                    this.bridgeBudget));
        }
        this.searches = Collections.unmodifiableMap(indexed);
    }

    public List<PatternRewriteRule> principalRules() {
        return principalRules;
    }

    public String principalInventoryFingerprint() {
        return principalInventoryFingerprint;
    }

    public String preparationInventoryFingerprint() {
        return preparationInventoryFingerprint;
    }

    /** Evaluates every declared principal under one frozen policy. */
    public Evaluation analyze(
        String sourceExpression,
        AssumptionSignature initialAssumptions
    ) {
        String source = normalize(sourceExpression);
        AssumptionSignature assumptions = normalized(initialAssumptions);
        List<Outcome> outcomes = new ArrayList<>();
        for (PatternRewriteRule principal : principalRules) {
            PatternTargetedLocalBridgeSearch search =
                searches.get(principal.id());
            PatternTargetedLocalBridgeSearch.Attempt attempt =
                search.analyze(source, assumptions);
            outcomes.add(outcome(
                principal,
                search,
                source,
                assumptions,
                attempt));
        }
        return new Evaluation(
            COORDINATOR_ID,
            repositoryRevision,
            principalInventoryFingerprint,
            preparationInventoryFingerprint,
            bridgeBudget,
            source,
            assumptions,
            outcomes,
            AggregateWork.from(outcomes));
    }

    /** Recomputes the complete deterministic evaluation and all bridge replay. */
    public Verification verify(Evaluation evaluation) {
        if (evaluation == null) {
            return new Verification(false, "EVALUATION_MISSING");
        }
        if (!COORDINATOR_ID.equals(evaluation.coordinatorId())
                || !repositoryRevision.equals(
                    evaluation.repositoryRevision())
                || !principalInventoryFingerprint.equals(
                    evaluation.principalInventoryFingerprint())
                || !preparationInventoryFingerprint.equals(
                    evaluation.preparationInventoryFingerprint())
                || !bridgeBudget.equals(evaluation.bridgeBudget())) {
            return new Verification(false,
                "COORDINATOR_CONFIGURATION_MISMATCH");
        }
        Evaluation recomputed = analyze(
            evaluation.sourceExpression(),
            evaluation.sourceAssumptions());
        return recomputed.equals(evaluation)
            ? new Verification(true, "VERIFIED")
            : new Verification(false, "EVALUATION_RECOMPUTATION_MISMATCH");
    }

    private Outcome outcome(
        PatternRewriteRule principal,
        PatternTargetedLocalBridgeSearch search,
        String source,
        AssumptionSignature sourceAssumptions,
        PatternTargetedLocalBridgeSearch.Attempt attempt
    ) {
        PatternTargetedLocalBridgeSearch.Status status = attempt.status();
        Optional<Transformation> candidate = Optional.empty();
        boolean replayVerified = false;
        String detailCode = attempt.detailCode();

        if (status
                == PatternTargetedLocalBridgeSearch.Status
                    .DIRECT_MATCH_AVAILABLE) {
            candidate = directCandidate(
                principal, source, sourceAssumptions);
            replayVerified = candidate.isPresent();
            if (candidate.isEmpty()) {
                status = PatternTargetedLocalBridgeSearch.Status
                    .TECHNICAL_FAILURE;
                detailCode = "COORDINATOR_DIRECT_REPLAY_FAILED";
            }
        } else if (status
                == PatternTargetedLocalBridgeSearch.Status.PREPARED) {
            PatternTargetedLocalBridgeSearch.Bridge bridge =
                attempt.bridge().orElseThrow();
            PatternTargetedLocalBridgeSearch.Verification verification =
                search.verify(bridge);
            replayVerified = verification.valid();
            if (replayVerified) {
                candidate = Optional.of(preparedCandidate(
                    principal, bridge));
            } else {
                status = PatternTargetedLocalBridgeSearch.Status
                    .INVALID_CERTIFICATE;
                detailCode = verification.detailCode();
            }
        }

        return new Outcome(
            principal.id(),
            RuleInventoryFingerprint.ruleContentHash(principal),
            status,
            candidate,
            replayVerified,
            attempt.initialAnalysis(),
            attempt.work(),
            attempt.reachedLimits(),
            detailCode,
            attempt.bridge().map(
                PatternTargetedLocalBridgeSearch.Bridge::certificateHash)
                .orElse(""));
    }

    private Optional<Transformation> directCandidate(
        PatternRewriteRule principal,
        String source,
        AssumptionSignature sourceAssumptions
    ) {
        List<Transformation> generated =
            new AstRewriteTransformationEngine(
                List.of(principal),
                Integer.MAX_VALUE,
                1)
                .transform(source);
        return generated.stream()
            .filter(value -> principal.id().equals(value.rule()))
            .findFirst()
            .map(value -> withCumulativeAssumptions(
                value, sourceAssumptions));
    }

    private static Transformation preparedCandidate(
        PatternRewriteRule principal,
        PatternTargetedLocalBridgeSearch.Bridge bridge
    ) {
        return new Transformation(
            principal.id(),
            bridge.resultExpression(),
            principal.kind(),
            principal.mayIncreaseComplexity(),
            principal.estimatedCostDelta(),
            principal.isEquivalencePreservingByConstruction(),
            "coordinated-preparation:" + bridge.certificateHash(),
            bridge.resultAssumptions().normalizedAssumptions(),
            principal.descriptor().packId(),
            principal.descriptor().license(),
            bridge.primitiveRuleIds());
    }

    private static Transformation withCumulativeAssumptions(
        Transformation transformation,
        AssumptionSignature sourceAssumptions
    ) {
        AssumptionSignature cumulative = AssumptionSignature.merge(
            sourceAssumptions,
            AssumptionSignature.ofExpressions(
                transformation.assumptions()));
        return new Transformation(
            transformation.rule(),
            transformation.transformedExpression(),
            transformation.kind(),
            transformation.mayIncreaseComplexity(),
            transformation.estimatedCostDelta(),
            transformation.equivalencePreservingByConstruction(),
            transformation.applicationKey(),
            cumulative.normalizedAssumptions(),
            transformation.packId(),
            transformation.license(),
            transformation.primitiveRuleIds());
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

    private static List<PatternRewriteRule> validatePrincipalRules(
        List<? extends PatternRewriteRule> supplied
    ) {
        Objects.requireNonNull(supplied, "principalRules");
        if (supplied.isEmpty()) {
            throw new IllegalArgumentException(
                "at least one principal rule is required");
        }
        Map<String, PatternRewriteRule> indexed = new LinkedHashMap<>();
        for (PatternRewriteRule rule : supplied) {
            PatternRewriteRule checked = Objects.requireNonNull(
                rule, "principal rule");
            if (!checked.isEquivalencePreservingByConstruction()) {
                throw new IllegalArgumentException(
                    "principal rules must preserve equivalence: "
                        + checked.id());
            }
            if (!checked.descriptor().eligibleForRegistration()) {
                throw new IllegalArgumentException(
                    "principal rule is not review-qualified: "
                        + checked.id());
            }
            if (checked.descriptor().external()
                    && !"low".equalsIgnoreCase(
                        checked.descriptor().riskLevel())) {
                throw new IllegalArgumentException(
                    "safe coordinator v1 accepts only low-risk external rules: "
                        + checked.id());
            }
            if (indexed.put(checked.id(), checked) != null) {
                throw new IllegalArgumentException(
                    "duplicate principal rule ID: " + checked.id());
            }
        }
        return indexed.values().stream()
            .sorted(Comparator.comparing(PatternRewriteRule::id))
            .toList();
    }

    private static List<RewriteRule> validatePreparationRules(
        List<? extends RewriteRule> supplied
    ) {
        Objects.requireNonNull(supplied, "preparationRules");
        Map<String, RewriteRule> indexed = new LinkedHashMap<>();
        for (RewriteRule rule : supplied) {
            RewriteRule checked = Objects.requireNonNull(
                rule, "preparation rule");
            if (!checked.isEquivalencePreservingByConstruction()) {
                throw new IllegalArgumentException(
                    "preparation rules must preserve equivalence: "
                        + checked.id());
            }
            if (indexed.put(checked.id(), checked) != null) {
                throw new IllegalArgumentException(
                    "duplicate preparation rule ID: " + checked.id());
            }
        }
        return indexed.values().stream()
            .sorted(Comparator.comparing(RewriteRule::id))
            .toList();
    }

    private static String requireRevision(String revision) {
        if (revision == null || !revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a lowercase commit SHA");
        }
        return revision;
    }

    public record Outcome(
        String ruleId,
        String ruleFingerprint,
        PatternTargetedLocalBridgeSearch.Status status,
        Optional<Transformation> candidate,
        boolean replayVerified,
        PatternTargetedLocalBridgeSearch.AnalysisSnapshot initialAnalysis,
        PatternTargetedLocalBridgeSearch.Work work,
        Set<String> reachedLimits,
        String detailCode,
        String bridgeCertificateHash
    ) {
        public Outcome {
            if (ruleId == null || ruleId.isBlank()
                    || ruleFingerprint == null
                    || !ruleFingerprint.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "rule identity is invalid");
            }
            status = Objects.requireNonNull(status, "status");
            candidate = Objects.requireNonNull(candidate, "candidate");
            initialAnalysis = Objects.requireNonNull(
                initialAnalysis, "initialAnalysis");
            work = Objects.requireNonNull(work, "work");
            reachedLimits = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNull(
                    reachedLimits, "reachedLimits")));
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
            bridgeCertificateHash = bridgeCertificateHash == null
                ? "" : bridgeCertificateHash;
            boolean positive = status
                    == PatternTargetedLocalBridgeSearch.Status
                        .DIRECT_MATCH_AVAILABLE
                || status
                    == PatternTargetedLocalBridgeSearch.Status.PREPARED;
            if (positive != candidate.isPresent()
                    || positive != replayVerified) {
                throw new IllegalArgumentException(
                    "only replay-verified positive outcomes retain candidates");
            }
            if (status
                    == PatternTargetedLocalBridgeSearch.Status.PREPARED) {
                if (!bridgeCertificateHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException(
                        "prepared outcome requires a bridge certificate");
                }
            } else if (!bridgeCertificateHash.isEmpty()) {
                throw new IllegalArgumentException(
                    "only prepared outcomes retain bridge certificates");
            }
        }

        public boolean direct() {
            return status
                == PatternTargetedLocalBridgeSearch.Status
                    .DIRECT_MATCH_AVAILABLE;
        }

        public boolean prepared() {
            return status
                == PatternTargetedLocalBridgeSearch.Status.PREPARED;
        }

        public boolean positive() {
            return direct() || prepared();
        }
    }

    public record AggregateWork(
        long expandedStates,
        long generatedTransitions,
        long discoveredStates,
        long retainedTransitions,
        long duplicateTransitions,
        long analyzedCandidates,
        int maxFrontierSize
    ) {
        public AggregateWork {
            if (expandedStates < 0 || generatedTransitions < 0
                    || discoveredStates < 0 || retainedTransitions < 0
                    || duplicateTransitions < 0 || analyzedCandidates < 0
                    || maxFrontierSize < 1) {
                throw new IllegalArgumentException(
                    "aggregate work is invalid");
            }
        }

        static AggregateWork from(List<Outcome> outcomes) {
            long expanded = 0;
            long generated = 0;
            long discovered = 0;
            long retained = 0;
            long duplicates = 0;
            long analyzed = 0;
            int frontier = 1;
            for (Outcome outcome : outcomes) {
                PatternTargetedLocalBridgeSearch.Work work = outcome.work();
                expanded += work.expandedStates();
                generated += work.generatedTransitions();
                discovered += work.discoveredStates();
                retained += work.retainedTransitions();
                duplicates += work.duplicateTransitions();
                analyzed += work.analyzedCandidates();
                frontier = Math.max(frontier, work.maxFrontierSize());
            }
            return new AggregateWork(
                expanded, generated, discovered, retained,
                duplicates, analyzed, frontier);
        }
    }

    public record Evaluation(
        String coordinatorId,
        String repositoryRevision,
        String principalInventoryFingerprint,
        String preparationInventoryFingerprint,
        PatternTargetedLocalBridgeSearch.Budget bridgeBudget,
        String sourceExpression,
        AssumptionSignature sourceAssumptions,
        List<Outcome> outcomes,
        AggregateWork aggregateWork
    ) {
        public Evaluation {
            if (!COORDINATOR_ID.equals(coordinatorId)
                    || repositoryRevision == null
                    || !repositoryRevision.matches("[0-9a-f]{40}")
                    || principalInventoryFingerprint == null
                    || !principalInventoryFingerprint.matches(
                        "sha256:[0-9a-f]{64}")
                    || preparationInventoryFingerprint == null
                    || !preparationInventoryFingerprint.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "evaluation identity is invalid");
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
                    "evaluation must retain principal outcomes");
            }
            Set<String> ids = new LinkedHashSet<>();
            for (Outcome outcome : outcomes) {
                if (!ids.add(outcome.ruleId())) {
                    throw new IllegalArgumentException(
                        "duplicate outcome rule ID: " + outcome.ruleId());
                }
            }
            aggregateWork = Objects.requireNonNull(
                aggregateWork, "aggregateWork");
        }

        public Optional<Outcome> outcome(String ruleId) {
            return outcomes.stream()
                .filter(value -> value.ruleId().equals(ruleId))
                .findFirst();
        }

        public List<Transformation> candidates() {
            return outcomes.stream()
                .flatMap(value -> value.candidate().stream())
                .toList();
        }

        public long directApplications() {
            return outcomes.stream().filter(Outcome::direct).count();
        }

        public long preparedApplications() {
            return outcomes.stream().filter(Outcome::prepared).count();
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
