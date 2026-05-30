package de.regelsuche.learning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import de.regelsuche.mining.MacroMoveTransformationEngine;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MacroLearningReuseBenchmarkTest {
    private final SymPyEquivalenceService equivalence = new SymPyEquivalenceService();

    @Test
    void benchmarkRowsLearnPromoteReuseAndValidateCoreMacros() {
        List<Row> rows = List.of(
            new Row(
                "sophie",
                "x^4 + 4",
                "(x^2 - 2*x + 2) * (x^2 + 2*x + 2)",
                "z^4 + 4",
                List.of(),
                ""
            ),
            new Row(
                "telescoping",
                "1 / (n * (n + 1))",
                "1 / n - 1 / (n + 1)",
                "1 / ((x + 2) * (x + 3))",
                List.of(),
                "1 / (n * (n + 2))"
            ),
            new Row(
                "rationalization",
                "1 / (sqrt(x) + 1)",
                "(sqrt(x) - 1) / (x - 1)",
                "1 / (sqrt(y + 2) + 1)",
                List.of("x != 1"),
                ""
            )
        );

        for (Row row : rows) {
            InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
            MacroLearningResult result = new MacroLearningPipeline(inventory).learn(List.of(path(row)));
            assertFalse(result.newlyActivated().isEmpty(), row.id() + " promoted?");
            assertTrue(result.validationExamples().stream().allMatch(MacroValidationExample::equivalent),
                row.id() + " validation examples");
            ReusableRule learned = result.newlyActivated().getFirst();

            MacroMoveTransformationEngine engine = new MacroMoveTransformationEngine(
                new AstRewriteTransformationEngine(List.of(), 0, 0),
                new GoalAwareMacroMoveSelector(inventory),
                null,
                Map.of(),
                learned.assumptions()
            );
            List<Transformation> reused = engine.transform(row.reuseInput()).stream()
                .filter(transformation -> transformation.rule().equals(learned.id()))
                .toList();

            assertFalse(reused.isEmpty(), row.id() + " reused?");
            assertTrue(equivalence.areEquivalent(row.reuseInput(), reused.getFirst().transformedExpression()),
                row.id() + " equivalent?");
            assertTrue(learned.assumptions().containsAll(
                row.assumptions().stream().map(a -> a.replace("x", "A")).toList()
            ), row.id() + " assumptions");
            assertTrue(engine.expansionFor(
                row.reuseInput(),
                reused.getFirst().transformedExpression(),
                learned.id()
            ).orElseThrow().supportingPathIds().contains(row.id() + "-discovery"));
            if (!row.falsePositiveInput().isBlank()) {
                assertTrue(engine.transform(row.falsePositiveInput()).stream()
                    .noneMatch(transformation -> transformation.rule().equals(learned.id())),
                    row.id() + " false positive guard");
            }
        }
    }

    private static SuccessfulTransformationPath path(Row row) {
        return new SuccessfulTransformationPath(
            row.id() + "-discovery",
            row.discoverySource(),
            row.discoveryTarget(),
            List.of(row.discoverySource(), row.discoveryTarget()),
            List.of("actual_replay_step_1", "actual_replay_step_2"),
            new ExpressionScore(row.discoverySource().length() + 10, 0, 0, 0, 0),
            new ExpressionScore(row.discoveryTarget().length(), 0, 0, 0, 0),
            true,
            "benchmark",
            Map.of("source", "replay")
        ).withAssumptions(row.assumptions());
    }

    private record Row(
        String id,
        String discoverySource,
        String discoveryTarget,
        String reuseInput,
        List<String> assumptions,
        String falsePositiveInput
    ) {
    }
}
