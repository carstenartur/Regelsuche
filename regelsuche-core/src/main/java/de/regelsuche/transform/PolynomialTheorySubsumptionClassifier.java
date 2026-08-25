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
 * Classifies one observed polynomial identity against the configured exact
 * factorization theory.
 */
public final class PolynomialTheorySubsumptionClassifier {
    private final PolynomialDecompositionSynthesisOperator factorization;
    private final ExpressionCanonicalizer canonicalizer;
    private final ExpressionParser parser;

    public PolynomialTheorySubsumptionClassifier() {
        this(
            new PolynomialDecompositionSynthesisOperator(
                Integer.MAX_VALUE),
            new ExpressionCanonicalizer(),
            new ExpressionParser());
    }

    PolynomialTheorySubsumptionClassifier(
        PolynomialDecompositionSynthesisOperator factorization,
        ExpressionCanonicalizer canonicalizer,
        ExpressionParser parser
    ) {
        this.factorization = Objects.requireNonNull(
            factorization,
            "factorization");
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

        ExpressionFactorizationReport report =
            factorization.factorExpression(leftExpression);
        if (!report.generated()) {
            return failure(report);
        }

        try (ExprValueFactory values = new ExprValueFactory()) {
            ExprValueFactory.ExprValue targetValue =
                values.fromExpr(target);
            for (ExpressionFactorizationReport.RenderedFactorization candidate
                    : report.candidates()) {
                Expr candidateExpression;
                try {
                    candidateExpression = parseCanonical(
                        candidate.transformedExpression());
                } catch (IllegalArgumentException exception) {
                    return Classification.failure(
                        Status.TECHNICAL_FAILURE,
                        "FACTORIZATION_RENDER_CANNOT_BE_PARSED",
                        report.arithmeticSteps());
                }
                if (targetValue.sameValue(
                        values.fromExpr(candidateExpression))) {
                    return Classification.subsumed(
                        canonicalSource,
                        candidate,
                        report.arithmeticSteps());
                }
            }
        }

        return Classification.failure(
            Status.NOT_SUBSUMED,
            "TARGET_NOT_GENERATED_WITHIN_BOUND",
            report.arithmeticSteps());
    }

    private Classification failure(
        ExpressionFactorizationReport report
    ) {
        Status status = switch (report.status()) {
            case BUDGET_INCONCLUSIVE -> Status.BUDGET_INCONCLUSIVE;
            case NO_FACTORIZATION_FOUND, IRREDUCIBLE ->
                Status.NOT_SUBSUMED;
            case PARSE_ERROR,
                UNSUPPORTED_SEMANTIC_VIEW,
                UNSUPPORTED_FACTORIZATION_REQUEST -> Status.UNSUPPORTED;
            case TECHNICAL_FAILURE -> Status.TECHNICAL_FAILURE;
            case GENERATED -> throw new IllegalStateException(
                "generated report was handled as a failure");
        };
        return Classification.failure(
            status,
            report.detailCode(),
            report.arithmeticSteps());
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
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }

    /** Project-inventory novelty requires a separate inventory snapshot. */
    public enum ProjectInventoryNovelty {
        NOT_EVALUATED
    }

    public enum RetentionDisposition {
        DERIVED_MACRO_CACHE_ONLY,
        NONE
    }

    /** Classifier-issued immutable theory evidence. */
    public static final class Classification {
        private final State state;

        private Classification(
            Status status,
            String detailCode,
            String theoryMethodId,
            String sourceExpression,
            String certificateHash,
            String derivedExpression,
            String applicationKey,
            long arithmeticSteps,
            ProjectInventoryNovelty projectInventoryNovelty,
            RetentionDisposition retentionDisposition
        ) {
            Status checkedStatus = Objects.requireNonNull(
                status,
                "status");
            if (detailCode == null
                    || detailCode.isBlank()
                    || !PolynomialDecompositionSynthesisOperator.METHOD_ID
                        .equals(theoryMethodId)
                    || sourceExpression == null
                    || certificateHash == null
                    || derivedExpression == null
                    || applicationKey == null
                    || arithmeticSteps < 0
                    || projectInventoryNovelty == null
                    || retentionDisposition == null) {
                throw new IllegalArgumentException(
                    "polynomial theory classification is invalid");
            }
            if (checkedStatus == Status.THEORY_SUBSUMED) {
                if (sourceExpression.isBlank()
                        || !certificateHash.matches(
                            "sha256:[0-9a-f]{64}")
                        || derivedExpression.isBlank()
                        || applicationKey.isBlank()
                        || retentionDisposition
                            != RetentionDisposition
                                .DERIVED_MACRO_CACHE_ONLY) {
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
            state = new State(
                checkedStatus,
                detailCode,
                theoryMethodId,
                sourceExpression,
                certificateHash,
                derivedExpression,
                applicationKey,
                arithmeticSteps,
                projectInventoryNovelty,
                retentionDisposition);
        }

        private static Classification subsumed(
            String sourceExpression,
            ExpressionFactorizationReport.RenderedFactorization candidate,
            long arithmeticSteps
        ) {
            return new Classification(
                Status.THEORY_SUBSUMED,
                "TARGET_MATCHES_GENERATED_FACTORIZATION",
                PolynomialDecompositionSynthesisOperator.METHOD_ID,
                sourceExpression,
                candidate.factorization().certificateHash(),
                candidate.transformedExpression(),
                candidate.applicationKey(),
                arithmeticSteps,
                ProjectInventoryNovelty.NOT_EVALUATED,
                RetentionDisposition.DERIVED_MACRO_CACHE_ONLY);
        }

        private static Classification failure(
            Status status,
            String detailCode,
            long arithmeticSteps
        ) {
            return new Classification(
                status,
                detailCode,
                PolynomialDecompositionSynthesisOperator.METHOD_ID,
                "",
                "",
                "",
                "",
                arithmeticSteps,
                ProjectInventoryNovelty.NOT_EVALUATED,
                RetentionDisposition.NONE);
        }

        public Status status() {
            return state.status();
        }

        public String detailCode() {
            return state.detailCode();
        }

        public String theoryMethodId() {
            return state.theoryMethodId();
        }

        public String sourceExpression() {
            return state.sourceExpression();
        }

        public String certificateHash() {
            return state.certificateHash();
        }

        public String derivedExpression() {
            return state.derivedExpression();
        }

        public String applicationKey() {
            return state.applicationKey();
        }

        public long arithmeticSteps() {
            return state.arithmeticSteps();
        }

        public ProjectInventoryNovelty projectInventoryNovelty() {
            return state.projectInventoryNovelty();
        }

        public RetentionDisposition retentionDisposition() {
            return state.retentionDisposition();
        }

        public boolean subsumed() {
            return status() == Status.THEORY_SUBSUMED;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                || other instanceof Classification classification
                    && state.equals(classification.state);
        }

        @Override
        public int hashCode() {
            return state.hashCode();
        }

        @Override
        public String toString() {
            return "Classification[" + state + "]";
        }

        private record State(
            Status status,
            String detailCode,
            String theoryMethodId,
            String sourceExpression,
            String certificateHash,
            String derivedExpression,
            String applicationKey,
            long arithmeticSteps,
            ProjectInventoryNovelty projectInventoryNovelty,
            RetentionDisposition retentionDisposition
        ) {
        }
    }
}
