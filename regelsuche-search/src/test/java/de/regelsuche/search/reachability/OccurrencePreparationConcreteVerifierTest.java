package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.Transformation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OccurrencePreparationConcreteVerifierTest {
    @ParameterizedTest
    @ValueSource(strings = {"target", "applicationKey", "assumptions", "lineage"})
    void concreteReplayRejectsTamperedCandidates(String field) {
        var fixture = fixture();
        var original = fixture.evaluation();
        var outcome = original.outcomes().getFirst();
        var step = outcome.candidate().orElseThrow();
        var changed = new Transformation(
            step.rule(), field.equals("target") ? "0" : step.transformedExpression(),
            step.kind(), step.mayIncreaseComplexity(), step.estimatedCostDelta(),
            step.equivalencePreservingByConstruction(),
            field.equals("applicationKey") ? step.applicationKey() + ":forged" : step.applicationKey(),
            field.equals("assumptions") ? List.of("y > 0") : step.assumptions(),
            step.packId(), step.license(),
            field.equals("lineage") ? List.of("different_rule") : step.primitiveRuleIds());
        var changedOutcome = new RulePreparationCoordinator.Outcome(
            outcome.ruleId(), outcome.ruleFingerprint(), outcome.status(),
            Optional.of(changed), true, outcome.initialAnalysis(), outcome.work(),
            outcome.reachedLimits(), outcome.detailCode(), outcome.bridgeCertificateHash());
        var tampered = new OccurrenceAwareSharedRulePreparationCoordinator.Evaluation(
            original.coordinatorId(), original.occurrenceBindingRevision(),
            original.repositoryRevision(), original.principalInventoryFingerprint(),
            original.preparationInventoryFingerprint(), original.exactRegistryFingerprint(),
            original.bridgeBudget(), original.sourceExpression(), original.sourceAssumptions(),
            List.of(changedOutcome), original.aggregateWork(), original.occurrenceWork(),
            original.directOccurrenceEvidence(), original.delegatedV2PrincipalIds(),
            original.delegatedSharedExecutionWork());

        var result = OccurrencePreparationReplay.verify(tampered, List.of(fixture.schema()));

        assertFalse(result.valid());
        assertEquals("DIRECT_PRIMITIVE_REPLAY_MISMATCH", result.detailCode());
        assertFalse(fixture.coordinator().verify(tampered).valid());
    }

    @Test
    void concreteReplayMissingCandidateIsNotVerificationSuccess() {
        var fixture = fixture();
        fixture.rule().suppress = true;

        var result = OccurrencePreparationReplay.verify(
            fixture.evaluation(), List.of(fixture.schema()));

        assertFalse(result.valid());
        assertEquals("DIRECT_PRIMITIVE_REPLAY_MISMATCH", result.detailCode());
    }

    @Test
    void concreteReplayExceptionBecomesATechnicalFailure() {
        var fixture = fixture();
        fixture.rule().broken = true;

        var result = assertDoesNotThrow(() -> OccurrencePreparationReplay.verify(
            fixture.evaluation(), List.of(fixture.schema())));

        assertFalse(result.valid());
        assertEquals("DIRECT_PRIMITIVE_REPLAY_TECHNICAL_FAILURE", result.detailCode());
    }

    private static Fixture fixture() {
        var rule = new ReplayRule();
        var schema = new RewriteApplicabilitySchema(
            "concrete-replay-control/v1", rule, rule.source(), rule.recognitionProfile());
        var coordinator = new OccurrenceAwareSharedRulePreparationCoordinator(
            List.of(schema), List.of(), "0123456789abcdef0123456789abcdef01234567",
            new PatternTargetedLocalBridgeSearch.Budget(
                3, 128, 1_024, 8, 160, 128, 32, 5_000, 2_500));
        var evaluation = coordinator.analyze(
            "1 + (x + 0)", AssumptionSignature.ofExpressions(List.of("z > 0")));
        return new Fixture(rule, schema, coordinator, evaluation);
    }

    private record Fixture(
        ReplayRule rule,
        RewriteApplicabilitySchema schema,
        OccurrenceAwareSharedRulePreparationCoordinator coordinator,
        OccurrenceAwareSharedRulePreparationCoordinator.Evaluation evaluation
    ) { }

    private static final class ReplayRule extends PatternRewriteRule {
        private boolean broken;
        private boolean suppress;

        private ReplayRule() {
            super("concrete_replay_control",
                PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.num(0)),
                PatternExpr.var("A"));
        }

        @Override
        public boolean matches(Expr expression) {
            if (broken) {
                throw new IllegalStateException("injected concrete replay failure");
            }
            return !suppress && super.matches(expression);
        }
    }
}
