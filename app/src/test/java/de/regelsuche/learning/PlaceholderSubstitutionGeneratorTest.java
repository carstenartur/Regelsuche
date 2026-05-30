package de.regelsuche.learning;

import de.regelsuche.ast.Expr;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderSubstitutionGeneratorTest {
    private final PlaceholderSubstitutionGenerator generator = new PlaceholderSubstitutionGenerator();
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void generatesIndependentAssignmentsForTwoPlaceholders() {
        List<Map<String, Expr>> substitutions = generator.generate(
            new LinkedHashSet<>(List.of("A", "B")),
            List.of()
        );

        assertContainsAssignment(substitutions, "x", "y");
        assertContainsAssignment(substitutions, "x + 1", "z");
        assertContainsAssignment(substitutions, "2*x", "y");
        assertContainsAssignment(substitutions, "x^2", "n + 2");

        SymPyEquivalenceService equivalence = new SymPyEquivalenceService();
        for (Map<String, Expr> substitution : substitutions) {
            String left = substitute("A^4 + 4*B^4", substitution);
            String right = substitute("(A^2 - 2*A*B + 2*B^2) * (A^2 + 2*A*B + 2*B^2)", substitution);
            assertTrue(equivalence.areEquivalent(left, right), () -> substitution.toString());
        }
    }

    private void assertContainsAssignment(List<Map<String, Expr>> substitutions, String a, String b) {
        String expectedA = format(parser.parseTerm(a));
        String expectedB = format(parser.parseTerm(b));
        assertTrue(substitutions.stream().anyMatch(map ->
            format(map.get("A")).equals(expectedA) && format(map.get("B")).equals(expectedB)
        ), "missing A=" + a + ", B=" + b);
    }

    private String substitute(String pattern, Map<String, Expr> substitution) {
        String result = pattern;
        for (Map.Entry<String, Expr> entry : substitution.entrySet()) {
            result = result.replaceAll("\\b" + entry.getKey() + "\\b", "(" + format(entry.getValue()) + ")");
        }
        return result;
    }

    private String format(Expr expression) {
        return ExpressionFormatter.format(expression);
    }
}
