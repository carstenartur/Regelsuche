package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.transform.PolynomialDecompositionSynthesisOperator;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveryEngineFactoryTest {
    private static final String SUBJECT = "x^4 + 4*y^4";

    private final DiscoveryEngineFactory factory = new DiscoveryEngineFactory();
    private final TransformationEngine emptyBase = expression -> List.of();

    @Test
    void explicitMoveEngineUnifiesHypothesesAndInventoryMacrosUnderOnePolicy() {
        var engine = factory.createMoveEngine(emptyBase,
            DiscoveryOptions.forProfile(DiscoveryProfile.HYPOTHESIS_AND_MACRO_REUSE), macroSelector(),
            (move, state, context) -> move.sourceKind() == de.regelsuche.search.moves.SearchMove.SourceKind.LEARNED ? 100 : 0,
            de.regelsuche.search.moves.MoveContext.frozen(""));
        var picker = engine.picker(de.regelsuche.search.moves.MoveState.root(SUBJECT));
        var first = picker.next().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("sophie_macro", first.ruleId());
        org.junit.jupiter.api.Assertions.assertEquals(de.regelsuche.search.moves.SearchMove.ProofStrength.EMPIRICAL, first.proofStrength());
        assertTrue(picker.generatedMoves().stream().anyMatch(move ->
            move.sourceKind() == de.regelsuche.search.moves.SearchMove.SourceKind.HYPOTHESIS));
        assertTrue(first.primitiveExpansion().isEmpty(), "inventory metadata alone cannot authorize a primitive replay");
    }

    @Test
    void pureRewriteDoesNotEmitHypothesisRuleIds() {
        List<Transformation> transformations = factory.create(
            emptyBase,
            DiscoveryOptions.forProfile(DiscoveryProfile.PURE_REWRITE),
            macroSelector()
        ).transform(SUBJECT);

        assertTrue(transformations.stream().noneMatch(this::isHypothesis));
        assertTrue(transformations.stream().noneMatch(this::isMacro));
    }

    @Test
    void hypothesisOnlyEmitsGeneralSynthesisCandidatesButNoMacroRuleIds() {
        List<Transformation> transformations = factory.create(
            emptyBase,
            DiscoveryOptions.forProfile(DiscoveryProfile.HYPOTHESIS_ONLY),
            macroSelector()
        ).transform(SUBJECT);

        assertTrue(transformations.stream().anyMatch(t -> t.rule().equals(
            PolynomialDecompositionSynthesisOperator.RULE_ID)));
        assertTrue(transformations.stream().noneMatch(this::isMacro));
    }

    @Test
    void macroReuseOnlyCanEmitMacroRulesButNoHypothesisOperators() {
        List<Transformation> transformations = factory.create(
            emptyBase,
            DiscoveryOptions.forProfile(DiscoveryProfile.MACRO_REUSE_ONLY),
            macroSelector()
        ).transform(SUBJECT);

        assertTrue(transformations.stream().anyMatch(this::isMacro));
        assertTrue(transformations.stream().noneMatch(this::isHypothesis));
    }

    @Test
    void hypothesisAndMacroReuseCanEmitBothHypothesisAndMacroRules() {
        List<Transformation> transformations = factory.create(
            emptyBase,
            DiscoveryOptions.forProfile(
                DiscoveryProfile.HYPOTHESIS_AND_MACRO_REUSE),
            macroSelector()
        ).transform(SUBJECT);

        assertTrue(transformations.stream().anyMatch(this::isHypothesis));
        assertTrue(transformations.stream().anyMatch(this::isMacro));
    }

    @Test
    void hypothesisAndMacroReuseWithNullMacroSelectorBehavesLikeHypothesisOnly() {
        List<Transformation> transformations = factory.create(
            emptyBase,
            DiscoveryOptions.forProfile(
                DiscoveryProfile.HYPOTHESIS_AND_MACRO_REUSE),
            null
        ).transform(SUBJECT);

        assertTrue(transformations.stream().anyMatch(this::isHypothesis));
        assertTrue(transformations.stream().noneMatch(this::isMacro));
    }

    private boolean isHypothesis(Transformation transformation) {
        return transformation.rule().startsWith("hypothesis_");
    }

    private boolean isMacro(Transformation transformation) {
        return transformation.rule().startsWith("macro_");
    }

    private GoalAwareMacroMoveSelector macroSelector() {
        InMemoryRuleInventoryRepository inventory =
            new InMemoryRuleInventoryRepository();
        ReusableRule macro = new ReusableRule(
            "sophie_macro",
            "x ^ 4 + 4 * y ^ 4",
            "(x ^ 2 - 2 * x * y + 2 * y ^ 2)"
                + " * (x ^ 2 + 2 * x * y + 2 * y ^ 2)",
            List.of(),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.NEW,
            2,
            10.0,
            Instant.EPOCH,
            "hash-sophie-macro",
            null,
            0,
            2,
            List.of("path-1", "path-2"),
            0.95
        );
        inventory.save(macro);
        inventory.setEnabled(macro.id(), true);
        return new GoalAwareMacroMoveSelector(
            inventory,
            0.0,
            -1000.0,
            1);
    }
}
