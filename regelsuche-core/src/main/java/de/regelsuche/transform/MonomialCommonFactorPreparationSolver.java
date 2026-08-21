package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
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
 * Bounded preparation solver that synthesizes the greatest common monomial of
 * two additive terms and exposes it to the ordinary common-left-factor rule.
 *
 * <p>The supported fragment contains positive exact integer coefficients,
 * variables, positive integer powers of variables, and multiplication. The
 * solver never distributes, guesses a factor, or treats an unsupported term as
 * a negative result. It may introduce an explicit unit remainder, for example
 * {@code x^2 + x -> x*x + x*1}, because that unit is certified by exact
 * monomial division.</p>
 */
public final class MonomialCommonFactorPreparationSolver {
    public static final String SOLVER_ID =
        "monomial-common-factor/exact-positive-integer/v1";
    public static final String CERTIFICATE_SCHEMA =
        "regelsuche.monomial-common-factor-certificate/v1";
    public static final String APPLICATION_SCHEMA =
        "regelsuche.monomial-common-factor-application/v1";
    public static final String PREPARATION_RULE_ID =
        "prepare_monomial_common_factor";
    public static final String PRINCIPAL_RULE_ID =
        "ast_factor_common_left";

    private final Budget budget;

    public MonomialCommonFactorPreparationSolver() {
        this(Budget.DEFAULT);
    }

    public MonomialCommonFactorPreparationSolver(Budget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
        budget.limits();
    }

    public Budget budget() {
        return budget;
    }

    /** Attempts one exact two-term common-monomial preparation. */
    public PlanAttempt plan(Expr subtree) {
        Objects.requireNonNull(subtree, "subtree");
        WorkLedger untouched = WorkLedger.untouched(budget.maxFactors());
        if (!(subtree instanceof BinaryExpr addition)
                || addition.operator() != BinaryOperator.ADD) {
            return PlanAttempt.withoutApplication(
                Status.NOT_APPLICABLE,
                untouched,
                "root-is-not-two-term-addition");
        }
        if (hasDirectFactorization(addition)
                || addition.left().equals(addition.right())) {
            return PlanAttempt.withoutApplication(
                Status.DIRECT_MATCH_AVAILABLE,
                untouched,
                "existing-direct-rule-already-applies");
        }

        ResidualObligation obligation = residualObligation(addition);
        ExactPositiveMonomial.Parser parser = new ExactPositiveMonomial.Parser(
            budget.limits());
        ExactPositiveMonomial.ParseResult left = parser.parse(addition.left());
        if (!left.supported()) {
            return failedParseAttempt(left, obligation, parser);
        }
        ExactPositiveMonomial.ParseResult right = parser.parse(addition.right());
        if (!right.supported()) {
            return failedParseAttempt(right, obligation, parser);
        }

        WorkLedger work = work(parser);
        ExactPositiveMonomial common = left.monomial().gcd(right.monomial());
        if (common.isOne()) {
            return PlanAttempt.withoutApplication(
                Status.NOT_APPLICABLE,
                Optional.of(obligation),
                work,
                "terms-have-no-nontrivial-supported-common-monomial");
        }

        ExactPositiveMonomial leftRemainder =
            left.monomial().divideExactly(common);
        ExactPositiveMonomial rightRemainder =
            right.monomial().divideExactly(common);
        if (leftRemainder == null || rightRemainder == null) {
            return PlanAttempt.withoutApplication(
                Status.INVALID_CERTIFICATE,
                Optional.of(obligation),
                work,
                "computed-gcd-does-not-divide-both-terms");
        }

        ExpectedApplication expected = expectedApplication(
            common,
            leftRemainder,
            rightRemainder);
        Certificate certificate = certificate(
            addition,
            expected.common(),
            expected.leftRemainder(),
            expected.rightRemainder(),
            expected.prepared(),
            expected.result(),
            left.monomial(),
            right.monomial(),
            common);
        PreparedApplication application = new PreparedApplication(
            APPLICATION_SCHEMA,
            SOLVER_ID,
            PRINCIPAL_RULE_ID,
            subtree,
            expected.prepared(),
            expected.result(),
            expected.common(),
            expected.leftRemainder(),
            expected.rightRemainder(),
            Map.of(
                "A", expected.common(),
                "B", expected.leftRemainder(),
                "C", expected.rightRemainder()),
            obligation,
            List.of(),
            List.of(PREPARATION_RULE_ID, PRINCIPAL_RULE_ID),
            budget,
            certificate,
            work);
        if (!verify(application)) {
            return PlanAttempt.withoutApplication(
                Status.INVALID_CERTIFICATE,
                Optional.of(obligation),
                work,
                "generated-monomial-certificate-rejected");
        }
        return PlanAttempt.prepared(application);
    }

    /** Independently checks the retained gcd, exact quotients and replay form. */
    public boolean verify(PreparedApplication application) {
        BinaryExpr addition = verificationSource(application);
        if (addition == null) {
            return false;
        }
        ParsedTerms parsed = parseForVerification(addition, application);
        if (parsed == null) {
            return false;
        }
        ExactFactorization factorization = exactFactorization(parsed);
        if (factorization == null) {
            return false;
        }
        ExpectedApplication expected = expectedApplication(
            factorization.common(),
            factorization.leftRemainder(),
            factorization.rightRemainder());
        if (!expressionsMatch(application, expected)
                || !bindingsMatch(application, expected)
                || !contextMatches(application, addition)) {
            return false;
        }
        Certificate expectedCertificate = certificate(
            addition,
            expected.common(),
            expected.leftRemainder(),
            expected.rightRemainder(),
            expected.prepared(),
            expected.result(),
            parsed.left(),
            parsed.right(),
            factorization.common());
        return expectedCertificate.equals(application.certificate());
    }

    private BinaryExpr verificationSource(PreparedApplication application) {
        if (!hasExpectedMetadata(application)
                || !budget.equals(application.budget())
                || !(application.originalSubtree()
                    instanceof BinaryExpr addition)) {
            return null;
        }
        return eligibleForPreparation(addition) ? addition : null;
    }

    private static boolean eligibleForPreparation(BinaryExpr addition) {
        return addition.operator() == BinaryOperator.ADD
            && !hasDirectFactorization(addition)
            && !addition.left().equals(addition.right());
    }

    private ParsedTerms parseForVerification(
        BinaryExpr addition,
        PreparedApplication application
    ) {
        ExactPositiveMonomial.Parser parser =
            new ExactPositiveMonomial.Parser(application.budget().limits());
        ExactPositiveMonomial.ParseResult left = parser.parse(addition.left());
        if (!left.supported()) {
            return null;
        }
        ExactPositiveMonomial.ParseResult right = parser.parse(addition.right());
        if (!right.supported() || !work(parser).equals(application.work())) {
            return null;
        }
        return new ParsedTerms(left.monomial(), right.monomial());
    }

    private static ExactFactorization exactFactorization(ParsedTerms parsed) {
        ExactPositiveMonomial common = parsed.left().gcd(parsed.right());
        if (common.isOne()) {
            return null;
        }
        ExactPositiveMonomial leftRemainder =
            parsed.left().divideExactly(common);
        ExactPositiveMonomial rightRemainder =
            parsed.right().divideExactly(common);
        if (leftRemainder == null || rightRemainder == null) {
            return null;
        }
        if (!reconstructs(parsed.left(), common, leftRemainder)
                || !reconstructs(parsed.right(), common, rightRemainder)) {
            return null;
        }
        return new ExactFactorization(
            common,
            leftRemainder,
            rightRemainder);
    }

    private static boolean reconstructs(
        ExactPositiveMonomial original,
        ExactPositiveMonomial common,
        ExactPositiveMonomial remainder
    ) {
        ExactPositiveMonomial reconstructed = common.multiply(remainder);
        return original.equals(reconstructed);
    }

    private static ExpectedApplication expectedApplication(
        ExactPositiveMonomial common,
        ExactPositiveMonomial leftRemainder,
        ExactPositiveMonomial rightRemainder
    ) {
        Expr commonExpression = common.toExpression();
        Expr leftExpression = leftRemainder.toExpression();
        Expr rightExpression = rightRemainder.toExpression();
        Expr prepared = new BinaryExpr(
            new BinaryExpr(
                commonExpression,
                BinaryOperator.MUL,
                leftExpression),
            BinaryOperator.ADD,
            new BinaryExpr(
                commonExpression,
                BinaryOperator.MUL,
                rightExpression));
        Expr result = new BinaryExpr(
            commonExpression,
            BinaryOperator.MUL,
            new BinaryExpr(
                leftExpression,
                BinaryOperator.ADD,
                rightExpression));
        return new ExpectedApplication(
            commonExpression,
            leftExpression,
            rightExpression,
            prepared,
            result);
    }

    private static boolean expressionsMatch(
        PreparedApplication application,
        ExpectedApplication expected
    ) {
        return expected.common().equals(application.commonFactor())
            && expected.leftRemainder().equals(application.leftRemainder())
            && expected.rightRemainder().equals(application.rightRemainder())
            && expected.prepared().equals(application.preparedSubtree())
            && expected.result().equals(application.resultSubtree());
    }

    private static boolean bindingsMatch(
        PreparedApplication application,
        ExpectedApplication expected
    ) {
        return application.bindings().size() == 3
            && expected.common().equals(application.bindings().get("A"))
            && expected.leftRemainder().equals(application.bindings().get("B"))
            && expected.rightRemainder().equals(application.bindings().get("C"));
    }

    private static boolean contextMatches(
        PreparedApplication application,
        BinaryExpr addition
    ) {
        return application.assumptions().isEmpty()
            && residualObligation(addition)
                .equals(application.residualObligation());
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

    private PlanAttempt failedParseAttempt(
        ExactPositiveMonomial.ParseResult result,
        ResidualObligation obligation,
        ExactPositiveMonomial.Parser parser
    ) {
        Status status = result.status()
                == ExactPositiveMonomial.ParseStatus.INCONCLUSIVE
            ? Status.BUDGET_INCONCLUSIVE
            : Status.UNSUPPORTED;
        return PlanAttempt.withoutApplication(
            status,
            Optional.of(obligation),
            work(parser),
            result.detail());
    }

    private WorkLedger work(ExactPositiveMonomial.Parser parser) {
        return new WorkLedger(
            budget.maxFactors(),
            parser.inspectedFactors(),
            parser.remainingFactors());
    }

    private static boolean hasDirectFactorization(BinaryExpr addition) {
        if (!(addition.left() instanceof BinaryExpr leftProduct)
                || leftProduct.operator() != BinaryOperator.MUL
                || !(addition.right() instanceof BinaryExpr rightProduct)
                || rightProduct.operator() != BinaryOperator.MUL) {
            return false;
        }
        return leftProduct.left().equals(rightProduct.left())
            || leftProduct.right().equals(rightProduct.right());
    }

    private static ResidualObligation residualObligation(
        BinaryExpr addition
    ) {
        String left = ExpressionFormatter.format(addition.left());
        String right = ExpressionFormatter.format(addition.right());
        return new ResidualObligation(
            "MONOMIAL_COMMON_FACTOR",
            left,
            right,
            "A",
            left + " = A * B; " + right + " = A * C");
    }

    private static Certificate certificate(
        BinaryExpr original,
        Expr common,
        Expr leftRemainder,
        Expr rightRemainder,
        Expr prepared,
        Expr result,
        ExactPositiveMonomial leftMonomial,
        ExactPositiveMonomial rightMonomial,
        ExactPositiveMonomial commonMonomial
    ) {
        String leftText = ExpressionFormatter.format(original.left());
        String rightText = ExpressionFormatter.format(original.right());
        String commonText = ExpressionFormatter.format(common);
        String leftRemainderText = ExpressionFormatter.format(leftRemainder);
        String rightRemainderText = ExpressionFormatter.format(rightRemainder);
        String preparedText = ExpressionFormatter.format(prepared);
        String resultText = ExpressionFormatter.format(result);
        String payload = String.join("\n",
            "schema=" + CERTIFICATE_SCHEMA,
            "solver=" + SOLVER_ID,
            "principalRule=" + PRINCIPAL_RULE_ID,
            "left=" + leftText,
            "right=" + rightText,
            "leftMonomial=" + leftMonomial.descriptor(),
            "rightMonomial=" + rightMonomial.descriptor(),
            "common=" + commonText,
            "commonMonomial=" + commonMonomial.descriptor(),
            "leftRemainder=" + leftRemainderText,
            "rightRemainder=" + rightRemainderText,
            "prepared=" + preparedText,
            "result=" + resultText);
        return new Certificate(
            CERTIFICATE_SCHEMA,
            SOLVER_ID,
            leftText,
            rightText,
            commonText,
            leftRemainderText,
            rightRemainderText,
            preparedText,
            resultText,
            leftMonomial.descriptor(),
            rightMonomial.descriptor(),
            commonMonomial.descriptor(),
            sha256(payload));
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
        int maxFactors,
        int maxExponent,
        long maxCoefficient
    ) {
        public static final Budget DEFAULT = new Budget(32, 16, 1_000_000L);

        public Budget {
            if (maxFactors < 0 || maxExponent < 1 || maxCoefficient < 1) {
                throw new IllegalArgumentException(
                    "factor limit must be non-negative and algebra limits positive");
            }
        }

        private ExactPositiveMonomial.Limits limits() {
            return new ExactPositiveMonomial.Limits(
                maxFactors,
                maxExponent,
                maxCoefficient);
        }
    }

    public record WorkLedger(
        int configuredFactorLimit,
        int inspectedFactors,
        int remainingFactorBudget
    ) {
        public WorkLedger {
            if (configuredFactorLimit < 0
                    || inspectedFactors < 0
                    || remainingFactorBudget < 0
                    || configuredFactorLimit
                        != inspectedFactors + remainingFactorBudget) {
                throw new IllegalArgumentException(
                    "factor work must be non-negative and balanced");
            }
        }

        private static WorkLedger untouched(int configured) {
            return new WorkLedger(configured, 0, configured);
        }
    }

    public record ResidualObligation(
        String kind,
        String leftTermExpression,
        String rightTermExpression,
        String commonPlaceholder,
        String equationTemplate
    ) {
        public ResidualObligation {
            kind = requireText(kind, "kind");
            leftTermExpression = requireText(
                leftTermExpression,
                "leftTermExpression");
            rightTermExpression = requireText(
                rightTermExpression,
                "rightTermExpression");
            commonPlaceholder = requireText(
                commonPlaceholder,
                "commonPlaceholder");
            equationTemplate = requireText(
                equationTemplate,
                "equationTemplate");
        }
    }

    public record Certificate(
        String schema,
        String solverId,
        String leftTermExpression,
        String rightTermExpression,
        String commonFactorExpression,
        String leftRemainderExpression,
        String rightRemainderExpression,
        String preparedExpression,
        String resultExpression,
        String leftMonomialDescriptor,
        String rightMonomialDescriptor,
        String commonMonomialDescriptor,
        String contentHash
    ) {
        public Certificate {
            schema = requireText(schema, "schema");
            solverId = requireText(solverId, "solverId");
            leftTermExpression = requireText(
                leftTermExpression,
                "leftTermExpression");
            rightTermExpression = requireText(
                rightTermExpression,
                "rightTermExpression");
            commonFactorExpression = requireText(
                commonFactorExpression,
                "commonFactorExpression");
            leftRemainderExpression = requireText(
                leftRemainderExpression,
                "leftRemainderExpression");
            rightRemainderExpression = requireText(
                rightRemainderExpression,
                "rightRemainderExpression");
            preparedExpression = requireText(
                preparedExpression,
                "preparedExpression");
            resultExpression = requireText(
                resultExpression,
                "resultExpression");
            leftMonomialDescriptor = requireText(
                leftMonomialDescriptor,
                "leftMonomialDescriptor");
            rightMonomialDescriptor = requireText(
                rightMonomialDescriptor,
                "rightMonomialDescriptor");
            commonMonomialDescriptor = requireText(
                commonMonomialDescriptor,
                "commonMonomialDescriptor");
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
        Expr commonFactor,
        Expr leftRemainder,
        Expr rightRemainder,
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
            commonFactor = Objects.requireNonNull(
                commonFactor,
                "commonFactor");
            leftRemainder = Objects.requireNonNull(
                leftRemainder,
                "leftRemainder");
            rightRemainder = Objects.requireNonNull(
                rightRemainder,
                "rightRemainder");
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
            certificate = Objects.requireNonNull(certificate, "certificate");
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

        private static PlanAttempt prepared(PreparedApplication application) {
            return new PlanAttempt(
                Status.PREPARED,
                Optional.of(application),
                Optional.of(application.residualObligation()),
                application.work(),
                "greatest-common-monomial-prepared");
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
            Optional<ResidualObligation> residualObligation,
            WorkLedger work,
            String detail
        ) {
            return new PlanAttempt(
                status,
                Optional.empty(),
                residualObligation,
                work,
                detail);
        }
    }

    private record ParsedTerms(
        ExactPositiveMonomial left,
        ExactPositiveMonomial right
    ) {
        private ParsedTerms {
            left = Objects.requireNonNull(left, "left");
            right = Objects.requireNonNull(right, "right");
        }
    }

    private record ExactFactorization(
        ExactPositiveMonomial common,
        ExactPositiveMonomial leftRemainder,
        ExactPositiveMonomial rightRemainder
    ) {
        private ExactFactorization {
            common = Objects.requireNonNull(common, "common");
            leftRemainder = Objects.requireNonNull(
                leftRemainder,
                "leftRemainder");
            rightRemainder = Objects.requireNonNull(
                rightRemainder,
                "rightRemainder");
        }
    }

    private record ExpectedApplication(
        Expr common,
        Expr leftRemainder,
        Expr rightRemainder,
        Expr prepared,
        Expr result
    ) {
        private ExpectedApplication {
            common = Objects.requireNonNull(common, "common");
            leftRemainder = Objects.requireNonNull(
                leftRemainder,
                "leftRemainder");
            rightRemainder = Objects.requireNonNull(
                rightRemainder,
                "rightRemainder");
            prepared = Objects.requireNonNull(prepared, "prepared");
            result = Objects.requireNonNull(result, "result");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
