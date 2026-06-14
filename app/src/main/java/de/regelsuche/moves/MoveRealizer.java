package de.regelsuche.moves;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.CommonSubexpressionDiscoveryOperator;
import de.regelsuche.transform.CompleteSquareBridgeOperator;
import de.regelsuche.transform.QuadraticFactorizationHypothesisOperator;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.SubstitutionIntroductionOperator;
import de.regelsuche.transform.SubstitutionRewriteState;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Realizes finite depth-1 move candidates into concrete transformations. */
public final class MoveRealizer {
    private final CompleteSquareBridgeOperator completeSquareOperator = new CompleteSquareBridgeOperator();
    private final CommonSubexpressionDiscoveryOperator commonSubexpressionOperator =
        new CommonSubexpressionDiscoveryOperator();
    private final SubstitutionIntroductionOperator substitutionIntroductionOperator =
        new SubstitutionIntroductionOperator();
    private final QuadraticFactorizationHypothesisOperator factorCandidateOperator =
        new QuadraticFactorizationHypothesisOperator();
    private final ExpressionCanonicalizer canonicalizer;
    private final ExpressionParser parser;

    public MoveRealizer() {
        this(new ExpressionCanonicalizer(), new ExpressionParser());
    }

    public MoveRealizer(ExpressionCanonicalizer canonicalizer, ExpressionParser parser) {
        this.canonicalizer = canonicalizer == null ? new ExpressionCanonicalizer() : canonicalizer;
        this.parser = parser == null ? new ExpressionParser() : parser;
    }

    public List<MoveCandidateTransformationEngine.MoveBackedTransformation> realize(
        String expression,
        List<Depth1MoveEnumerator.CandidateMove> candidates
    ) {
        List<Depth1MoveEnumerator.CandidateMove> source = candidates == null ? List.of() : candidates;
        Map<String, MoveCandidateTransformationEngine.MoveBackedTransformation> distinct = new LinkedHashMap<>();
        addEquationCandidates(expression, source, distinct);
        addCompleteSquareCandidates(expression, source, distinct);
        addRepeatedSubexpressionCandidates(expression, source, distinct);
        addFactorCandidates(expression, source, distinct);
        List<MoveCandidateTransformationEngine.MoveBackedTransformation> ordered = new ArrayList<>(distinct.values());
        ordered.sort(Comparator.<MoveCandidateTransformationEngine.MoveBackedTransformation, MoveOrdinal>comparing(
                candidate -> candidate.move().ordinal(), MoveOrdinal.CANONICAL_ORDER)
            .thenComparing(candidate -> candidate.transformation().transformedExpression())
            .thenComparing(candidate -> candidate.move().moveId()));
        return List.copyOf(ordered);
    }

    private void addEquationCandidates(
        String expression,
        List<Depth1MoveEnumerator.CandidateMove> candidates,
        Map<String, MoveCandidateTransformationEngine.MoveBackedTransformation> out
    ) {
        Equation equation;
        try {
            equation = parser.parseEquation(expression);
        } catch (IllegalArgumentException exception) {
            return;
        }
        int occurrence = 0;
        for (Depth1MoveEnumerator.CandidateMove candidate : candidates) {
            if (!"cancellation-candidate".equals(candidate.enumeratorId())) {
                continue;
            }
            String value = candidate.parameter().value();
            if (value.isBlank()) {
                continue;
            }
            Expr operand = parseSignedOperand(value);
            BinaryOperator operator = value.startsWith("-") ? BinaryOperator.SUB : BinaryOperator.ADD;
            Equation transformedEquation = new Equation(
                new BinaryExpr(equation.left(), operator, operand),
                new BinaryExpr(equation.right(), operator, operand)
            );
            String transformed = ExpressionFormatter.format(transformedEquation);
            String ruleId = operator == BinaryOperator.ADD
                ? "equation_add_both_sides"
                : "equation_subtract_both_sides";
            RewriteMove move = buildMove(
                RewriteMoveKind.ADD_SAME_TERM_BOTH_SIDES,
                ruleId,
                "cancellation-candidate",
                expression,
                transformed,
                List.of(candidate.parameter()),
                occurrence++,
                List.of()
            );
            Transformation transformation = instrument(
                move,
                new Transformation(
                    ruleId,
                    transformed,
                    RewriteKind.SIMPLIFY,
                    true,
                    1,
                    true,
                    ruleId + "|" + transformed,
                    List.of()
                )
            );
            out.putIfAbsent(move.moveId(), new MoveCandidateTransformationEngine.MoveBackedTransformation(move, transformation));
        }
    }

    private void addCompleteSquareCandidates(
        String expression,
        List<Depth1MoveEnumerator.CandidateMove> candidates,
        Map<String, MoveCandidateTransformationEngine.MoveBackedTransformation> out
    ) {
        List<MoveParameter> parameters = candidates.stream()
            .filter(candidate -> "complete-square".equals(candidate.enumeratorId()))
            .map(Depth1MoveEnumerator.CandidateMove::parameter)
            .sorted(MoveParameter.CANONICAL_ORDER)
            .toList();
        if (parameters.size() < 2) {
            return;
        }
        String residue = parameters.stream()
            .filter(parameter -> parameter.name().equals("residue"))
            .map(MoveParameter::value)
            .findFirst()
            .orElse("");
        Transformation selected = completeSquareOperator.generateCandidates(expression).stream()
            .sorted(Comparator
                .comparing((Transformation transformation) -> !matchesResidueLiteral(transformation.transformedExpression(), residue))
                .thenComparing(Transformation::transformedExpression))
            .findFirst()
            .orElse(null);
        if (selected == null) {
            return;
        }
        RewriteMove move = buildMove(
            RewriteMoveKind.COMPLETE_SQUARE,
            selected.rule(),
            "complete_square_bridge",
            expression,
            selected.transformedExpression(),
            parameters,
            0,
            selected.assumptions()
        );
        out.putIfAbsent(
            move.moveId(),
            new MoveCandidateTransformationEngine.MoveBackedTransformation(move, instrument(move, selected))
        );
    }

    private void addRepeatedSubexpressionCandidates(
        String expression,
        List<Depth1MoveEnumerator.CandidateMove> candidates,
        Map<String, MoveCandidateTransformationEngine.MoveBackedTransformation> out
    ) {
        int occurrence = 0;
        for (Depth1MoveEnumerator.CandidateMove candidate : candidates) {
            if (!"repeated-subexpression".equals(candidate.enumeratorId())) {
                continue;
            }
            Transformation selected = selectCommonSubexpressionTransformation(expression, candidate.parameter().value())
                .orElse(null);
            if (selected == null) {
                continue;
            }
            RewriteMove move = buildMove(
                RewriteMoveKind.COMMON_SUBEXPRESSION,
                selected.rule(),
                operatorIdForRule(selected.rule()),
                expression,
                selected.transformedExpression(),
                List.of(candidate.parameter()),
                occurrence++,
                selected.assumptions()
            );
            out.putIfAbsent(
                move.moveId(),
                new MoveCandidateTransformationEngine.MoveBackedTransformation(move, instrument(move, selected))
            );
        }
    }

    private void addFactorCandidates(
        String expression,
        List<Depth1MoveEnumerator.CandidateMove> candidates,
        Map<String, MoveCandidateTransformationEngine.MoveBackedTransformation> out
    ) {
        List<Depth1MoveEnumerator.CandidateMove> factorMoveCandidates = candidates.stream()
            .filter(candidate -> "factor-candidate".equals(candidate.enumeratorId()))
            .toList();
        if (factorMoveCandidates.isEmpty()) {
            return;
        }
        Map<String, Transformation> byTransformedExpression = new LinkedHashMap<>();
        List<Transformation> factorTransformations = new ArrayList<>(factorCandidateOperator.generateCandidates(expression));
        factorTransformations.sort(Comparator.comparing(Transformation::rule)
            .thenComparing(Transformation::transformedExpression)
            .thenComparing(Transformation::applicationKey));
        for (Transformation transformation : factorTransformations) {
            byTransformedExpression.putIfAbsent(transformation.transformedExpression(), transformation);
        }
        int occurrence = 0;
        for (Depth1MoveEnumerator.CandidateMove candidate : factorMoveCandidates) {
            Transformation selected = byTransformedExpression.get(candidate.parameter().value());
            if (selected == null) {
                continue;
            }
            RewriteMove move = buildMove(
                RewriteMoveKind.FACTOR,
                selected.rule(),
                selected.rule(),
                expression,
                selected.transformedExpression(),
                List.of(candidate.parameter()),
                occurrence++,
                selected.assumptions()
            );
            out.putIfAbsent(
                move.moveId(),
                new MoveCandidateTransformationEngine.MoveBackedTransformation(move, instrument(move, selected))
            );
        }
    }

    private Optional<Transformation> selectCommonSubexpressionTransformation(String expression, String parameterValue) {
        SubstitutionRewriteState.clear();
        Optional<Transformation> factored = commonSubexpressionOperator.generateCandidates(expression).stream()
            .filter(candidate -> candidate.transformedExpression().contains(parameterValue))
            .findFirst();
        if (factored.isPresent()) {
            return factored;
        }
        SubstitutionRewriteState.clear();
        return substitutionIntroductionOperator.generateCandidates(expression).stream()
            .filter(candidate -> candidate.assumptions().stream()
                .anyMatch(assumption -> assumption.startsWith("substitution.placeholder.")
                    && assumption.endsWith("=" + parameterValue)))
            .findFirst();
    }

    private RewriteMove buildMove(
        RewriteMoveKind kind,
        String ruleId,
        String operatorId,
        String beforeExpression,
        String afterExpression,
        List<MoveParameter> parameters,
        int occurrence,
        List<String> assumptions
    ) {
        MoveOrdinal ordinal = MoveOrdinal.of(kind, occurrence, parameters);
        String canonicalBefore = canonical(beforeExpression);
        String canonicalAfter = canonical(afterExpression);
        return RewriteMove.builder(kind)
            .moveId(moveId(kind, ruleId, operatorId, canonicalBefore, canonicalAfter, ordinal))
            .ruleId(ruleId)
            .operatorId(operatorId)
            .sourceExpression(beforeExpression)
            .targetExpression(afterExpression)
            .canonicalBefore(canonicalBefore)
            .canonicalAfter(canonicalAfter)
            .ordinal(ordinal)
            .parameters(parameters)
            .assumptions(assumptions)
            .tags(List.of("depth1-move-enumeration"))
            .build();
    }

    private Transformation instrument(RewriteMove move, Transformation base) {
        LinkedHashSet<String> assumptions = new LinkedHashSet<>(base.assumptions());
        assumptions.add(MoveCandidateTransformationEngine.MOVE_SOURCE + "=depth1-enumerator");
        assumptions.add(MoveCandidateTransformationEngine.MOVE_RULE_ID + "=" + move.ruleId());
        assumptions.add(MoveCandidateTransformationEngine.MOVE_OPERATOR_ID + "=" + move.operatorId());
        assumptions.add(MoveCandidateTransformationEngine.MOVE_ID + "=" + move.moveId());
        assumptions.add(MoveCandidateTransformationEngine.MOVE_ORDINAL + "=" + ordinalText(move.ordinal()));
        for (MoveParameter parameter : move.parameters()) {
            assumptions.add(MoveCandidateTransformationEngine.MOVE_PARAMETER_PREFIX + parameter.name() + "=" + parameter.value());
        }
        return new Transformation(
            base.rule(),
            base.transformedExpression(),
            base.kind(),
            base.mayIncreaseComplexity(),
            base.estimatedCostDelta(),
            base.equivalencePreservingByConstruction(),
            base.applicationKey() + "|moveId=" + move.moveId(),
            new ArrayList<>(assumptions),
            base.packId(),
            base.license()
        );
    }

    private Expr parseSignedOperand(String value) {
        String trimmed = value.trim();
        String normalized = trimmed.startsWith("+") ? trimmed.substring(1) : trimmed.startsWith("-")
            ? trimmed.substring(1)
            : trimmed;
        return parser.parse(new InputRequest(InputType.TERM, normalized)).terms().getFirst();
    }

    private String canonical(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        try {
            return canonicalizer.canonicalize(expression);
        } catch (RuntimeException exception) {
            if (expression.contains("=")) {
                try {
                    return ExpressionFormatter.format(parser.parseEquation(expression));
                } catch (RuntimeException ignored) {
                    // fall through to whitespace normalization
                }
            }
            return expression.trim().replaceAll("\\s+", " ");
        }
    }

    private String moveId(
        RewriteMoveKind kind,
        String ruleId,
        String operatorId,
        String canonicalBefore,
        String canonicalAfter,
        MoveOrdinal ordinal
    ) {
        return kind.name()
            + "|" + ruleId
            + "|" + operatorId
            + "|" + canonicalBefore
            + "=>" + canonicalAfter
            + "|" + ordinalText(ordinal);
    }

    private String operatorIdForRule(String ruleId) {
        return switch (ruleId) {
            case CompleteSquareBridgeOperator.RULE_ID -> "complete_square_bridge";
            case CommonSubexpressionDiscoveryOperator.RULE_ID -> "common_subexpression_discovery";
            case SubstitutionIntroductionOperator.RULE_ID -> "substitution_introduction";
            default -> ruleId;
        };
    }

    private boolean matchesResidueLiteral(String transformedExpression, String residue) {
        if (residue == null || residue.isBlank()) {
            return true;
        }
        String normalized = residue.startsWith("+") ? residue.substring(1) : residue;
        if (normalized.startsWith("-")) {
            return transformedExpression.contains("- " + normalized.substring(1));
        }
        return transformedExpression.contains("+ " + normalized)
            || transformedExpression.endsWith(normalized)
            || transformedExpression.contains(" " + normalized + " ");
    }

    private static String ordinalText(MoveOrdinal ordinal) {
        Objects.requireNonNull(ordinal, "ordinal");
        return ordinal.ruleOrdinal() + ":" + ordinal.occurrenceOrdinal() + ":" + ordinal.parameterOrdinals();
    }
}
