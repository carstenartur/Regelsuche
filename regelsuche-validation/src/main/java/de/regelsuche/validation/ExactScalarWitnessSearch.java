package de.regelsuche.validation;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactParsedUnivariatePolynomialView;
import de.regelsuche.scalar.ExactRational;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** A bounded, per-search checker for real scalar assignments in the rational arithmetic fragment. */
final class ExactScalarWitnessSearch {
    private static final Pattern RELATION = Pattern.compile("(.+?)\\s*(!=|>=|<=|=|>|<)\\s*(.+)");
    private final ExpressionParser parser = new ExpressionParser();
    private final ExactParsedUnivariatePolynomialView arithmetic = new ExactParsedUnivariatePolynomialView();
    private final String left;
    private final String right;
    private final List<String> assumptions;
    private final Map<String, Optional<Template>> templates = new HashMap<>();
    private boolean evaluated;

    ExactScalarWitnessSearch(String left, String right, List<String> assumptions) {
        this.left = left;
        this.right = right;
        this.assumptions = assumptions.stream().map(AssumptionSignature::normalizeExpression).toList();
    }

    static boolean accepts(Expr expression) {
        return expression instanceof NumberExpr || expression instanceof VariableExpr
            || expression instanceof BinaryExpr binary && accepts(binary.left()) && accepts(binary.right());
    }

    static String sampleText(double value, boolean rationalGrid) {
        if (rationalGrid && Math.abs(value) == 2.0 / 3.0) return value < 0 ? "-2/3" : "2/3";
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    boolean evaluated() {
        return evaluated;
    }

    Comparison compare(Map<String, String> assignment, List<String> labels, Runnable onAssignment) {
        if (!assumptions.stream().allMatch(guard -> satisfied(guard, assignment))) {
            return new Comparison(true, Optional.empty());
        }
        // A rejected premise does not evaluate the source/candidate pair.
        onAssignment.run();
        var first = evaluate(left, assignment);
        var second = evaluate(right, assignment);
        if (first.isEmpty() || second.isEmpty()) return new Comparison(false, Optional.empty());
        evaluated = true;
        var a = first.orElseThrow();
        var b = second.orElseThrow();
        return new Comparison(true, a.equals(b) ? Optional.empty()
            : Optional.of(new CounterexampleSearchService.Counterexample(labels, a.canonicalText(), b.canonicalText())));
    }

    private boolean satisfied(String predicate, Map<String, String> assignment) {
        var relation = RELATION.matcher(predicate);
        if (!relation.matches()) return false;
        var a = evaluate(relation.group(1), assignment);
        var b = evaluate(relation.group(3), assignment);
        if (a.isEmpty() || b.isEmpty()) return false;
        int comparison = a.orElseThrow().compareTo(b.orElseThrow());
        return switch (relation.group(2)) {
            case "!=" -> comparison != 0;
            case ">=" -> comparison >= 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case "<" -> comparison < 0;
            case "=" -> comparison == 0;
            default -> false;
        };
    }

    private Optional<ExactRational> evaluate(String source, Map<String, String> assignment) {
        var template = templates.computeIfAbsent(source, this::parse);
        if (template.isEmpty()) return Optional.empty();
        return template.orElseThrow().instantiate(assignment, parser)
            .flatMap(term -> arithmetic.analyze(term).polynomial())
            .filter(value -> value.isConstant())
            .map(value -> value.isZero() ? ExactRational.ZERO : value.leadingCoefficient());
    }

    private Optional<Template> parse(String source) {
        try {
            var term = parser.parseExactTerm(source);
            if (!accepts(term.expression())) return Optional.empty();
            var occurrences = new ArrayList<Occurrence>();
            collect(term, term.expression(), occurrences);
            return Optional.of(new Template(term, List.copyOf(occurrences)));
        } catch (IllegalArgumentException invalidPredicate) {
            return Optional.empty();
        }
    }

    private static void collect(ExactParsedTerm term, Expr expression, List<Occurrence> occurrences) {
        if (expression instanceof VariableExpr variable) {
            occurrences.add(new Occurrence(variable.name(), term.sourceRangeFor(variable).orElseThrow()));
        } else if (expression instanceof BinaryExpr binary) {
            collect(term, binary.left(), occurrences);
            collect(term, binary.right(), occurrences);
        }
    }

    private record Occurrence(String name, ExactParsedTerm.SourceRange range) {}

    private record Template(ExactParsedTerm term, List<Occurrence> variables) {
        Optional<ExactParsedTerm> instantiate(Map<String, String> assignment, ExpressionParser parser) {
            if (variables.isEmpty()) return Optional.of(term);
            var grounded = new StringBuilder();
            int start = 0;
            for (var variable : variables) {
                String value = assignment.get(variable.name());
                if (value == null) return Optional.empty();
                grounded.append(term.source(), start, variable.range().startInclusive());
                grounded.append('(').append(value).append(')');
                start = variable.range().endExclusive();
            }
            grounded.append(term.source(), start, term.source().length());
            return Optional.of(parser.parseExactTerm(grounded.toString()));
        }
    }

    record Comparison(boolean defined, Optional<CounterexampleSearchService.Counterexample> witness) {}
}
