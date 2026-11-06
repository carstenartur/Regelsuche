package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.HistoricalRediscoveryCorpus;
import de.regelsuche.evolution.CheckedSchemaTaskSolver;
import de.regelsuche.evolution.CumulativeTaskKnowledge;
import de.regelsuche.evolution.StructuralTaskObjective;
import de.regelsuche.evolution.TaskConstructionProfile;
import de.regelsuche.evolution.TraceStrategyTransferExample;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The target and the corpus's oracle are consulted only after source-only task search. */
@Timeout(240)
class HistoricalCatalogTaskCoverageTest {
    @Test
    void coversAllFrozenHistoricalInputsAtADeclaredStructuralGoal() {
        var corpus = HistoricalRediscoveryCorpus.load();
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(),
            TaskConstructionProfile.STRUCTURAL_EXPANSION_V1);
        var budget = new MoveSearch.Budget(32, 8, 10_000_000, 512, 10_000_000);
        var parser = new ExpressionParser();
        var exact = new ExactPolynomialAnalysis();
        int positives = 0, syntaxMatches = 0, exactRequired = 0, exactRequiredMatches = 0;
        assertEquals(14, corpus.cases().size(), "this is a frozen, explicitly versioned corpus");
        for (var item : corpus.cases()) {
            if (item.role() != HistoricalRediscoveryCorpus.Role.HISTORICAL_POSITIVE) continue;
            var objective = switch (item.family()) {
                case "COMPLETING_THE_SQUARE" -> StructuralTaskObjective.PERFECT_SQUARE;
                case "FACTORIZATION", "HIDDEN_STRUCTURE_BRIDGE" -> StructuralTaskObjective.FACTORIZATION;
                case "TEMPORARY_COMPLEXITY" -> StructuralTaskObjective.EXPANDED_POLYNOMIAL;
                default -> throw new AssertionError("unclassified historical family: " + item.family());
            };
            var task = new CheckedSchemaTaskSolver.Task(parser.parseExactTerm(item.source()).expression(), List.of(),
                objective.goal(), budget);
            var result = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
            assertTrue(result.solved(), () -> "source-only task remained unsolved: " + item.id());
            var evidence = result.solution().orElseThrow().evidence();
            assertTrue(evidence.withinBudget(), item.id());
            assertFalse(evidence.witness().isEmpty(), item.id());
            String output = ExpressionFormatter.format(evidence.incumbent().expression());
            exact.requireEquivalent(item.source(), output);
            exact.requireEquivalent(item.source(), item.target());
            boolean syntaxMatch = parser.parseExactTerm(output).expression().equals(parser.parseExactTerm(item.target()).expression());
            if (syntaxMatch) syntaxMatches++;
            if (item.targetRelation() == HistoricalRediscoveryCorpus.TargetRelation.SYNTAX_EXACT) {
                exactRequired++;
                if (syntaxMatch) exactRequiredMatches++;
            }
            positives++;
            System.out.println("CATALOG_TASK id=" + item.id() + " work=" + result.totalWork() + " steps="
                + evidence.witness().size() + " syntaxMatch=" + syntaxMatch + " output=" + output);
            for (var step : evidence.witness()) {
                assertTrue(step.verification().accepted(), item.id());
                System.out.println("CATALOG_STEP id=" + item.id() + " source=" + ExpressionFormatter.format(step.source().expression())
                    + " rule=" + step.move().ruleId() + " kind=" + step.move().sourceKind()
                    + " target=" + ExpressionFormatter.format(step.target().expression()));
            }
        }
        assertEquals(12, positives);
        assertEquals(11, exactRequired, "preserve the frozen corpus's target contracts");
        assertTrue(exactRequiredMatches >= 8, "do not silently regress the original syntax-exact targets");
        System.out.println("CATALOG_TASK_TOTAL positives=" + positives + " syntaxMatches=" + syntaxMatches
            + " exactRequired=" + exactRequired + " exactRequiredMatches=" + exactRequiredMatches);

        var negative = corpus.cases().stream().filter(item -> item.role() == HistoricalRediscoveryCorpus.Role.NEGATIVE_CONTROL)
            .findFirst().orElseThrow();
        var task = new CheckedSchemaTaskSolver.Task(parser.parseExactTerm(negative.source()).expression(), List.of(),
            StructuralTaskObjective.PERFECT_SQUARE.goal(), new MoveSearch.Budget(8, 2, 1_000_000, 32, 200_000));
        assertFalse(knowledge.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE).solved());
        assertThrows(IllegalArgumentException.class, () -> exact.requireEquivalent(negative.source(), negative.target()));

        var policyControl = corpus.cases().stream().filter(item -> item.role() == HistoricalRediscoveryCorpus.Role.SEARCH_POLICY_CONTROL)
            .findFirst().orElseThrow();
        var controlTask = new CheckedSchemaTaskSolver.Task(parser.parseExactTerm(policyControl.source()).expression(), List.of(),
            StructuralTaskObjective.EXPANDED_POLYNOMIAL.goal(), budget);
        var controlResult = knowledge.solve(controlTask, CumulativeTaskKnowledge.Strategy.BASELINE);
        assertTrue(controlResult.solved());
        assertTrue(controlResult.solution().orElseThrow().evidence().withinBudget());
        assertFalse(controlResult.solution().orElseThrow().evidence().witness().isEmpty());
        String controlOutput = ExpressionFormatter.format(controlResult.solution().orElseThrow().evidence().incumbent().expression());
        exact.requireEquivalent(policyControl.source(), controlOutput);
        System.out.println("CATALOG_CONTROL id=" + policyControl.id() + " work=" + controlResult.totalWork()
            + " output=" + controlOutput);
    }
}
