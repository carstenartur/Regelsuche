package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TargetFreeRepresentationSearchTest {
    @Test
    void boundedEnumerationIsTargetFreeDeterministicAndContentAddressed() {
        var boundary = RepresentationDiscoveryInformationBoundary
            .fromKnowledgePacks(
                new KnowledgePackRegistry(),
                RepresentationDiscoveryInformationBoundary.Track
                    .R2_CATALOG_BLIND_POST_HOC_BRIDGE,
                KnowledgePackSelection.CORE.enablePack(
                    TargetFreeSymPyBridgeDiscoveryScenario.PACK_ID),
                Set.of()
            );
        TargetFreeRepresentationSearch searcher =
            new TargetFreeRepresentationSearch();

        var first = searcher.search(
            TargetFreeSymPyBridgeDiscoveryScenario.SOURCE_EXPRESSION,
            boundary.candidateFormationRules(),
            TargetFreeRepresentationSearch.Budget.small()
        );
        var second = searcher.search(
            TargetFreeSymPyBridgeDiscoveryScenario.SOURCE_EXPRESSION,
            boundary.candidateFormationRules(),
            TargetFreeRepresentationSearch.Budget.small()
        );

        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(
            boundary.candidateFormationRuleInventoryHash(),
            first.content().ruleInventoryHash()
        );
        assertTrue(first.content().candidateStates().size() > 1);
        assertFalse(first.content().paretoStateHashes().isEmpty());
        assertTrue(first.content().candidateStates().stream()
            .noneMatch(state -> state.expression().equals(
                TargetFreeSymPyBridgeDiscoveryScenario
                    .FOLLOW_ON_EXPRESSION)));
        assertEquals(
            first.content().states().size(),
            new HashSet<>(first.content().states().stream()
                .map(TargetFreeRepresentationSearch.State::stateHash)
                .toList()).size()
        );
        assertTrue(first.content().transitions().stream()
            .anyMatch(transition -> transition.disposition()
                == TargetFreeRepresentationSearch
                    .TransitionDisposition.ACCEPTED_NEW_STATE));
    }

    @Test
    void sameRepresentationUnderDifferentAssumptionsRemainsDistinct() {
        List<RewriteRule> rules = List.of(
            new TestRewriteRule("plain", false),
            new TestRewriteRule("conditional", true)
        );
        var result = new TargetFreeRepresentationSearch().search(
            "x",
            rules,
            new TargetFreeRepresentationSearch.Budget(
                1, 4, 4, 8, 8, 0)
        );
        List<TargetFreeRepresentationSearch.State> candidates =
            result.content().candidateStates();

        assertEquals(2, candidates.size());
        assertEquals(
            Set.of(List.of(), List.of("x != 0")),
            candidates.stream()
                .map(TargetFreeRepresentationSearch.State::assumptions)
                .collect(java.util.stream.Collectors.toSet())
        );
        assertEquals(2L, candidates.stream()
            .map(TargetFreeRepresentationSearch.State::stateHash)
            .distinct()
            .count());
        assertTrue(candidates.stream()
            .allMatch(state -> state.expression().equals("y")));
    }

    @Test
    void exhaustedBudgetsRemainVisibleInsteadOfSilentlyPruning() {
        var boundary = RepresentationDiscoveryInformationBoundary
            .fromKnowledgePacks(
                RepresentationDiscoveryInformationBoundary.Track
                    .R1_TARGET_FREE_COMPRESSION,
                KnowledgePackSelection.CORE
            );
        var result = new TargetFreeRepresentationSearch().search(
            "x + 0",
            boundary.candidateFormationRules(),
            new TargetFreeRepresentationSearch.Budget(
                1, 1, 1, 1, 1, 0)
        );

        assertTrue(result.content().truncated());
        assertEquals(1, result.content().states().size());
        assertEquals(1, result.content().transitions().size());
        assertEquals(
            TargetFreeRepresentationSearch.TransitionDisposition
                .STATE_BUDGET_EXHAUSTED,
            result.content().transitions().getFirst().disposition()
        );
        assertNotEquals(
            result.content().sourceStateHash(),
            result.content().transitions().getFirst().toStateHash()
        );
    }

    private static final class TestRewriteRule implements RewriteRule {
        private final String id;
        private final boolean conditional;

        private TestRewriteRule(String id, boolean conditional) {
            this.id = id;
            this.conditional = conditional;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public RewriteKind kind() {
            return RewriteKind.NORMALIZE;
        }

        @Override
        public boolean mayIncreaseComplexity() {
            return false;
        }

        @Override
        public int estimatedCostDelta() {
            return 0;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return false;
        }

        @Override
        public boolean matches(Expr subtree) {
            return subtree instanceof VariableExpr variable
                && variable.name().equals("x");
        }

        @Override
        public Expr apply(Expr subtree) {
            return new VariableExpr("y");
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            return conditional
                ? List.of(Assumption.nonZero("x"))
                : List.of();
        }

        @Override
        public boolean mayEmitAssumptions() {
            return conditional;
        }
    }
}
