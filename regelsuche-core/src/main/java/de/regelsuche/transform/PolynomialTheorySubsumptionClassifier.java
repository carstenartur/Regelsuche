package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactFactorizationTransformationPipeline;
import de.regelsuche.polynomial.ExactParsedFactorizationPipeline;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Classifies one observed polynomial identity against one explicitly selected
 * exact factorization engine.
 *
 * <p>The classifier consumes only parser-issued exact literal evidence and the
 * parser-to-request-to-engine-to-verifier-to-reparse transformation authority.
 * It never converts a coefficient through binary floating point and it does
 * not select a hidden best engine. Representation comparison is deliberately
 * narrower than general algebraic equivalence: exact scalar literals are
 * normalized and addition/multiplication are compared modulo associativity and
 * commutativity, while subtraction, division, powers and functions remain
 * ordered.</p>
 */
public final class PolynomialTheorySubsumptionClassifier {
    public static final String THEORY_METHOD_ID =
        ExactFactorizationTransformationPipeline.TRANSFORMATION_ID;
    private static final int DEFAULT_MAX_CANDIDATE_TRANSFORMATIONS = 256;
    private static final int DEFAULT_MAX_CANONICAL_NODES = 4_096;
    private static final int MAX_CANONICAL_SCALAR_BITS = 8_192;

    private final FactorizationEngine<ExactRational> engine;
    private final ExactParsedFactorizationPipeline factorization;
    private final ExactFactorizationTransformationPipeline transformation;
    private final ExpressionParser parser;
    private final int maxCandidateTransformations;
    private final int maxCanonicalNodes;

    /**
     * Creates a classifier for one visible, caller-selected exact engine.
     * Engine choice therefore remains part of the surrounding policy rather
     * than becoming an implicit best-of decision inside the classifier.
     */
    public PolynomialTheorySubsumptionClassifier(
        FactorizationEngine<ExactRational> engine
    ) {
        this(
            engine,
            new ExactParsedFactorizationPipeline(),
            new ExactFactorizationTransformationPipeline(),
            new ExpressionParser(),
            DEFAULT_MAX_CANDIDATE_TRANSFORMATIONS,
            DEFAULT_MAX_CANONICAL_NODES);
    }

    PolynomialTheorySubsumptionClassifier(
        FactorizationEngine<ExactRational> engine,
        ExactParsedFactorizationPipeline factorization,
        ExactFactorizationTransformationPipeline transformation,
        ExpressionParser parser,
        int maxCandidateTransformations,
        int maxCanonicalNodes
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.factorization = Objects.requireNonNull(
            factorization,
            "factorization");
        this.transformation = Objects.requireNonNull(
            transformation,
            "transformation");
        this.parser = Objects.requireNonNull(parser, "parser");
        if (maxCandidateTransformations < 1 || maxCanonicalNodes < 1) {
            throw new IllegalArgumentException(
                "polynomial theory classifier limits must be positive");
        }
        this.maxCandidateTransformations = maxCandidateTransformations;
        this.maxCanonicalNodes = maxCanonicalNodes;
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

        ExactParsedTerm source;
        ExactParsedTerm target;
        CanonicalExpression canonicalSource;
        CanonicalExpression canonicalTarget;
        try {
            source = parser.parseExactTerm(leftExpression);
            target = parser.parseExactTerm(rightExpression);
            canonicalSource = canonicalize(source);
            canonicalTarget = canonicalize(target);
        } catch (IdentityBudgetExceeded exception) {
            return Classification.failure(
                Status.BUDGET_INCONCLUSIVE,
                exception.getMessage(),
                0);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            return Classification.failure(
                Status.UNSUPPORTED,
                "EXPRESSION_PARSE_OR_EXACT_CANONICALIZATION_FAILED",
                0);
        } catch (IllegalStateException exception) {
            return Classification.failure(
                Status.TECHNICAL_FAILURE,
                "EXACT_EXPRESSION_EVIDENCE_INVARIANT_FAILED",
                0);
        }

        long workUnits = Math.addExact(
            Math.addExact(
                (long) leftExpression.length(),
                rightExpression.length()),
            Math.addExact(
                canonicalSource.workUnits(),
                canonicalTarget.workUnits()));

        ExactParsedFactorizationPipeline.Result factorizationResult =
            factorization.factor(source, engine);
        workUnits = Math.addExact(
            workUnits,
            factorizationResult.totalWork().totalWorkUnits());
        if (!factorizationResult.executed()) {
            return failure(factorizationResult, workUnits);
        }

        FactorizationVerifier.Report<ExactRational> report =
            factorizationResult.report().orElseThrow();
        if (!report.successful()) {
            return failure(report, workUnits);
        }
        if (report.candidates().isEmpty()) {
            return Classification.failure(
                Status.TECHNICAL_FAILURE,
                "SUCCESSFUL_VERIFIER_REPORT_HAS_NO_CANDIDATE",
                workUnits);
        }
        if (report.candidates().size() > maxCandidateTransformations) {
            return Classification.failure(
                Status.BUDGET_INCONCLUSIVE,
                "MAX_CANDIDATE_TRANSFORMATIONS_EXCEEDED",
                workUnits);
        }

        boolean budgetInconclusive = false;
        boolean unsupportedCandidate = false;
        long factorizationWork =
            factorizationResult.totalWork().totalWorkUnits();
        for (int candidateIndex = 0;
                candidateIndex < report.candidates().size();
                candidateIndex++) {
            ExactFactorizationTransformationPipeline.Result transformed =
                transformation.transformRoot(
                    source,
                    factorizationResult,
                    candidateIndex);
            long candidateWork = transformed.totalWork().totalWorkUnits();
            if (candidateWork < factorizationWork) {
                return Classification.failure(
                    Status.TECHNICAL_FAILURE,
                    "TRANSFORMATION_WORK_PRECEDES_FACTORIZATION_WORK",
                    workUnits);
            }
            workUnits = Math.addExact(
                workUnits,
                candidateWork - factorizationWork);

            switch (transformed.status()) {
                case TRANSFORMED -> {
                    CanonicalExpression generated;
                    try {
                        generated = canonicalize(
                            transformed.reparsed().orElseThrow());
                    } catch (IdentityBudgetExceeded exception) {
                        budgetInconclusive = true;
                        continue;
                    } catch (IllegalArgumentException
                            | ArithmeticException exception) {
                        return Classification.failure(
                            Status.TECHNICAL_FAILURE,
                            "VERIFIER_TRANSFORMATION_CANNOT_BE_CANONICALIZED",
                            workUnits);
                    } catch (IllegalStateException exception) {
                        return Classification.failure(
                            Status.TECHNICAL_FAILURE,
                            "VERIFIER_TRANSFORMATION_EVIDENCE_INVARIANT_FAILED",
                            workUnits);
                    }
                    workUnits = Math.addExact(
                        workUnits,
                        generated.workUnits());
                    if (canonicalTarget.material().equals(
                            generated.material())) {
                        return Classification.subsumed(
                            canonicalSource.expression(),
                            transformed,
                            workUnits);
                    }
                }
                case BUDGET_INCONCLUSIVE ->
                    budgetInconclusive = true;
                case UNSUPPORTED -> unsupportedCandidate = true;
                case TECHNICAL_FAILURE, SOURCE_EVIDENCE_MISMATCH -> {
                    return Classification.failure(
                        Status.TECHNICAL_FAILURE,
                        transformed.detailCode(),
                        workUnits);
                }
                case NO_CHANGE,
                    NO_CANDIDATE,
                    BACKEND_CLAIMED_IRREDUCIBLE,
                    IRREDUCIBLE -> {
                    // These outcomes issue no reusable transformation.
                }
            }
        }

        if (budgetInconclusive) {
            return Classification.failure(
                Status.BUDGET_INCONCLUSIVE,
                "CANDIDATE_TRANSFORMATION_OR_COMPARISON_INCONCLUSIVE",
                workUnits);
        }
        if (unsupportedCandidate) {
            return Classification.failure(
                Status.UNSUPPORTED,
                "VERIFIED_CANDIDATE_NOT_RENDERABLE_IN_EXACT_TERM_LANGUAGE",
                workUnits);
        }
        return Classification.failure(
            Status.NOT_SUBSUMED,
            "TARGET_NOT_GENERATED_BY_EXACT_VERIFIED_FACTORIZATION",
            workUnits);
    }

    private Classification failure(
        ExactParsedFactorizationPipeline.Result result,
        long workUnits
    ) {
        Status status = switch (result.status()) {
            case BUDGET_INCONCLUSIVE -> Status.BUDGET_INCONCLUSIVE;
            case UNSUPPORTED_EXPRESSION, UNSUPPORTED_REQUEST ->
                Status.UNSUPPORTED;
            case EXECUTED -> throw new IllegalStateException(
                "executed exact factorization requires a verifier report");
        };
        return Classification.failure(
            status,
            result.detailCode(),
            workUnits);
    }

    private Classification failure(
        FactorizationVerifier.Report<ExactRational> report,
        long workUnits
    ) {
        Status status = switch (report.status()) {
            case IRREDUCIBLE, NO_FACTORIZATION_FOUND ->
                Status.NOT_SUBSUMED;
            case UNSUPPORTED_DOMAIN, UNSUPPORTED_REQUEST ->
                Status.UNSUPPORTED;
            case BUDGET_INCONCLUSIVE -> Status.BUDGET_INCONCLUSIVE;
            case TECHNICAL_FAILURE -> Status.TECHNICAL_FAILURE;
            case COMPLETE_FACTORIZATION, PARTIAL_FACTORIZATION ->
                throw new IllegalStateException(
                    "successful verifier report was handled as a failure");
        };
        return Classification.failure(
            status,
            report.detailCode(),
            workUnits);
    }

    private CanonicalExpression canonicalize(ExactParsedTerm parsed) {
        IdentityWork work = new IdentityWork(maxCanonicalNodes);
        CanonicalNode root = canonicalNode(
            parsed.expression(),
            parsed,
            work);
        return new CanonicalExpression(
            root.material(),
            root.expression(),
            work.units());
    }

    private CanonicalNode canonicalNode(
        Expr expression,
        ExactParsedTerm parsed,
        IdentityWork work
    ) {
        work.visit();
        if (expression instanceof NumberExpr number) {
            return numberNode(exactNumber(number, parsed));
        }
        if (expression instanceof VariableExpr variable) {
            return new CanonicalNode(
                material("variable", List.of(variable.name())),
                variable.name(),
                Optional.empty(),
                null,
                List.of());
        }
        if (expression instanceof FunctionExpr function) {
            List<CanonicalNode> arguments = function.arguments().stream()
                .map(argument -> canonicalNode(argument, parsed, work))
                .toList();
            return new CanonicalNode(
                material(
                    "function:" + function.name(),
                    arguments.stream()
                        .map(CanonicalNode::material)
                        .toList()),
                function.name() + "(" + arguments.stream()
                    .map(CanonicalNode::expression)
                    .collect(Collectors.joining(", ")) + ")",
                Optional.empty(),
                null,
                List.of());
        }
        if (!(expression instanceof BinaryExpr binary)) {
            throw new IllegalStateException(
                "unknown exact expression node kind");
        }

        CanonicalNode left = canonicalNode(binary.left(), parsed, work);
        CanonicalNode right = canonicalNode(binary.right(), parsed, work);
        Optional<ExactRational> constant = exactConstant(
            binary.operator(),
            left.constant(),
            right.constant());
        if (constant.isPresent()) {
            return numberNode(constant.orElseThrow());
        }
        if (binary.operator() == BinaryOperator.ADD
                || binary.operator() == BinaryOperator.MUL) {
            return associativeNode(binary.operator(), left, right, work);
        }
        return new CanonicalNode(
            material(
                "binary:" + binary.operator().name(),
                List.of(left.material(), right.material())),
            "(" + left.expression() + " "
                + binary.operator().symbol() + " "
                + right.expression() + ")",
            Optional.empty(),
            null,
            List.of());
    }

    private CanonicalNode associativeNode(
        BinaryOperator operator,
        CanonicalNode left,
        CanonicalNode right,
        IdentityWork work
    ) {
        List<CanonicalNode> operands = new ArrayList<>();
        appendAssociativeOperand(operands, left, operator);
        appendAssociativeOperand(operands, right, operator);
        operands = combineAssociativeConstants(
            operator,
            operands,
            work);
        work.sort(operands.size());
        operands.sort(Comparator.comparing(CanonicalNode::material));
        String separator = " " + operator.symbol() + " ";
        return new CanonicalNode(
            material(
                "associative:" + operator.name(),
                operands.stream()
                    .map(CanonicalNode::material)
                    .toList()),
            "(" + operands.stream()
                .map(CanonicalNode::expression)
                .collect(Collectors.joining(separator)) + ")",
            Optional.empty(),
            operator,
            operands);
    }

    private List<CanonicalNode> combineAssociativeConstants(
        BinaryOperator operator,
        List<CanonicalNode> operands,
        IdentityWork work
    ) {
        List<CanonicalNode> constants = new ArrayList<>();
        List<CanonicalNode> nonConstants = new ArrayList<>(
            operands.size());
        for (CanonicalNode operand : operands) {
            if (operand.constant().isPresent()) {
                constants.add(operand);
            } else {
                nonConstants.add(operand);
            }
        }
        if (constants.size() < 2) {
            return operands;
        }

        work.sort(constants.size());
        constants.sort(Comparator.comparing(CanonicalNode::material));
        ExactRational combined = constants.getFirst()
            .constant()
            .orElseThrow();
        for (int index = 1; index < constants.size(); index++) {
            work.scalarArithmetic();
            Optional<ExactRational> next = exactConstant(
                operator,
                Optional.of(combined),
                constants.get(index).constant());
            if (next.isEmpty()) {
                return operands;
            }
            combined = next.orElseThrow();
        }
        nonConstants.add(numberNode(combined));
        return nonConstants;
    }

    private static void appendAssociativeOperand(
        List<CanonicalNode> target,
        CanonicalNode node,
        BinaryOperator operator
    ) {
        if (node.associativeOperator() == operator) {
            target.addAll(node.associativeOperands());
        } else {
            target.add(node);
        }
    }

    private ExactRational exactNumber(
        NumberExpr number,
        ExactParsedTerm parsed
    ) {
        return parsed.literalFor(number)
            .map(ExactParsedTerm.LiteralOccurrence::exactValue)
            .orElseGet(() -> {
                if (number.value() == 0.0d) {
                    return ExactRational.ZERO;
                }
                throw new IllegalStateException(
                    "numeric node lacks parser-issued exact evidence");
            });
    }

    private Optional<ExactRational> exactConstant(
        BinaryOperator operator,
        Optional<ExactRational> left,
        Optional<ExactRational> right
    ) {
        if (left.isEmpty() || right.isEmpty()) {
            return Optional.empty();
        }
        ExactRational first = left.orElseThrow();
        ExactRational second = right.orElseThrow();
        try {
            return switch (operator) {
                case ADD -> checkedBinary(first, second, first::add);
                case SUB -> checkedBinary(first, second, first::subtract);
                case MUL -> checkedBinary(first, second, first::multiply);
                case DIV -> second.isZero()
                    ? Optional.empty()
                    : checkedBinary(first, second, first::divide);
                case POW -> checkedPower(first, second);
            };
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
    }

    private Optional<ExactRational> checkedBinary(
        ExactRational left,
        ExactRational right,
        java.util.function.Function<ExactRational, ExactRational> operation
    ) {
        if ((long) scalarBits(left) + scalarBits(right)
                > MAX_CANONICAL_SCALAR_BITS) {
            return Optional.empty();
        }
        ExactRational result = operation.apply(right);
        return scalarBits(result) <= MAX_CANONICAL_SCALAR_BITS
            ? Optional.of(result)
            : Optional.empty();
    }

    private Optional<ExactRational> checkedPower(
        ExactRational base,
        ExactRational exponent
    ) {
        if (!exponent.isInteger()
                || exponent.signum() < 0
                || exponent.numerator().bitLength() > 31) {
            return Optional.empty();
        }
        int power = exponent.numerator().intValueExact();
        if (power > 64
                || Math.multiplyExact(
                    (long) scalarBits(base),
                    Math.max(1, power)) > MAX_CANONICAL_SCALAR_BITS) {
            return Optional.empty();
        }
        ExactRational result = base.pow(power);
        return scalarBits(result) <= MAX_CANONICAL_SCALAR_BITS
            ? Optional.of(result)
            : Optional.empty();
    }

    private static int scalarBits(ExactRational value) {
        return Math.addExact(
            value.numerator().abs().bitLength(),
            value.denominator().bitLength());
    }

    private static CanonicalNode numberNode(ExactRational value) {
        String expression = value.isInteger()
            ? value.numerator().toString()
            : "(" + value.numerator() + " / "
                + value.denominator() + ")";
        return new CanonicalNode(
            material("number", List.of(value.canonicalText())),
            expression,
            Optional.of(value),
            null,
            List.of());
    }

    private static String material(
        String kind,
        List<String> components
    ) {
        StringBuilder result = new StringBuilder(kind);
        for (String component : components) {
            result.append('|')
                .append(component.length())
                .append(':')
                .append(component);
        }
        return result.toString();
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
            long workUnits,
            ProjectInventoryNovelty projectInventoryNovelty,
            RetentionDisposition retentionDisposition
        ) {
            Status checkedStatus = Objects.requireNonNull(
                status,
                "status");
            if (detailCode == null
                    || detailCode.isBlank()
                    || !THEORY_METHOD_ID.equals(theoryMethodId)
                    || sourceExpression == null
                    || certificateHash == null
                    || derivedExpression == null
                    || applicationKey == null
                    || workUnits < 0
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
                        || !applicationKey.matches(
                            "sha256:[0-9a-f]{64}")
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
                workUnits,
                projectInventoryNovelty,
                retentionDisposition);
        }

        private static Classification subsumed(
            String sourceExpression,
            ExactFactorizationTransformationPipeline.Result transformation,
            long workUnits
        ) {
            return new Classification(
                Status.THEORY_SUBSUMED,
                "TARGET_MATCHES_EXACT_VERIFIER_TRANSFORMATION",
                THEORY_METHOD_ID,
                sourceExpression,
                transformation.candidateCertificateHash(),
                transformation.transformedExpression().orElseThrow(),
                transformation.certificateHash(),
                workUnits,
                ProjectInventoryNovelty.NOT_EVALUATED,
                RetentionDisposition.DERIVED_MACRO_CACHE_ONLY);
        }

        private static Classification failure(
            Status status,
            String detailCode,
            long workUnits
        ) {
            return new Classification(
                status,
                detailCode,
                THEORY_METHOD_ID,
                "",
                "",
                "",
                "",
                workUnits,
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

        public long workUnits() {
            return state.workUnits();
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
            long workUnits,
            ProjectInventoryNovelty projectInventoryNovelty,
            RetentionDisposition retentionDisposition
        ) {
        }
    }

    private record CanonicalExpression(
        String material,
        String expression,
        long workUnits
    ) {
        private CanonicalExpression {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(expression, "expression");
            if (workUnits < 0) {
                throw new IllegalArgumentException(
                    "canonical expression work must not be negative");
            }
        }
    }

    private record CanonicalNode(
        String material,
        String expression,
        Optional<ExactRational> constant,
        BinaryOperator associativeOperator,
        List<CanonicalNode> associativeOperands
    ) {
        private CanonicalNode {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(constant, "constant");
            associativeOperands = List.copyOf(
                Objects.requireNonNull(
                    associativeOperands,
                    "associativeOperands"));
            if ((associativeOperator == null)
                    != associativeOperands.isEmpty()) {
                throw new IllegalArgumentException(
                    "associative canonical node state is inconsistent");
            }
        }
    }

    private static final class IdentityWork {
        private final int maxNodes;
        private int nodes;
        private long units;

        private IdentityWork(int maxNodes) {
            this.maxNodes = maxNodes;
        }

        private void visit() {
            if (nodes >= maxNodes) {
                throw new IdentityBudgetExceeded(
                    "MAX_EXACT_CANONICAL_NODES_EXCEEDED");
            }
            nodes++;
            units = Math.addExact(units, 1);
        }

        private void sort(int values) {
            if (values < 2) {
                return;
            }
            int rounds = 32 - Integer.numberOfLeadingZeros(values - 1);
            units = Math.addExact(
                units,
                Math.multiplyExact((long) values, rounds));
        }

        private void scalarArithmetic() {
            units = Math.addExact(units, 1);
        }

        private long units() {
            return units;
        }
    }

    private static final class IdentityBudgetExceeded
            extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private IdentityBudgetExceeded(String detailCode) {
            super(detailCode);
        }
    }
}
