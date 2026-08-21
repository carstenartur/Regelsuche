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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded rule-directed preparation planning for structurally close rules.
 *
 * <p>The first executable slice recognizes a failed application of
 * {@code ast_cancel_division_factor} on {@code P / D}. It binds the visible
 * divisor as {@code A = D}, solves the residual exact-factor obligation
 * {@code P = D * B}, materializes the prepared subtree and then applies the
 * ordinary cancellation rule. No target or preferred result is supplied to
 * the planner.</p>
 *
 * <p>Sessions own deterministic work budgets and a bounded invocation-local
 * cache. Unsupported fragments, non-exact divisions and exhausted budgets
 * fail closed.</p>
 */
public final class RulePreparationPlanner {
    public static final String REVISION = "rule-preparation-planner/v1";
    public static final String EXACT_POLYNOMIAL_FACTOR_SOLVER =
        "exact-univariate-polynomial-factor-solver/v1";
    public static final String EXACT_POLYNOMIAL_FACTOR_STEP =
        "prepare_exact_polynomial_factor";
    public static final String CANCELLATION_RULE_ID =
        "ast_cancel_division_factor";

    private final boolean enabled;

    private RulePreparationPlanner(boolean enabled) {
        this.enabled = enabled;
    }

    public static RulePreparationPlanner standard() {
        return new RulePreparationPlanner(true);
    }

    public static RulePreparationPlanner disabled() {
        return new RulePreparationPlanner(false);
    }

    public boolean enabled() {
        return enabled;
    }

    public Session openSession(Budget budget) {
        return new Session(enabled, Objects.requireNonNull(budget, "budget"));
    }

    public record Budget(int maxSolverAttempts, int maxPreparedApplications) {
        public Budget {
            if (maxSolverAttempts < 0 || maxPreparedApplications < 0) {
                throw new IllegalArgumentException(
                    "preparation budgets must not be negative");
            }
        }

        public static Budget defaults() {
            return new Budget(16, 8);
        }

        public static Budget disabled() {
            return new Budget(0, 0);
        }
    }

    public record Context(
        String assumptionSignature,
        String ruleInventoryHash
    ) {
        public Context {
            assumptionSignature = normalizeIdentity(
                assumptionSignature,
                "NO_DECLARED_ASSUMPTIONS"
            );
            ruleInventoryHash = normalizeIdentity(
                ruleInventoryHash,
                "UNBOUND_RULE_INVENTORY"
            );
        }

        public static Context unqualified(String ruleInventoryHash) {
            return new Context("NO_DECLARED_ASSUMPTIONS", ruleInventoryHash);
        }
    }

    public enum Status {
        PREPARED,
        NOT_APPLICABLE,
        UNSUPPORTED,
        BUDGET_EXHAUSTED,
        INVALID_CERTIFICATE,
        DISABLED
    }

    public record Work(
        int configuredSolverAttempts,
        int consumedSolverAttempts,
        int remainingSolverAttempts,
        int configuredPreparedApplications,
        int consumedPreparedApplications,
        int remainingPreparedApplications,
        int cacheHits
    ) {
        public Work {
            if (configuredSolverAttempts < 0
                    || consumedSolverAttempts < 0
                    || remainingSolverAttempts < 0
                    || configuredPreparedApplications < 0
                    || consumedPreparedApplications < 0
                    || remainingPreparedApplications < 0
                    || cacheHits < 0
                    || consumedSolverAttempts + remainingSolverAttempts
                        != configuredSolverAttempts
                    || consumedPreparedApplications
                        + remainingPreparedApplications
                        != configuredPreparedApplications) {
                throw new IllegalArgumentException(
                    "preparation work accounting must be non-negative and balanced");
            }
        }
    }

    public record PlanningResult(
        Status status,
        List<PreparedRuleApplication> applications,
        Work work,
        String detail
    ) {
        public PlanningResult {
            status = Objects.requireNonNull(status, "status");
            applications = List.copyOf(
                Objects.requireNonNull(applications, "applications"));
            work = Objects.requireNonNull(work, "work");
            detail = detail == null ? "" : detail;
            if (status == Status.PREPARED && applications.isEmpty()) {
                throw new IllegalArgumentException(
                    "prepared result must contain an application");
            }
            if (status != Status.PREPARED && !applications.isEmpty()) {
                throw new IllegalArgumentException(
                    "non-prepared result must not contain applications");
            }
        }
    }

    public record PolynomialFactorizationCertificate(
        String schema,
        Expr dividend,
        Expr divisor,
        Expr quotient,
        Expr remainder
    ) {
        public static final String SCHEMA =
            "regelsuche.polynomial-factorization-certificate/v1";

        public PolynomialFactorizationCertificate {
            schema = normalizeIdentity(schema, SCHEMA);
            dividend = Objects.requireNonNull(dividend, "dividend");
            divisor = Objects.requireNonNull(divisor, "divisor");
            quotient = Objects.requireNonNull(quotient, "quotient");
            remainder = Objects.requireNonNull(remainder, "remainder");
        }

        public static PolynomialFactorizationCertificate exact(
            Expr dividend,
            Expr divisor,
            Expr quotient
        ) {
            return new PolynomialFactorizationCertificate(
                SCHEMA,
                dividend,
                divisor,
                quotient,
                new NumberExpr(0)
            );
        }

        /**
         * Checks the retained witness through exact multiplication rather than
         * rerunning the long-division proposal algorithm.
         */
        public boolean verify() {
            UnivariatePolynomial dividendPolynomial =
                UnivariatePolynomial.of(dividend);
            UnivariatePolynomial divisorPolynomial =
                UnivariatePolynomial.of(divisor);
            UnivariatePolynomial quotientPolynomial =
                UnivariatePolynomial.of(quotient);
            UnivariatePolynomial remainderPolynomial =
                UnivariatePolynomial.of(remainder);
            if (dividendPolynomial == null
                    || divisorPolynomial == null
                    || quotientPolynomial == null
                    || remainderPolynomial == null
                    || divisorPolynomial.isZero()
                    || !remainderPolynomial.isZero()) {
                return false;
            }
            UnivariatePolynomial product =
                divisorPolynomial.multiplyExact(quotientPolynomial);
            return product != null && product.sameValue(dividendPolynomial);
        }

        public String statement() {
            Expr product = new BinaryExpr(
                divisor,
                BinaryOperator.MUL,
                quotient
            );
            Expr rightHandSide = new BinaryExpr(
                product,
                BinaryOperator.ADD,
                remainder
            );
            return ExpressionFormatter.format(dividend)
                + " = "
                + ExpressionFormatter.format(rightHandSide);
        }

        public String contentHash() {
            return sha256(
                schema
                    + "\ndividend=" + ExpressionFormatter.format(dividend)
                    + "\ndivisor=" + ExpressionFormatter.format(divisor)
                    + "\nquotient=" + ExpressionFormatter.format(quotient)
                    + "\nremainder=" + ExpressionFormatter.format(remainder)
            );
        }
    }

    public record PreparedRuleApplication(
        String plannerRevision,
        String solverId,
        String principalRuleId,
        Expr originalSubtree,
        Expr preparedSubtree,
        Expr resultSubtree,
        Map<String, Expr> bindings,
        List<String> residualObligations,
        List<Assumption> assumptions,
        PolynomialFactorizationCertificate certificate,
        List<String> primitiveRuleIds,
        Context context,
        Work work
    ) {
        public PreparedRuleApplication {
            plannerRevision = normalizeIdentity(plannerRevision, REVISION);
            solverId = normalizeIdentity(
                solverId,
                EXACT_POLYNOMIAL_FACTOR_SOLVER
            );
            principalRuleId = normalizeIdentity(
                principalRuleId,
                CANCELLATION_RULE_ID
            );
            originalSubtree = Objects.requireNonNull(
                originalSubtree,
                "originalSubtree"
            );
            preparedSubtree = Objects.requireNonNull(
                preparedSubtree,
                "preparedSubtree"
            );
            resultSubtree = Objects.requireNonNull(
                resultSubtree,
                "resultSubtree"
            );
            bindings = Map.copyOf(Objects.requireNonNull(bindings, "bindings"));
            residualObligations = List.copyOf(
                Objects.requireNonNull(
                    residualObligations,
                    "residualObligations"
                )
            );
            assumptions = List.copyOf(
                Objects.requireNonNull(assumptions, "assumptions"));
            certificate = Objects.requireNonNull(certificate, "certificate");
            primitiveRuleIds = List.copyOf(
                Objects.requireNonNull(primitiveRuleIds, "primitiveRuleIds"));
            context = Objects.requireNonNull(context, "context");
            work = Objects.requireNonNull(work, "work");
            if (primitiveRuleIds.size() < 2
                    || !primitiveRuleIds.getLast().equals(principalRuleId)) {
                throw new IllegalArgumentException(
                    "prepared application must end with its principal rule");
            }
            if (!certificate.verify()) {
                throw new IllegalArgumentException(
                    "prepared application requires a valid factorization certificate");
            }
        }

        public String solutionHash() {
            StringBuilder payload = new StringBuilder()
                .append(plannerRevision)
                .append("\nsolver=").append(solverId)
                .append("\nprincipal=").append(principalRuleId)
                .append("\noriginal=")
                    .append(ExpressionFormatter.format(originalSubtree))
                .append("\nprepared=")
                    .append(ExpressionFormatter.format(preparedSubtree))
                .append("\nresult=")
                    .append(ExpressionFormatter.format(resultSubtree))
                .append("\ncertificate=").append(certificate.contentHash())
                .append("\nassumptionSignature=")
                    .append(context.assumptionSignature())
                .append("\nruleInventoryHash=")
                    .append(context.ruleInventoryHash());
            bindings.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> payload
                    .append("\nbinding:").append(entry.getKey()).append('=')
                    .append(ExpressionFormatter.format(entry.getValue())));
            residualObligations.forEach(obligation -> payload
                .append("\nobligation=").append(obligation));
            assumptions.stream()
                .map(Assumption::expression)
                .sorted()
                .forEach(assumption -> payload
                    .append("\nassumption=").append(assumption));
            primitiveRuleIds.forEach(ruleId -> payload
                .append("\nprimitive=").append(ruleId));
            return sha256(payload.toString());
        }

        public String evidenceHash() {
            return sha256(
                solutionHash()
                    + "\nsolverWork=" + work.consumedSolverAttempts()
                    + "/" + work.configuredSolverAttempts()
                    + "\nplanWork=" + work.consumedPreparedApplications()
                    + "/" + work.configuredPreparedApplications()
                    + "\ncacheHits=" + work.cacheHits()
            );
        }
    }

    public static final class Session implements AutoCloseable {
        private final boolean enabled;
        private final Budget budget;
        private final Map<CacheKey, CachedPlanning> cache =
            new LinkedHashMap<>();
        private int consumedSolverAttempts;
        private int consumedPreparedApplications;
        private int cacheHits;
        private boolean closed;

        private Session(boolean enabled, Budget budget) {
            this.enabled = enabled;
            this.budget = budget;
        }

        public PlanningResult plan(
            RewriteRule rule,
            Expr subtree,
            Context context
        ) {
            ensureOpen();
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(subtree, "subtree");
            Context safeContext = context == null
                ? Context.unqualified("")
                : context;
            if (!enabled) {
                return result(Status.DISABLED, List.of(), "PLANNER_DISABLED");
            }
            if (!CANCELLATION_RULE_ID.equals(rule.id())) {
                return result(
                    Status.NOT_APPLICABLE,
                    List.of(),
                    "NO_PREPARATION_SCHEMA_FOR_RULE"
                );
            }
            DivisionShape shape = divisionShape(subtree);
            if (shape == null) {
                return result(
                    Status.NOT_APPLICABLE,
                    List.of(),
                    "CANCELLATION_REQUIRES_DIVISION"
                );
            }

            CacheKey key = new CacheKey(
                REVISION,
                EXACT_POLYNOMIAL_FACTOR_SOLVER,
                rule.id(),
                sha256(ExpressionFormatter.format(subtree)),
                safeContext.assumptionSignature(),
                safeContext.ruleInventoryHash()
            );
            CachedPlanning cached = cache.get(key);
            if (cached != null) {
                cacheHits++;
                return materializeCached(rule, cached, safeContext);
            }
            if (remainingPreparedApplications() == 0) {
                return result(
                    Status.BUDGET_EXHAUSTED,
                    List.of(),
                    "PREPARED_APPLICATION_BUDGET_EXHAUSTED"
                );
            }
            if (remainingSolverAttempts() == 0) {
                return result(
                    Status.BUDGET_EXHAUSTED,
                    List.of(),
                    "SOLVER_ATTEMPT_BUDGET_EXHAUSTED"
                );
            }
            consumedSolverAttempts++;
            CachedPlanning solved = solveExactPolynomialFactor(rule, shape);
            cache.put(key, solved);
            return materializeCached(rule, solved, safeContext);
        }

        public Work work() {
            return new Work(
                budget.maxSolverAttempts(),
                consumedSolverAttempts,
                remainingSolverAttempts(),
                budget.maxPreparedApplications(),
                consumedPreparedApplications,
                remainingPreparedApplications(),
                cacheHits
            );
        }

        private PlanningResult materializeCached(
            RewriteRule rule,
            CachedPlanning cached,
            Context context
        ) {
            if (cached.solution().isEmpty()) {
                return result(cached.status(), List.of(), cached.detail());
            }
            if (remainingPreparedApplications() == 0) {
                return result(
                    Status.BUDGET_EXHAUSTED,
                    List.of(),
                    "PREPARED_APPLICATION_BUDGET_EXHAUSTED"
                );
            }
            consumedPreparedApplications++;
            Solution solution = cached.solution().orElseThrow();
            PreparedRuleApplication application = solution.materialize(
                rule,
                context,
                work()
            );
            return result(
                Status.PREPARED,
                List.of(application),
                cached.detail()
            );
        }

        private CachedPlanning solveExactPolynomialFactor(
            RewriteRule principalRule,
            DivisionShape shape
        ) {
            UnivariatePolynomial dividend =
                UnivariatePolynomial.of(shape.dividend());
            UnivariatePolynomial divisor =
                UnivariatePolynomial.of(shape.divisor());
            if (dividend == null || divisor == null || divisor.isConstant()) {
                return CachedPlanning.empty(
                    Status.UNSUPPORTED,
                    "UNSUPPORTED_EXACT_UNIVARIATE_POLYNOMIAL_FRAGMENT"
                );
            }
            UnivariatePolynomial quotient = dividend.divideExactly(divisor);
            if (quotient == null) {
                return CachedPlanning.empty(
                    Status.NOT_APPLICABLE,
                    "DIVISION_NOT_EXACT"
                );
            }

            Expr quotientExpression = quotient.toExpression();
            PolynomialFactorizationCertificate certificate =
                PolynomialFactorizationCertificate.exact(
                    shape.dividend(),
                    shape.divisor(),
                    quotientExpression
                );
            if (!certificate.verify()) {
                return CachedPlanning.empty(
                    Status.INVALID_CERTIFICATE,
                    "FACTORIZATION_CERTIFICATE_REJECTED"
                );
            }

            Expr preparedNumerator = new BinaryExpr(
                shape.divisor(),
                BinaryOperator.MUL,
                quotientExpression
            );
            Expr preparedSubtree = new BinaryExpr(
                preparedNumerator,
                BinaryOperator.DIV,
                shape.divisor()
            );
            boolean matches;
            try {
                matches = principalRule.matches(preparedSubtree);
            } catch (RuntimeException exception) {
                return CachedPlanning.empty(
                    Status.NOT_APPLICABLE,
                    "PRINCIPAL_RULE_MATCH_FAILED"
                );
            }
            if (!matches) {
                return CachedPlanning.empty(
                    Status.NOT_APPLICABLE,
                    "PREPARED_SUBTREE_DID_NOT_MATCH_PRINCIPAL_RULE"
                );
            }
            List<Assumption> assumptions = principalRule.assumptions(
                preparedSubtree
            );
            Expr resultSubtree;
            try {
                resultSubtree = principalRule.apply(preparedSubtree);
            } catch (RuntimeException exception) {
                return CachedPlanning.empty(
                    Status.NOT_APPLICABLE,
                    "PRINCIPAL_RULE_APPLICATION_FAILED"
                );
            }
            if (resultSubtree == null || resultSubtree.equals(preparedSubtree)) {
                return CachedPlanning.empty(
                    Status.NOT_APPLICABLE,
                    "PRINCIPAL_RULE_PRODUCED_NO_CHANGE"
                );
            }

            Expr factorization = new BinaryExpr(
                shape.divisor(),
                BinaryOperator.MUL,
                quotientExpression
            );
            String residualObligation = ExpressionFormatter.format(
                shape.dividend()
            ) + " = " + ExpressionFormatter.format(factorization);
            Solution solution = new Solution(
                shape.original(),
                preparedSubtree,
                resultSubtree,
                Map.of("A", shape.divisor(), "B", quotientExpression),
                List.of(residualObligation),
                assumptions,
                certificate,
                List.of(EXACT_POLYNOMIAL_FACTOR_STEP, principalRule.id())
            );
            return new CachedPlanning(
                Status.PREPARED,
                Optional.of(solution),
                "EXACT_POLYNOMIAL_FACTOR_PREPARED"
            );
        }

        private PlanningResult result(
            Status status,
            List<PreparedRuleApplication> applications,
            String detail
        ) {
            return new PlanningResult(status, applications, work(), detail);
        }

        private int remainingSolverAttempts() {
            return budget.maxSolverAttempts() - consumedSolverAttempts;
        }

        private int remainingPreparedApplications() {
            return budget.maxPreparedApplications()
                - consumedPreparedApplications;
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException(
                    "rule preparation session is closed");
            }
        }

        @Override
        public void close() {
            cache.clear();
            closed = true;
        }
    }

    private record Solution(
        Expr originalSubtree,
        Expr preparedSubtree,
        Expr resultSubtree,
        Map<String, Expr> bindings,
        List<String> residualObligations,
        List<Assumption> assumptions,
        PolynomialFactorizationCertificate certificate,
        List<String> primitiveRuleIds
    ) {
        private PreparedRuleApplication materialize(
            RewriteRule principalRule,
            Context context,
            Work work
        ) {
            return new PreparedRuleApplication(
                REVISION,
                EXACT_POLYNOMIAL_FACTOR_SOLVER,
                principalRule.id(),
                originalSubtree,
                preparedSubtree,
                resultSubtree,
                bindings,
                residualObligations,
                assumptions,
                certificate,
                primitiveRuleIds,
                context,
                work
            );
        }
    }

    private record CachedPlanning(
        Status status,
        Optional<Solution> solution,
        String detail
    ) {
        private CachedPlanning {
            status = Objects.requireNonNull(status, "status");
            solution = Objects.requireNonNull(solution, "solution");
            detail = detail == null ? "" : detail;
        }

        private static CachedPlanning empty(Status status, String detail) {
            return new CachedPlanning(status, Optional.empty(), detail);
        }
    }

    private record CacheKey(
        String plannerRevision,
        String solverId,
        String principalRuleId,
        String subtreeHash,
        String assumptionSignature,
        String ruleInventoryHash
    ) {
    }

    private record DivisionShape(
        Expr original,
        Expr dividend,
        Expr divisor
    ) {
    }

    private static DivisionShape divisionShape(Expr subtree) {
        if (!(subtree instanceof BinaryExpr division)
                || division.operator() != BinaryOperator.DIV
                || division.right() instanceof NumberExpr number
                    && number.value() == 0) {
            return null;
        }
        return new DivisionShape(
            subtree,
            division.left(),
            division.right()
        );
    }

    private static String normalizeIdentity(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
