package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.DiscoveryOptions;
import de.regelsuche.transform.DiscoveryProfile;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveryEngineFactoryTest {
    private final DiscoveryEngineFactory factory = new DiscoveryEngineFactory();
    private final TransformationEngine emptyBase = expression -> List.of();

    @Test
    void pureRewriteDoesNotEmitHypothesisRuleIds() {
        List<Transformation> transformations = factory.create(
            emptyBase,
            DiscoveryOptions.forProfile(DiscoveryProfile.PURE_REWRITE),
            macroSelector()
        ).transform("x^4 + 4");

        assertTrue(transformations.stream().noneMatch(this::isHypothesis));
        assertTrue(transformations.stream().noneMatch(this::isMacro));
    }

    @Test
    void hypothesisOnlyEmitsHypothesisCandidatesButNoMacroRuleIds() {
        List<Transformation> transformations = factory.create(
            emptyBase,
            DiscoveryOptions.forProfile(DiscoveryProfile.HYPOTHESIS_ONLY),
            macroSelector()
        ).transform("x^4 + 4");

        assertTrue(transformations.stream().anyMatch(t -> t.rule().equals(DifferenceOfSquaresPreparationOperator.RULE_ID)));
        assertTrue(transformations.stream().noneMatch(this::isMacro));
    }

    @Test
    void macroReuseOnlyCanEmitMacroRulesButNoHypothesisOperators() {
        List<Transformation> transformations = factory.create(
            emptyBase,
            DiscoveryOptions.forProfile(DiscoveryProfile.MACRO_REUSE_ONLY),
            macroSelector()
        ).transform("x^4 + 4");

        assertTrue(transformations.stream().anyMatch(this::isMacro));
        assertTrue(transformations.stream().noneMatch(this::isHypothesis));
    }

    @Test
    void fullDiscoveryCanEmitBothHypothesisAndMacroRules() {
        List<Transformation> transformations = factory.create(
            emptyBase,
            DiscoveryOptions.forProfile(DiscoveryProfile.FULL_DISCOVERY),
            macroSelector()
        ).transform("x^4 + 4");

        assertTrue(transformations.stream().anyMatch(this::isHypothesis));
        assertTrue(transformations.stream().anyMatch(this::isMacro));
    }

    private boolean isHypothesis(Transformation transformation) {
        return transformation.rule().startsWith("hypothesis_");
    }

    private boolean isMacro(Transformation transformation) {
        return transformation.rule().startsWith("macro_");
    }

    private GoalAwareMacroMoveSelector macroSelector() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        ReusableRule macro = new ReusableRule(
            "sophie_macro",
            "x ^ 4 + 4",
            "(x ^ 2 + 2 - 2 * x) * (x ^ 2 + 2 + 2 * x)",
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
        return new GoalAwareMacroMoveSelector(inventory, 0.0, -1000.0, 1);
    }
}
