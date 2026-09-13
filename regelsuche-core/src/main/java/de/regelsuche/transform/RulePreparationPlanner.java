package de.regelsuche.transform;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
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
    public static final String MEASUREMENT_CONTRACT =
        "regelsuche.exact-polynomial-preparation-work/v1";

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

    /** Observes one plan through the caller's shared, non-resetting authority. */
    public MeasuredAttempt planObserved(Expr subtree, PolynomialWorkAuthority authority) {
        Objects.requireNonNull(subtree, "subtree");
        var observation = new PreparationObservation(authority);
        try {
            PlanAttempt attempt = plan(subtree, observation.scope("plan"));
            return new MeasuredAttempt(subtree, Optional.of(attempt), MeasurementOutcome.COMPLETED,
                "COMPLETED", "", observation.ledger(), observation.refusedCharge());
        } catch (PolynomialWorkAuthority.LimitReached failure) {
            return new MeasuredAttempt(subtree, Optional.empty(), MeasurementOutcome.BUDGET_INCONCLUSIVE,
                "SHARED_POLYNOMIAL_WORK_AUTHORITY_EXHAUSTED", failure.getClass().getName(),
                observation.ledger(), observation.refusedCharge());
        } catch (RuntimeException | StackOverflowError failure) {
            return new MeasuredAttempt(subtree, Optional.empty(), MeasurementOutcome.TECHNICAL_FAILURE,
                "EXACT_PREPARATION_TECHNICAL_FAILURE", failure.getClass().getName(),
                observation.ledger(), observation.refusedCharge());
        }
    }

    /** Independently repeats verification through the same cumulative authority. */
    public MeasuredVerification verifyObserved(PreparedRuleApplication application,
            PolynomialWorkAuthority authority) {
        var observation = new PreparationObservation(authority);
        try {
            boolean verified = verify(application, observation.scope("verify"));
            return new MeasuredVerification(application, verified, MeasurementOutcome.COMPLETED,
                verified ? "VERIFIED" : "REJECTED", "", observation.ledger(), observation.refusedCharge());
        } catch (PolynomialWorkAuthority.LimitReached failure) {
            return new MeasuredVerification(application, false, MeasurementOutcome.BUDGET_INCONCLUSIVE,
                "SHARED_POLYNOMIAL_WORK_AUTHORITY_EXHAUSTED", failure.getClass().getName(),
                observation.ledger(), observation.refusedCharge());
        } catch (RuntimeException | StackOverflowError failure) {
            return new MeasuredVerification(application, false, MeasurementOutcome.TECHNICAL_FAILURE,
                "EXACT_PREPARATION_VERIFICATION_TECHNICAL_FAILURE", failure.getClass().getName(),
                observation.ledger(), observation.refusedCharge());
        }
    }

    public enum MeasurementOutcome { COMPLETED, BUDGET_INCONCLUSIVE, TECHNICAL_FAILURE }

    /** Issued only by actual planner execution; caller ledgers cannot construct an observation. */
    public static final class MeasuredAttempt {
        private final Expr input;
        private final Optional<PlanAttempt> attempt;
        private final MeasurementOutcome outcome;
        private final String detailCode, failureClass;
        private final PolynomialWorkLedger work;
        private final Optional<PolynomialWorkLedger> refusedCharge;
        private MeasuredAttempt(Expr input, Optional<PlanAttempt> attempt, MeasurementOutcome outcome,
                String detailCode, String failureClass, PolynomialWorkLedger work,
                Optional<PolynomialWorkLedger> refusedCharge) {
            this.input = input; this.attempt = attempt; this.outcome = outcome; this.detailCode = detailCode;
            this.failureClass = failureClass; this.work = work; this.refusedCharge = refusedCharge;
        }
        public Expr input() { return input; }
        public Optional<PlanAttempt> attempt() { return attempt; }
        public MeasurementOutcome outcome() { return outcome; }
        public boolean completed() { return outcome == MeasurementOutcome.COMPLETED; }
        public String detailCode() { return detailCode; }
        public String failureClass() { return failureClass; }
        public String measurementContract() { return MEASUREMENT_CONTRACT; }
        public PolynomialWorkLedger work() { return work; }
        public Optional<PolynomialWorkLedger> refusedCharge() { return refusedCharge; }
    }

    /** A fresh verification observation, never a caller-issued proof or reset budget. */
    public static final class MeasuredVerification {
        private final PreparedRuleApplication input;
        private final boolean verified;
        private final MeasurementOutcome outcome;
        private final String detailCode, failureClass;
        private final PolynomialWorkLedger work;
        private final Optional<PolynomialWorkLedger> refusedCharge;
        private MeasuredVerification(PreparedRuleApplication input, boolean verified, MeasurementOutcome outcome,
                String detailCode, String failureClass, PolynomialWorkLedger work,
                Optional<PolynomialWorkLedger> refusedCharge) {
            this.input = input; this.verified = verified; this.outcome = outcome; this.detailCode = detailCode;
            this.failureClass = failureClass; this.work = work; this.refusedCharge = refusedCharge;
        }
        public Optional<PreparedRuleApplication> input() { return Optional.ofNullable(input); }
        public boolean verified() { return verified; }
        public MeasurementOutcome outcome() { return outcome; }
        public boolean completed() { return outcome == MeasurementOutcome.COMPLETED; }
        public String detailCode() { return detailCode; }
        public String failureClass() { return failureClass; }
        public String measurementContract() { return MEASUREMENT_CONTRACT; }
        public PolynomialWorkLedger work() { return work; }
        public Optional<PolynomialWorkLedger> refusedCharge() { return refusedCharge; }
    }

    /**
     * Analyzes one AST subtree against the cancellation preparation schema.
     *
     * @return a deterministic attempt that distinguishes non-applicability,
     *     unsupported input, an absent exact quotient and budget exhaustion
     */
    public PlanAttempt plan(Expr subtree) {
        return plan(subtree, PolynomialWorkAuthority.unbounded());
    }

    private PlanAttempt plan(Expr subtree, PolynomialWorkAuthority work) {
        Objects.requireNonNull(subtree, "subtree");
        work.consume("planner.dispatches", 1);
        var arithmetic = new PreparationArithmetic(work);
        WorkLedger untouched = WorkLedger.untouched(budget.maxSolverAttempts());
        if (!(subtree instanceof BinaryExpr division)
                || division.operator() != BinaryOperator.DIV) {
            return PlanAttempt.withoutApplication(
                Status.NOT_APPLICABLE,
                untouched,
                "root-is-not-division");
        }
        if (isExplicitZero(division.right(), arithmetic)) {
            return PlanAttempt.withoutApplication(
                Status.UNSUPPORTED,
                untouched,
                "explicit-zero-divisor");
        }
        if (isDirectCancellation(division.left(), division.right(), arithmetic)) {
            return PlanAttempt.withoutApplication(
                Status.DIRECT_MATCH_AVAILABLE,
                untouched,
                "principal-rule-already-matches");
        }
        ResidualObligation obligation = residualObligation(division, work);
        if (budget.maxSolverAttempts() == 0) {
            return PlanAttempt.withoutApplication(
                Status.BUDGET_INCONCLUSIVE,
                Optional.of(obligation),
                untouched,
                "solver-attempt-budget-exhausted");
        }

        WorkLedger attempted = WorkLedger.afterOneAttempt(
            budget.maxSolverAttempts());
        work.consume("solver.invocations", 1);
        UnivariatePolynomial divisor =
            UnivariatePolynomial.of(division.right(), work);
        if (divisor == null || divisor.isConstant()) {
            return PlanAttempt.withoutApplication(
                Status.UNSUPPORTED,
                Optional.of(obligation),
                attempted,
                "divisor-outside-exact-nonconstant-univariate-polynomial-fragment");
        }
        UnivariatePolynomial dividend =
            UnivariatePolynomial.of(division.left(), work);
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
        Expr preparedNumerator = arithmetic.binary(
            division.right(),
            BinaryOperator.MUL,
            quotientExpression);
        Expr preparedSubtree = arithmetic.binary(
            preparedNumerator,
            BinaryOperator.DIV,
            division.right());
        List<String> assumptions = assumptionsFor(division.right(), work);
        Certificate certificate = certificate(
            division.left(),
            division.right(),
            quotientExpression,
            preparedSubtree, work);
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
        if (!verify(application, PreparationObservation.phase(work, "internal-verification"))) {
            throw new IllegalStateException(
                "generated rule-preparation application failed verification");
        }
        return PlanAttempt.prepared(application);
    }

    /** Independently recomputes the exact quotient and all bound plan fields. */
    public boolean verify(PreparedRuleApplication application) {
        return verify(application, PolynomialWorkAuthority.unbounded());
    }

    private boolean verify(PreparedRuleApplication application, PolynomialWorkAuthority work) {
        work.consume("verification.dispatches", 1);
        var arithmetic = new PreparationArithmetic(work);
        if (application == null
                || !arithmetic.sameText(APPLICATION_SCHEMA, application.schema())
                || !arithmetic.sameText(PLANNER_ID, application.plannerId())
                || !arithmetic.sameText(PRINCIPAL_RULE_ID, application.principalRuleId())
                || !arithmetic.sameStrings(List.of(PREPARATION_RULE_ID, PRINCIPAL_RULE_ID), application.primitiveRuleIds())
                || !(application.originalSubtree()
                    instanceof BinaryExpr division)
                || division.operator() != BinaryOperator.DIV
                || isExplicitZero(division.right(), arithmetic)) {
            return false;
        }
        work.consume("solver.invocations", 1);
        UnivariatePolynomial divisor =
            UnivariatePolynomial.of(division.right(), work);
        UnivariatePolynomial dividend =
            UnivariatePolynomial.of(division.left(), work);
        if (divisor == null || divisor.isConstant() || dividend == null) {
            return false;
        }
        UnivariatePolynomial quotient = dividend.divideExactly(divisor);
        if (quotient == null) {
            return false;
        }
        Expr expectedResult = quotient.toExpression();
        Expr expectedPrepared = arithmetic.binary(
            arithmetic.binary(
                division.right(),
                BinaryOperator.MUL,
                expectedResult),
            BinaryOperator.DIV,
            division.right());
        if (!arithmetic.sameExpression(expectedResult, application.resultSubtree())
                || !arithmetic.sameExpression(expectedPrepared, application.preparedSubtree())
                || !arithmetic.sameExpression(division.right(), application.bindings().get("A"))
                || !arithmetic.sameExpression(expectedResult, application.bindings().get("B"))
                || application.bindings().size() != 2
                || !sameObligation(residualObligation(division, work), application.residualObligation(), arithmetic)
                || !arithmetic.sameStrings(assumptionsFor(division.right(), work), application.assumptions())) {
            return false;
        }
        Certificate expectedCertificate = certificate(
            division.left(),
            division.right(),
            expectedResult,
            expectedPrepared, work);
        return sameCertificate(expectedCertificate, application.certificate(), arithmetic)
            && application.work().consumedSolverAttempts() == 1;
    }

    private static Certificate certificate(
        Expr dividend,
        Expr divisor,
        Expr quotient,
        Expr preparedSubtree,
        PolynomialWorkAuthority work
    ) {
        String dividendText = format(dividend, work);
        String divisorText = format(divisor, work);
        String quotientText = format(quotient, work);
        String preparedText = format(preparedSubtree, work);
        String payload;
        if (work == PolynomialWorkAuthority.unbounded()) {
            payload = String.join("\n", "schema=" + CERTIFICATE_SCHEMA, "planner=" + PLANNER_ID,
                "principalRule=" + PRINCIPAL_RULE_ID, "solver=" + SOLVER_ID,
                "dividend=" + dividendText, "divisor=" + divisorText, "quotient=" + quotientText,
                "remainder=0", "prepared=" + preparedText);
        } else {
            String[] fields = { certificateField(work, "schema=", CERTIFICATE_SCHEMA),
                certificateField(work, "planner=", PLANNER_ID), certificateField(work, "principalRule=", PRINCIPAL_RULE_ID),
                certificateField(work, "solver=", SOLVER_ID), certificateField(work, "dividend=", dividendText),
                certificateField(work, "divisor=", divisorText), certificateField(work, "quotient=", quotientText),
                "remainder=0", certificateField(work, "prepared=", preparedText) };
            long materialLength = fields.length - 1L;
            for (String field : fields) materialLength = Math.addExact(materialLength, field.length());
            work.consume("certificate.material-code-units", materialLength);
            payload = String.join("\n", fields);
        }
        return new Certificate(
            CERTIFICATE_SCHEMA,
            SOLVER_ID,
            dividendText,
            divisorText,
            quotientText,
            "0",
            preparedText,
            sha256(payload, work));
    }

    private static ResidualObligation residualObligation(
        BinaryExpr division,
        PolynomialWorkAuthority work
    ) {
        String dividend = format(division.left(), work);
        String divisor = format(division.right(), work);
        work.consume("obligation.material-code-units", dividend.length() + 9L + divisor.length());
        return new ResidualObligation(
            "EXACT_FACTOR",
            dividend,
            divisor,
            "B",
            dividend + " = (" + divisor + ") * B");
    }

    private static List<String> assumptionsFor(Expr divisor, PolynomialWorkAuthority work) {
        if (divisor instanceof NumberExpr) {
            return List.of();
        }
        String text = format(divisor, work);
        work.consume("assumption.material-code-units", text.length() + 5L);
        return List.of(Assumption.nonZero(text).expression());
    }

    private static boolean isDirectCancellation(
        Expr numerator,
        Expr divisor,
        PreparationArithmetic arithmetic
    ) {
        if (!(numerator instanceof BinaryExpr product)
                || product.operator() != BinaryOperator.MUL) {
            return false;
        }
        return arithmetic.sameExpression(product.left(), divisor)
            || arithmetic.sameExpression(product.right(), divisor);
    }

    private static boolean isExplicitZero(Expr expression, PreparationArithmetic arithmetic) {
        if (arithmetic.work == PolynomialWorkAuthority.unbounded()) return expression instanceof NumberExpr number
            && number.value().equalsInteger(0);
        return expression instanceof NumberExpr number
            && arithmetic.isInteger(number.value())
            && arithmetic.equal(number.value().numerator(), java.math.BigInteger.ZERO);
    }

    private static String sha256(String value, PolynomialWorkAuthority work) {
        try {
            if (work == PolynomialWorkAuthority.unbounded()) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
                return java.util.HexFormat.of().formatHex(digest);
            }
            work.consume("certificate.utf8-input-code-units", value.length());
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            work.consume(new PolynomialWorkLedger(Map.of("certificate.sha256-invocations", 1L,
                "certificate.sha256-input-bytes", (long) bytes.length)));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes);
            work.consume("certificate.hex-output-code-units", digest.length * 2L);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String certificateField(PolynomialWorkAuthority work, String label, String value) {
        work.consume("certificate.field-code-units", label.length() + (long) value.length());
        return label + value;
    }

    private static String format(Expr expression, PolynomialWorkAuthority work) {
        return work == PolynomialWorkAuthority.unbounded() ? ExpressionFormatter.format(expression)
            : ExpressionFormatter.formatMeasured(expression, work);
    }

    private static boolean sameObligation(ResidualObligation first, ResidualObligation second, PreparationArithmetic arithmetic) {
        if (arithmetic.work == PolynomialWorkAuthority.unbounded()) return first.equals(second);
        return arithmetic.sameText(first.kind(), second.kind())
            && arithmetic.sameText(first.actualExpression(), second.actualExpression())
            && arithmetic.sameText(first.knownFactorExpression(), second.knownFactorExpression())
            && arithmetic.sameText(first.quotientPlaceholder(), second.quotientPlaceholder())
            && arithmetic.sameText(first.equationTemplate(), second.equationTemplate());
    }

    private static boolean sameCertificate(Certificate first, Certificate second, PreparationArithmetic arithmetic) {
        if (arithmetic.work == PolynomialWorkAuthority.unbounded()) return first.equals(second);
        return arithmetic.sameText(first.schema(), second.schema()) && arithmetic.sameText(first.solverId(), second.solverId())
            && arithmetic.sameText(first.dividendExpression(), second.dividendExpression())
            && arithmetic.sameText(first.divisorExpression(), second.divisorExpression())
            && arithmetic.sameText(first.quotientExpression(), second.quotientExpression())
            && arithmetic.sameText(first.remainderExpression(), second.remainderExpression())
            && arithmetic.sameText(first.preparedExpression(), second.preparedExpression())
            && arithmetic.sameText(first.contentHash(), second.contentHash());
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
