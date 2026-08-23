package de.regelsuche.transform;

import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.value.ExprValueFactory;
import java.util.Objects;

/**
 * Classifies an observed polynomial identity against the exact bounded
 * decomposition theory implemented by
 * {@link PolynomialDecompositionSynthesisOperator}.
 *
 * <p>A positive result means that the right-hand side is one of the exact
 * decompositions generated from the left-hand side under the same finite
 * theory budget. It therefore describes a theory-derived instance, not a new
 * kernel rule or an externally novel mathematical statement. Project-inventory
 * novelty is deliberately left unevaluated.</p>
 */
public final class PolynomialTheorySubsumptionClassifier {
    private final PolynomialDecompositionSynthesisOperator synthesizer;
    private final ExpressionCanonicalizer canonicalizer;
    private final ExpressionParser parser;

    public PolynomialTheorySubsumptionClassifier() {
        this(
            new PolynomialDecompositionSynthesisOperator(Integer.MAX_VALUE),
            new ExpressionCanonicalizer(),
            new ExpressionParser());
    }

    PolynomialTheorySubsumptionClassifier(
        PolynomialDecompositionSynthesisOperator synthesizer,
        ExpressionCanonicalizer canonicalizer,
        ExpressionParser parser
    ) {
        this.synthesizer = Objects.requireNonNull(
            synthesizer,
            "synthesizer");
        this.canonicalizer = Objects.requireNonNull(
            canonicalizer,
            "canonicalizer");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public Classification classify(
        String leftExpression,
        String rightExpression
    ) {
        if (isBlank(leftExpression) || isBlank(rightExpression)) {
            return Classification.failure(
                Status.UNSUPPORTED,
                "EXPRESSION_BLANK",
                0);
        }

        String canonicalSource;
        Expr target;
        try {
            canonicalSource = formatCanonical(leftExpression);
            target = parseCanonical(rightExpression);
        } catch (IllegalArgumentException exception) {
            return Classification.failure(
                Status.UNSUPPORTED,
                "EXPRESSION_PARSE_OR_CANONICALIZATION_FAILED",
                0);
        }

        PolynomialDecompositionSynthesisOperator.SynthesisReport report =
            synthesizer.synthesize(leftExpression);
        if (!report.generated()) {
            return switch (report.status()) {
                case BUDGET_EXCEEDED, CANDIDATE_BUDGET_ZERO ->
                    Classification.failure(
                        Status.BUDGET_INCONCLUSIVE,
                        report.detailCode(),
                        report.consideredConfigurations());
                case NO_INTEGER_QUADRATIC_FACTORIZATION ->
                    Classification.failure(
                        Status.NOT_SUBSUMED,
                        report.detailCode(),
                        report.consideredConfigurations());
                case PARSE_ERROR,
                    UNSUPPORTED_SEMANTIC_VIEW,
                    NOT_BINARY_HOMOGENEOUS_QUARTIC ->
                    Classification.failure(
                        Status.UNSUPPORTED,
                        report.detailCode(),
                        report.consideredConfigurations());
                case GENERATED -> throw new IllegalStateException(
                    "generated report was handled as a failure");
            };
        }

        try (ExprValueFactory values = new ExprValueFactory()) {
            ExprValueFactory.ExprValue targetValue = values.fromExpr(target);
            for (PolynomialDecompositionSynthesisOperator.Candidate candidate
                    : report.candidates()) {
                Expr candidateExpression;
                try {
                    candidateExpression = parseCanonical(
                        candidate.transformedExpression());
                } catch (IllegalArgumentException exception) {
                    continue;
                }
                if (targetValue.sameValue(
                        values.fromExpr(candidateExpression))) {
                    return Classification.subsumed(
                        canonicalSource,
                        candidate,
                        report.consideredConfigurations());
                }
            }
        }

        return Classification.failure(
            Status.NOT_SUBSUMED,
            "TARGET_NOT_GENERATED_WITHIN_BOUND",
            report.consideredConfigurations());
    }

    private String formatCanonical(String expression) {
        return ExpressionFormatter.format(parseCanonical(expression));
    }

    private Expr parseCanonical(String expression) {
        String canonical = canonicalizer.canonicalize(expression);
        return parser.parse(new InputRequest(InputType.TERM, canonical))
            .terms()
            .getFirst();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum Status {
        THEORY_SUBSUMED,
        NOT_SUBSUMED,
        UNSUPPORTED,
        BUDGET_INCONCLUSIVE
    }

    /**
     * Project-inventory novelty needs an inventory snapshot and is intentionally
     * not inferred from semantic theory classification alone.
     */
    public enum ProjectInventoryNovelty {
        NOT_EVALUATED
    }

    public enum RetentionDisposition {
        DERIVED_MACRO_CACHE_ONLY,
        NONE
    }

    public record Classification(
        Status status,
        String detailCode,
        String theoryMethodId,
        String sourceExpression,
        String certificateHash,
        String derivedExpression,
        String applicationKey,
        int consideredConfigurations,
        ProjectInventoryNovelty projectInventoryNovelty,
        RetentionDisposition retentionDisposition
    ) {
        public Classification {
            Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()
                    || !PolynomialDecompositionSynthesisOperator.METHOD_ID.equals(
                        theoryMethodId)
                    || sourceExpression == null
                    || certificateHash == null
                    || derivedExpression == null
                    || applicationKey == null
                    || consideredConfigurations < 0
                    || projectInventoryNovelty == null
                    || retentionDisposition == null) {
                throw new IllegalArgumentException(
                    "polynomial theory classification is invalid");
            }
            if (status == Status.THEORY_SUBSUMED) {
                if (sourceExpression.isBlank()
                        || !certificateHash.matches("sha256:[0-9a-f]{64}")
                        || derivedExpression.isBlank()
                        || applicationKey.isBlank()
                        || retentionDisposition
                            != RetentionDisposition.DERIVED_MACRO_CACHE_ONLY) {
                    throw new IllegalArgumentException(
                        "subsumed classification lacks exact theory evidence");
                }
            } else if (!sourceExpression.isEmpty()
                    || !certificateHash.isEmpty()
                    || !derivedExpression.isEmpty()
                    || !applicationKey.isEmpty()
                    || retentionDisposition != RetentionDisposition.NONE) {
                throw new IllegalArgumentException(
                    "non-subsumed classification must not expose a cache candidate");
            }
        }

        private static Classification subsumed(
            String sourceExpression,
            PolynomialDecompositionSynthesisOperator.Candidate candidate,
            int consideredConfigurations
        ) {
            return new Classification(
                Status.THEORY_SUBSUMED,
                "TARGET_MATCHES_GENERATED_DECOMPOSITION",
                PolynomialDecompositionSynthesisOperator.METHOD_ID,
                sourceExpression,
                candidate.certificateHash(),
                candidate.transformedExpression(),
                candidate.applicationKey(),
                consideredConfigurations,
                ProjectInventoryNovelty.NOT_EVALUATED,
                RetentionDisposition.DERIVED_MACRO_CACHE_ONLY);
        }

        private static Classification failure(
            Status status,
            String detailCode,
            int consideredConfigurations
        ) {
            return new Classification(
                status,
                detailCode,
                PolynomialDecompositionSynthesisOperator.METHOD_ID,
                "",
                "",
                "",
                "",
                consideredConfigurations,
                ProjectInventoryNovelty.NOT_EVALUATED,
                RetentionDisposition.NONE);
        }

        public boolean subsumed() {
            return status == Status.THEORY_SUBSUMED;
        }
    }
}
