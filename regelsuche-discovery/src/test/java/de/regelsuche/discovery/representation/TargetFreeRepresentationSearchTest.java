package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import java.util.HashSet;
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
                .map(TargetFreeRepresentationSearch.State::expression)
                .toList()).size()
        );
        assertTrue(first.content().transitions().stream()
            .anyMatch(transition -> transition.disposition()
                == TargetFreeRepresentationSearch
                    .TransitionDisposition.ACCEPTED_NEW_STATE));
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
}
