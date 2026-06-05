package de.regelsuche.moves;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.CommonSubexpressionDiscoveryOperator;
import de.regelsuche.transform.CompleteSquareBridgeOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.SubstitutionIntroductionOperator;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private final MoveRealizer moveRealizer;
    private final MoveComparisonService comparisonService;
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionParser parser = new ExpressionParser();

    public MoveCandidateTransformationEngine() {
        this(
            defaultClassicEngine(),
            new Depth1MoveEnumerator(),
            new MoveRealizer(),
            new MoveComparisonService()
        );
    }

    public MoveCandidateTransformationEngine(TransformationEngine baseEngine, Depth1MoveEnumerator enumerator) {
        this(baseEngine, enumerator, new MoveRealizer(), new MoveComparisonService());
    }

    public MoveCandidateTransformationEngine(
        TransformationEngine baseEngine,
        Depth1MoveEnumerator enumerator,
        MoveRealizer moveRealizer,
        MoveComparisonService comparisonService
    ) {
        this.baseEngine = Objects.requireNonNull(baseEngine, "baseEngine");
        this.enumerator = Objects.requireNonNull(enumerator, "enumerator");
        this.moveRealizer = Objects.requireNonNull(moveRealizer, "moveRealizer");
        this.comparisonService = Objects.requireNonNull(comparisonService, "comparisonService");
    }

    public List<Transformation> classicCandidates(String expression) {
        List<Transformation> sorted = new ArrayList<>(baseEngine.transform(expression));
        sorted.sort(candidateOrdering());
        return List.copyOf(sorted);
    }

    public List<MoveBackedTransformation> moveCandidates(String expression) {
        return moveRealizer.realize(expression, enumerator.enumerate(expression));
    }

    public ComparisonReport compare(String expression) {
        return comparisonService.compare(
            expression,
            classicCandidates(expression),
            moveCandidates(expression),
            this::transformedKey
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

    String canonical(String expression) {
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

    private String transformedKey(String transformedExpression) {
        return canonical(transformedExpression);
    }

    private Comparator<Transformation> candidateOrdering() {
        return Comparator.comparing(Transformation::rule)
            .thenComparing(Transformation::transformedExpression)
            .thenComparing(Transformation::applicationKey);
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
