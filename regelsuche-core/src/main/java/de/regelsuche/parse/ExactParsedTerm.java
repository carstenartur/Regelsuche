package de.regelsuche.parse;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.scalar.ExactRationalEvidenceVerifier;
import de.regelsuche.scalar.ExactRationalParseEvidence;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One parsed term together with source-bound exact evidence for numeric tokens
 * and parser-issued source ranges for concrete AST nodes.
 *
 * <p>The ordinary AST remains unchanged: literal spelling and source layout are
 * deliberately not part of {@code Expr.equals}, canonical search identity or
 * historical evidence. Consumers that need exact source coefficients or one
 * exact subtree occurrence must retain this parser-issued companion object and
 * resolve data by node identity.</p>
 *
 * <p>Every source-backed node receives one range. The provenance-free zero node
 * synthesized for unary minus has no source range because no source token
 * created it.</p>
 */
public final class ExactParsedTerm {
    private final String source;
    private final Expr expression;
    private final List<LiteralOccurrence> literals;
    private final Map<NumberExpr, LiteralOccurrence> literalsByNode;
    private final Map<Expr, SourceRange> sourceRangesByNode;

    ExactParsedTerm(
        String source,
        Expr expression,
        List<LiteralOccurrence> literals,
        Map<Expr, SourceRange> sourceRanges
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.expression = Objects.requireNonNull(expression, "expression");
        this.literals = List.copyOf(
            Objects.requireNonNull(literals, "literals"));
        this.literalsByNode = validateAndIndexLiterals();
        this.sourceRangesByNode = copyAndValidateSourceRanges(
            Objects.requireNonNull(sourceRanges, "sourceRanges"));
    }

    private Map<NumberExpr, LiteralOccurrence> validateAndIndexLiterals() {
        Map<NumberExpr, LiteralOccurrence> occurrences =
            new IdentityHashMap<>();
        for (LiteralOccurrence literal : literals) {
            Objects.requireNonNull(literal, "literal");
            if (occurrences.put(literal.node(), literal) != null) {
                throw new IllegalArgumentException(
                    "numeric literal node occurs more than once");
            }
            if (literal.startInclusive() < 0
                    || literal.endExclusive() <= literal.startInclusive()
                    || literal.endExclusive() > source.length()
                    || !source.substring(
                        literal.startInclusive(),
                        literal.endExclusive()).equals(
                            literal.sourceLexeme())) {
                throw new IllegalArgumentException(
                    "numeric literal source range is invalid");
            }
            ExactRationalParseEvidence evidence = literal.evidence();
            if (!evidence.exact()
                    || !literal.sourceLexeme().equals(
                        evidence.sourceLiteral())
                    || evidence.verify().status()
                        != ExactRationalEvidenceVerifier.Status
                            .VERIFIED_EXACT) {
                throw new IllegalArgumentException(
                    "numeric literal lacks verified exact evidence");
            }
        }
        return Collections.unmodifiableMap(occurrences);
    }

    private Map<Expr, SourceRange> copyAndValidateSourceRanges(
        Map<Expr, SourceRange> sourceRanges
    ) {
        IdentityHashMap<Expr, SourceRange> ranges =
            new IdentityHashMap<>();
        sourceRanges.forEach((node, range) -> {
            Objects.requireNonNull(node, "source range node");
            Objects.requireNonNull(range, "source range");
            if (range.endExclusive() > source.length()) {
                throw new IllegalArgumentException(
                    "AST source range exceeds parsed source");
            }
            ranges.put(node, range);
        });
        SourceRange rootRange = ranges.get(expression);
        if (rootRange == null) {
            throw new IllegalArgumentException(
                "parsed root expression lacks a source range");
        }
        validateRootCoverage(rootRange);
        literals.forEach(literal -> {
            SourceRange nodeRange = ranges.get(literal.node());
            if (nodeRange == null
                    || !nodeRange.contains(
                        literal.startInclusive(),
                        literal.endExclusive())) {
                throw new IllegalArgumentException(
                    "numeric literal is outside its AST source range");
            }
        });
        Set<Expr> visitedNodes = validateUniqueNodeIdentities();
        validateTreeRangesIteratively(ranges);
        for (Expr rangedNode : ranges.keySet()) {
            if (!visitedNodes.contains(rangedNode)) {
                throw new IllegalArgumentException(
                    "AST source range belongs to a node outside the parsed tree");
            }
        }
        return Collections.unmodifiableMap(ranges);
    }

    private Set<Expr> validateUniqueNodeIdentities() {
        Set<Expr> visitedNodes = Collections.newSetFromMap(
            new IdentityHashMap<>());
        Deque<Expr> pending = new ArrayDeque<>();
        pending.push(expression);
        while (!pending.isEmpty()) {
            Expr node = pending.pop();
            if (!visitedNodes.add(node)) {
                throw new IllegalArgumentException(
                    "AST node identity occurs more than once in the parsed tree");
            }
            if (node instanceof BinaryExpr binary) {
                pending.push(binary.right());
                pending.push(binary.left());
            } else if (node instanceof FunctionExpr function) {
                List<Expr> arguments = function.arguments();
                for (int index = arguments.size() - 1;
                        index >= 0;
                        index--) {
                    pending.push(arguments.get(index));
                }
            }
        }
        return visitedNodes;
    }

    private void validateRootCoverage(SourceRange rootRange) {
        if (Character.isWhitespace(source.charAt(rootRange.startInclusive()))
                || Character.isWhitespace(
                    source.charAt(rootRange.endExclusive() - 1))) {
            throw new IllegalArgumentException(
                "root AST source range includes outer whitespace");
        }
        for (int index = 0; index < rootRange.startInclusive(); index++) {
            if (!Character.isWhitespace(source.charAt(index))) {
                throw new IllegalArgumentException(
                    "root AST source range omits source syntax");
            }
        }
        for (int index = rootRange.endExclusive();
                index < source.length();
                index++) {
            if (!Character.isWhitespace(source.charAt(index))) {
                throw new IllegalArgumentException(
                    "root AST source range omits source syntax");
            }
        }
    }

    private void validateTreeRangesIteratively(
        Map<Expr, SourceRange> ranges
    ) {
        Deque<RangeFrame> pending = new ArrayDeque<>();
        pending.push(new RangeFrame(expression, null, false));
        while (!pending.isEmpty()) {
            RangeFrame frame = pending.pop();
            Expr node = frame.node();
            SourceRange range = ranges.get(node);
            NumberExpr number = node instanceof NumberExpr numeric
                ? numeric
                : null;
            boolean numericWithoutLiteral = number != null
                && !literalsByNode.containsKey(number);
            boolean syntheticUnaryZero = numericWithoutLiteral
                && frame.allowSyntheticUnaryZero()
                && number.value() == 0.0d
                && range == null;
            if (numericWithoutLiteral && !syntheticUnaryZero) {
                throw new IllegalArgumentException(
                    "numeric AST node lacks verified exact literal evidence");
            }
            if (range == null && !syntheticUnaryZero) {
                throw new IllegalArgumentException(
                    "source-backed AST node lacks a source range");
            }
            if (range != null
                    && frame.parentRange() != null
                    && !frame.parentRange().contains(range)) {
                throw new IllegalArgumentException(
                    "child AST source range escapes its parent range");
            }
            SourceRange effectiveParent = range == null
                ? frame.parentRange()
                : range;
            if (node instanceof BinaryExpr binary) {
                boolean unaryMinus = isParserUnaryMinus(
                    binary,
                    range,
                    ranges);
                validateBinaryChildOrder(binary, unaryMinus, ranges);
                pending.push(new RangeFrame(
                    binary.right(),
                    effectiveParent,
                    false));
                pending.push(new RangeFrame(
                    binary.left(),
                    effectiveParent,
                    unaryMinus));
            } else if (node instanceof FunctionExpr function) {
                validateFunctionArgumentOrder(function, ranges);
                List<Expr> arguments = function.arguments();
                for (int index = arguments.size() - 1;
                        index >= 0;
                        index--) {
                    pending.push(new RangeFrame(
                        arguments.get(index),
                        effectiveParent,
                        false));
                }
            }
        }
    }

    private static void validateBinaryChildOrder(
        BinaryExpr binary,
        boolean unaryMinus,
        Map<Expr, SourceRange> ranges
    ) {
        if (unaryMinus) {
            return;
        }
        SourceRange left = ranges.get(binary.left());
        SourceRange right = ranges.get(binary.right());
        if (left != null
                && right != null
                && left.endExclusive() > right.startInclusive()) {
            throw new IllegalArgumentException(
                "binary AST source ranges overlap or are out of order");
        }
    }

    private static void validateFunctionArgumentOrder(
        FunctionExpr function,
        Map<Expr, SourceRange> ranges
    ) {
        int previousEnd = -1;
        for (Expr argument : function.arguments()) {
            SourceRange range = ranges.get(argument);
            if (range != null && previousEnd > range.startInclusive()) {
                throw new IllegalArgumentException(
                    "function argument source ranges overlap or are out of order");
            }
            if (range != null) {
                previousEnd = range.endExclusive();
            }
        }
    }

    private boolean isParserUnaryMinus(
        BinaryExpr binary,
        SourceRange binaryRange,
        Map<Expr, SourceRange> ranges
    ) {
        if (binary.operator() != BinaryOperator.SUB
                || !(binary.left() instanceof NumberExpr zero)
                || zero.value() != 0.0d
                || literalsByNode.containsKey(zero)
                || ranges.containsKey(zero)
                || binaryRange == null) {
            return false;
        }
        SourceRange operandRange = ranges.get(binary.right());
        return operandRange != null
            && binaryRange.contains(operandRange)
            && hasUnaryGroupingShape(binaryRange, operandRange);
    }

    private boolean hasUnaryGroupingShape(
        SourceRange binaryRange,
        SourceRange operandRange
    ) {
        int openingGroups = 0;
        boolean sawMinus = false;
        for (int index = binaryRange.startInclusive();
                index < operandRange.startInclusive();
                index++) {
            char character = source.charAt(index);
            if (Character.isWhitespace(character)) {
                continue;
            }
            if (!sawMinus && character == '(') {
                openingGroups++;
            } else if (!sawMinus && character == '-') {
                sawMinus = true;
            } else {
                return false;
            }
        }
        int closingGroups = 0;
        for (int index = operandRange.endExclusive();
                index < binaryRange.endExclusive();
                index++) {
            char character = source.charAt(index);
            if (Character.isWhitespace(character)) {
                continue;
            }
            if (character != ')') {
                return false;
            }
            closingGroups++;
        }
        return sawMinus && openingGroups == closingGroups;
    }

    public String source() {
        return source;
    }

    public Expr expression() {
        return expression;
    }

    public List<LiteralOccurrence> literals() {
        return literals;
    }

    /**
     * Resolves evidence for the exact parser-created node instance. Value-equal
     * nodes created later do not inherit source provenance.
     */
    public Optional<LiteralOccurrence> literalFor(NumberExpr node) {
        Objects.requireNonNull(node, "node");
        return Optional.ofNullable(literalsByNode.get(node));
    }

    /**
     * Returns the parser-issued source range for this concrete node instance.
     * A value-equal foreign node and the synthetic unary-minus zero have none.
     */
    public Optional<SourceRange> sourceRangeFor(Expr node) {
        Objects.requireNonNull(node, "node");
        return Optional.ofNullable(sourceRangesByNode.get(node));
    }

    /** Returns the exact source substring owned by one concrete AST node. */
    public Optional<String> sourceTextFor(Expr node) {
        return sourceRangeFor(node).map(range -> range.textFrom(source));
    }

    /** The non-whitespace source range that produced the root AST node. */
    public SourceRange rootSourceRange() {
        return sourceRangeFor(expression).orElseThrow();
    }

    /** Parser-issued half-open source interval. */
    public record SourceRange(
        int startInclusive,
        int endExclusive
    ) {
        public SourceRange {
            if (startInclusive < 0 || endExclusive <= startInclusive) {
                throw new IllegalArgumentException(
                    "AST source range is invalid");
            }
        }

        public boolean contains(SourceRange other) {
            Objects.requireNonNull(other, "other");
            return contains(other.startInclusive, other.endExclusive);
        }

        public boolean contains(int start, int end) {
            return startInclusive <= start && end <= endExclusive;
        }

        public String textFrom(String source) {
            Objects.requireNonNull(source, "source");
            if (endExclusive > source.length()) {
                throw new IllegalArgumentException(
                    "AST source range exceeds supplied source");
            }
            return source.substring(startInclusive, endExclusive);
        }

        public String canonicalMaterial() {
            return startInclusive + ":" + endExclusive;
        }
    }

    /** Parser-issued occurrence; construction is restricted to this package. */
    public static final class LiteralOccurrence {
        private final NumberExpr node;
        private final int startInclusive;
        private final int endExclusive;
        private final String sourceLexeme;
        private final ExactRationalParseEvidence evidence;

        LiteralOccurrence(
            NumberExpr node,
            int startInclusive,
            int endExclusive,
            String sourceLexeme,
            ExactRationalParseEvidence evidence
        ) {
            this.node = Objects.requireNonNull(node, "node");
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
            this.sourceLexeme = Objects.requireNonNull(
                sourceLexeme,
                "sourceLexeme");
            this.evidence = Objects.requireNonNull(evidence, "evidence");
        }

        public NumberExpr node() {
            return node;
        }

        public int startInclusive() {
            return startInclusive;
        }

        public int endExclusive() {
            return endExclusive;
        }

        public String sourceLexeme() {
            return sourceLexeme;
        }

        public ExactRationalParseEvidence evidence() {
            return evidence;
        }

        public ExactRational exactValue() {
            return evidence.value().orElseThrow();
        }
    }

    private record RangeFrame(
        Expr node,
        SourceRange parentRange,
        boolean allowSyntheticUnaryZero
    ) {
        private RangeFrame {
            Objects.requireNonNull(node, "node");
        }
    }
}
