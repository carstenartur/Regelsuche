package de.regelsuche.transform;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded planner for making a structurally close rewrite rule applicable.
 *
 * <p>The first supported schema is factor preparation for the existing
 * {@code ast_cancel_division_factor} rule. Given {@code P / A}, the planner
 * binds the already visible divisor to {@code A} and asks the exact univariate
 * polynomial solver for {@code B} in {@code P = A * B}. A plan is emitted only
 * when the quotient is exact. No target expression or arbitrary zero-term
 * enumeration is used.</p>
 */
public final class RulePreparationPlanner {
    public static final String PLANNER_ID =
        "rule-preparation-planner/exact-polynomial-cancellation/v1";
    public static final String CERTIFICATE_SCHEMA =
        "regelsuche.rule-preparation-certificate/v1";
    public static final String APPLICATION_SCHEMA =
        "regelsuche.rule-preparation-application/v1";
    public static final String SOLVER_ID =
        "exact-univariate-polynomial-quotient/v1";
    public static final String PREPARATION_RULE_ID =
        "prepare_exact_polynomial_factor";
    public static final String PRINCIPAL_RULE_ID =
        "ast_cancel_division_factor";

    private final Budget budget;

    public RulePreparationPlanner() {
        this(Budget.DEFAULT);
    }

    public RulePreparationPlanner(Budget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public Budget budget() {
        return budget;
    }

    /**
     * Analyzes one AST subtree against the cancellation preparation schema.
     *
     * @return a deterministic attempt that distinguishes non-applicability,
     *     unsupported input, an absent exact quotient and budget exhaustion
     */
    public PlanAttempt plan(Expr subtree) {
        Objects.requireNonNull(subtree, "subtree");
        WorkLedger untouched = WorkLedger.untouched(budget.maxSolverAttempts());
        if (!(subtree instanceof BinaryExpr division)
                || division.operator() != BinaryOperator.DIV) {
            return PlanAttempt.withoutApplication(
                Status.NOT_APPLICABLE,
                untouched,
                "root-is-not-division");
        }
        if (isExplicitZero(division.right())) {
            return PlanAttempt.withoutApplication(
                Status.UNSUPPORTED,
                untouched,
                "explicit-zero-divisor");
        }
        if (isDirectCancellation(division.left(), division.right())) {
            return PlanAttempt.withoutApplication(
                Status.DIRECT_MATCH_AVAILABLE,
                untouched,
                "principal-rule-already-matches");
        }
        ResidualObligation obligation = residualObligation(division);
        if (budget.maxSolverAttempts() == 0) {
            return PlanAttempt.withoutApplication(
                Status.BUDGET_INCONCLUSIVE,
                Optional.of(obligation),
                untouched,
                "solver-attempt-budget-exhausted");
        }

        WorkLedger attempted = WorkLedger.afterOneAttempt(
            budget.maxSolverAttempts());
        UnivariatePolynomial divisor =
            UnivariatePolynomial.of(division.right());
        if (divisor == null || divisor.isConstant()) {
            return PlanAttempt.withoutApplication(
                Status.UNSUPPORTED,
                Optional.of(obligation),
                attempted,
                "divisor-outside-exact-nonconstant-univariate-polynomial-fragment");
        }
        UnivariatePolynomial dividend =
            UnivariatePolynomial.of(division.left());
        if (dividend == null) {
            return PlanAttempt.withoutApplication(
                Status.UNSUPPORTED,
                Optional.of(obligation),
                attempted,
                "dividend-outside-exact-univariate-polynomial-fragment");
        }
        UnivariatePolynomial quotient = dividend.divideExactly(divisor);
        if (quotient == null) {
            return PlanAttempt.withoutApplication(
                Status.NO_EXACT_QUOTIENT,
                Optional.of(obligation),
                attempted,
                "division-has-no-supported-exact-quotient");
        }

        Expr quotientExpression = quotient.toExpression();
        Expr preparedNumerator = new BinaryExpr(
            division.right(),
            BinaryOperator.MUL,
            quotientExpression);
        Expr preparedSubtree = new BinaryExpr(
            preparedNumerator,
            BinaryOperator.DIV,
            division.right());
        List<String> assumptions = assumptionsFor(division.right());
        Certificate certificate = certificate(
            division.left(),
            division.right(),
            quotientExpression,
            preparedSubtree);
        PreparedRuleApplication application = new PreparedRuleApplication(
            APPLICATION_SCHEMA,
            PLANNER_ID,
            PRINCIPAL_RULE_ID,
            subtree,
            preparedSubtree,
            quotientExpression,
            Map.of(
                "A", division.right(),
                "B", quotientExpression),
            obligation,
            assumptions,
            List.of(PREPARATION_RULE_ID, PRINCIPAL_RULE_ID),
            certificate,
            attempted);
        if (!verify(application)) {
            throw new IllegalStateException(
                "generated rule-preparation application failed verification");
        }
        return PlanAttempt.prepared(application);
    }

    /** Independently recomputes the exact quotient and all bound plan fields. */
    public boolean verify(PreparedRuleApplication application) {
        if (application == null
                || !APPLICATION_SCHEMA.equals(application.schema())
                || !PLANNER_ID.equals(application.plannerId())
                || !PRINCIPAL_RULE_ID.equals(application.principalRuleId())
                || !List.of(PREPARATION_RULE_ID, PRINCIPAL_RULE_ID)
                    .equals(application.primitiveRuleIds())
                || !(application.originalSubtree()
                    instanceof BinaryExpr division)
                || division.operator() != BinaryOperator.DIV
                || isExplicitZero(division.right())) {
            return false;
        }
        UnivariatePolynomial divisor =
            UnivariatePolynomial.of(division.right());
        UnivariatePolynomial dividend =
            UnivariatePolynomial.of(division.left());
        if (divisor == null || divisor.isConstant() || dividend == null) {
            return false;
        }
        UnivariatePolynomial quotient = dividend.divideExactly(divisor);
        if (quotient == null) {
            return false;
        }
        Expr expectedResult = quotient.toExpression();
        Expr expectedPrepared = new BinaryExpr(
            new BinaryExpr(
                division.right(),
                BinaryOperator.MUL,
                expectedResult),
            BinaryOperator.DIV,
            division.right());
        if (!expectedResult.equals(application.resultSubtree())
                || !expectedPrepared.equals(application.preparedSubtree())
                || !division.right().equals(application.bindings().get("A"))
                || !expectedResult.equals(application.bindings().get("B"))
                || application.bindings().size() != 2
                || !residualObligation(division)
                    .equals(application.residualObligation())
                || !assumptionsFor(division.right())
                    .equals(application.assumptions())) {
            return false;
        }
        Certificate expectedCertificate = certificate(
            division.left(),
            division.right(),
            expectedResult,
            expectedPrepared);
        return expectedCertificate.equals(application.certificate())
            && application.work().consumedSolverAttempts() == 1;
    }

    private static Certificate certificate(
        Expr dividend,
        Expr divisor,
        Expr quotient,
        Expr preparedSubtree
    ) {
        String dividendText = ExpressionFormatter.format(dividend);
        String divisorText = ExpressionFormatter.format(divisor);
        String quotientText = ExpressionFormatter.format(quotient);
        String preparedText = ExpressionFormatter.format(preparedSubtree);
        String payload = String.join("\n",
            "schema=" + CERTIFICATE_SCHEMA,
            "planner=" + PLANNER_ID,
            "principalRule=" + PRINCIPAL_RULE_ID,
            "solver=" + SOLVER_ID,
            "dividend=" + dividendText,
            "divisor=" + divisorText,
            "quotient=" + quotientText,
            "remainder=0",
            "prepared=" + preparedText);
        return new Certificate(
            CERTIFICATE_SCHEMA,
            SOLVER_ID,
            dividendText,
            divisorText,
            quotientText,
            "0",
            preparedText,
            sha256(payload));
    }

    private static ResidualObligation residualObligation(
        BinaryExpr division
    ) {
        String dividend = ExpressionFormatter.format(division.left());
        String divisor = ExpressionFormatter.format(division.right());
        return new ResidualObligation(
            "EXACT_FACTOR",
            dividend,
            divisor,
            "B",
            dividend + " = (" + divisor + ") * B");
    }

    private static List<String> assumptionsFor(Expr divisor) {
        if (divisor instanceof NumberExpr) {
            return List.of();
        }
        return List.of(
            Assumption.nonZero(ExpressionFormatter.format(divisor))
                .expression());
    }

    private static boolean isDirectCancellation(
        Expr numerator,
        Expr divisor
    ) {
        if (!(numerator instanceof BinaryExpr product)
                || product.operator() != BinaryOperator.MUL) {
            return false;
        }
        return product.left().equals(divisor)
            || product.right().equals(divisor);
    }

    private static boolean isExplicitZero(Expr expression) {
        return expression instanceof NumberExpr number
            && number.value() == 0;
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
        NO_EXACT_QUOTIENT,
        BUDGET_INCONCLUSIVE
    }

    public record Budget(int maxSolverAttempts) {
        public static final Budget DEFAULT = new Budget(1);

        public Budget {
            if (maxSolverAttempts < 0) {
                throw new IllegalArgumentException(
                    "maxSolverAttempts must not be negative");
            }
        }
    }

    public record WorkLedger(
        int configuredSolverAttempts,
        int consumedSolverAttempts,
        int remainingSolverAttempts
    ) {
        public WorkLedger {
            if (configuredSolverAttempts < 0
                    || consumedSolverAttempts < 0
                    || remainingSolverAttempts < 0
                    || configuredSolverAttempts
                        != consumedSolverAttempts
                            + remainingSolverAttempts) {
                throw new IllegalArgumentException(
                    "solver work must be non-negative and balanced");
            }
        }

        private static WorkLedger untouched(int configured) {
            return new WorkLedger(configured, 0, configured);
        }

        private static WorkLedger afterOneAttempt(int configured) {
            if (configured < 1) {
                throw new IllegalArgumentException(
                    "one solver attempt requires positive configured work");
            }
            return new WorkLedger(configured, 1, configured - 1);
        }
    }

    public record ResidualObligation(
        String kind,
        String actualExpression,
        String knownFactorExpression,
        String quotientPlaceholder,
        String equationTemplate
    ) {
        public ResidualObligation {
            if (kind == null || kind.isBlank()
                    || actualExpression == null
                        || actualExpression.isBlank()
                    || knownFactorExpression == null
                        || knownFactorExpression.isBlank()
                    || quotientPlaceholder == null
                        || quotientPlaceholder.isBlank()
                    || equationTemplate == null
                        || equationTemplate.isBlank()) {
                throw new IllegalArgumentException(
                    "residual-obligation fields must not be blank");
            }
        }
    }

    public record Certificate(
        String schema,
        String solverId,
        String dividendExpression,
        String divisorExpression,
        String quotientExpression,
        String remainderExpression,
        String preparedExpression,
        String contentHash
    ) {
        public Certificate {
            if (schema == null || schema.isBlank()
                    || solverId == null || solverId.isBlank()
                    || dividendExpression == null
                        || dividendExpression.isBlank()
                    || divisorExpression == null
                        || divisorExpression.isBlank()
                    || quotientExpression == null
                        || quotientExpression.isBlank()
                    || remainderExpression == null
                        || remainderExpression.isBlank()
                    || preparedExpression == null
                        || preparedExpression.isBlank()
                    || contentHash == null || contentHash.isBlank()) {
                throw new IllegalArgumentException(
                    "certificate fields must not be blank");
            }
        }
    }

    public record PreparedRuleApplication(
        String schema,
        String plannerId,
        String principalRuleId,
        Expr originalSubtree,
        Expr preparedSubtree,
        Expr resultSubtree,
        Map<String, Expr> bindings,
        ResidualObligation residualObligation,
        List<String> assumptions,
        List<String> primitiveRuleIds,
        Certificate certificate,
        WorkLedger work
    ) {
        public PreparedRuleApplication {
            if (schema == null || schema.isBlank()
                    || plannerId == null || plannerId.isBlank()
                    || principalRuleId == null
                        || principalRuleId.isBlank()
                    || originalSubtree == null
                    || preparedSubtree == null
                    || resultSubtree == null
                    || bindings == null
                    || residualObligation == null
                    || assumptions == null
                    || primitiveRuleIds == null
                        || primitiveRuleIds.isEmpty()
                    || certificate == null
                    || work == null) {
                throw new IllegalArgumentException(
                    "prepared application fields are required");
            }
            bindings = Map.copyOf(bindings);
            assumptions = List.copyOf(assumptions);
            primitiveRuleIds = List.copyOf(primitiveRuleIds);
        }
    }

    public record PlanAttempt(
        Status status,
        Optional<PreparedRuleApplication> application,
        Optional<ResidualObligation> residualObligation,
        WorkLedger work,
        String detail
    ) {
        public PlanAttempt {
            Objects.requireNonNull(status, "status");
            application = Objects.requireNonNull(
                application,
                "application");
            residualObligation = Objects.requireNonNull(
                residualObligation,
                "residualObligation");
            Objects.requireNonNull(work, "work");
            detail = detail == null ? "" : detail;
            if ((status == Status.PREPARED) != application.isPresent()) {
                throw new IllegalArgumentException(
                    "only PREPARED attempts may contain an application");
            }
            if (application.isPresent()
                    && (!application.get().work().equals(work)
                        || !residualObligation.equals(Optional.of(
                            application.get().residualObligation())))) {
                throw new IllegalArgumentException(
                    "attempt and application work must agree");
            }
        }

        private static PlanAttempt prepared(
            PreparedRuleApplication application
        ) {
            return new PlanAttempt(
                Status.PREPARED,
                Optional.of(application),
                Optional.of(application.residualObligation()),
                application.work(),
                "exact-polynomial-factor-prepared");
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
}
