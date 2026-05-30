package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.FractionDecompositionAstPredicate;
import de.regelsuche.transform.HypothesisOperator;
import de.regelsuche.transform.RationalizationHypothesisOperator;
import de.regelsuche.transform.RationalizedDenominatorAstPredicate;
import de.regelsuche.transform.TelescopingFractionHypothesisOperator;
import de.regelsuche.transform.Transformation;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.validation.DiscoveryEvidenceKind;
import de.regelsuche.validation.DiscoveryResultKind;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiscoveryOperatorCorpusTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final Map<String, HypothesisOperator> operators = Map.of(
        "telescoping-fraction", new TelescopingFractionHypothesisOperator(),
        "rationalization", new RationalizationHypothesisOperator()
    );

    @Test
    void operatorCorpusProducesReplayRowsAndDashboardFromActualResults() {
        List<CorpusCase> corpus = List.of(
            new CorpusCase("telescoping-fraction", "1 / (n * (n + 1))",
                Expectation.REQUIRE_TRANSFORMED, Set.of(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED), "unit step"),
            new CorpusCase("telescoping-fraction", "1 / ((x + 2) * (x + 3))",
                Expectation.REQUIRE_TRANSFORMED, Set.of(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED), "compound unit step"),
            new CorpusCase("telescoping-fraction", "1 / (n * (n + 2))",
                Expectation.REQUIRE_NO_FALSE_POSITIVE, Set.of(), "phase-2 k-form near miss"),
            new CorpusCase("telescoping-fraction", "1 / (n * (m + 1))",
                Expectation.REQUIRE_NO_FALSE_POSITIVE, Set.of(), "mixed-symbol near miss"),
            new CorpusCase("rationalization", "1 / (sqrt(x) + 1)",
                Expectation.REQUIRE_TRANSFORMED, Set.of(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED), "assumption x != 1"),
            new CorpusCase("rationalization", "1 / (sqrt(x) - 1)",
                Expectation.REQUIRE_TRANSFORMED, Set.of(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED), "assumption x != 1"),
            new CorpusCase("rationalization", "1 / (sqrt(x) + sqrt(y))",
                Expectation.DOCUMENT_ONLY, Set.of(), "future two-radical form"),
            new CorpusCase("rationalization", "1 / (sqrt(x) + y)",
                Expectation.REQUIRE_NO_FALSE_POSITIVE, Set.of(), "symbolic conjugate not enabled")
        );

        List<CorpusRow> rows = corpus.stream().map(this::evaluate).toList();
        for (CorpusRow row : rows) {
            if (row.seed().expectation() == Expectation.REQUIRE_TRANSFORMED) {
                assertEquals(DiscoveryResultKind.TRANSFORMED, row.resultKind(), row.toString());
                assertTrue(row.validated(), row.toString());
                assertTrue(row.evidenceKinds().containsAll(row.seed().expectedEvidence()), row.toString());
                assertEquals(row.seed().expression(), row.replayPath().getFirst());
            }
            if (row.seed().expectation() == Expectation.REQUIRE_NO_FALSE_POSITIVE) {
                assertFalse(row.validated(), row.toString());
                assertFalse(row.resultKind() == DiscoveryResultKind.FALSE_POSITIVE, row.toString());
            }
        }

        List<DeterministicDiscoveryExperimentRunner.SeedRunReport> reportRows = rows.stream()
            .map(this::toReport)
            .toList();
        DiscoveryBenchmarkDashboard dashboard = new DiscoveryBenchmarkDashboard();
        List<DiscoveryBenchmarkDashboard.Row> dashboardRows = dashboard.aggregate(reportRows);
        String table = dashboard.renderMarkdown(dashboardRows);

        assertTrue(table.contains("| Operator | Cases | Candidates | Bridge | Transformed | Macro learned | Macro reused | False positives | Avg time | Notes |"));
        assertTrue(table.contains("telescoping-fraction"));
        assertTrue(table.contains("rationalization"));
        assertEquals(2, dashboardRows.stream()
            .filter(row -> row.operator().equals("telescoping-fraction"))
            .findFirst().orElseThrow().transformed());
        assertEquals(2, dashboardRows.stream()
            .filter(row -> row.operator().equals("rationalization"))
            .findFirst().orElseThrow().transformed());
        assertTrue(dashboardRows.stream().allMatch(row -> row.falsePositives() == 0));
    }

    private CorpusRow evaluate(CorpusCase seed) {
        long started = System.nanoTime();
        List<Transformation> candidates = operators.get(seed.operatorId()).generateCandidates(seed.expression());
        Transformation first = candidates.isEmpty() ? null : candidates.getFirst();
        String finalExpression = first == null ? "" : first.transformedExpression();
        boolean structurallyTransformed = first != null && switch (seed.operatorId()) {
            case "telescoping-fraction" -> FractionDecompositionAstPredicate.containsFractionDecomposition(finalExpression);
            case "rationalization" -> RationalizedDenominatorAstPredicate.hasRationalizedDenominator(finalExpression);
            default -> false;
        };
        boolean validated = first != null && structurallyTransformed && numericallyEquivalent(seed.expression(), finalExpression);
        EnumSet<DiscoveryEvidenceKind> evidence = EnumSet.noneOf(DiscoveryEvidenceKind.class);
        if (validated) {
            evidence.add(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED);
            evidence.add(DiscoveryEvidenceKind.SIMPLIFIED);
        }
        DiscoveryResultKind kind;
        if (validated) {
            kind = DiscoveryResultKind.TRANSFORMED;
        } else if (!candidates.isEmpty() && seed.expectation() == Expectation.REQUIRE_NO_FALSE_POSITIVE) {
            kind = DiscoveryResultKind.FALSE_POSITIVE;
        } else if (!candidates.isEmpty()) {
            kind = DiscoveryResultKind.HYPOTHESIS_ONLY;
        } else {
            kind = DiscoveryResultKind.NO_CANDIDATE;
        }
        List<String> replay = first == null ? List.of() : List.of(seed.expression(), finalExpression);
        List<String> rules = first == null ? List.of() : List.of(first.rule());
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        return new CorpusRow(seed, kind, evidence, rules, candidates.size(), finalExpression, validated, replay, elapsed);
    }

    private boolean numericallyEquivalent(String leftExpression, String rightExpression) {
        Expr left = parser.parseTerm(leftExpression);
        Expr right = parser.parseTerm(rightExpression);
        for (int seed = 2; seed <= 8; seed++) {
            Map<String, Double> variables = new HashMap<>();
            variables.put("n", (double) seed);
            variables.put("m", (double) (seed + 2));
            variables.put("x", (double) (seed + 3));
            variables.put("y", (double) (seed + 5));
            double leftValue = evaluate(left, variables);
            double rightValue = evaluate(right, variables);
            if (!Double.isFinite(leftValue) || !Double.isFinite(rightValue)
                || Math.abs(leftValue - rightValue) > 1e-8) {
                return false;
            }
        }
        return true;
    }

    private double evaluate(Expr expression, Map<String, Double> variables) {
        if (expression instanceof NumberExpr number) {
            return number.value();
        }
        if (expression instanceof VariableExpr variable) {
            return variables.get(variable.name());
        }
        if (expression instanceof FunctionExpr function) {
            double argument = evaluate(function.argument(), variables);
            return "sqrt".equals(function.name()) ? Math.sqrt(argument) : Double.NaN;
        }
        BinaryExpr binary = (BinaryExpr) expression;
        double left = evaluate(binary.left(), variables);
        double right = evaluate(binary.right(), variables);
        BinaryOperator operator = binary.operator();
        return switch (operator) {
            case ADD -> left + right;
            case SUB -> left - right;
            case MUL -> left * right;
            case DIV -> Math.abs(right) < 1e-12 ? Double.NaN : left / right;
            case POW -> Math.pow(left, right);
        };
    }

    private DeterministicDiscoveryExperimentRunner.SeedRunReport toReport(CorpusRow row) {
        return new DeterministicDiscoveryExperimentRunner.SeedRunReport(
            new SeedExpression(row.seed().expression(), row.seed().expression(), "operator-corpus",
                row.seed().operatorId(), List.of("operator:" + row.seed().operatorId()), List.of()),
            row.validated(),
            row.seed().notes(),
            row.finalExpression().isBlank() ? List.of() : List.of(row.finalExpression()),
            List.of(),
            row.validated()
                ? CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND
                : CounterexampleSearchService.Status.INCONCLUSIVE,
            List.of(),
            row.finalExpression().contains("x - 1") ? List.of("x != 1") : List.of(),
            "",
            row.replayPath(),
            row.resultKind(),
            row.rules(),
            row.elapsedMillis(),
            0L,
            row.evidenceKinds()
        );
    }

    private enum Expectation {
        REQUIRE_TRANSFORMED,
        REQUIRE_NO_FALSE_POSITIVE,
        DOCUMENT_ONLY
    }

    private record CorpusCase(
        String operatorId,
        String expression,
        Expectation expectation,
        Set<DiscoveryEvidenceKind> expectedEvidence,
        String notes
    ) {
    }

    private record CorpusRow(
        CorpusCase seed,
        DiscoveryResultKind resultKind,
        Set<DiscoveryEvidenceKind> evidenceKinds,
        List<String> rules,
        int candidateCount,
        String finalExpression,
        boolean validated,
        List<String> replayPath,
        long elapsedMillis
    ) {
    }
}
