package de.regelsuche.solver.ir;

import de.regelsuche.solver.ir.SolverIr.Predicate;
import de.regelsuche.solver.ir.SolverIr.Relation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts the bounded legacy assumption syntax into typed IR predicates. */
public final class StructuredAssumptionParser {
    private static final Pattern COMPARISON = Pattern.compile(
        "^(.+?)\\s*(<=|>=|!=|=|<|>)\\s*(.+)$");
    private static final Pattern INTEGER = Pattern.compile(
        "^([A-Za-z][A-Za-z0-9_]*)\\s+is\\s+integer$",
        Pattern.CASE_INSENSITIVE);

    private final CoreExpressionIrAdapter expressions = new CoreExpressionIrAdapter();

    public List<Predicate> parse(List<String> assumptions) {
        if (assumptions == null) {
            return List.of();
        }
        List<String> ordered = assumptions.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().replaceAll("\\s+", " "))
            .distinct()
            .sorted()
            .toList();
        List<Predicate> result = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            result.add(parseOne(String.format(Locale.ROOT, "assumption-%03d", index + 1),
                ordered.get(index)));
        }
        result.sort(Comparator.comparing(Predicate::id));
        return List.copyOf(result);
    }

    private Predicate parseOne(String id, String assumption) {
        Matcher integer = INTEGER.matcher(assumption);
        if (integer.matches()) {
            return new Predicate(
                id,
                Relation.IS_INTEGER,
                expressions.parse(integer.group(1)),
                null);
        }
        Matcher comparison = COMPARISON.matcher(assumption);
        if (!comparison.matches()) {
            throw new IllegalArgumentException(
                "unsupported structured assumption: " + assumption);
        }
        return new Predicate(
            id,
            relation(comparison.group(2)),
            expressions.parse(comparison.group(1)),
            expressions.parse(comparison.group(3)));
    }

    private static Relation relation(String operator) {
        return switch (operator) {
            case "=" -> Relation.EQUALS;
            case "!=" -> Relation.NOT_EQUALS;
            case "<" -> Relation.LESS_THAN;
            case "<=" -> Relation.LESS_OR_EQUAL;
            case ">" -> Relation.GREATER_THAN;
            case ">=" -> Relation.GREATER_OR_EQUAL;
            default -> throw new IllegalArgumentException(
                "unsupported assumption relation: " + operator);
        };
    }
}
