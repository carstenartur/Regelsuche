package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.math.algorithms.equivalence.PolynomialNormalFormEquivalenceService;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HiddenStructureDiscoveryCorpusTest {
    private final PolynomialNormalFormEquivalenceService equivalence =
        new PolynomialNormalFormEquivalenceService(new DefaultMathematicalAlgorithmRegistry());

    @Test
    void hiddenStructureDiscoveryCorpusDocumentsGeneralizationAndRejectsNearMisses() {
        List<CorpusCase> corpus = List.of(
            new CorpusCase("same-schema", "y^4 + 4", Expectation.REQUIRE_DISCOVERY,
                "A^4 + 4 with a different symbol"),
            new CorpusCase("same-schema", "(x + 1)^4 + 4", Expectation.REQUIRE_DISCOVERY,
                "A^4 + 4 with a compound base"),
            new CorpusCase("same-schema", "(2*x)^4 + 4", Expectation.REQUIRE_DISCOVERY,
                "A^4 + 4 with a scaled base"),
            new CorpusCase("same-schema", "(x^2)^4 + 4", Expectation.REQUIRE_DISCOVERY,
                "A^4 + 4 with a power base"),
            new CorpusCase("normalization", "(x^2)^2 + 4", Expectation.REQUIRE_DISCOVERY,
                "should work directly or after power normalization"),
            new CorpusCase("normalization", "x^2 * x^2 + 4", Expectation.DOCUMENT_ONLY,
                "documents whether product-to-power normalization feeds the hypothesis"),
            new CorpusCase("near-miss", "x^4 + 5", Expectation.REQUIRE_NO_DISCOVERY,
                "constant is not a square compatible with the Sophie-Germain bridge"),
            new CorpusCase("near-miss", "x^4 + 2", Expectation.REQUIRE_NO_DISCOVERY,
                "constant is not a square compatible with the Sophie-Germain bridge"),
            new CorpusCase("near-miss", "x^4 + 4 + y", Expectation.REQUIRE_NO_DISCOVERY,
                "extra addend must not become a false positive"),
            new CorpusCase("future-sophie-germain", "x^4 + 4*y^4", Expectation.DOCUMENT_ONLY,
                "future general Sophie-Germain form"),
            new CorpusCase("future-sophie-germain", "x^4 + 64", Expectation.DOCUMENT_ONLY,
                "future numeric Sophie-Germain form"),
            new CorpusCase("future-sophie-germain", "16*x^4 + 4*y^4", Expectation.DOCUMENT_ONLY,
                "future scaled Sophie-Germain form"),
            new CorpusCase("hidden-square", "x^2 + 6*x + 5", Expectation.DOCUMENT_ONLY,
                "hidden square-completion candidate"),
            new CorpusCase("hidden-square", "x^2 + 10*x + 21", Expectation.DOCUMENT_ONLY,
                "hidden square-completion candidate"),
            new CorpusCase("hidden-square", "x^2 + 2*x*y + y^2", Expectation.DOCUMENT_ONLY,
                "perfect-square candidate")
        );

        List<CorpusRow> rows = corpus.stream().map(this::evaluate).toList();
        String summaryTable = summaryTable(rows);
        System.out.println(summaryTable);

        assertEquals(corpus.size(), rows.size(), summaryTable);
        assertTrue(summaryTable.contains("| expression | discovered? | rule path | learned macro? | reusable? | notes |"));
        for (CorpusRow row : rows) {
            assertValidReplay(row);
            if (row.seed().expectation() == Expectation.REQUIRE_DISCOVERY) {
                assertTrue(row.discovered(), row.seed().expression() + "\n" + summaryTable);
            }
            if (row.seed().expectation() == Expectation.REQUIRE_NO_DISCOVERY) {
                assertFalse(row.discovered(), row.seed().expression() + "\n" + summaryTable);
            }
        }
    }

    private CorpusRow evaluate(CorpusCase seed) {
        TransformationEngine engine = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(),
            List.of(new DifferenceOfSquaresPreparationOperator())
        );
        List<Transformation> hypothesisCandidates = engine.transform(seed.expression()).stream()
            .filter(transformation -> transformation.rule().equals(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .toList();
        SearchProblem problem = new SearchProblem(
            seed.expression(),
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(4, 160, 1)
        );
        List<SearchState> states = new BestFirstSearchStrategy().search(problem);
        SearchState reportedState = bestReportedState(states);
        boolean discovered = reportedState != null
            && reportedState.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID)
            && reportedState.appliedRuleIds().contains("ast_square_difference_factor")
            && reportedState.path().size() > 1;
        boolean learnedMacro = discovered && !hypothesisCandidates.isEmpty();
        boolean reusable = discovered && (seed.group().equals("same-schema") || seed.group().equals("normalization"));
        List<String> rulePath = reportedState == null ? List.of() : reportedState.appliedRuleIds();
        List<String> replayPath = reportedState == null ? List.of() : reportedState.path();
        String notes = notes(seed, reportedState, hypothesisCandidates);
        return new CorpusRow(seed, discovered, rulePath, learnedMacro, reusable, replayPath, notes);
    }

    private SearchState bestReportedState(List<SearchState> states) {
        SearchState factored = states.stream()
            .filter(state -> state.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .filter(state -> state.appliedRuleIds().contains("ast_square_difference_factor"))
            .findFirst()
            .orElse(null);
        if (factored != null) {
            return factored;
        }
        SearchState squareDifference = states.stream()
            .filter(state -> state.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .filter(state -> state.expression().contains("^ 2 -"))
            .findFirst()
            .orElse(null);
        if (squareDifference != null) {
            return squareDifference;
        }
        return states.stream()
            .filter(state -> state.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .findFirst()
            .orElse(null);
    }

    private void assertValidReplay(CorpusRow row) {
        if (!row.discovered()) {
            return;
        }
        assertFalse(row.replayPath().isEmpty(), row.seed().expression());
        assertEquals(row.seed().expression(), row.replayPath().getFirst(), row.seed().expression());
        for (String expression : row.replayPath()) {
            assertTrue(
                equivalence.arePolynomiallyEquivalent(row.seed().expression(), expression),
                () -> row.seed().expression() + " not equivalent to " + expression + ": "
                    + equivalence.evidence(row.seed().expression(), expression)
            );
        }
    }

    private String notes(CorpusCase seed, SearchState reportedState, List<Transformation> hypothesisCandidates) {
        List<String> notes = new ArrayList<>();
        notes.add(seed.notes());
        if (hypothesisCandidates.isEmpty()) {
            notes.add("no hypothesis candidate");
        } else {
            notes.add("hypothesis candidates: " + hypothesisCandidates.size());
        }
        if (reportedState == null) {
            notes.add("no replay/search state reported");
        } else if (reportedState.appliedRuleIds().contains("ast_square_difference_factor")) {
            notes.add("validated factored replay state");
        } else if (reportedState.expression().contains("^ 2 -")) {
            notes.add("reached square-difference bridge only");
        } else {
            notes.add("hypothesis appeared without factorization");
        }
        return String.join("; ", notes);
    }

    private String summaryTable(List<CorpusRow> rows) {
        StringBuilder table = new StringBuilder();
        table.append("| expression | discovered? | rule path | learned macro? | reusable? | notes |\n");
        table.append("| --- | --- | --- | --- | --- | --- |\n");
        for (CorpusRow row : rows) {
            table.append("| ")
                .append(escape(row.seed().expression()))
                .append(" | ")
                .append(row.discovered() ? "yes" : "no")
                .append(" | ")
                .append(escape(row.rulePath().isEmpty() ? "—" : String.join(" -> ", row.rulePath())))
                .append(" | ")
                .append(row.learnedMacro() ? "yes" : "no")
                .append(" | ")
                .append(row.reusable() ? "yes" : "no")
                .append(" | ")
                .append(escape(row.notes()))
                .append(" |\n");
        }
        return table.toString();
    }

    private String escape(String value) {
        return value.replace("|", "\\|");
    }

    private enum Expectation {
        REQUIRE_DISCOVERY,
        REQUIRE_NO_DISCOVERY,
        DOCUMENT_ONLY
    }

    private record CorpusCase(String group, String expression, Expectation expectation, String notes) {
    }

    private record CorpusRow(
        CorpusCase seed,
        boolean discovered,
        List<String> rulePath,
        boolean learnedMacro,
        boolean reusable,
        List<String> replayPath,
        String notes
    ) {
    }
}
