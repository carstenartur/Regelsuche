package de.regelsuche.discovery.representation;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Classifies target-free search trace occurrences by structural
 * representation correspondence to a target expression, as distinct from
 * value/semantic equivalence.
 *
 * <p>The historical-rediscovery atlas previously qualified a hit through
 * {@code SymPyEquivalenceService.areEquivalent(candidate, target)} for
 * {@code VALUE_EQUIVALENT} cases. That relation is true whenever the source
 * and target denote the same value by a standing algebraic identity, which
 * for identities such as Sophie Germain's is true of the unchanged source
 * expression at depth 0. Crediting that depth-0 hit as a rediscovery
 * conflates semantic truth with representation occurrence: the search never
 * had to change the representation to satisfy value equivalence.</p>
 *
 * <p>This classifier therefore uses an AC-structural signature rather than
 * the repository's strong algebraic canonicalizer. It ignores only
 * associative regrouping and commutative ordering of additions and
 * multiplications, while preserving multiplicity, coefficients, neutral
 * terms, powers, division and factorization boundaries. In particular,
 * {@code x + x}, {@code 2 * x} and {@code x^2} remain three different
 * representation classes.</p>
 *
 * <p>Additive structure is flattened only in positive position. A direct
 * subtraction contributes one negative term, so reordering such as
 * {@code a - b + c} versus {@code c + a - b} is accepted. The classifier
 * never distributes a negative sign through a grouped expression:
 * {@code a - (b + c)} remains distinct from {@code a - b - c}, and
 * {@code a - (b - c)} remains distinct from {@code a - b + c}. This keeps
 * subtraction grouping visible while still permitting harmless AC
 * regrouping. A match found at depth 0 is reported as a false-positive
 * diagnostic rather than as a genuine representation rediscovery.</p>
 */
public final class RepresentationCorrespondenceClassifier {
    private final ExpressionParser parser;

    public RepresentationCorrespondenceClassifier() {
        this(new ExpressionParser());
    }

    RepresentationCorrespondenceClassifier(ExpressionParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    /**
     * Whether two expressions belong to the same AC-structural
     * representation class.
     */
    public enum Correspondence {
        SAME_REPRESENTATION_CLASS,
        DIFFERENT_REPRESENTATION_CLASS
    }

    /**
     * Terminal localization of a single trace evaluation against a target
     * representation class.
     */
    public enum RediscoveryStatus {
        /**
         * The frozen source expression, at depth 0, already belongs to the
         * target's representation class; no representation change was
         * required, so this is a false-positive rediscovery diagnostic.
         */
        SOURCE_ALREADY_MATCHES_FALSE_POSITIVE,
        /** A later trace step first reached the target representation class. */
        REPRESENTATION_REDISCOVERED,
        /** No trace step reached the target representation class. */
        NOT_REACHED
    }

    /** A single frozen search-trace occurrence at a given depth. */
    public record TraceStep(int depth, String expression) {
        public TraceStep {
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException(
                    "expression must not be blank");
            }
            if (depth < 0) {
                throw new IllegalArgumentException(
                    "depth must not be negative");
            }
        }
    }

    /** Evidence produced by {@link #evaluateTrace(List, String)}. */
    public record RediscoveryEvidence(
        RediscoveryStatus status,
        Integer matchedDepth,
        String matchedExpression
    ) {
        public RediscoveryEvidence {
            Objects.requireNonNull(status, "status");
            if (status == RediscoveryStatus.NOT_REACHED) {
                if (matchedDepth != null || matchedExpression != null) {
                    throw new IllegalArgumentException(
                        "NOT_REACHED must not carry a matched occurrence");
                }
            } else {
                Objects.requireNonNull(matchedDepth, "matchedDepth");
                if (matchedExpression == null || matchedExpression.isBlank()) {
                    throw new IllegalArgumentException(
                        "matchedExpression must not be blank");
                }
                if (status
                    == RediscoveryStatus.SOURCE_ALREADY_MATCHES_FALSE_POSITIVE
                    && matchedDepth != 0) {
                    throw new IllegalArgumentException(
                        "source-match evidence must have depth zero");
                }
                if (status == RediscoveryStatus.REPRESENTATION_REDISCOVERED
                    && matchedDepth <= 0) {
                    throw new IllegalArgumentException(
                        "rediscovery evidence must have positive depth");
                }
            }
        }
    }

    public Correspondence classify(
        String candidateExpression,
        String targetExpression
    ) {
        Objects.requireNonNull(candidateExpression, "candidateExpression");
        Objects.requireNonNull(targetExpression, "targetExpression");
        return signature(candidateExpression).equals(signature(targetExpression))
            ? Correspondence.SAME_REPRESENTATION_CLASS
            : Correspondence.DIFFERENT_REPRESENTATION_CLASS;
    }

    /**
     * Evaluates a frozen search trace including the source expression at
     * depth 0 against the target's representation class. The shallowest
     * matching occurrence is selected deterministically.
     */
    public RediscoveryEvidence evaluateTrace(
        List<TraceStep> trace,
        String targetExpression
    ) {
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(targetExpression, "targetExpression");
        if (trace.isEmpty()) {
            throw new IllegalArgumentException("trace must not be empty");
        }
        if (trace.stream().noneMatch(step -> step.depth() == 0)) {
            throw new IllegalArgumentException(
                "trace must include the source expression at depth zero");
        }

        String targetSignature = signature(targetExpression);
        TraceStep match = trace.stream()
            .filter(step -> signature(step.expression())
                .equals(targetSignature))
            .min(Comparator.comparingInt(TraceStep::depth)
                .thenComparing(TraceStep::expression))
            .orElse(null);
        if (match == null) {
            return new RediscoveryEvidence(
                RediscoveryStatus.NOT_REACHED, null, null);
        }
        RediscoveryStatus status = match.depth() == 0
            ? RediscoveryStatus.SOURCE_ALREADY_MATCHES_FALSE_POSITIVE
            : RediscoveryStatus.REPRESENTATION_REDISCOVERED;
        return new RediscoveryEvidence(
            status, match.depth(), match.expression());
    }

    private String signature(String expression) {
        return signature(parser.parseTerm(expression));
    }

    private String signature(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return atom("number", Double.toHexString(number.value()));
        }
        if (expression instanceof VariableExpr variable) {
            return atom("variable", variable.name());
        }
        if (expression instanceof FunctionExpr function) {
            List<String> arguments = function.arguments().stream()
                .map(this::signature)
                .toList();
            List<String> components = new ArrayList<>(arguments.size() + 1);
            components.add(atom("name", function.name()));
            components.addAll(arguments);
            return sequence("function", components);
        }
        BinaryExpr binary = (BinaryExpr) expression;
        return switch (binary.operator()) {
            case ADD, SUB -> additiveSignature(binary);
            case MUL -> multiplicativeSignature(binary);
            case DIV -> sequence(
                "division",
                List.of(signature(binary.left()), signature(binary.right()))
            );
            case POW -> sequence(
                "power",
                List.of(signature(binary.left()), signature(binary.right()))
            );
        };
    }

    private String additiveSignature(Expr expression) {
        List<SignedTerm> terms = new ArrayList<>();
        collectPositiveAdditiveTerms(expression, terms);
        List<String> components = terms.stream()
            .map(term -> atom(
                term.sign() > 0 ? "positive" : "negative",
                signature(term.expression())
            ))
            .sorted()
            .toList();
        return sequence("addition", components);
    }

    /**
     * Flattens addition and the left spine of subtraction, but keeps every
     * right-hand subtraction operand intact as one negative term. Recursing
     * into that operand would distribute a negative sign through explicit
     * grouping and incorrectly identify non-associative subtraction forms.
     */
    private void collectPositiveAdditiveTerms(
        Expr expression,
        List<SignedTerm> terms
    ) {
        if (expression instanceof BinaryExpr binary) {
            if (binary.operator() == de.regelsuche.ast.BinaryOperator.ADD) {
                collectPositiveAdditiveTerms(binary.left(), terms);
                collectPositiveAdditiveTerms(binary.right(), terms);
                return;
            }
            if (binary.operator() == de.regelsuche.ast.BinaryOperator.SUB) {
                collectPositiveAdditiveTerms(binary.left(), terms);
                terms.add(new SignedTerm(-1, binary.right()));
                return;
            }
        }
        terms.add(new SignedTerm(1, expression));
    }

    private String multiplicativeSignature(Expr expression) {
        List<Expr> factors = new ArrayList<>();
        collectFactors(expression, factors);
        List<String> components = factors.stream()
            .map(this::signature)
            .sorted()
            .toList();
        return sequence("multiplication", components);
    }

    private void collectFactors(Expr expression, List<Expr> factors) {
        if (expression instanceof BinaryExpr binary
            && binary.operator() == de.regelsuche.ast.BinaryOperator.MUL) {
            collectFactors(binary.left(), factors);
            collectFactors(binary.right(), factors);
            return;
        }
        factors.add(expression);
    }

    private static String atom(String tag, String value) {
        return tag + '[' + value.length() + ':' + value + ']';
    }

    private static String sequence(String tag, List<String> components) {
        StringBuilder result = new StringBuilder(tag).append('[');
        for (String component : components) {
            result.append(component.length()).append(':').append(component);
        }
        return result.append(']').toString();
    }

    private record SignedTerm(int sign, Expr expression) {
    }
}
