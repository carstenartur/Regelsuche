package de.regelsuche.discovery.representation;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.value.ExprValueArena;
import de.regelsuche.value.ExprValueArena.Projection;
import de.regelsuche.value.ExprValueArena.ValueId;
import de.regelsuche.value.ExprValueFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        Map<ValueId, Integer> occurrenceCounts = new HashMap<>();
        Map<ValueId, List<RepeatedStructureExtractionCandidate.Occurrence>> occurrencesByValue = new HashMap<>();

        try (var arena = new ExprValueArena(ExprValueFactory.DEFAULT_MAXIMUM_ENTRIES);
             var evidenceKeys = new ExprValueFactory()) {
            // Each source token creates at most one AST node, plus an implicit zero for unary minus.
            var projection = arena.project(root, 2L * sourceExpression.length());
            countOccurrences(root, projection, occurrenceCounts);
            collectRepeatedOccurrences(root, ExpressionOccurrencePath.root(), projection, occurrenceCounts,
                occurrencesByValue);

            return occurrencesByValue.values().stream().map(occurrences -> {
                // Only published groups need the historical, recursively encoded evidence key.
                var first = occurrences.getFirst();
                var position = new TreePosition(first.path().childIndexes(), first.expression());
                var syntax = projection.at(position).orElseThrow().syntax();
                return RepeatedStructureExtractionCandidate.create(normalizedSource,
                    evidenceKeys.fromExpr(syntax).key().toString(), occurrences, policy);
            }).sorted().toList();
        }
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
        Projection projection,
        Map<ValueId, Integer> occurrenceCounts
    ) {
        int astNodeCount = 1;
        List<Expr> children = children(expression);
        for (int index = 0; index < children.size(); index++) {
            astNodeCount = Math.addExact(
                astNodeCount,
                countOccurrences(
                    children.get(index),
                    projection,
                    occurrenceCounts
                )
            );
        }
        if (astNodeCount >= policy.minimumSubtreeNodes()) {
            ValueId value = projection.valueOf(expression);
            occurrenceCounts.merge(value, 1, Integer::sum);
        }
        return astNodeCount;
    }

    private int collectRepeatedOccurrences(
        Expr expression,
        ExpressionOccurrencePath path,
        Projection projection,
        Map<ValueId, Integer> occurrenceCounts,
        Map<ValueId, List<RepeatedStructureExtractionCandidate.Occurrence>>
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
                    projection,
                    occurrenceCounts,
                    occurrencesByValue
                )
            );
        }
        if (astNodeCount >= policy.minimumSubtreeNodes()) {
            ValueId value = projection.valueOf(expression);
            if (occurrenceCounts.getOrDefault(value, 0)
                >= policy.minimumOccurrences()) {
                occurrencesByValue.computeIfAbsent(
                    value, ignored -> new ArrayList<>()).add(
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
