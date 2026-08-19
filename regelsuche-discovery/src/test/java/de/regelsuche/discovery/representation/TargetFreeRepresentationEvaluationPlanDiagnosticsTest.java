package de.regelsuche.discovery.representation;

import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TargetFreeRepresentationEvaluationPlanDiagnosticsTest {
    private static final String REPOSITORY_REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void dumpsOccurrenceLocalBridgeCandidatesAndMatches() {
        var benchmarkCase = TargetFreeRepresentationEvaluationPlan.create(
            REPOSITORY_REVISION
        ).content().cases().stream()
            .filter(value -> value.id().equals(
                "occurrence-local-trigonometric-bridge"
            ))
            .findFirst()
            .orElseThrow();
        var boundary = boundary(benchmarkCase);
        var result = new TargetFreeRepresentationSearch().search(
            benchmarkCase.sourceExpression(),
            boundary.candidateFormationRules(),
            budget(benchmarkCase.budget())
        );
        var disclosure = boundary.disclosePostFreeze(
            boundary.freezeCandidates(List.of())
        );
        KnownStructureMatcher matcher = new KnownStructureMatcher(
            disclosure.classificationCatalog()
        );

        System.out.println("SOURCE=" + benchmarkCase.sourceExpression());
        System.out.println("FORMATION_RULES="
            + boundary.candidateFormationRules().stream()
                .map(rule -> rule.id())
                .sorted()
                .toList());
        System.out.println("CATALOG_STRUCTURES="
            + disclosure.classificationCatalog().structures().stream()
                .map(structure -> structure.id() + "="
                    + structure.consequenceIds())
                .sorted()
                .toList());
        result.content().candidateStates().forEach(state -> {
            var scan = matcher.scan(state.expression());
            System.out.println("STATE=" + state.sequence()
                + " depth=" + state.depth()
                + " expression=" + state.expression()
                + " assumptions=" + state.assumptions());
            System.out.println("MATCHES=" + scan.matches().stream()
                .map(match -> match.structureId()
                    + " path=" + match.occurrencePath().canonical()
                    + " whole=" + match.wholeExpression()
                    + " consequences=" + match.consequenceIds()
                    + " bindings=" + match.bindings())
                .toList());
            System.out.println("DIAGNOSTICS=" + scan.diagnostics());
        });
    }

    private static RepresentationDiscoveryInformationBoundary boundary(
        TargetFreeRepresentationEvaluationPlan.CaseDefinition benchmarkCase
    ) {
        KnowledgePackSelection selection = KnowledgePackSelection.profile(
            benchmarkCase.ruleProfile()
        );
        for (String packId : benchmarkCase.enabledRulePackIds()) {
            selection = selection.enablePack(packId);
        }
        return RepresentationDiscoveryInformationBoundary.fromKnowledgePacks(
            new KnowledgePackRegistry(),
            benchmarkCase.informationTrack(),
            selection,
            Set.of()
        );
    }

    private static TargetFreeRepresentationSearch.Budget budget(
        TargetFreeRepresentationEvaluationPlan.WorkBudget budget
    ) {
        return new TargetFreeRepresentationSearch.Budget(
            budget.maxDepth(),
            budget.maxExploredStates(),
            budget.maxRetainedStates(),
            budget.maxGeneratedTransitions(),
            budget.maxCandidatesPerState(),
            budget.maxAstSizeIncreasePerStep()
        );
    }
}
