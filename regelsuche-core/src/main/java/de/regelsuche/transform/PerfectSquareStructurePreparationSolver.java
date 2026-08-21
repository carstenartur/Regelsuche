package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
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
import java.util.TreeMap;

/**
 * Exposes exact positive-monomial squares to the ordinary difference-of-squares
 * rewrite rule.
 *
 * <p>Given {@code L - R}, both terms are parsed in the bounded exact monomial
 * fragment. If both have an exact monomial square root, the solver constructs
 * {@code root(L)^2 - root(R)^2}. It never guesses a root or changes subtraction
 * order, and the visible principal rule must replay successfully.</p>
 */
public final class PerfectSquareStructurePreparationSolver {
    public static final String SOLVER_ID =
        "perfect-square-structure/exact-positive-monomial/v1";
    public static final String CERTIFICATE_SCHEMA =
        "regelsuche.perfect-square-structure-certificate/v1";
    public static final String APPLICATION_SCHEMA =
        "regelsuche.perfect-square-structure-application/v1";
    public static final String PREPARATION_RULE_ID =
        "prepare_exact_monomial_square_structure";
    public static final String PRINCIPAL_RULE_ID =
        "ast_square_difference_factor";

    private final Budget budget;

    public PerfectSquareStructurePreparationSolver() {
        this(Budget.DEFAULT);
    }

    public PerfectSquareStructurePreparationSolver(Budget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
        budget.limits();
    }

    public Budget budget() {
        return budget;
    }

    public PlanAttempt plan(Expr subtree) {
        Objects.requireNonNull(subtree, "subtree");
        WorkLedger untouched = WorkLedger.untouched(budget.maxFactors());
        if (!(subtree instanceof BinaryExpr difference)
                || difference.operator() != BinaryOperator.SUB) {
            return PlanAttempt.withoutApplication(
                Status.NOT_APPLICABLE,
                untouched,
                "root-is-not-subtraction");
        }
        if (isExplicitSquare(difference.left())
                && isExplicitSquare(difference.right())) {
            return PlanAttempt.withoutApplication(
                Status.DIRECT_MATCH_AVAILABLE,
                untouched,
                "principal-rule-already-matches");
        }

        ResidualObligation obligation = residualObligation(difference);
        ParsedTerms parsed = parseTerms(difference, obligation);
        if (parsed.failure() != null) {
            return parsed.failure();
        }
        ExactRoots roots = roots(parsed.left(), parsed.right());
        WorkLedger work = parsed.work();
        if (roots == null) {
            return PlanAttempt.withoutApplication(
                Status.NOT_APPLICABLE,
                Optional.of(obligation),
                work,
                "one-or-both-terms-are-not-exact-monomial-squares");
        }

        ExpectedApplication expected = expectedApplication(roots);
        Certificate certificate = certificate(
            difference,
            parsed.left(),
            parsed.right(),
            roots,
            expected);
        PreparedApplication application = new PreparedApplication(
            APPLICATION_SCHEMA,
            SOLVER_ID,
            PRINCIPAL_RULE_ID,
            subtree,
            expected.prepared(),
            expected.result(),
            expected.leftRoot(),
            expected.rightRoot(),
            Map.of(
                "A", expected.leftRoot(),
                "B", expected.rightRoot()),
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
                "generated-square-structure-certificate-rejected");
        }
        return PlanAttempt.prepared(application);
    }

    public boolean verify(PreparedApplication application) {
        BinaryExpr difference = verificationSource(application);
        if (difference == null) {
            return false;
        }
        ParsedTerms parsed = parseTerms(difference, null);
        if (parsed.failure() != null
                || !parsed.work().equals(application.work())) {
            return false;
        }
        ExactRoots roots = roots(parsed.left(), parsed.right());
        if (roots == null
                || !reconstructs(parsed.left(), roots.left())
                || !reconstructs(parsed.right(), roots.right())) {
            return false;
        }
        ExpectedApplication expected = expectedApplication(roots);
        if (!expressionsMatch(application, expected)
                || !bindingsMatch(application, expected)
                || !contextMatches(application, difference)) {
            return false;
        }
        return certificate(
            difference,
            parsed.left(),
            parsed.right(),
            roots,
            expected).equals(application.certificate());
    }

    private BinaryExpr verificationSource(PreparedApplication application) {
        if (!hasExpectedMetadata(application)
                || !budget.equals(application.budget())
                || !(application.originalSubtree()
                    instanceof BinaryExpr difference)
                || difference.operator() != BinaryOperator.SUB
                || isExplicitSquare(difference.left())
                    && isExplicitSquare(difference.right())) {
            return null;
        }
        return difference;
    }

    private ParsedTerms parseTerms(
        BinaryExpr difference,
        ResidualObligation obligation
    ) {
        ExactPositiveMonomial.Parser parser =
            new ExactPositiveMonomial.Parser(budget.limits());
        ExactPositiveMonomial.ParseResult left = parser.parse(difference.left());
        if (!left.supported()) {
            return ParsedTerms.failed(
                parseFailure(left, obligation, parser));
        }
        ExactPositiveMonomial.ParseResult right = parser.parse(difference.right());
        if (!right.supported()) {
            return ParsedTerms.failed(
                parseFailure(right, obligation, parser));
        }
        return ParsedTerms.supported(
            left.monomial(),
            right.monomial(),
            work(parser));
    }

    private PlanAttempt parseFailure(
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
            Optional.ofNullable(obligation),
            work(parser),
            result.detail());
    }

    private WorkLedger work(ExactPositiveMonomial.Parser parser) {
        return new WorkLedger(
            budget.maxFactors(),
            parser.inspectedFactors(),
            parser.remainingFactors());
    }

    private static ExactRoots roots(
        ExactPositiveMonomial left,
        ExactPositiveMonomial right
    ) {
        ExactPositiveMonomial leftRoot = exactSquareRoot(left);
        ExactPositiveMonomial rightRoot = exactSquareRoot(right);
        return leftRoot == null || rightRoot == null
            ? null
            : new ExactRoots(leftRoot, rightRoot);
    }

    private static ExactPositiveMonomial exactSquareRoot(
        ExactPositiveMonomial monomial
    ) {
        long coefficientRoot = perfectSquareRoot(monomial.coefficient());
        if (coefficientRoot < 1
                || monomial.powers().values().stream()
                    .anyMatch(exponent -> exponent % 2 != 0)) {
            return null;
        }
        TreeMap<String, Integer> powers = new TreeMap<>();
        monomial.powers().forEach((name, exponent) ->
            powers.put(name, exponent / 2));
        powers.values().removeIf(exponent -> exponent == 0);
        return new ExactPositiveMonomial(coefficientRoot, powers);
    }

    private static long perfectSquareRoot(long value) {
        long root = Math.round(Math.sqrt(value));
        return root * root == value ? root : -1;
    }

    private static boolean reconstructs(
        ExactPositiveMonomial original,
        ExactPositiveMonomial root
    ) {
        return original.equals(root.multiply(root));
    }

    private static ExpectedApplication expectedApplication(ExactRoots roots) {
        Expr leftRoot = roots.left().toExpression();
        Expr rightRoot = roots.right().toExpression();
        Expr prepared = new BinaryExpr(
            square(leftRoot),
            BinaryOperator.SUB,
            square(rightRoot));
        Expr result = new BinaryExpr(
            new BinaryExpr(leftRoot, BinaryOperator.SUB, rightRoot),
            BinaryOperator.MUL,
            new BinaryExpr(leftRoot, BinaryOperator.ADD, rightRoot));
        return new ExpectedApplication(
            leftRoot,
            rightRoot,
            prepared,
            result);
    }

    private static Expr square(Expr expression) {
        return new BinaryExpr(
            expression,
            BinaryOperator.POW,
            new NumberExpr(2));
    }

    private static boolean expressionsMatch(
        PreparedApplication application,
        ExpectedApplication expected
    ) {
        return expected.leftRoot().equals(application.leftRoot())
            && expected.rightRoot().equals(application.rightRoot())
            && expected.prepared().equals(application.preparedSubtree())
            && expected.result().equals(application.resultSubtree());
    }

    private static boolean bindingsMatch(
        PreparedApplication application,
        ExpectedApplication expected
    ) {
        return application.bindings().size() == 2
            && expected.leftRoot().equals(application.bindings().get("A"))
            && expected.rightRoot().equals(application.bindings().get("B"));
    }

    private static boolean contextMatches(
        PreparedApplication application,
        BinaryExpr difference
    ) {
        return application.assumptions().isEmpty()
            && residualObligation(difference)
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

    private static boolean isExplicitSquare(Expr expression) {
        return expression instanceof BinaryExpr power
            && power.operator() == BinaryOperator.POW
            && power.right() instanceof NumberExpr exponent
            && exponent.value() == 2;
    }

    private static ResidualObligation residualObligation(
        BinaryExpr difference
    ) {
        String left = ExpressionFormatter.format(difference.left());
        String right = ExpressionFormatter.format(difference.right());
        return new ResidualObligation(
            "EXACT_MONOMIAL_SQUARES",
            left,
            right,
            "A",
            "B",
            left + " = A^2; " + right + " = B^2");
    }

    private static Certificate certificate(
        BinaryExpr difference,
        ExactPositiveMonomial left,
        ExactPositiveMonomial right,
        ExactRoots roots,
        ExpectedApplication expected
    ) {
        String leftText = ExpressionFormatter.format(difference.left());
        String rightText = ExpressionFormatter.format(difference.right());
        String leftRootText = ExpressionFormatter.format(expected.leftRoot());
        String rightRootText = ExpressionFormatter.format(expected.rightRoot());
        String preparedText = ExpressionFormatter.format(expected.prepared());
        String resultText = ExpressionFormatter.format(expected.result());
        String payload = String.join("\n",
            "schema=" + CERTIFICATE_SCHEMA,
            "solver=" + SOLVER_ID,
            "principalRule=" + PRINCIPAL_RULE_ID,
            "left=" + leftText,
            "right=" + rightText,
            "leftMonomial=" + left.descriptor(),
            "rightMonomial=" + right.descriptor(),
            "leftRoot=" + leftRootText,
            "rightRoot=" + rightRootText,
            "leftRootMonomial=" + roots.left().descriptor(),
            "rightRootMonomial=" + roots.right().descriptor(),
            "prepared=" + preparedText,
            "result=" + resultText);
        return new Certificate(
            CERTIFICATE_SCHEMA,
            SOLVER_ID,
            leftText,
            rightText,
            leftRootText,
            rightRootText,
            preparedText,
            resultText,
            left.descriptor(),
            right.descriptor(),
            roots.left().descriptor(),
            roots.right().descriptor(),
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
        String leftRootPlaceholder,
        String rightRootPlaceholder,
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
            leftRootPlaceholder = requireText(
                leftRootPlaceholder,
                "leftRootPlaceholder");
            rightRootPlaceholder = requireText(
                rightRootPlaceholder,
                "rightRootPlaceholder");
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
        String leftRootExpression,
        String rightRootExpression,
        String preparedExpression,
        String resultExpression,
        String leftMonomialDescriptor,
        String rightMonomialDescriptor,
        String leftRootMonomialDescriptor,
        String rightRootMonomialDescriptor,
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
            leftRootExpression = requireText(
                leftRootExpression,
                "leftRootExpression");
            rightRootExpression = requireText(
                rightRootExpression,
                "rightRootExpression");
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
            leftRootMonomialDescriptor = requireText(
                leftRootMonomialDescriptor,
                "leftRootMonomialDescriptor");
            rightRootMonomialDescriptor = requireText(
                rightRootMonomialDescriptor,
                "rightRootMonomialDescriptor");
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
        Expr leftRoot,
        Expr rightRoot,
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
            leftRoot = Objects.requireNonNull(leftRoot, "leftRoot");
            rightRoot = Objects.requireNonNull(rightRoot, "rightRoot");
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
                "exact-monomial-square-structure-prepared");
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

    private record ParsedTerms(
        ExactPositiveMonomial left,
        ExactPositiveMonomial right,
        WorkLedger work,
        PlanAttempt failure
    ) {
        private static ParsedTerms supported(
            ExactPositiveMonomial left,
            ExactPositiveMonomial right,
            WorkLedger work
        ) {
            return new ParsedTerms(
                Objects.requireNonNull(left, "left"),
                Objects.requireNonNull(right, "right"),
                Objects.requireNonNull(work, "work"),
                null);
        }

        private static ParsedTerms failed(PlanAttempt failure) {
            return new ParsedTerms(
                null,
                null,
                Objects.requireNonNull(failure, "failure").work(),
                failure);
        }
    }

    private record ExactRoots(
        ExactPositiveMonomial left,
        ExactPositiveMonomial right
    ) {
        private ExactRoots {
            left = Objects.requireNonNull(left, "left");
            right = Objects.requireNonNull(right, "right");
        }
    }

    private record ExpectedApplication(
        Expr leftRoot,
        Expr rightRoot,
        Expr prepared,
        Expr result
    ) {
        private ExpectedApplication {
            leftRoot = Objects.requireNonNull(leftRoot, "leftRoot");
            rightRoot = Objects.requireNonNull(rightRoot, "rightRoot");
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
