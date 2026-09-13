package de.regelsuche.search.reachability;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RecordedExecution;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationProvenance;
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
 * V4 SAFE authority: unchanged V3 evidence plus bounded, occurrence-local
 * preparation successors lifted into their original AST context.
 */
public final class ContextualOccurrenceRulePreparationCoordinator {
    public static final String COORDINATOR_ID =
        "regelsuche.unified-safe-rule-preparation-coordinator/v4";
    public static final String SUCCESSOR_IDENTITY_REVISION =
        "regelsuche.contextual-occurrence-successor/v1";
    public static final String CONTEXTUAL_WORK_REVISION =
        "regelsuche.contextual-occurrence-work/v2";

    private final List<RewriteApplicabilitySchema> principalSchemas;
    private final List<RewriteRule> preparationRules;
    private final Map<String, RewriteApplicabilitySchema> schemasById;
    private final String repositoryRevision;
    private final PatternTargetedLocalBridgeSearch.Budget bridgeBudget;
    private final ContextualBudget contextualBudget;
    private final OccurrenceAwareSharedRulePreparationCoordinator v3;
    private final ExpressionParser parser = new ExpressionParser();

    public ContextualOccurrenceRulePreparationCoordinator(
        List<RewriteApplicabilitySchema> principalSchemas,
        List<? extends RewriteRule> preparationRules,
        String repositoryRevision,
        PatternTargetedLocalBridgeSearch.Budget bridgeBudget,
        ContextualBudget contextualBudget
    ) {
        this.v3 = new OccurrenceAwareSharedRulePreparationCoordinator(
            principalSchemas, preparationRules, repositoryRevision, bridgeBudget);
        this.principalSchemas = v3.principalSchemas();
        this.preparationRules = List.copyOf(Objects.requireNonNull(
            preparationRules, "preparationRules"));
        Map<String, RewriteApplicabilitySchema> indexed = new LinkedHashMap<>();
        this.principalSchemas.forEach(schema -> indexed.put(schema.ruleId(), schema));
        this.schemasById = Map.copyOf(indexed);
        this.repositoryRevision = requireRevision(repositoryRevision);
        this.bridgeBudget = Objects.requireNonNull(bridgeBudget, "bridgeBudget");
        this.contextualBudget = Objects.requireNonNull(
            contextualBudget, "contextualBudget");
    }

    public Evaluation analyze(
        String sourceExpression,
        AssumptionSignature initialAssumptions
    ) {
        OccurrenceAwareSharedRulePreparationCoordinator.Evaluation base =
            v3.analyze(sourceExpression, initialAssumptions);
        String source = base.sourceExpression();
        Expr root = parser.parseTerm(source);
        long baseUnits = baseAnalysisUnits(base);
        long analysisUnits = baseUnits;
        long occurrenceNodes = 0;
        long batchUnits = 0;
        long failedBatchReservationUnits = 0;
        long localAuthorizationUnits = 0;
        long liftedReplayUnits = 0;
        long failedLiftReservationUnits = 0;
        int batches = 0;
        boolean limitReached = baseUnits >= contextualBudget.maxAnalysisUnits();

        Map<String, RulePreparationCoordinator.Outcome> outcomes =
            new LinkedHashMap<>();
        base.outcomes().forEach(outcome -> outcomes.put(outcome.ruleId(), outcome));
        List<ContextualSuccessorEvidence> successors = new ArrayList<>();
        List<RewriteApplicabilitySchema> unresolved = unresolved(outcomes);

        List<Occurrence> occurrences = List.of();
        if (!unresolved.isEmpty() && !limitReached) {
            occurrences = occurrences(root);
            occurrenceNodes = occurrences.size() + 1L;
            analysisUnits = add(analysisUnits, occurrenceNodes);
            limitReached = analysisUnits >= contextualBudget.maxAnalysisUnits();
        }

        for (Occurrence occurrence : occurrences) {
            if (unresolved.isEmpty() || limitReached
                    || batches >= contextualBudget.maxOccurrenceBatches()) {
                if (!unresolved.isEmpty()) {
                    limitReached = true;
                }
                break;
            }
            batches++;
            long setupUnits = add(1,
                add(unresolved.size(), preparationRules.size()));
            batchUnits = add(batchUnits, setupUnits);
            analysisUnits = add(analysisUnits, setupUnits);
            long traversalReservation = failedTraversalReservation(
                occurrence.subtree(), unresolved.size());
            long analysisBeforeTraversal = analysisUnits;
            long batchBeforeTraversal = batchUnits;
            analysisUnits = add(analysisUnits, traversalReservation);
            batchUnits = add(batchUnits, traversalReservation);
            final SharedMultiPrincipalPreparationTraversal.Evaluation local;
            try {
                local = new SharedMultiPrincipalPreparationTraversal(
                        unresolved, preparationRules, bridgeBudget)
                    .analyze(ExpressionFormatter.format(occurrence.subtree()),
                        base.sourceAssumptions());
            } catch (RuntimeException exception) {
                failedBatchReservationUnits = add(
                    failedBatchReservationUnits, traversalReservation);
                for (RewriteApplicabilitySchema schema : unresolved) {
                    outcomes.put(schema.ruleId(), negative(
                        schema,
                        PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
                        outcomes.get(schema.ruleId()).initialAnalysis(),
                        "V4_CONTEXTUAL_TRAVERSAL_TECHNICAL_FAILURE"));
                }
                unresolved.clear();
                break;
            }
            LocalWorkReceipt localWork = LocalWorkReceipt.from(local.work());
            batchUnits = add(batchBeforeTraversal, localWork.chargedUnits());
            analysisUnits = add(
                analysisBeforeTraversal, localWork.chargedUnits());

            SharedPreparationGuardFacts guardFacts =
                new SharedPreparationGuardFacts();
            SharedPreparedPrincipalReplay replay = new SharedPreparedPrincipalReplay(
                preparationRules, repositoryRevision, bridgeBudget, guardFacts);
            for (RewriteApplicabilitySchema schema : List.copyOf(unresolved)) {
                SharedMultiPrincipalPreparationTraversal.PrincipalOutcome localResult =
                    local.outcome(schema.ruleId()).orElseThrow();
                // V4 introduces only a real preparation successor. A local
                // source match is not a second direct-occurrence selector.
                if (localResult.status()
                        == SharedMultiPrincipalPreparationTraversal.Status.MATCHED_AT_SOURCE) {
                    continue;
                }
                localAuthorizationUnits = add(localAuthorizationUnits, 1);
                analysisUnits = add(analysisUnits, 1);
                if (localResult.status()
                        == SharedMultiPrincipalPreparationTraversal.Status.PREPARED_MATCH) {
                    long principalReservation = engineReplayUnits(
                        localResult.terminalExpression(), 0);
                    localAuthorizationUnits = add(
                        localAuthorizationUnits, principalReservation);
                    analysisUnits = add(analysisUnits, principalReservation);
                }
                final RulePreparationCoordinator.Outcome authorized;
                try {
                    authorized = replay.replay(schema, local, localResult);
                } catch (RuntimeException exception) {
                    outcomes.put(schema.ruleId(), negative(
                        schema,
                        PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
                        localResult.initialAnalysis(),
                        "V4_CONTEXTUAL_AUTHORIZATION_TECHNICAL_FAILURE"));
                    unresolved.remove(schema);
                    continue;
                }
                if (authorized.candidate().isPresent()) {
                    long executionUnits = authorized.candidate().orElseThrow()
                        .executionWork().canonicalWorkUnits();
                    localAuthorizationUnits = add(
                        localAuthorizationUnits, executionUnits);
                    analysisUnits = add(analysisUnits, executionUnits);
                }
                if (!authorized.prepared()) {
                    if (authorized.status()
                            == PatternTargetedLocalBridgeSearch.Status.UNSUPPORTED
                            || authorized.status()
                                == PatternTargetedLocalBridgeSearch.Status.BUDGET_INCONCLUSIVE
                            || authorized.status()
                                == PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE) {
                        retainNegative(outcomes, schema.ruleId(), authorized);
                    }
                    if (authorized.status()
                            == PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE) {
                        unresolved.remove(schema);
                    }
                    continue;
                }
                long liftReservation = liftReservation(localResult);
                liftedReplayUnits = add(liftedReplayUnits, liftReservation);
                analysisUnits = add(analysisUnits, liftReservation);
                try {
                    LiftedSuccessor lifted = lift(
                        root, source, occurrence, schema, localResult,
                        authorized, localWork, base.sourceAssumptions());
                    if (lifted.evidence().replayUnits() != liftReservation) {
                        throw new IllegalStateException(
                            "contextual replay reservation mismatch");
                    }
                    successors.add(lifted.evidence());
                    outcomes.put(schema.ruleId(), lifted.outcome());
                    unresolved.remove(schema);
                } catch (NonLiftableProvenance exception) {
                    outcomes.put(schema.ruleId(), negative(
                        schema,
                        PatternTargetedLocalBridgeSearch.Status.UNSUPPORTED,
                        localResult.initialAnalysis(),
                        "V4_NON_LIFTABLE_PROVENANCE"));
                    unresolved.remove(schema);
                } catch (RuntimeException exception) {
                    failedLiftReservationUnits = add(
                        failedLiftReservationUnits, liftReservation);
                    outcomes.put(schema.ruleId(), negative(
                        schema,
                        PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
                        localResult.initialAnalysis(),
                        "V4_CONTEXTUAL_REPLAY_TECHNICAL_FAILURE"));
                    unresolved.remove(schema);
                }
            }
            long guardUnits = guardFacts.work().requests();
            localAuthorizationUnits = add(localAuthorizationUnits, guardUnits);
            analysisUnits = add(analysisUnits, guardUnits);
            if (analysisUnits >= contextualBudget.maxAnalysisUnits()) {
                limitReached = !unresolved.isEmpty();
            }
        }

        if (limitReached || batches >= contextualBudget.maxOccurrenceBatches()) {
            for (RewriteApplicabilitySchema schema : unresolved) {
                RulePreparationCoordinator.Outcome prior = outcomes.get(schema.ruleId());
                outcomes.put(schema.ruleId(), negative(
                    schema,
                    PatternTargetedLocalBridgeSearch.Status.BUDGET_INCONCLUSIVE,
                    prior.initialAnalysis(),
                    "V4_GLOBAL_ANALYSIS_BUDGET_EXHAUSTED"));
            }
        }

        List<RulePreparationCoordinator.Outcome> ordered = principalSchemas.stream()
            .map(schema -> outcomes.get(schema.ruleId())).toList();
        long directReplayReservation = multiply(
            base.directOccurrenceEvidence().size(), add(1, multiply(3, nodeCount(root))));
        long contextualReplayReservation = successors.stream()
            .mapToLong(ContextualSuccessorEvidence::replayUnits).sum();
        long verificationReservation = add(
            analysisUnits, add(directReplayReservation, contextualReplayReservation));
        ContextualWork work = new ContextualWork(
            CONTEXTUAL_WORK_REVISION,
            baseUnits,
            occurrenceNodes,
            batches,
            batchUnits,
            failedBatchReservationUnits,
            localAuthorizationUnits,
            liftedReplayUnits,
            failedLiftReservationUnits,
            analysisUnits,
            directReplayReservation,
            contextualReplayReservation,
            verificationReservation,
            limitReached || (batches >= contextualBudget.maxOccurrenceBatches()
                && !unresolved.isEmpty()));
        return new Evaluation(
            COORDINATOR_ID,
            SUCCESSOR_IDENTITY_REVISION,
            repositoryRevision,
            v3.principalInventoryFingerprint(),
            v3.preparationInventoryFingerprint(),
            v3.exactRegistryFingerprint(),
            bridgeBudget,
            contextualBudget,
            source,
            base.sourceAssumptions(),
            base,
            ordered,
            successors,
            work);
    }

    public Verification verify(Evaluation evaluation) {
        if (evaluation == null) {
            return new Verification(false, "EVALUATION_MISSING");
        }
        if (!COORDINATOR_ID.equals(evaluation.coordinatorId())
                || !SUCCESSOR_IDENTITY_REVISION.equals(
                    evaluation.successorIdentityRevision())
                || !repositoryRevision.equals(evaluation.repositoryRevision())
                || !v3.principalInventoryFingerprint().equals(
                    evaluation.principalInventoryFingerprint())
                || !v3.preparationInventoryFingerprint().equals(
                    evaluation.preparationInventoryFingerprint())
                || !v3.exactRegistryFingerprint().equals(
                    evaluation.exactRegistryFingerprint())
                || !bridgeBudget.equals(evaluation.bridgeBudget())
                || !contextualBudget.equals(evaluation.contextualBudget())) {
            return new Verification(false, "COORDINATOR_CONFIGURATION_MISMATCH");
        }
        final Evaluation recomputed;
        try {
            recomputed = analyze(
                evaluation.sourceExpression(), evaluation.sourceAssumptions());
        } catch (RuntimeException exception) {
            return new Verification(
                false, "EVALUATION_RECOMPUTATION_TECHNICAL_FAILURE");
        }
        if (!recomputed.equals(evaluation)) {
            return new Verification(false, "EVALUATION_RECOMPUTATION_MISMATCH");
        }
        OccurrenceAwareSharedRulePreparationCoordinator.Verification baseReplay =
            OccurrencePreparationReplay.verify(
                evaluation.baseV3Evaluation(), principalSchemas);
        if (!baseReplay.valid()) {
            return new Verification(false, "BASE_V3_" + baseReplay.detailCode());
        }
        try {
            Expr root = parser.parseTerm(evaluation.sourceExpression());
            for (ContextualSuccessorEvidence evidence : evaluation.successors()) {
                RewriteApplicabilitySchema schema = schemasById.get(evidence.ruleId());
                if (schema == null || !replayEvidence(
                        root, evaluation.sourceExpression(), evidence, schema,
                        evaluation.sourceAssumptions())) {
                    return new Verification(false, "CONTEXTUAL_SUCCESSOR_REPLAY_MISMATCH");
                }
            }
        } catch (RuntimeException exception) {
            return new Verification(
                false, "CONTEXTUAL_SUCCESSOR_REPLAY_TECHNICAL_FAILURE");
        }
        return new Verification(true, "VERIFIED");
    }

    private LiftedSuccessor lift(
        Expr root,
        String source,
        Occurrence occurrence,
        RewriteApplicabilitySchema schema,
        SharedMultiPrincipalPreparationTraversal.PrincipalOutcome localResult,
        RulePreparationCoordinator.Outcome authorized,
        LocalWorkReceipt localWork,
        AssumptionSignature sourceAssumptions
    ) {
        List<ContextualStepEvidence> retainedSteps = new ArrayList<>();
        localResult.preparationSteps().forEach(step -> retainedSteps.add(
            ContextualStepEvidence.from(step)));
        Transformation principal = replayPrincipal(
            schema, localResult.terminalExpression());
        retainedSteps.add(ContextualStepEvidence.from(principal,
            localResult.terminalExpression()));

        ReplayResult lifted = replayAndLift(
            root, source, occurrence.path(), retainedSteps, schema,
            sourceAssumptions);
        List<Transformation> liftedSteps = lifted.steps();
        if (liftedSteps.stream().anyMatch(step ->
                !(step.provenance()
                    instanceof TransformationProvenance.PrimitiveRewriteSequence))) {
            throw new NonLiftableProvenance();
        }
        RecordedExecution execution = RecordedExecution.capture(source, liftedSteps);
        long replayUnits = add(lifted.replayUnits(), engineReplayUnits(
            localResult.terminalExpression(),
            principal.executionWork().canonicalWorkUnits()));
        String successorIdentity = successorIdentity(
            schema, source, sourceAssumptions, occurrence,
            localResult, authorized.bridgeCertificateHash(), localWork,
            execution, retainedSteps);
        AssumptionSignature resultAssumptions = AssumptionSignature.merge(
            sourceAssumptions,
            AssumptionSignature.ofExpressions(execution.assumptions()));
        Transformation last = liftedSteps.getLast();
        Transformation candidate = new Transformation(
            schema.ruleId(),
            execution.transformedExpression(),
            last.kind(),
            last.mayIncreaseComplexity(),
            last.estimatedCostDelta(),
            last.equivalencePreservingByConstruction(),
            "contextual-preparation:" + successorIdentity,
            resultAssumptions.normalizedAssumptions(),
            last.packId(),
            last.license(),
            execution.work().primitiveRewrites() == 0
                ? List.of()
                : liftedSteps.stream()
                    .flatMap(step -> step.primitiveRuleIds().stream()).toList(),
            new TransformationProvenance.Sequence(source, liftedSteps));
        ContextualSuccessorEvidence evidence = new ContextualSuccessorEvidence(
            SUCCESSOR_IDENTITY_REVISION,
            successorIdentity,
            schema.ruleId(),
            schema.contentHash(),
            occurrence.path(),
            ExpressionFormatter.format(occurrence.subtree()),
            localResult.terminalExpression(),
            principal.transformedExpression(),
            execution.transformedExpression(),
            retainedSteps,
            localResult.initialAnalysis(),
            localResult.terminalAnalysis(),
            localWork,
            authorized.bridgeCertificateHash(),
            execution.toCanonicalJson(),
            execution.contentHash(),
            replayUnits);
        RulePreparationCoordinator.Outcome outcome =
            new RulePreparationCoordinator.Outcome(
                schema.ruleId(),
                schema.contentHash(),
                PatternTargetedLocalBridgeSearch.Status.PREPARED,
                Optional.of(candidate),
                true,
                localResult.initialAnalysis(),
                SharedPreparedPrincipalReplay.legacyWork(
                    new SharedMultiPrincipalPreparationTraversal.Work(
                        localWork.expandedStates(), localWork.generatedTransitions(),
                        localWork.discoveredStates(), localWork.retainedTransitions(),
                        localWork.duplicateTransitions(),
                        localWork.principalAnalysisRequests(),
                        localWork.maxFrontierSize(),
                        Set.copyOf(localWork.reachedLimits()),
                        localWork.toPhysicalWork())),
                Set.copyOf(localWork.reachedLimits()),
                "UNIFIED_V4_CONTEXTUAL_SUCCESSOR_REPLAYED",
                successorIdentity);
        return new LiftedSuccessor(outcome, evidence);
    }

    private boolean replayEvidence(
        Expr root,
        String source,
        ContextualSuccessorEvidence evidence,
        RewriteApplicabilitySchema schema,
        AssumptionSignature sourceAssumptions
    ) {
        Occurrence occurrence = new Occurrence(
            evidence.occurrencePath(), subtreeAt(root, evidence.occurrencePath()));
        if (!ExpressionFormatter.format(occurrence.subtree())
                .equals(evidence.sourceSubtree())
                || !schema.contentHash().equals(evidence.ruleFingerprint())
                || evidence.localSteps().size() < 2
                || !evidence.localSteps().getFirst().expressionBefore()
                    .equals(evidence.sourceSubtree())
                || !evidence.localSteps().getLast().expressionBefore()
                    .equals(evidence.terminalSubtree())
                || !evidence.localSteps().getLast().expressionAfter()
                    .equals(evidence.resultSubtree())
                || !evidence.localSteps().getLast().ruleId()
                    .equals(schema.ruleId())) {
            return false;
        }
        AssumptionSignature terminalAssumptions = sourceAssumptions;
        for (ContextualStepEvidence step : evidence.localSteps()
                .subList(0, evidence.localSteps().size() - 1)) {
            terminalAssumptions = AssumptionSignature.merge(
                terminalAssumptions,
                AssumptionSignature.ofExpressions(step.emittedAssumptions()));
        }
        SharedPreparationGuardFacts.Fact guards =
            new SharedPreparationGuardFacts().evaluateSnapshot(
                schema, evidence.terminalAnalysis(), terminalAssumptions);
        if (!guards.satisfied()) {
            return false;
        }
        ReplayResult replay = replayAndLift(
            root, source, occurrence.path(), evidence.localSteps(), schema,
            sourceAssumptions);
        List<Transformation> steps = replay.steps();
        RecordedExecution replayed = RecordedExecution.capture(source, steps);
        if (!replayed.toCanonicalJson().equals(evidence.liftedExecutionJson())
                || !replayed.contentHash().equals(evidence.liftedExecutionHash())) {
            return false;
        }
        String identity = successorIdentity(
            schema, source, sourceAssumptions, occurrence,
            evidence.initialAnalysis(), evidence.terminalAnalysis(),
            evidence.terminalSubtree(), evidence.resultSubtree(),
            evidence.localCertificateHash(), evidence.localWork(), replayed,
            evidence.localSteps());
        return identity.equals(evidence.successorIdentity());
    }

    private ReplayResult replayAndLift(
        Expr root,
        String source,
        String path,
        List<ContextualStepEvidence> retained,
        RewriteApplicabilitySchema principal,
        AssumptionSignature sourceAssumptions
    ) {
        String local = ExpressionFormatter.format(subtreeAt(root, path));
        Expr currentRoot = root;
        List<Transformation> result = new ArrayList<>();
        long replayUnits = 0;
        for (ContextualStepEvidence step : retained) {
            if (!local.equals(step.expressionBefore())) {
                throw new IllegalArgumentException("contextual path is disconnected");
            }
            RewriteRule selectedRule = step.ruleId().equals(principal.ruleId())
                ? principal.executor()
                : preparationRules.stream()
                    .filter(rule -> step.ruleId().equals(rule.id()))
                    .findFirst().orElseThrow();
            List<RewriteRule> inventory = List.of(selectedRule);
            Transformation replayed = new AstRewriteTransformationEngine(
                    inventory, Integer.MAX_VALUE,
                    Math.toIntExact(Math.min(Integer.MAX_VALUE,
                        nodeCount(parser.parseTerm(local)))))
                .transform(local).stream()
                .filter(value -> step.ruleId().equals(value.rule()))
                .filter(value -> step.expressionAfter().equals(
                    normalize(value.transformedExpression())))
                .filter(value -> step.applicationKey().equals(value.applicationKey()))
                .filter(value -> step.emittedAssumptions().equals(value.assumptions()))
                .filter(value -> step.primitiveRuleIds().equals(value.primitiveRuleIds()))
                .findFirst().orElseThrow();
            replayUnits = add(replayUnits, engineReplayUnits(
                local, replayed.executionWork().canonicalWorkUnits()));
            replayUnits = add(replayUnits, 1);
            if (!(replayed.provenance()
                    instanceof TransformationProvenance.PrimitiveRewriteSequence)) {
                throw new NonLiftableProvenance();
            }
            Expr nextRoot = replace(currentRoot, path,
                parser.parseTerm(replayed.transformedExpression()));
            String nextExpression = ExpressionFormatter.format(nextRoot);
            String liftedKey = "contextual-lift:" + sha256(
                SUCCESSOR_IDENTITY_REVISION + "\u0000" + source + "\u0000"
                    + path + "\u0000" + local + "\u0000"
                    + replayed.applicationKey() + "\u0000" + nextExpression);
            List<String> retainedAssumptions = result.isEmpty()
                ? AssumptionSignature.merge(
                    sourceAssumptions,
                    AssumptionSignature.ofExpressions(replayed.assumptions()))
                    .normalizedAssumptions()
                : replayed.assumptions();
            Transformation lifted = new Transformation(
                replayed.rule(), nextExpression, replayed.kind(),
                replayed.mayIncreaseComplexity(), replayed.estimatedCostDelta(),
                replayed.equivalencePreservingByConstruction(), liftedKey,
                retainedAssumptions, replayed.packId(), replayed.license(),
                replayed.primitiveRuleIds());
            result.add(lifted);
            currentRoot = nextRoot;
            local = normalize(replayed.transformedExpression());
        }
        return new ReplayResult(List.copyOf(result), replayUnits);
    }

    private Transformation replayPrincipal(
        RewriteApplicabilitySchema schema,
        String terminalExpression
    ) {
        return new AstRewriteTransformationEngine(
                List.of(schema.executor()), Integer.MAX_VALUE, 1)
            .transform(terminalExpression).stream()
            .filter(value -> schema.ruleId().equals(value.rule()))
            .findFirst().orElseThrow();
    }

    private String successorIdentity(
        RewriteApplicabilitySchema schema,
        String source,
        AssumptionSignature assumptions,
        Occurrence occurrence,
        SharedMultiPrincipalPreparationTraversal.PrincipalOutcome local,
        String localCertificate,
        LocalWorkReceipt localWork,
        RecordedExecution execution,
        List<ContextualStepEvidence> steps
    ) {
        return successorIdentity(schema, source, assumptions, occurrence,
            local.initialAnalysis(), local.terminalAnalysis(),
            local.terminalExpression(), steps.getLast().expressionAfter(),
            localCertificate, localWork, execution, steps);
    }

    private String successorIdentity(
        RewriteApplicabilitySchema schema,
        String source,
        AssumptionSignature assumptions,
        Occurrence occurrence,
        PatternTargetedLocalBridgeSearch.AnalysisSnapshot initial,
        PatternTargetedLocalBridgeSearch.AnalysisSnapshot terminal,
        String terminalExpression,
        String resultExpression,
        String localCertificate,
        LocalWorkReceipt localWork,
        RecordedExecution execution,
        List<ContextualStepEvidence> steps
    ) {
        StringBuilder value = new StringBuilder();
        append(value, SUCCESSOR_IDENTITY_REVISION);
        append(value, repositoryRevision);
        append(value, v3.principalInventoryFingerprint());
        append(value, v3.preparationInventoryFingerprint());
        append(value, v3.exactRegistryFingerprint());
        append(value, bridgeBudget.identity());
        append(value, contextualBudget.identity());
        append(value, schema.contentHash());
        append(value, source);
        append(value, assumptions.fingerprint());
        append(value, occurrence.path());
        append(value, ExpressionFormatter.format(occurrence.subtree()));
        append(value, initial.descriptor());
        append(value, terminal.descriptor());
        append(value, terminalExpression);
        append(value, resultExpression);
        append(value, localCertificate);
        append(value, localWork.identity());
        steps.forEach(step -> append(value, step.identity()));
        append(value, execution.contentHash());
        append(value, Long.toString(execution.work().canonicalWorkUnits()));
        return sha256(value.toString());
    }

    private List<RewriteApplicabilitySchema> unresolved(
        Map<String, RulePreparationCoordinator.Outcome> outcomes
    ) {
        return principalSchemas.stream().filter(schema -> {
            RulePreparationCoordinator.Outcome outcome = outcomes.get(schema.ruleId());
            return !outcome.positive()
                && outcome.status()
                    != PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE;
        }).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static void retainNegative(
        Map<String, RulePreparationCoordinator.Outcome> outcomes,
        String ruleId,
        RulePreparationCoordinator.Outcome candidate
    ) {
        RulePreparationCoordinator.Outcome current = outcomes.get(ruleId);
        boolean currentBudget = current != null && current.status()
            == PatternTargetedLocalBridgeSearch.Status.BUDGET_INCONCLUSIVE;
        boolean candidateTechnical = candidate.status()
            == PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE;
        boolean candidateBudget = candidate.status()
            == PatternTargetedLocalBridgeSearch.Status.BUDGET_INCONCLUSIVE;
        if (candidateTechnical || candidateBudget || !currentBudget) {
            outcomes.put(ruleId, candidate);
        }
    }

    private RulePreparationCoordinator.Outcome negative(
        RewriteApplicabilitySchema schema,
        PatternTargetedLocalBridgeSearch.Status status,
        PatternTargetedLocalBridgeSearch.AnalysisSnapshot analysis,
        String detail
    ) {
        return new RulePreparationCoordinator.Outcome(
            schema.ruleId(), schema.contentHash(), status, Optional.empty(), false,
            analysis, PatternTargetedLocalBridgeSearch.Work.empty(),
            status == PatternTargetedLocalBridgeSearch.Status.BUDGET_INCONCLUSIVE
                ? Set.of("V4_GLOBAL_ANALYSIS") : Set.of(),
            detail, "");
    }

    private List<Occurrence> occurrences(Expr root) {
        List<Occurrence> result = new ArrayList<>();
        collect(root, "$", false, result);
        return List.copyOf(result);
    }

    private void collect(
        Expr expression,
        String path,
        boolean retain,
        List<Occurrence> target
    ) {
        if (retain) {
            target.add(new Occurrence(path, expression));
        }
        if (expression instanceof BinaryExpr binary) {
            collect(binary.left(), path + "L", true, target);
            collect(binary.right(), path + "R", true, target);
        } else if (expression instanceof FunctionExpr function) {
            for (int i = 0; i < function.arguments().size(); i++) {
                collect(function.arguments().get(i), path + "A" + i, true, target);
            }
        }
    }

    private Expr subtreeAt(Expr root, String path) {
        if (path == null || !path.startsWith("$")) {
            throw new IllegalArgumentException("invalid occurrence path");
        }
        Expr current = root;
        int offset = 1;
        while (offset < path.length()) {
            char marker = path.charAt(offset++);
            if (marker == 'L' && current instanceof BinaryExpr binary) {
                current = binary.left();
            } else if (marker == 'R' && current instanceof BinaryExpr binary) {
                current = binary.right();
            } else if (marker == 'A' && current instanceof FunctionExpr function) {
                int start = offset;
                while (offset < path.length()
                        && Character.isDigit(path.charAt(offset))) {
                    offset++;
                }
                if (start == offset) {
                    throw new IllegalArgumentException("invalid argument path");
                }
                current = function.arguments().get(
                    Integer.parseInt(path.substring(start, offset)));
            } else {
                throw new IllegalArgumentException("path does not select a subtree");
            }
        }
        return current;
    }

    private Expr replace(Expr root, String path, Expr replacement) {
        if ("$".equals(path)) {
            return replacement;
        }
        return replace(root, path, 1, replacement);
    }

    private Expr replace(Expr current, String path, int offset, Expr replacement) {
        char marker = path.charAt(offset++);
        if (marker == 'L' && current instanceof BinaryExpr binary) {
            Expr left = offset == path.length()
                ? replacement
                : replace(binary.left(), path, offset, replacement);
            return new BinaryExpr(left,
                binary.operator(), binary.right());
        }
        if (marker == 'R' && current instanceof BinaryExpr binary) {
            Expr right = offset == path.length()
                ? replacement
                : replace(binary.right(), path, offset, replacement);
            return new BinaryExpr(binary.left(), binary.operator(), right);
        }
        if (marker == 'A' && current instanceof FunctionExpr function) {
            int start = offset;
            while (offset < path.length()
                    && Character.isDigit(path.charAt(offset))) {
                offset++;
            }
            int index = Integer.parseInt(path.substring(start, offset));
            List<Expr> arguments = new ArrayList<>(function.arguments());
            arguments.set(index, offset == path.length()
                ? replacement
                : replace(arguments.get(index), path, offset, replacement));
            return new FunctionExpr(function.name(), arguments);
        }
        throw new IllegalArgumentException("path does not select a subtree");
    }

    private static long baseAnalysisUnits(
        OccurrenceAwareSharedRulePreparationCoordinator.Evaluation evaluation
    ) {
        RulePreparationCoordinator.AggregateWork aggregate =
            evaluation.aggregateWork();
        long units = add(evaluation.occurrenceWork().chargedUnits(),
            add(aggregate.expandedStates(), add(aggregate.generatedTransitions(),
                add(aggregate.discoveredStates(), add(aggregate.retainedTransitions(),
                    add(aggregate.duplicateTransitions(), aggregate.analyzedCandidates()))))));
        for (Transformation candidate : evaluation.candidates()) {
            units = add(units, candidate.executionWork().canonicalWorkUnits());
        }
        if (evaluation.delegatedSharedExecutionWork().isPresent()) {
            SharedUnifiedRulePreparationCoordinator.SharedExecutionWork shared =
                evaluation.delegatedSharedExecutionWork().orElseThrow();
            units = add(units, add(shared.sourceAnalysisWork().uniqueParsedExpressions(),
                add(shared.sourceAnalysisWork().uniqueAnalyses(), shared.guardRequests())));
        }
        return units;
    }

    private static long nodeCount(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return add(1, add(nodeCount(binary.left()), nodeCount(binary.right())));
        }
        if (expression instanceof FunctionExpr function) {
            long result = 1;
            for (Expr argument : function.arguments()) {
                result = add(result, nodeCount(argument));
            }
            return result;
        }
        return 1;
    }

    private long engineReplayUnits(String expression, long candidateUnits) {
        return add(add(1, multiply(3, nodeCount(parser.parseTerm(expression)))),
            candidateUnits);
    }

    /**
     * Reserved before a package-local traversal whose partial counters cannot
     * be recovered if a rule throws. Successful traversals replace this with
     * their exact retained receipt; failed traversals keep this bounded charge.
     */
    private long failedTraversalReservation(Expr subtree, int principals) {
        long states = bridgeBudget.maxVisitedStates();
        long transitions = bridgeBudget.maxGeneratedTransitions();
        // An expansion observes its complete bounded engine batch before the
        // traversal's global transition cut is applied. The +1 covers the
        // transition that discovers that cut.
        long transformationsPerState = add(
            Math.min(bridgeBudget.maxSuccessorsPerState(),
                bridgeBudget.maxGeneratedTransitions()), 1);
        long generatedTransformations = multiply(
            states, transformationsPerState);

        // Complete LocalWorkReceipt upper bound:
        //   principal ledger       N*(S+G)
        //   physical analyses      N*(1+S+G)
        //   state/request ledgers  5*S
        //   transition ledgers     2*G
        //   transform+parse        2*T
        long principalAnalyses = multiply(principals,
            add(1, add(multiply(2, states), multiply(2, transitions))));
        long retainedLedger = add(multiply(5, states),
            add(multiply(2, transitions),
                multiply(2, generatedTransformations)));
        // Separate bounded engine-call reservation for the call that threw.
        return add(engineReplayUnits(
            ExpressionFormatter.format(subtree), 0),
            add(principalAnalyses, retainedLedger));
    }

    private long liftReservation(
        SharedMultiPrincipalPreparationTraversal.PrincipalOutcome local
    ) {
        long units = engineReplayUnits(local.terminalExpression(), 1);
        for (PatternTargetedLocalBridgeSearch.Step step
                : local.preparationSteps()) {
            units = add(units, add(engineReplayUnits(
                step.expressionBefore(), step.primitiveRuleIds().size()), 1));
        }
        return add(units, add(engineReplayUnits(
            local.terminalExpression(), 1), 1));
    }

    private String normalize(String expression) {
        return ExpressionFormatter.format(parser.parseTerm(expression));
    }

    private static String requireRevision(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a lowercase commit SHA");
        }
        return value;
    }

    private static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long multiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
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

    private record Occurrence(String path, Expr subtree) {
        private Occurrence {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("blank occurrence path");
            }
            Objects.requireNonNull(subtree, "subtree");
        }
    }

    private record LiftedSuccessor(
        RulePreparationCoordinator.Outcome outcome,
        ContextualSuccessorEvidence evidence
    ) { }

    private record ReplayResult(
        List<Transformation> steps,
        long replayUnits
    ) {
        private ReplayResult {
            steps = List.copyOf(steps);
            if (steps.isEmpty() || replayUnits < 1) {
                throw new IllegalArgumentException("invalid replay result");
            }
        }
    }

    private static final class NonLiftableProvenance
        extends RuntimeException { }

    public record ContextualBudget(
        long maxAnalysisUnits,
        int maxOccurrenceBatches
    ) {
        public ContextualBudget {
            if (maxAnalysisUnits < 0 || maxOccurrenceBatches < 1) {
                throw new IllegalArgumentException("invalid contextual budget");
            }
        }

        public String identity() {
            return maxAnalysisUnits + ":" + maxOccurrenceBatches;
        }
    }

    public record ContextualStepEvidence(
        String expressionBefore,
        String expressionAfter,
        String ruleId,
        List<String> emittedAssumptions,
        String applicationKey,
        List<String> primitiveRuleIds
    ) {
        public ContextualStepEvidence {
            if (expressionBefore == null || expressionBefore.isBlank()
                    || expressionAfter == null || expressionAfter.isBlank()
                    || ruleId == null || ruleId.isBlank()
                    || applicationKey == null || applicationKey.isBlank()) {
                throw new IllegalArgumentException("invalid contextual step");
            }
            emittedAssumptions = List.copyOf(emittedAssumptions);
            primitiveRuleIds = List.copyOf(primitiveRuleIds);
        }

        private static ContextualStepEvidence from(
            PatternTargetedLocalBridgeSearch.Step step
        ) {
            return new ContextualStepEvidence(
                step.expressionBefore(), step.expressionAfter(), step.ruleId(),
                step.emittedAssumptions(), step.applicationKey(),
                step.primitiveRuleIds());
        }

        private static ContextualStepEvidence from(
            Transformation step,
            String source
        ) {
            return new ContextualStepEvidence(
                source, step.transformedExpression(), step.rule(),
                step.assumptions(), step.applicationKey(), step.primitiveRuleIds());
        }

        private String identity() {
            return expressionBefore + "\u0000" + expressionAfter + "\u0000"
                + ruleId + "\u0000" + emittedAssumptions + "\u0000"
                + applicationKey + "\u0000" + primitiveRuleIds;
        }
    }

    /** Public, JSON-safe projection of one package-local traversal receipt. */
    public record LocalWorkReceipt(
        int expandedStates,
        int generatedTransitions,
        int discoveredStates,
        int retainedTransitions,
        int duplicateTransitions,
        long principalAnalysisRequests,
        int maxFrontierSize,
        List<String> reachedLimits,
        long expansionRequests,
        long uniqueExpansions,
        long expansionCacheHits,
        long generatedTransformations,
        long parseRequests,
        long uniqueParsedExpressions,
        long parseCacheHits,
        long analysisRequests,
        long uniqueAnalyses,
        long analysisCacheHits
    ) {
        public LocalWorkReceipt {
            reachedLimits = List.copyOf(reachedLimits);
        }

        private static LocalWorkReceipt from(
            SharedMultiPrincipalPreparationTraversal.Work work
        ) {
            SharedPreparationTraversal.Work physical = work.physicalWork();
            return new LocalWorkReceipt(
                work.expandedStates(), work.generatedTransitions(),
                work.discoveredStates(), work.retainedTransitions(),
                work.duplicateTransitions(), work.principalAnalysisRequests(),
                work.maxFrontierSize(), work.reachedLimits().stream().sorted().toList(),
                physical.expansionRequests(), physical.uniqueExpansions(),
                physical.expansionCacheHits(), physical.generatedTransformations(),
                physical.parseRequests(), physical.uniqueParsedExpressions(),
                physical.parseCacheHits(), physical.analysisRequests(),
                physical.uniqueAnalyses(), physical.analysisCacheHits());
        }

        private SharedPreparationTraversal.Work toPhysicalWork() {
            return new SharedPreparationTraversal.Work(
                expansionRequests, uniqueExpansions, expansionCacheHits,
                generatedTransformations, parseRequests,
                uniqueParsedExpressions, parseCacheHits, analysisRequests,
                uniqueAnalyses, analysisCacheHits);
        }

        public long chargedUnits() {
            long total = expandedStates;
            total = add(total, generatedTransitions);
            total = add(total, discoveredStates);
            total = add(total, retainedTransitions);
            total = add(total, duplicateTransitions);
            total = add(total, principalAnalysisRequests);
            total = add(total, expansionRequests);
            total = add(total, generatedTransformations);
            total = add(total, parseRequests);
            total = add(total, analysisRequests);
            return total;
        }

        private String identity() {
            return expandedStates + ":" + generatedTransitions + ":"
                + discoveredStates + ":" + retainedTransitions + ":"
                + duplicateTransitions + ":" + principalAnalysisRequests + ":"
                + maxFrontierSize + ":" + reachedLimits + ":"
                + expansionRequests + ":" + uniqueExpansions + ":"
                + expansionCacheHits + ":" + generatedTransformations + ":"
                + parseRequests + ":" + uniqueParsedExpressions + ":"
                + parseCacheHits + ":" + analysisRequests + ":"
                + uniqueAnalyses + ":" + analysisCacheHits;
        }
    }

    public record ContextualSuccessorEvidence(
        String revision,
        String successorIdentity,
        String ruleId,
        String ruleFingerprint,
        String occurrencePath,
        String sourceSubtree,
        String terminalSubtree,
        String resultSubtree,
        String resultExpression,
        List<ContextualStepEvidence> localSteps,
        PatternTargetedLocalBridgeSearch.AnalysisSnapshot initialAnalysis,
        PatternTargetedLocalBridgeSearch.AnalysisSnapshot terminalAnalysis,
        LocalWorkReceipt localWork,
        String localCertificateHash,
        String liftedExecutionJson,
        String liftedExecutionHash,
        long replayUnits
    ) {
        public ContextualSuccessorEvidence {
            if (!SUCCESSOR_IDENTITY_REVISION.equals(revision)
                    || successorIdentity == null
                    || !successorIdentity.matches("sha256:[0-9a-f]{64}")
                    || ruleId == null || ruleId.isBlank()
                    || ruleFingerprint == null
                    || !ruleFingerprint.matches("sha256:[0-9a-f]{64}")
                    || occurrencePath == null || occurrencePath.isBlank()
                    || localCertificateHash == null
                    || !localCertificateHash.matches("sha256:[0-9a-f]{64}")
                    || liftedExecutionHash == null
                    || !liftedExecutionHash.matches("sha256:[0-9a-f]{64}")
                    || replayUnits < 1 || localSteps == null
                    || localSteps.size() < 2) {
                throw new IllegalArgumentException("invalid contextual successor");
            }
            localSteps = List.copyOf(localSteps);
            Objects.requireNonNull(initialAnalysis, "initialAnalysis");
            Objects.requireNonNull(terminalAnalysis, "terminalAnalysis");
            Objects.requireNonNull(localWork, "localWork");
            RecordedExecution execution = RecordedExecution.fromCanonicalJson(
                liftedExecutionJson);
            if (!execution.contentHash().equals(liftedExecutionHash)
                    || !execution.transformedExpression().equals(resultExpression)) {
                throw new IllegalArgumentException(
                    "lifted execution identity is inconsistent");
            }
        }
    }

    /** Versioned logical accounting; these units are not CPU or wall time. */
    public record ContextualWork(
        String revision,
        long baseAnalysisUnits,
        long occurrenceNodes,
        int occurrenceBatches,
        long localBatchUnits,
        long failedBatchReservationUnits,
        long localAuthorizationUnits,
        long liftedReplayUnits,
        long failedLiftReservationUnits,
        long analysisUnits,
        long baseDirectReplayReservationUnits,
        long contextualReplayReservationUnits,
        long verificationReservationUnits,
        boolean limitReached
    ) {
        public ContextualWork {
            if (!CONTEXTUAL_WORK_REVISION.equals(revision)
                    || baseAnalysisUnits < 0 || occurrenceNodes < 0
                    || occurrenceBatches < 0 || localBatchUnits < 0
                    || failedBatchReservationUnits < 0
                    || localAuthorizationUnits < 0 || liftedReplayUnits < 0
                    || failedLiftReservationUnits < 0
                    || analysisUnits < baseAnalysisUnits
                    || baseDirectReplayReservationUnits < 0
                    || contextualReplayReservationUnits < 0
                    || verificationReservationUnits < analysisUnits) {
                throw new IllegalArgumentException("invalid contextual work ledger");
            }
        }
    }

    public record Evaluation(
        String coordinatorId,
        String successorIdentityRevision,
        String repositoryRevision,
        String principalInventoryFingerprint,
        String preparationInventoryFingerprint,
        String exactRegistryFingerprint,
        PatternTargetedLocalBridgeSearch.Budget bridgeBudget,
        ContextualBudget contextualBudget,
        String sourceExpression,
        AssumptionSignature sourceAssumptions,
        OccurrenceAwareSharedRulePreparationCoordinator.Evaluation baseV3Evaluation,
        List<RulePreparationCoordinator.Outcome> outcomes,
        List<ContextualSuccessorEvidence> successors,
        ContextualWork work
    ) {
        public Evaluation {
            if (!COORDINATOR_ID.equals(coordinatorId)
                    || !SUCCESSOR_IDENTITY_REVISION.equals(
                        successorIdentityRevision)) {
                throw new IllegalArgumentException("invalid v4 evaluation identity");
            }
            outcomes = List.copyOf(outcomes);
            successors = List.copyOf(successors);
            Objects.requireNonNull(baseV3Evaluation, "baseV3Evaluation");
            Objects.requireNonNull(work, "work");
        }

        public Optional<RulePreparationCoordinator.Outcome> outcome(String ruleId) {
            return outcomes.stream().filter(value -> value.ruleId().equals(ruleId))
                .findFirst();
        }

        public List<Transformation> candidates() {
            return outcomes.stream().flatMap(value -> value.candidate().stream())
                .toList();
        }
    }

    public record Verification(boolean valid, String detailCode) {
        public Verification {
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException("blank verification detail");
            }
        }
    }
}
