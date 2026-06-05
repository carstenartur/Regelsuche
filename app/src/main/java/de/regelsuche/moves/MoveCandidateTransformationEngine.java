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
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.CommonSubexpressionDiscoveryOperator;
import de.regelsuche.transform.CompleteSquareBridgeOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.SubstitutionRewriteState;
import de.regelsuche.transform.SubstitutionIntroductionOperator;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bridges explicit depth-1 move enumeration into the existing search
 * infrastructure by turning enumerated {@link RewriteMove}s into ordinary
 * {@link Transformation}s.
 *
 * <p>The adapter keeps the legacy engine intact: {@link #transform(String)}
 * returns classic candidates plus move-enumerator-only candidates, while
 * {@link #compare(String)} exposes the full overlap report.</p>
 */
public final class MoveCandidateTransformationEngine implements TransformationEngine {
    public static final String MOVE_RULE_ID = "move.ruleId";
    public static final String MOVE_OPERATOR_ID = "move.operatorId";
    public static final String MOVE_ID = "move.moveId";
    public static final String MOVE_ORDINAL = "move.ordinal";
    public static final String MOVE_SOURCE = "move.source";
    public static final String MOVE_PARAMETER_PREFIX = "move.parameter.";

    private final TransformationEngine baseEngine;
    private final Depth1MoveEnumerator enumerator;
    private final CompleteSquareBridgeOperator completeSquareOperator = new CompleteSquareBridgeOperator();
    private final CommonSubexpressionDiscoveryOperator commonSubexpressionOperator =
        new CommonSubexpressionDiscoveryOperator();
    private final SubstitutionIntroductionOperator substitutionIntroductionOperator =
        new SubstitutionIntroductionOperator();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionParser parser = new ExpressionParser();

    public MoveCandidateTransformationEngine() {
        this(defaultClassicEngine(), new Depth1MoveEnumerator());
    }

    public MoveCandidateTransformationEngine(TransformationEngine baseEngine, Depth1MoveEnumerator enumerator) {
        this.baseEngine = Objects.requireNonNull(baseEngine, "baseEngine");
        this.enumerator = Objects.requireNonNull(enumerator, "enumerator");
    }

    public List<Transformation> classicCandidates(String expression) {
        SubstitutionRewriteState.clear();
        List<Transformation> sorted = new ArrayList<>(baseEngine.transform(expression));
        sorted.sort(candidateOrdering());
        return List.copyOf(sorted);
    }

    public List<MoveBackedTransformation> moveCandidates(String expression) {
        List<Depth1MoveEnumerator.CandidateMove> enumerated = enumerator.enumerate(expression);
        Map<String, MoveBackedTransformation> distinct = new LinkedHashMap<>();
        addEquationCandidates(expression, enumerated, distinct);
        addCompleteSquareCandidates(expression, enumerated, distinct);
        addRepeatedSubexpressionCandidates(expression, enumerated, distinct);
        List<MoveBackedTransformation> ordered = new ArrayList<>(distinct.values());
        ordered.sort(Comparator.<MoveBackedTransformation, MoveOrdinal>comparing(
                candidate -> candidate.move().ordinal(), MoveOrdinal.CANONICAL_ORDER)
            .thenComparing(candidate -> candidate.transformation().transformedExpression())
            .thenComparing(candidate -> candidate.move().moveId()));
        return List.copyOf(ordered);
    }

    public ComparisonReport compare(String expression) {
        List<Transformation> classic = classicCandidates(expression);
        List<MoveBackedTransformation> moveCandidates = moveCandidates(expression);

        Map<String, CandidateSummary> classicByExpression = new LinkedHashMap<>();
        for (Transformation transformation : classic) {
            classicByExpression.putIfAbsent(
                transformedKey(transformation.transformedExpression()),
                CandidateSummary.fromClassic(transformation)
            );
        }

        Map<String, CandidateSummary> moveByExpression = new LinkedHashMap<>();
        for (MoveBackedTransformation candidate : moveCandidates) {
            moveByExpression.putIfAbsent(
                transformedKey(candidate.transformation().transformedExpression()),
                CandidateSummary.fromMove(candidate)
            );
        }

        List<CandidateSummary> overlaps = new ArrayList<>();
        List<CandidateSummary> moveOnly = new ArrayList<>();
        for (Map.Entry<String, CandidateSummary> entry : moveByExpression.entrySet()) {
            if (classicByExpression.containsKey(entry.getKey())) {
                overlaps.add(entry.getValue());
            } else {
                moveOnly.add(entry.getValue());
            }
        }

        List<CandidateSummary> classicOnly = new ArrayList<>();
        for (Map.Entry<String, CandidateSummary> entry : classicByExpression.entrySet()) {
            if (!moveByExpression.containsKey(entry.getKey())) {
                classicOnly.add(entry.getValue());
            }
        }

        return new ComparisonReport(
            expression,
            new ArrayList<>(classicByExpression.values()),
            new ArrayList<>(moveByExpression.values()),
            overlaps,
            moveOnly,
            classicOnly
        );
    }

    @Override
    public List<Transformation> transform(String expression) {
        List<Transformation> classic = classicCandidates(expression);
        Map<String, Transformation> combined = new LinkedHashMap<>();
        for (Transformation transformation : classic) {
            combined.putIfAbsent(transformedKey(transformation.transformedExpression()), transformation);
        }
        for (MoveBackedTransformation candidate : moveCandidates(expression)) {
            combined.putIfAbsent(
                transformedKey(candidate.transformation().transformedExpression()),
                candidate.transformation()
            );
        }
        List<Transformation> ordered = new ArrayList<>(combined.values());
        ordered.sort(candidateOrdering());
        return List.copyOf(ordered);
    }

    public static Optional<MoveMetadata> metadataOf(Transformation transformation) {
        if (transformation == null) {
            return Optional.empty();
        }
        String ruleId = "";
        String operatorId = "";
        String moveId = "";
        String ordinal = "";
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        for (String assumption : transformation.assumptions()) {
            if (assumption.startsWith(MOVE_RULE_ID + "=")) {
                ruleId = valueAfterEquals(assumption);
            } else if (assumption.startsWith(MOVE_OPERATOR_ID + "=")) {
                operatorId = valueAfterEquals(assumption);
            } else if (assumption.startsWith(MOVE_ID + "=")) {
                moveId = valueAfterEquals(assumption);
            } else if (assumption.startsWith(MOVE_ORDINAL + "=")) {
                ordinal = valueAfterEquals(assumption);
            } else if (assumption.startsWith(MOVE_PARAMETER_PREFIX)) {
                int separator = assumption.indexOf('=');
                if (separator > 0) {
                    parameters.put(
                        assumption.substring(MOVE_PARAMETER_PREFIX.length(), separator),
                        assumption.substring(separator + 1)
                    );
                }
            }
        }
        if (moveId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new MoveMetadata(ruleId, operatorId, moveId, ordinal, parameters));
    }

    public static TransformationEngine defaultClassicEngine() {
        return new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(AstRewriteTransformationEngine.defaultRules(), 128, 160),
            List.of(
                new CompleteSquareBridgeOperator(),
                new CommonSubexpressionDiscoveryOperator(),
                new SubstitutionIntroductionOperator()
            ),
            16
        );
    }

    private void addEquationCandidates(
        String expression,
        List<Depth1MoveEnumerator.CandidateMove> candidates,
        Map<String, MoveBackedTransformation> out
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
            out.putIfAbsent(moveKey(move), new MoveBackedTransformation(move, transformation));
        }
    }

    private void addCompleteSquareCandidates(
        String expression,
        List<Depth1MoveEnumerator.CandidateMove> candidates,
        Map<String, MoveBackedTransformation> out
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
        out.putIfAbsent(moveKey(move), new MoveBackedTransformation(move, instrument(move, selected)));
    }

    private void addRepeatedSubexpressionCandidates(
        String expression,
        List<Depth1MoveEnumerator.CandidateMove> candidates,
        Map<String, MoveBackedTransformation> out
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
            out.putIfAbsent(moveKey(move), new MoveBackedTransformation(move, instrument(move, selected)));
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
        assumptions.add(MOVE_SOURCE + "=depth1-enumerator");
        assumptions.add(MOVE_RULE_ID + "=" + move.ruleId());
        assumptions.add(MOVE_OPERATOR_ID + "=" + move.operatorId());
        assumptions.add(MOVE_ID + "=" + move.moveId());
        assumptions.add(MOVE_ORDINAL + "=" + ordinalText(move.ordinal()));
        for (MoveParameter parameter : move.parameters()) {
            assumptions.add(MOVE_PARAMETER_PREFIX + parameter.name() + "=" + parameter.value());
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
            return expression.trim().replaceAll("\\s+", " ");
        }
    }

    private String transformedKey(String transformedExpression) {
        return canonical(transformedExpression);
    }

    private Comparator<Transformation> candidateOrdering() {
        return Comparator.comparing(Transformation::rule)
            .thenComparing(Transformation::transformedExpression)
            .thenComparing(Transformation::applicationKey);
    }

    private String moveKey(RewriteMove move) {
        return move.moveId();
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
        return ordinal.ruleOrdinal() + ":" + ordinal.occurrenceOrdinal() + ":" + ordinal.parameterOrdinals();
    }

    private static String valueAfterEquals(String assumption) {
        int index = assumption.indexOf('=');
        return index < 0 ? "" : assumption.substring(index + 1);
    }

    public record MoveBackedTransformation(RewriteMove move, Transformation transformation) {
    }

    public record MoveMetadata(
        String ruleId,
        String operatorId,
        String moveId,
        String ordinal,
        Map<String, String> parameters
    ) {
        public MoveMetadata {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    public record CandidateSummary(
        String transformedExpression,
        String ruleId,
        String operatorId,
        String moveId,
        String ordinal,
        Map<String, String> parameters
    ) {
        public CandidateSummary {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }

        static CandidateSummary fromClassic(Transformation transformation) {
            return metadataOf(transformation)
                .map(metadata -> new CandidateSummary(
                    transformation.transformedExpression(),
                    metadata.ruleId(),
                    metadata.operatorId(),
                    metadata.moveId(),
                    metadata.ordinal(),
                    metadata.parameters()
                ))
                .orElseGet(() -> new CandidateSummary(
                    transformation.transformedExpression(),
                    transformation.rule(),
                    "",
                    "",
                    "",
                    Map.of()
                ));
        }

        static CandidateSummary fromMove(MoveBackedTransformation moveBackedTransformation) {
            RewriteMove move = moveBackedTransformation.move();
            Map<String, String> parameters = new LinkedHashMap<>();
            for (MoveParameter parameter : move.parameters()) {
                parameters.put(parameter.name(), parameter.value());
            }
            return new CandidateSummary(
                moveBackedTransformation.transformation().transformedExpression(),
                move.ruleId(),
                move.operatorId(),
                move.moveId(),
                ordinalText(move.ordinal()),
                parameters
            );
        }
    }

    public record ComparisonReport(
        String expression,
        List<CandidateSummary> classicCandidates,
        List<CandidateSummary> moveCandidates,
        List<CandidateSummary> overlaps,
        List<CandidateSummary> moveOnlyCandidates,
        List<CandidateSummary> classicOnlyCandidates
    ) {
        public ComparisonReport {
            classicCandidates = classicCandidates == null ? List.of() : List.copyOf(classicCandidates);
            moveCandidates = moveCandidates == null ? List.of() : List.copyOf(moveCandidates);
            overlaps = overlaps == null ? List.of() : List.copyOf(overlaps);
            moveOnlyCandidates = moveOnlyCandidates == null ? List.of() : List.copyOf(moveOnlyCandidates);
            classicOnlyCandidates = classicOnlyCandidates == null ? List.of() : List.copyOf(classicOnlyCandidates);
        }
    }
}
