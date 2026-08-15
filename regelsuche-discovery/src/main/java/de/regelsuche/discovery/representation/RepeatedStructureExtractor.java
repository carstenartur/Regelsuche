package de.regelsuche.discovery.representation;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.value.ExprValueFactory;
import de.regelsuche.value.ExprValueFactory.ExprValue;
import de.regelsuche.value.ExprValueFactory.ValueKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Finds repeated non-leaf semantic values with exact AST occurrences. */
public final class RepeatedStructureExtractor {
    private final ExpressionParser parser;
    private final RepeatedStructureExtractionCandidate.Policy policy;

    public RepeatedStructureExtractor() {
        this(
            new ExpressionParser(),
            RepeatedStructureExtractionCandidate.Policy.standard()
        );
    }

    public RepeatedStructureExtractor(
        RepeatedStructureExtractionCandidate.Policy policy
    ) {
        this(new ExpressionParser(), policy);
    }

    RepeatedStructureExtractor(
        ExpressionParser parser,
        RepeatedStructureExtractionCandidate.Policy policy
    ) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public RepeatedStructureExtractionCandidate.Policy policy() {
        return policy;
    }

    public List<RepeatedStructureExtractionCandidate> extract(
        String sourceExpression
    ) {
        Expr root = parser.parseTerm(
            RepresentationCandidateAssessment.requireText(
                sourceExpression, "sourceExpression"));
        String normalizedSource = ExpressionFormatter.format(root);
        Map<ValueKey, Integer> occurrenceCounts = new TreeMap<>();
        Map<ValueKey, List<RepeatedStructureExtractionCandidate.Occurrence>>
            occurrencesByValue = new TreeMap<>();

        try (ExprValueFactory factory = new ExprValueFactory()) {
            ExprValueFactory.Projection projection = factory.project(root);
            countOccurrences(
                root,
                projection.valuesBySyntaxIdentity(),
                occurrenceCounts
            );
            collectRepeatedOccurrences(
                root,
                ExpressionOccurrencePath.root(),
                projection.valuesBySyntaxIdentity(),
                occurrenceCounts,
                occurrencesByValue
            );
        }

        return occurrencesByValue.entrySet().stream()
            .filter(entry ->
                entry.getValue().size() >= policy.minimumOccurrences())
            .map(entry -> RepeatedStructureExtractionCandidate.create(
                normalizedSource,
                entry.getKey().toString(),
                entry.getValue(),
                policy
            ))
            .sorted()
            .toList();
    }

    public List<RepeatedStructureExtractionCandidate> extractMaterial(
        String sourceExpression
    ) {
        return extract(sourceExpression).stream()
            .filter(RepeatedStructureExtractionCandidate::material)
            .toList();
    }

    private int countOccurrences(
        Expr expression,
        Map<Expr, ExprValue> valuesBySyntaxIdentity,
        Map<ValueKey, Integer> occurrenceCounts
    ) {
        int astNodeCount = 1;
        List<Expr> children = children(expression);
        for (int index = 0; index < children.size(); index++) {
            astNodeCount = Math.addExact(
                astNodeCount,
                countOccurrences(
                    children.get(index),
                    valuesBySyntaxIdentity,
                    occurrenceCounts
                )
            );
        }
        if (astNodeCount >= policy.minimumSubtreeNodes()) {
            ExprValue value = Objects.requireNonNull(
                valuesBySyntaxIdentity.get(expression),
                "semantic value for syntax occurrence");
            occurrenceCounts.merge(value.key(), 1, Integer::sum);
        }
        return astNodeCount;
    }

    private int collectRepeatedOccurrences(
        Expr expression,
        ExpressionOccurrencePath path,
        Map<Expr, ExprValue> valuesBySyntaxIdentity,
        Map<ValueKey, Integer> occurrenceCounts,
        Map<ValueKey, List<RepeatedStructureExtractionCandidate.Occurrence>>
            occurrencesByValue
    ) {
        int astNodeCount = 1;
        List<Expr> children = children(expression);
        for (int index = 0; index < children.size(); index++) {
            astNodeCount = Math.addExact(
                astNodeCount,
                collectRepeatedOccurrences(
                    children.get(index),
                    path.append(index),
                    valuesBySyntaxIdentity,
                    occurrenceCounts,
                    occurrencesByValue
                )
            );
        }
        if (astNodeCount >= policy.minimumSubtreeNodes()) {
            ExprValue value = Objects.requireNonNull(
                valuesBySyntaxIdentity.get(expression),
                "semantic value for syntax occurrence");
            if (occurrenceCounts.getOrDefault(value.key(), 0)
                >= policy.minimumOccurrences()) {
                occurrencesByValue.computeIfAbsent(
                    value.key(), ignored -> new ArrayList<>()).add(
                        new RepeatedStructureExtractionCandidate.Occurrence(
                            path,
                            ExpressionFormatter.format(expression),
                            astNodeCount
                        )
                    );
            }
        }
        return astNodeCount;
    }

    private static List<Expr> children(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return List.of(binary.left(), binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            return function.arguments();
        }
        return List.of();
    }
}
