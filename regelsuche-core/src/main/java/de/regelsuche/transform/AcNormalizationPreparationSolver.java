package de.regelsuche.transform;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded associative/commutative normalization for exposing an existing
 * multiplicative factor to the ordinary cancellation rule.
 *
 * <p>Given {@code P / A}, this solver flattens only multiplication nodes in
 * {@code P}, looks for one structurally identical occurrence of {@code A}, and
 * rebuilds the numerator as {@code A * B}. It never invents a factor and never
 * distributes through addition. The result is therefore a normalization
 * witness, not a factorization algorithm.</p>
 */
public final class AcNormalizationPreparationSolver {
    public static final String SOLVER_ID =
        "ac-normalization/multiplicative-factor-exposure/v1";
    public static final String CERTIFICATE_SCHEMA =
        "regelsuche.ac-normalization-certificate/v1";
    public static final String APPLICATION_SCHEMA =
        "regelsuche.ac-normalization-application/v1";
    public static final String PREPARATION_RULE_ID =
        "prepare_ac_multiplication_factor_exposure";
    public static final String PRINCIPAL_RULE_ID =
        RulePreparationPlanner.PRINCIPAL_RULE_ID;

    private final Budget budget;

    public AcNormalizationPreparationSolver() {
        this(Budget.DEFAULT);
    }

    public AcNormalizationPreparationSolver(Budget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public Budget budget() {
        return budget;
    }

    /**
     * Attempts to expose one already present numerator factor modulo
     * associativity and commutativity of scalar multiplication.
     */
    public PlanAttempt plan(Expr subtree) {
        Objects.requireNonNull(subtree, "subtree");
        WorkLedger untouched = WorkLedger.untouched(budget.maxFactors());
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
        FactorCollection collected = collectFactors(
            division.left(),
            budget.maxFactors());
        WorkLedger work = WorkLedger.inspected(
            budget.maxFactors(),
            collected.factors().size());
        if (!collected.complete()) {
            return PlanAttempt.withoutApplication(
                Status.BUDGET_INCONCLUSIVE,
                Optional.of(obligation),
                work,
                "multiplicative-factor-limit-exhausted");
        }
        if (collected.factors().size() < 2) {
            return PlanAttempt.withoutApplication(
                Status.NOT_APPLICABLE,
                Optional.of(obligation),
                work,
                "numerator-has-no-multiplicative-factor-tree");
        }

        int selectedFactorIndex = firstFactorIndex(
            collected.factors(),
            division.right());
        if (selectedFactorIndex < 0) {
            return PlanAttempt.withoutApplication(
                Status.NOT_APPLICABLE,
                Optional.of(obligation),
                work,
                "divisor-is-not-an-existing-ac-factor");
        }

        List<Expr> remainingFactors = withoutIndex(
            collected.factors(),
            selectedFactorIndex);
        if (remainingFactors.isEmpty()) {
            return PlanAttempt.withoutApplication(
                Status.NOT_APPLICABLE,
                Optional.of(obligation),
                work,
                "ac-normalization-would-require-inventing-unit-factor");
        }

        Expr resultSubtree = multiply(remainingFactors);
        Expr preparedNumerator = new BinaryExpr(
            division.right(),
            BinaryOperator.MUL,
            resultSubtree);
        Expr preparedSubtree = new BinaryExpr(
            preparedNumerator,
            BinaryOperator.DIV,
            division.right());
        List<String> assumptions = assumptionsFor(division.right());
        Certificate certificate = certificate(
            division.left(),
            preparedNumerator,
            division.right(),
            resultSubtree,
            selectedFactorIndex,
            collected.factors());
        PreparedApplication application = new PreparedApplication(
            APPLICATION_SCHEMA,
            SOLVER_ID,
            PRINCIPAL_RULE_ID,
            subtree,
            preparedSubtree,
            resultSubtree,
            Map.of(
                "A", division.right(),
                "B", resultSubtree),
            obligation,
            assumptions,
            List.of(PREPARATION_RULE_ID, PRINCIPAL_RULE_ID),
            certificate,
            work);
        if (!verify(application)) {
            return PlanAttempt.withoutApplication(
                Status.INVALID_CERTIFICATE,
                Optional.of(obligation),
                work,
                "generated-ac-certificate-rejected");
        }
        return PlanAttempt.prepared(application);
    }

    /**
     * Independently checks the AC multiset witness and every retained field.
     */
    public boolean verify(PreparedApplication application) {
        if (!hasExpectedMetadata(application)
                || !(application.originalSubtree()
                    instanceof BinaryExpr division)
                || division.operator() != BinaryOperator.DIV
                || isExplicitZero(division.right())) {
            return false;
        }

        FactorCollection original = collectFactors(
            division.left(),
            application.work().configuredFactorLimit());
        if (!original.complete()
                || original.factors().size()
                    != application.work().inspectedFactors()) {
            return false;
        }
        int selected = application.certificate().selectedFactorIndex();
        if (selected < 0
                || selected >= original.factors().size()
                || !original.factors().get(selected).equals(division.right())) {
            return false;
        }

        List<Expr> remaining = withoutIndex(original.factors(), selected);
        if (remaining.isEmpty()) {
            return false;
        }
        Expr expectedResult = multiply(remaining);
        Expr expectedPreparedNumerator = new BinaryExpr(
            division.right(),
            BinaryOperator.MUL,
            expectedResult);
        Expr expectedPrepared = new BinaryExpr(
            expectedPreparedNumerator,
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

        FactorCollection prepared = collectFactors(
            expectedPreparedNumerator,
            application.work().configuredFactorLimit());
        if (!prepared.complete()
                || !sameFactorMultiset(
                    original.factors(),
                    prepared.factors())) {
            return false;
        }
        Certificate expectedCertificate = certificate(
            division.left(),
            expectedPreparedNumerator,
            division.right(),
            expectedResult,
            selected,
            original.factors());
        return expectedCertificate.equals(application.certificate());
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

    private static Certificate certificate(
        Expr originalNumerator,
        Expr preparedNumerator,
        Expr divisor,
        Expr result,
        int selectedFactorIndex,
        List<Expr> originalFactors
    ) {
        FactorCollection preparedFactors = collectFactors(
            preparedNumerator,
            Math.max(2, originalFactors.size() + 1));
        if (!preparedFactors.complete()) {
            throw new IllegalStateException(
                "prepared numerator exceeded its own factor witness");
        }
        List<String> originalHashes = factorHashes(originalFactors);
        List<String> preparedHashes = factorHashes(
            preparedFactors.factors());
        String originalText = ExpressionFormatter.format(originalNumerator);
        String preparedText = ExpressionFormatter.format(preparedNumerator);
        String divisorText = ExpressionFormatter.format(divisor);
        String resultText = ExpressionFormatter.format(result);
        StringBuilder payload = new StringBuilder();
        appendField(payload, CERTIFICATE_SCHEMA);
        appendField(payload, SOLVER_ID);
        appendField(payload, BinaryOperator.MUL.name());
        appendField(payload, originalText);
        appendField(payload, preparedText);
        appendField(payload, divisorText);
        appendField(payload, resultText);
        appendField(payload, Integer.toString(selectedFactorIndex));
        originalHashes.forEach(value -> appendField(payload, value));
        preparedHashes.forEach(value -> appendField(payload, value));
        return new Certificate(
            CERTIFICATE_SCHEMA,
            SOLVER_ID,
            BinaryOperator.MUL.name(),
            originalText,
            preparedText,
            divisorText,
            resultText,
            selectedFactorIndex,
            originalHashes,
            preparedHashes,
            sha256(payload.toString()));
    }

    private static ResidualObligation residualObligation(
        BinaryExpr division
    ) {
        String numerator = ExpressionFormatter.format(division.left());
        String divisor = ExpressionFormatter.format(division.right());
        return new ResidualObligation(
            "EXPOSE_EXISTING_AC_FACTOR",
            numerator,
            divisor,
            "B",
            numerator + " =_AC (" + divisor + ") * B");
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

    private static int firstFactorIndex(
        List<Expr> factors,
        Expr required
    ) {
        for (int index = 0; index < factors.size(); index++) {
            if (factors.get(index).equals(required)) {
                return index;
            }
        }
        return -1;
    }

    private static List<Expr> withoutIndex(
        List<Expr> values,
        int excluded
    ) {
        List<Expr> result = new ArrayList<>(values.size() - 1);
        for (int index = 0; index < values.size(); index++) {
            if (index != excluded) {
                result.add(values.get(index));
            }
        }
        return List.copyOf(result);
    }

    private static Expr multiply(List<Expr> factors) {
        if (factors.isEmpty()) {
            throw new IllegalArgumentException(
                "multiplication requires at least one factor");
        }
        Expr result = factors.getFirst();
        for (int index = 1; index < factors.size(); index++) {
            result = new BinaryExpr(
                result,
                BinaryOperator.MUL,
                factors.get(index));
        }
        return result;
    }

    private static FactorCollection collectFactors(
        Expr expression,
        int maxFactors
    ) {
        List<Expr> result = new ArrayList<>();
        boolean complete = collectFactors(
            expression,
            maxFactors,
            result);
        return new FactorCollection(List.copyOf(result), complete);
    }

    private static boolean collectFactors(
        Expr expression,
        int maxFactors,
        List<Expr> target
    ) {
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.MUL) {
            return collectFactors(binary.left(), maxFactors, target)
                && collectFactors(binary.right(), maxFactors, target);
        }
        if (target.size() >= maxFactors) {
            return false;
        }
        target.add(expression);
        return true;
    }

    private static boolean sameFactorMultiset(
        List<Expr> left,
        List<Expr> right
    ) {
        if (left.size() != right.size()) {
            return false;
        }
        List<String> leftHashes = new ArrayList<>(factorHashes(left));
        List<String> rightHashes = new ArrayList<>(factorHashes(right));
        leftHashes.sort(Comparator.naturalOrder());
        rightHashes.sort(Comparator.naturalOrder());
        return leftHashes.equals(rightHashes);
    }

    private static List<String> factorHashes(List<Expr> factors) {
        return factors.stream()
            .map(AcNormalizationPreparationSolver::structuralHash)
            .toList();
    }

    private static String structuralHash(Expr expression) {
        StringBuilder descriptor = new StringBuilder();
        appendExpression(expression, descriptor);
        return sha256(descriptor.toString());
    }

    private static void appendExpression(
        Expr expression,
        StringBuilder target
    ) {
        if (expression instanceof NumberExpr number) {
            appendField(target, "number");
            appendField(target, Long.toHexString(
                Double.doubleToLongBits(number.value())));
            return;
        }
        if (expression instanceof VariableExpr variable) {
            appendField(target, "variable");
            appendField(target, variable.name());
            return;
        }
        if (expression instanceof BinaryExpr binary) {
            appendField(target, "binary");
            appendField(target, binary.operator().name());
            appendExpression(binary.left(), target);
            appendExpression(binary.right(), target);
            return;
        }
        FunctionExpr function = (FunctionExpr) expression;
        appendField(target, "function");
        appendField(target, function.name());
        appendField(target, Integer.toString(function.arguments().size()));
        function.arguments().forEach(argument ->
            appendExpression(argument, target));
    }

    private static void appendField(
        StringBuilder target,
        String value
    ) {
        target.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static boolean isExplicitZero(Expr expression) {
        return expression instanceof NumberExpr number
            && number.value() == 0;
    }

    public enum Status {
        PREPARED,
        DIRECT_MATCH_AVAILABLE,
        NOT_APPLICABLE,
        UNSUPPORTED,
        BUDGET_INCONCLUSIVE,
        INVALID_CERTIFICATE
    }

    public record Budget(int maxFactors) {
        public static final Budget DEFAULT = new Budget(16);

        public Budget {
            if (maxFactors < 0) {
                throw new IllegalArgumentException(
                    "maxFactors must not be negative");
            }
        }
    }

    public record WorkLedger(
        int configuredFactorLimit,
        int inspectedFactors,
        int remainingFactorCapacity
    ) {
        public WorkLedger {
            if (configuredFactorLimit < 0
                    || inspectedFactors < 0
                    || remainingFactorCapacity < 0
                    || configuredFactorLimit
                        != inspectedFactors + remainingFactorCapacity) {
                throw new IllegalArgumentException(
                    "factor work must be non-negative and balanced");
            }
        }

        private static WorkLedger untouched(int configured) {
            return new WorkLedger(configured, 0, configured);
        }

        private static WorkLedger inspected(
            int configured,
            int inspected
        ) {
            if (inspected > configured) {
                throw new IllegalArgumentException(
                    "inspected factors exceed configured limit");
            }
            return new WorkLedger(
                configured,
                inspected,
                configured - inspected);
        }
    }

    public record ResidualObligation(
        String kind,
        String actualExpression,
        String requiredFactorExpression,
        String remainderPlaceholder,
        String equationTemplate
    ) {
        public ResidualObligation {
            kind = requireText(kind, "kind");
            actualExpression = requireText(
                actualExpression,
                "actualExpression");
            requiredFactorExpression = requireText(
                requiredFactorExpression,
                "requiredFactorExpression");
            remainderPlaceholder = requireText(
                remainderPlaceholder,
                "remainderPlaceholder");
            equationTemplate = requireText(
                equationTemplate,
                "equationTemplate");
        }
    }

    public record Certificate(
        String schema,
        String solverId,
        String operator,
        String originalNumeratorExpression,
        String preparedNumeratorExpression,
        String divisorExpression,
        String resultExpression,
        int selectedFactorIndex,
        List<String> originalFactorHashes,
        List<String> preparedFactorHashes,
        String contentHash
    ) {
        public Certificate {
            schema = requireText(schema, "schema");
            solverId = requireText(solverId, "solverId");
            operator = requireText(operator, "operator");
            originalNumeratorExpression = requireText(
                originalNumeratorExpression,
                "originalNumeratorExpression");
            preparedNumeratorExpression = requireText(
                preparedNumeratorExpression,
                "preparedNumeratorExpression");
            divisorExpression = requireText(
                divisorExpression,
                "divisorExpression");
            resultExpression = requireText(
                resultExpression,
                "resultExpression");
            if (selectedFactorIndex < 0) {
                throw new IllegalArgumentException(
                    "selectedFactorIndex must not be negative");
            }
            originalFactorHashes = immutableTexts(
                originalFactorHashes,
                "originalFactorHashes");
            preparedFactorHashes = immutableTexts(
                preparedFactorHashes,
                "preparedFactorHashes");
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
        Map<String, Expr> bindings,
        ResidualObligation residualObligation,
        List<String> assumptions,
        List<String> primitiveRuleIds,
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
            bindings = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(
                    bindings,
                    "bindings")));
            residualObligation = Objects.requireNonNull(
                residualObligation,
                "residualObligation");
            assumptions = immutableTexts(assumptions, "assumptions");
            primitiveRuleIds = immutableTexts(
                primitiveRuleIds,
                "primitiveRuleIds");
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
            detail = requireText(detail, "detail");
            if ((status == Status.PREPARED) != application.isPresent()) {
                throw new IllegalArgumentException(
                    "only PREPARED attempts may contain an application");
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
                "ac-factor-exposed");
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

    private record FactorCollection(
        List<Expr> factors,
        boolean complete
    ) {
        private FactorCollection {
            factors = List.copyOf(factors);
        }
    }

    private static List<String> immutableTexts(
        List<String> values,
        String field
    ) {
        Objects.requireNonNull(values, field);
        return values.stream()
            .map(value -> requireText(value, field + " entry"))
            .toList();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                field + " must not be blank");
        }
        return normalized;
    }
}
