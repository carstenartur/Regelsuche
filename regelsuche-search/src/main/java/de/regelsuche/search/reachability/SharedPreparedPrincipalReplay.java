package de.regelsuche.search.reachability;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Converts one shared preparation result into one independently replayed
 * principal outcome.
 *
 * <p>The shared traversal is only a search optimization. Authorization stays
 * principal-local: terminal guards are checked from the retained match
 * snapshot and the concrete principal executor is replayed at the terminal
 * expression. A positive outcome retains the complete preparation plus
 * principal primitive lineage and a content-addressed certificate bound to the
 * repository revision, schema, preparation inventory, budget and shared work
 * identity.</p>
 */
final class SharedPreparedPrincipalReplay {
    static final String CERTIFICATE_SCHEMA =
        "regelsuche.unified-shared-preparation-certificate/v1";

    private final String repositoryRevision;
    private final String preparationInventoryFingerprint;
    private final PatternTargetedLocalBridgeSearch.Budget budget;
    private final SharedPreparationGuardFacts guardFacts;

    SharedPreparedPrincipalReplay(
        List<? extends RewriteRule> preparationRules,
        String repositoryRevision,
        PatternTargetedLocalBridgeSearch.Budget budget,
        SharedPreparationGuardFacts guardFacts
    ) {
        Objects.requireNonNull(preparationRules, "preparationRules");
        this.preparationInventoryFingerprint =
            RuleInventoryFingerprint.contentHash(preparationRules);
        if (repositoryRevision == null
                || !repositoryRevision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a lowercase commit SHA");
        }
        this.repositoryRevision = repositoryRevision;
        this.budget = Objects.requireNonNull(budget, "budget");
        this.guardFacts = Objects.requireNonNull(guardFacts, "guardFacts");
    }

    RulePreparationCoordinator.Outcome replay(
        RewriteApplicabilitySchema schema,
        SharedMultiPrincipalPreparationTraversal.Evaluation sharedEvaluation,
        SharedMultiPrincipalPreparationTraversal.PrincipalOutcome sharedOutcome
    ) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(sharedEvaluation, "sharedEvaluation");
        Objects.requireNonNull(sharedOutcome, "sharedOutcome");
        if (!schema.ruleId().equals(sharedOutcome.ruleId())
                || !schema.contentHash().equals(
                    sharedOutcome.applicabilitySchemaHash())) {
            throw new IllegalArgumentException(
                "shared principal outcome does not match applicability schema");
        }

        PatternTargetedLocalBridgeSearch.Work sharedWork =
            legacyWork(sharedEvaluation.work());
        Set<String> limits = sharedEvaluation.work().reachedLimits();
        return switch (sharedOutcome.status()) {
            case PREPARED_MATCH -> prepared(
                schema,
                sharedEvaluation,
                sharedOutcome,
                sharedWork,
                limits);
            case MATCHED_AT_SOURCE -> negative(
                schema,
                sharedOutcome,
                PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
                sharedWork,
                limits,
                "UNIFIED_SCHEMA_MATCH_WITHOUT_DIRECT_REPLAY");
            case NO_MATCH_IN_COMPLETE_FROZEN_CLOSURE -> negative(
                schema,
                sharedOutcome,
                PatternTargetedLocalBridgeSearch.Status
                    .NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
                sharedWork,
                limits,
                "SHARED_FROZEN_CLOSURE_EXHAUSTED");
            case BUDGET_INCONCLUSIVE -> negative(
                schema,
                sharedOutcome,
                PatternTargetedLocalBridgeSearch.Status.BUDGET_INCONCLUSIVE,
                sharedWork,
                limits,
                "SHARED_BRIDGE_BUDGET_INCONCLUSIVE");
        };
    }

    private RulePreparationCoordinator.Outcome prepared(
        RewriteApplicabilitySchema schema,
        SharedMultiPrincipalPreparationTraversal.Evaluation sharedEvaluation,
        SharedMultiPrincipalPreparationTraversal.PrincipalOutcome sharedOutcome,
        PatternTargetedLocalBridgeSearch.Work sharedWork,
        Set<String> limits
    ) {
        SharedPreparationGuardFacts.Fact guards;
        try {
            guards = guardFacts.evaluateSnapshot(
                schema,
                sharedOutcome.terminalAnalysis(),
                sharedOutcome.terminalAssumptions());
        } catch (RuntimeException exception) {
            return negative(
                schema,
                sharedOutcome,
                PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
                sharedWork,
                limits,
                "UNIFIED_SHARED_GUARD_REPLAY_TECHNICAL_FAILURE");
        }
        if (!guards.satisfied()) {
            PatternTargetedLocalBridgeSearch.Status status =
                guards.status() == SharedPreparationGuardFacts.Status.INVALID
                    ? PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE
                    : PatternTargetedLocalBridgeSearch.Status.UNSUPPORTED;
            return negative(
                schema,
                sharedOutcome,
                status,
                sharedWork,
                limits,
                guards.detailCode());
        }

        Optional<Transformation> replay;
        try {
            replay = concreteReplay(
                schema.executor(),
                sharedOutcome.terminalExpression());
        } catch (RuntimeException exception) {
            return negative(
                schema,
                sharedOutcome,
                PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
                sharedWork,
                limits,
                "UNIFIED_SHARED_PRINCIPAL_REPLAY_TECHNICAL_FAILURE");
        }
        if (replay.isEmpty()) {
            return negative(
                schema,
                sharedOutcome,
                PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
                sharedWork,
                limits,
                "UNIFIED_SHARED_PRINCIPAL_REPLAY_FAILED");
        }

        Transformation principal = replay.orElseThrow();
        AssumptionSignature resultAssumptions = AssumptionSignature.merge(
            sharedOutcome.terminalAssumptions(),
            AssumptionSignature.ofExpressions(principal.assumptions()));
        List<String> primitiveRuleIds = new ArrayList<>();
        sharedOutcome.preparationSteps().forEach(step ->
            primitiveRuleIds.addAll(step.primitiveRuleIds()));
        primitiveRuleIds.addAll(principal.primitiveRuleIds());
        List<String> retainedPrimitiveRuleIds = List.copyOf(primitiveRuleIds);
        String certificateHash = certificateHash(
            schema,
            sharedEvaluation,
            sharedOutcome,
            principal,
            resultAssumptions,
            retainedPrimitiveRuleIds,
            sharedWork);
        Transformation candidate = new Transformation(
            schema.executor().id(),
            principal.transformedExpression(),
            schema.executor().kind(),
            schema.executor().mayIncreaseComplexity(),
            schema.executor().estimatedCostDelta(),
            schema.executor().isEquivalencePreservingByConstruction(),
            "unified-shared-preparation:" + certificateHash,
            resultAssumptions.normalizedAssumptions(),
            principal.packId(),
            principal.license(),
            retainedPrimitiveRuleIds);

        return new RulePreparationCoordinator.Outcome(
            schema.ruleId(),
            schema.contentHash(),
            PatternTargetedLocalBridgeSearch.Status.PREPARED,
            Optional.of(candidate),
            true,
            sharedOutcome.initialAnalysis(),
            sharedWork,
            limits,
            "UNIFIED_SHARED_PREPARATION_REPLAYED",
            certificateHash);
    }

    private static RulePreparationCoordinator.Outcome negative(
        RewriteApplicabilitySchema schema,
        SharedMultiPrincipalPreparationTraversal.PrincipalOutcome sharedOutcome,
        PatternTargetedLocalBridgeSearch.Status status,
        PatternTargetedLocalBridgeSearch.Work work,
        Set<String> limits,
        String detailCode
    ) {
        return new RulePreparationCoordinator.Outcome(
            schema.ruleId(),
            schema.contentHash(),
            status,
            Optional.empty(),
            false,
            sharedOutcome.initialAnalysis(),
            work,
            limits,
            detailCode,
            "");
    }

    private static Optional<Transformation> concreteReplay(
        RewriteRule executor,
        String terminalExpression
    ) {
        return new AstRewriteTransformationEngine(
                List.of(executor),
                Integer.MAX_VALUE,
                1)
            .transform(terminalExpression)
            .stream()
            .filter(value -> executor.id().equals(value.rule()))
            .findFirst();
    }

    private String certificateHash(
        RewriteApplicabilitySchema schema,
        SharedMultiPrincipalPreparationTraversal.Evaluation sharedEvaluation,
        SharedMultiPrincipalPreparationTraversal.PrincipalOutcome sharedOutcome,
        Transformation principal,
        AssumptionSignature resultAssumptions,
        List<String> primitiveRuleIds,
        PatternTargetedLocalBridgeSearch.Work sharedWork
    ) {
        StringBuilder value = new StringBuilder();
        append(value, CERTIFICATE_SCHEMA);
        append(value, SharedMultiPrincipalPreparationTraversal.REVISION);
        append(value, SharedPreparationTraversal.REVISION);
        append(value, SharedPreparationGuardFacts.REVISION);
        append(value, repositoryRevision);
        append(value, preparationInventoryFingerprint);
        append(value, schema.contentHash());
        append(value, budget.identity());
        append(value, sharedEvaluation.sourceExpression());
        append(value, sharedEvaluation.sourceAssumptions().fingerprint());
        append(value, sharedOutcome.initialAnalysis().descriptor());
        append(value, sharedOutcome.terminalExpression());
        append(value, sharedOutcome.terminalAssumptions().fingerprint());
        append(value, sharedOutcome.terminalAnalysis().descriptor());
        sharedOutcome.preparationSteps().forEach(step ->
            append(value, step.descriptor()));
        append(value, principal.rule());
        append(value, principal.transformedExpression());
        append(value, principal.applicationKey());
        append(value, String.join("\u0000", principal.assumptions()));
        append(value, String.join("\u0000", principal.primitiveRuleIds()));
        append(value, resultAssumptions.fingerprint());
        append(value, String.join("\u0000", primitiveRuleIds));
        append(value, sharedWork.descriptor());
        return sha256(value.toString());
    }

    static PatternTargetedLocalBridgeSearch.Work legacyWork(
        SharedMultiPrincipalPreparationTraversal.Work work
    ) {
        Objects.requireNonNull(work, "work");
        return new PatternTargetedLocalBridgeSearch.Work(
            work.expandedStates(),
            work.generatedTransitions(),
            work.discoveredStates(),
            work.retainedTransitions(),
            work.duplicateTransitions(),
            Math.toIntExact(work.principalAnalysisRequests()),
            work.maxFrontierSize());
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
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
}
