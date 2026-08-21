package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionFormatter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded, certificate-carrying preparation for two rational terms with
 * different denominators.
 *
 * <p>For {@code A/B op C/D}, where {@code op} is addition or subtraction, the
 * solver constructs two fractions with the identical retained denominator
 * {@code B*D}. The ordinary {@link RationalNormalizationHypothesisOperator}
 * must then replay on that prepared AST. The solver does not cancel factors,
 * simplify products, or infer that a symbolic denominator is non-zero.</p>
 */
public final class RationalCommonDenominatorPreparationSolver {
    public static final String SOLVER_ID =
        "rational-common-denominator/bounded-ast/v1";
    public static final String CERTIFICATE_SCHEMA =
        "regelsuche.rational-common-denominator-certificate/v1";
    public static final String APPLICATION_SCHEMA =
        "regelsuche.rational-common-denominator-application/v1";
    public static final String PREPARATION_RULE_ID =
        "prepare_rational_common_denominator";
    public static final String PRINCIPAL_RULE_ID =
        RationalNormalizationHypothesisOperator.RULE_ID;

    private final Budget budget;
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    public RationalCommonDenominatorPreparationSolver() {
        this(Budget.DEFAULT);
    }

    public RationalCommonDenominatorPreparationSolver(Budget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public Budget budget() {
        return budget;
    }

    public PlanAttempt plan(Expr subtree) {
        Objects.requireNonNull(subtree, "subtree");
        WorkLedger untouched = WorkLedger.untouched(budget);
        BinaryExpr operation = rationalOperation(subtree);
        if (operation == null) {
            return PlanAttempt.withoutApplication(
                Status.NOT_APPLICABLE,
                untouched,
                "root-is-not-a-two-fraction-sum-or-difference");
        }

        int inputNodes = boundedNodeCount(
            subtree,
            budget.maxInputNodes());
        if (inputNodes > budget.maxInputNodes()) {
            return PlanAttempt.withoutApplication(
                Status.BUDGET_INCONCLUSIVE,
                WorkLedger.inputExhausted(budget),
                "input-node-limit-exhausted");
        }

        DivisionParts left = division(operation.left());
        DivisionParts right = division(operation.right());
        if (left == null || right == null) {
            return PlanAttempt.withoutApplication(
                Status.NOT_APPLICABLE,
                WorkLedger.withInput(budget, inputNodes),
                "both-operands-must-be-fractions");
        }

        ResidualObligation obligation = residualObligation(operation, left, right);
        if (isExplicitZero(left.denominator())
                || isExplicitZero(right.denominator())) {
            return PlanAttempt.withoutApplication(
                Status.UNSUPPORTED,
                Optional.of(obligation),
                WorkLedger.withInput(budget, inputNodes),
                "explicit-zero-denominator");
        }
        if (sameValue(left.denominator(), right.denominator())) {
            return PlanAttempt.withoutApplication(
                Status.DIRECT_MATCH_AVAILABLE,
                WorkLedger.withInput(budget, inputNodes),
                "principal-operator-already-matches");
        }

        ExpectedApplication expected =
            expectedApplication(operation.operator(), left, right);
        int constructedNodes = boundedNodeCount(
            expected.prepared(),
            budget.maxConstructedNodes());
        if (constructedNodes <= budget.maxConstructedNodes()) {
            int remaining = budget.maxConstructedNodes() - constructedNodes;
            int resultNodes = boundedNodeCount(expected.result(), remaining);
            constructedNodes = resultNodes > remaining
                ? budget.maxConstructedNodes() + 1
                : constructedNodes + resultNodes;
        }
        if (constructedNodes > budget.maxConstructedNodes()) {
            return PlanAttempt.withoutApplication(
                Status.BUDGET_INCONCLUSIVE,
                Optional.of(obligation),
                WorkLedger.constructedExhausted(budget, inputNodes),
                "constructed-node-limit-exhausted");
        }

        WorkLedger work = new WorkLedger(
            budget.maxInputNodes(),
            inputNodes,
            budget.maxInputNodes() - inputNodes,
            budget.maxConstructedNodes(),
            constructedNodes,
            budget.maxConstructedNodes() - constructedNodes);
        List<String> assumptions = denominatorAssumptions(
            expected.commonDenominator());
        Certificate certificate = certificate(
            operation,
            left,
            right,
            expected);
        PreparedApplication application = new PreparedApplication(
            APPLICATION_SCHEMA,
            SOLVER_ID,
            PRINCIPAL_RULE_ID,
            subtree,
            expected.prepared(),
            expected.result(),
            expected.leftScaledNumerator(),
            expected.rightScaledNumerator(),
            expected.commonDenominator(),
            Map.of(
                "A", left.numerator(),
                "B", left.denominator(),
                "C", right.numerator(),
                "D", right.denominator(),
                "Q", expected.commonDenominator()),
            obligation,
            assumptions,
            List.of(PREPARATION_RULE_ID, PRINCIPAL_RULE_ID),
            budget,
            certificate,
            work);
        if (!verify(application)) {
            return PlanAttempt.withoutApplication(
                Status.INVALID_CERTIFICATE,
                Optional.of(obligation),
                work,
                "generated-common-denominator-certificate-rejected");
        }
        return PlanAttempt.prepared(application);
    }

    public boolean verify(PreparedApplication application) {
        VerificationInput input = verificationInput(application);
        if (input == null) {
            return false;
        }
        ExpectedApplication expected = expectedApplication(
            input.operation().operator(),
            input.left(),
            input.right());
        int constructedNodes = nodeCount(expected.prepared())
            + nodeCount(expected.result());
        WorkLedger expectedWork = new WorkLedger(
            budget.maxInputNodes(),
            input.inputNodes(),
            budget.maxInputNodes() - input.inputNodes(),
            budget.maxConstructedNodes(),
            constructedNodes,
            budget.maxConstructedNodes() - constructedNodes);
        if (!expectedWork.equals(application.work())
                || !expressionsMatch(application, expected)
                || !bindingsMatch(application, input, expected)
                || !contextMatches(application, input, expected)) {
            return false;
        }
        return certificate(
            input.operation(),
            input.left(),
            input.right(),
            expected).equals(application.certificate());
    }

    private VerificationInput verificationInput(
        PreparedApplication application
    ) {
        if (!hasExpectedMetadata(application)
                || !budget.equals(application.budget())
                || !(application.originalSubtree()
                    instanceof BinaryExpr operation)
                || (operation.operator() != BinaryOperator.ADD
                    && operation.operator() != BinaryOperator.SUB)) {
            return null;
        }
        DivisionParts left = division(operation.left());
        DivisionParts right = division(operation.right());
        if (left == null || right == null
                || isExplicitZero(left.denominator())
                || isExplicitZero(right.denominator())
                || sameValue(left.denominator(), right.denominator())) {
            return null;
        }
        int inputNodes = nodeCount(operation);
        if (inputNodes > budget.maxInputNodes()) {
            return null;
        }
        ExpectedApplication expected = expectedApplication(
            operation.operator(),
            left,
            right);
        if (nodeCount(expected.prepared()) + nodeCount(expected.result())
                > budget.maxConstructedNodes()) {
            return null;
        }
        return new VerificationInput(operation, left, right, inputNodes);
    }

    private static ExpectedApplication expectedApplication(
        BinaryOperator operator,
        DivisionParts left,
        DivisionParts right
    ) {
        Expr commonDenominator = multiply(
            left.denominator(),
            right.denominator());
        Expr leftScaledNumerator = multiply(
            left.numerator(),
            right.denominator());
        Expr rightScaledNumerator = multiply(
            right.numerator(),
            left.denominator());
        Expr prepared = new BinaryExpr(
            divide(leftScaledNumerator, commonDenominator),
            operator,
            divide(rightScaledNumerator, commonDenominator));
        Expr result = divide(
            new BinaryExpr(
                leftScaledNumerator,
                operator,
                rightScaledNumerator),
            commonDenominator);
        return new ExpectedApplication(
            leftScaledNumerator,
            rightScaledNumerator,
            commonDenominator,
            prepared,
            result);
    }

    private static Expr multiply(Expr left, Expr right) {
        return new BinaryExpr(left, BinaryOperator.MUL, right);
    }

    private static Expr divide(Expr numerator, Expr denominator) {
        return new BinaryExpr(numerator, BinaryOperator.DIV, denominator);
    }

    private static DivisionParts division(Expr expression) {
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.DIV) {
            return new DivisionParts(binary.left(), binary.right());
        }
        return null;
    }

    private static BinaryExpr rationalOperation(Expr expression) {
        if (expression instanceof BinaryExpr binary
                && (binary.operator() == BinaryOperator.ADD
                    || binary.operator() == BinaryOperator.SUB)) {
            return binary;
        }
        return null;
    }

    private boolean sameValue(Expr left, Expr right) {
        if (left.equals(right)) {
            return true;
        }
        try {
            return canonicalizer.stableHash(ExpressionFormatter.format(left))
                .equals(canonicalizer.stableHash(
                    ExpressionFormatter.format(right)));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isExplicitZero(Expr expression) {
        return expression instanceof NumberExpr number
            && number.value() == 0;
    }

    private static List<String> denominatorAssumptions(Expr denominator) {
        if (denominator instanceof NumberExpr number) {
            return number.value() == 0
                ? List.of("0 != 0")
                : List.of();
        }
        return List.of(ExpressionFormatter.format(denominator) + " != 0");
    }

    private static boolean expressionsMatch(
        PreparedApplication application,
        ExpectedApplication expected
    ) {
        return expected.leftScaledNumerator().equals(
                application.leftScaledNumerator())
            && expected.rightScaledNumerator().equals(
                application.rightScaledNumerator())
            && expected.commonDenominator().equals(
                application.commonDenominator())
            && expected.prepared().equals(application.preparedSubtree())
            && expected.result().equals(application.resultSubtree());
    }

    private static boolean bindingsMatch(
        PreparedApplication application,
        VerificationInput input,
        ExpectedApplication expected
    ) {
        return application.bindings().size() == 5
            && input.left().numerator().equals(
                application.bindings().get("A"))
            && input.left().denominator().equals(
                application.bindings().get("B"))
            && input.right().numerator().equals(
                application.bindings().get("C"))
            && input.right().denominator().equals(
                application.bindings().get("D"))
            && expected.commonDenominator().equals(
                application.bindings().get("Q"));
    }

    private static boolean contextMatches(
        PreparedApplication application,
        VerificationInput input,
        ExpectedApplication expected
    ) {
        return residualObligation(
                input.operation(),
                input.left(),
                input.right()).equals(application.residualObligation())
            && denominatorAssumptions(expected.commonDenominator())
                .equals(application.assumptions());
    }

    private static boolean hasExpectedMetadata(
        PreparedApplication application
    ) {
        return application != null
            && APPLICATION_SCHEMA.equals(application.schema())
            && SOLVER_ID.equals(application.solverId())
            && PRINCIPAL_RULE_ID.equals(application.principalRuleId())
            && List.of(PREPARATION_RULE_ID, PRINCIPAL_RULE_ID)
                .equals(application.primitiveRuleIds());
    }

    private static ResidualObligation residualObligation(
        BinaryExpr operation,
        DivisionParts left,
        DivisionParts right
    ) {
        return new ResidualObligation(
            "RATIONAL_COMMON_DENOMINATOR",
            operation.operator().name(),
            ExpressionFormatter.format(left.numerator()),
            ExpressionFormatter.format(left.denominator()),
            ExpressionFormatter.format(right.numerator()),
            ExpressionFormatter.format(right.denominator()),
            "Q",
            "A/B "
                + operatorToken(operation.operator())
                + " C/D -> N/Q");
    }

    private static String operatorToken(BinaryOperator operator) {
        return operator == BinaryOperator.ADD ? "+" : "-";
    }

    private static Certificate certificate(
        BinaryExpr operation,
        DivisionParts left,
        DivisionParts right,
        ExpectedApplication expected
    ) {
        String originalText = ExpressionFormatter.format(operation);
        String preparedText = ExpressionFormatter.format(expected.prepared());
        String resultText = ExpressionFormatter.format(expected.result());
        String commonText = ExpressionFormatter.format(
            expected.commonDenominator());
        String originalHash = structureHash(operation);
        String preparedHash = structureHash(expected.prepared());
        String resultHash = structureHash(expected.result());
        String commonHash = structureHash(expected.commonDenominator());
        String payload = String.join("\n",
            "schema=" + CERTIFICATE_SCHEMA,
            "solver=" + SOLVER_ID,
            "principalRule=" + PRINCIPAL_RULE_ID,
            "operator=" + operation.operator().name(),
            "leftNumerator=" + ExpressionFormatter.format(left.numerator()),
            "leftDenominator=" + ExpressionFormatter.format(left.denominator()),
            "rightNumerator=" + ExpressionFormatter.format(right.numerator()),
            "rightDenominator=" + ExpressionFormatter.format(right.denominator()),
            "original=" + originalText,
            "originalStructure=" + originalHash,
            "commonDenominator=" + commonText,
            "commonStructure=" + commonHash,
            "prepared=" + preparedText,
            "preparedStructure=" + preparedHash,
            "result=" + resultText,
            "resultStructure=" + resultHash);
        return new Certificate(
            CERTIFICATE_SCHEMA,
            SOLVER_ID,
            operation.operator().name(),
            originalText,
            preparedText,
            resultText,
            commonText,
            originalHash,
            preparedHash,
            resultHash,
            commonHash,
            sha256(payload));
    }

    private static String structureHash(Expr expression) {
        return sha256(structureDescriptor(expression));
    }

    private static String structureDescriptor(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return "N" + Long.toUnsignedString(
                Double.doubleToRawLongBits(number.value()),
                16);
        }
        if (expression instanceof VariableExpr variable) {
            return token("V", variable.name());
        }
        if (expression instanceof BinaryExpr binary) {
            return token("B", binary.operator().name())
                + "(" + structureDescriptor(binary.left()) + ")"
                + "(" + structureDescriptor(binary.right()) + ")";
        }
        FunctionExpr function = (FunctionExpr) expression;
        StringBuilder result = new StringBuilder(
            token("F", function.name()))
            .append('#')
            .append(function.arguments().size());
        for (Expr argument : function.arguments()) {
            result.append('(')
                .append(structureDescriptor(argument))
                .append(')');
        }
        return result.toString();
    }

    private static String token(String kind, String value) {
        return kind + value.length() + ":" + value;
    }

    private static int boundedNodeCount(Expr expression, int limit) {
        NodeCounter counter = new NodeCounter(limit);
        counter.visit(expression);
        return counter.exhausted() ? limit + 1 : counter.count();
    }

    private static int nodeCount(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return 1 + nodeCount(binary.left()) + nodeCount(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            return 1 + function.arguments().stream()
                .mapToInt(RationalCommonDenominatorPreparationSolver::nodeCount)
                .sum();
        }
        return 1;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum Status {
        PREPARED,
        DIRECT_MATCH_AVAILABLE,
        NOT_APPLICABLE,
        UNSUPPORTED,
        BUDGET_INCONCLUSIVE,
        INVALID_CERTIFICATE
    }

    public record Budget(
        int maxInputNodes,
        int maxConstructedNodes
    ) {
        public static final Budget DEFAULT = new Budget(96, 384);

        public Budget {
            if (maxInputNodes < 1 || maxConstructedNodes < 1) {
                throw new IllegalArgumentException(
                    "node limits must be positive");
            }
        }
    }

    public record WorkLedger(
        int configuredInputNodeLimit,
        int inspectedInputNodes,
        int remainingInputNodeBudget,
        int configuredConstructedNodeLimit,
        int constructedNodes,
        int remainingConstructedNodeBudget
    ) {
        public WorkLedger {
            if (configuredInputNodeLimit < 1
                    || inspectedInputNodes < 0
                    || remainingInputNodeBudget < 0
                    || configuredInputNodeLimit
                        != inspectedInputNodes + remainingInputNodeBudget
                    || configuredConstructedNodeLimit < 1
                    || constructedNodes < 0
                    || remainingConstructedNodeBudget < 0
                    || configuredConstructedNodeLimit
                        != constructedNodes
                            + remainingConstructedNodeBudget) {
                throw new IllegalArgumentException(
                    "node work must be non-negative and balanced");
            }
        }

        private static WorkLedger untouched(Budget budget) {
            return new WorkLedger(
                budget.maxInputNodes(),
                0,
                budget.maxInputNodes(),
                budget.maxConstructedNodes(),
                0,
                budget.maxConstructedNodes());
        }

        private static WorkLedger withInput(Budget budget, int inputNodes) {
            return new WorkLedger(
                budget.maxInputNodes(),
                inputNodes,
                budget.maxInputNodes() - inputNodes,
                budget.maxConstructedNodes(),
                0,
                budget.maxConstructedNodes());
        }

        private static WorkLedger inputExhausted(Budget budget) {
            return new WorkLedger(
                budget.maxInputNodes(),
                budget.maxInputNodes(),
                0,
                budget.maxConstructedNodes(),
                0,
                budget.maxConstructedNodes());
        }

        private static WorkLedger constructedExhausted(
            Budget budget,
            int inputNodes
        ) {
            return new WorkLedger(
                budget.maxInputNodes(),
                inputNodes,
                budget.maxInputNodes() - inputNodes,
                budget.maxConstructedNodes(),
                budget.maxConstructedNodes(),
                0);
        }
    }

    public record ResidualObligation(
        String kind,
        String operator,
        String leftNumeratorExpression,
        String leftDenominatorExpression,
        String rightNumeratorExpression,
        String rightDenominatorExpression,
        String commonDenominatorPlaceholder,
        String equationTemplate
    ) {
        public ResidualObligation {
            kind = requireText(kind, "kind");
            operator = requireText(operator, "operator");
            leftNumeratorExpression = requireText(
                leftNumeratorExpression,
                "leftNumeratorExpression");
            leftDenominatorExpression = requireText(
                leftDenominatorExpression,
                "leftDenominatorExpression");
            rightNumeratorExpression = requireText(
                rightNumeratorExpression,
                "rightNumeratorExpression");
            rightDenominatorExpression = requireText(
                rightDenominatorExpression,
                "rightDenominatorExpression");
            commonDenominatorPlaceholder = requireText(
                commonDenominatorPlaceholder,
                "commonDenominatorPlaceholder");
            equationTemplate = requireText(
                equationTemplate,
                "equationTemplate");
        }
    }

    public record Certificate(
        String schema,
        String solverId,
        String operator,
        String originalExpression,
        String preparedExpression,
        String resultExpression,
        String commonDenominatorExpression,
        String originalStructureHash,
        String preparedStructureHash,
        String resultStructureHash,
        String commonDenominatorStructureHash,
        String contentHash
    ) {
        public Certificate {
            schema = requireText(schema, "schema");
            solverId = requireText(solverId, "solverId");
            operator = requireText(operator, "operator");
            originalExpression = requireText(
                originalExpression,
                "originalExpression");
            preparedExpression = requireText(
                preparedExpression,
                "preparedExpression");
            resultExpression = requireText(
                resultExpression,
                "resultExpression");
            commonDenominatorExpression = requireText(
                commonDenominatorExpression,
                "commonDenominatorExpression");
            originalStructureHash = requireText(
                originalStructureHash,
                "originalStructureHash");
            preparedStructureHash = requireText(
                preparedStructureHash,
                "preparedStructureHash");
            resultStructureHash = requireText(
                resultStructureHash,
                "resultStructureHash");
            commonDenominatorStructureHash = requireText(
                commonDenominatorStructureHash,
                "commonDenominatorStructureHash");
            contentHash = requireText(contentHash, "contentHash");
        }
    }

    public record PreparedApplication(
        String schema,
        String solverId,
        String principalRuleId,
        Expr originalSubtree,
        Expr preparedSubtree,
        Expr resultSubtree,
        Expr leftScaledNumerator,
        Expr rightScaledNumerator,
        Expr commonDenominator,
        Map<String, Expr> bindings,
        ResidualObligation residualObligation,
        List<String> assumptions,
        List<String> primitiveRuleIds,
        Budget budget,
        Certificate certificate,
        WorkLedger work
    ) {
        public PreparedApplication {
            schema = requireText(schema, "schema");
            solverId = requireText(solverId, "solverId");
            principalRuleId = requireText(
                principalRuleId,
                "principalRuleId");
            originalSubtree = Objects.requireNonNull(
                originalSubtree,
                "originalSubtree");
            preparedSubtree = Objects.requireNonNull(
                preparedSubtree,
                "preparedSubtree");
            resultSubtree = Objects.requireNonNull(
                resultSubtree,
                "resultSubtree");
            leftScaledNumerator = Objects.requireNonNull(
                leftScaledNumerator,
                "leftScaledNumerator");
            rightScaledNumerator = Objects.requireNonNull(
                rightScaledNumerator,
                "rightScaledNumerator");
            commonDenominator = Objects.requireNonNull(
                commonDenominator,
                "commonDenominator");
            bindings = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(bindings, "bindings")));
            residualObligation = Objects.requireNonNull(
                residualObligation,
                "residualObligation");
            assumptions = List.copyOf(
                Objects.requireNonNull(assumptions, "assumptions"));
            primitiveRuleIds = List.copyOf(
                Objects.requireNonNull(
                    primitiveRuleIds,
                    "primitiveRuleIds"));
            if (primitiveRuleIds.isEmpty()) {
                throw new IllegalArgumentException(
                    "primitiveRuleIds must not be empty");
            }
            budget = Objects.requireNonNull(budget, "budget");
            certificate = Objects.requireNonNull(
                certificate,
                "certificate");
            work = Objects.requireNonNull(work, "work");
        }
    }

    public record PlanAttempt(
        Status status,
        Optional<PreparedApplication> application,
        Optional<ResidualObligation> residualObligation,
        WorkLedger work,
        String detail
    ) {
        public PlanAttempt {
            status = Objects.requireNonNull(status, "status");
            application = Objects.requireNonNull(
                application,
                "application");
            residualObligation = Objects.requireNonNull(
                residualObligation,
                "residualObligation");
            work = Objects.requireNonNull(work, "work");
            detail = detail == null ? "" : detail;
            if ((status == Status.PREPARED) != application.isPresent()) {
                throw new IllegalArgumentException(
                    "only PREPARED attempts may retain an application");
            }
            if (application.isPresent()
                    && (!application.get().work().equals(work)
                        || !residualObligation.equals(Optional.of(
                            application.get().residualObligation())))) {
                throw new IllegalArgumentException(
                    "attempt and application evidence must agree");
            }
        }

        private static PlanAttempt prepared(
            PreparedApplication application
        ) {
            return new PlanAttempt(
                Status.PREPARED,
                Optional.of(application),
                Optional.of(application.residualObligation()),
                application.work(),
                "rational-common-denominator-prepared");
        }

        private static PlanAttempt withoutApplication(
            Status status,
            WorkLedger work,
            String detail
        ) {
            return withoutApplication(
                status,
                Optional.empty(),
                work,
                detail);
        }

        private static PlanAttempt withoutApplication(
            Status status,
            Optional<ResidualObligation> obligation,
            WorkLedger work,
            String detail
        ) {
            return new PlanAttempt(
                status,
                Optional.empty(),
                obligation,
                work,
                detail);
        }
    }

    private record DivisionParts(Expr numerator, Expr denominator) {
        private DivisionParts {
            numerator = Objects.requireNonNull(numerator, "numerator");
            denominator = Objects.requireNonNull(
                denominator,
                "denominator");
        }
    }

    private record ExpectedApplication(
        Expr leftScaledNumerator,
        Expr rightScaledNumerator,
        Expr commonDenominator,
        Expr prepared,
        Expr result
    ) {
        private ExpectedApplication {
            leftScaledNumerator = Objects.requireNonNull(
                leftScaledNumerator,
                "leftScaledNumerator");
            rightScaledNumerator = Objects.requireNonNull(
                rightScaledNumerator,
                "rightScaledNumerator");
            commonDenominator = Objects.requireNonNull(
                commonDenominator,
                "commonDenominator");
            prepared = Objects.requireNonNull(prepared, "prepared");
            result = Objects.requireNonNull(result, "result");
        }
    }

    private record VerificationInput(
        BinaryExpr operation,
        DivisionParts left,
        DivisionParts right,
        int inputNodes
    ) {
    }

    private static final class NodeCounter {
        private final int limit;
        private int count;
        private boolean exhausted;

        private NodeCounter(int limit) {
            this.limit = limit;
        }

        private void visit(Expr expression) {
            if (exhausted) {
                return;
            }
            count++;
            if (count > limit) {
                exhausted = true;
                return;
            }
            if (expression instanceof BinaryExpr binary) {
                visit(binary.left());
                visit(binary.right());
            } else if (expression instanceof FunctionExpr function) {
                for (Expr argument : function.arguments()) {
                    visit(argument);
                }
            }
        }

        private int count() {
            return count;
        }

        private boolean exhausted() {
            return exhausted;
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                name + " must not be blank");
        }
        return value;
    }
}
