package de.regelsuche.parse;

import de.regelsuche.retention.RetainedGraph;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.scalar.ExactRationalDomain;
import de.regelsuche.scalar.ExactRationalParseEvidence;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ExpressionParser implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.requireExact(this,ExpressionParser.class);v.reference(exactRationalDomain);}

    private final ExactRationalDomain exactRationalDomain;

    public ExpressionParser() {
        this(new ExactRationalDomain());
    }

    ExpressionParser(ExactRationalDomain exactRationalDomain) {
        this.exactRationalDomain = Objects.requireNonNull(
            exactRationalDomain,
            "exactRationalDomain");
    }

    public ParsedInput parse(InputRequest input) {
        if (input.type() == InputType.TERM) {
            return new ParsedInput(List.of(parseTerm(input.rawInput())), List.of());
        }
        if (input.type() == InputType.EQUATION) {
            return new ParsedInput(List.of(), List.of(parseEquation(input.rawInput())));
        }

        List<Equation> equations = new ArrayList<>();
        var segments = java.util.regex.Pattern.compile("[^;\n]+").matcher(input.rawInput());
        while (segments.find()) {
            String segment = segments.group();
            String trimmed = segment.trim();
            if (!trimmed.isEmpty()) {
                equations.add(parseEquation(trimmed, segments.start() + segment.indexOf(trimmed)));
            }
        }
        return new ParsedInput(List.of(), equations);
    }

    /**
     * Parses one term with exact rational numeric leaves. Integer and finite
     * decimal tokens share the bounded exact scalar grammar.
     * Exact source certificates and node ranges are created only by
     * {@link #parseExactTerm}.
     */
    public Expr parseTerm(String term) {
        String source = Objects.requireNonNull(term, "term");
        return parseTerm(source, 0);
    }

    private Expr parseTerm(String source, int offset) {
        Cursor cursor = new Cursor(source, false, offset);
        Expr expr = parseExpression(cursor);
        requireEnd(cursor);
        return expr;
    }

    /**
     * Parses one term and retains source positions plus exact evidence for each
     * integer or finite-decimal token. Every source-backed AST node also receives
     * a parser-issued half-open source range. Both parser entry points construct the same
     * exact {@link NumberExpr} values.
     */
    public ExactParsedTerm parseExactTerm(String term) {
        String source = Objects.requireNonNull(term, "term");
        Cursor cursor = Cursor.exact(source);
        Expr expr = parseExpression(cursor);
        requireEnd(cursor);
        return new ExactParsedTerm(
            source,
            expr,
            cursor.exactLiterals(),
            cursor.exactSourceRanges());
    }

    private static void requireEnd(Cursor cursor) {
        cursor.skipWhitespace();
        if (!cursor.isAtEnd()) {
            throw cursor.atCurrent("Unexpected token at position ");
        }
    }

    public Equation parseEquation(String equation) {
        return parseEquation(equation, 0);
    }

    private Equation parseEquation(String equation, int offset) {
        int separator = equation.indexOf('=');
        if (separator <= 0 || separator == equation.length() - 1) {
            int missing = separator == 0 ? offset : offset + equation.length();
            throw new Diagnostic("Equation must contain exactly one '=' with both sides present", missing, missing, null);
        }
        int extra = equation.indexOf('=', separator + 1);
        if (extra >= 0) {
            throw new Diagnostic("Equation must contain exactly one '='", offset + extra, offset + extra + 1, null);
        }
        return new Equation(parseTerm(equation.substring(0, separator), offset),
            parseTerm(equation.substring(separator + 1), offset + separator + 1));
    }

    /** Parser-issued half-open UTF-16 interval in the original input. End-of-input is an empty interval. */
    public static final class Diagnostic extends IllegalArgumentException {
        private final int startInclusive;
        private final int endExclusive;

        private Diagnostic(String message, int startInclusive, int endExclusive, Throwable cause) {
            super(message, cause);
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
        }

        public int startInclusive() { return startInclusive; }
        public int endExclusive() { return endExclusive; }
    }

    private Expr parseExpression(Cursor cursor) {
        Expr result = parseTermInternal(cursor);
        while (true) {
            cursor.skipWhitespace();
            if (cursor.consume('+')) {
                Expr right = parseTermInternal(cursor);
                BinaryExpr combined = new BinaryExpr(
                    result,
                    BinaryOperator.ADD,
                    right);
                result = cursor.retainCombinedRange(
                    combined,
                    result,
                    right);
            } else if (cursor.consume('-')) {
                Expr right = parseTermInternal(cursor);
                BinaryExpr combined = new BinaryExpr(
                    result,
                    BinaryOperator.SUB,
                    right);
                result = cursor.retainCombinedRange(
                    combined,
                    result,
                    right);
            } else {
                return result;
            }
        }
    }

    private Expr parseTermInternal(Cursor cursor) {
        Expr result = parseUnary(cursor);
        while (true) {
            cursor.skipWhitespace();
            if (cursor.consume('*')) {
                Expr right = parseUnary(cursor);
                BinaryExpr combined = new BinaryExpr(
                    result,
                    BinaryOperator.MUL,
                    right);
                result = cursor.retainCombinedRange(
                    combined,
                    result,
                    right);
            } else if (cursor.consume('/')) {
                Expr right = parseUnary(cursor);
                BinaryExpr combined = new BinaryExpr(
                    result,
                    BinaryOperator.DIV,
                    right);
                result = cursor.retainCombinedRange(
                    combined,
                    result,
                    right);
            } else {
                return result;
            }
        }
    }

    private Expr parseUnary(Cursor cursor) {
        cursor.skipWhitespace();
        int start = cursor.position();
        if (cursor.consume('-')) {
            Expr operand = parseUnary(cursor);
            BinaryExpr unary = new BinaryExpr(
                new NumberExpr(0),
                BinaryOperator.SUB,
                operand);
            return cursor.retainRange(
                unary,
                start,
                cursor.rangeEnd(operand));
        }
        return parsePower(cursor);
    }

    private Expr parsePower(Cursor cursor) {
        Expr left = parsePrimary(cursor);
        cursor.skipWhitespace();
        if (cursor.consume('^')) {
            Expr right = parseUnary(cursor);
            return cursor.retainCombinedRange(
                new BinaryExpr(
                    left,
                    BinaryOperator.POW,
                    right),
                left,
                right);
        }
        return left;
    }

    private Expr parsePrimary(Cursor cursor) {
        cursor.skipWhitespace();
        int start = cursor.position();
        if (cursor.consume('(')) {
            Expr inner = parseExpression(cursor);
            cursor.skipWhitespace();
            if (!cursor.consume(')')) {
                throw cursor.atCurrent("Missing closing ')' at position ");
            }
            return cursor.retainRange(inner, start, cursor.position());
        }
        if (cursor.peek('.')) {
            throw cursor.atCurrent("Decimal point must be preceded by a digit at position ");
        }
        if (cursor.peekDigit()) {
            return parseNumber(cursor);
        }
        if (cursor.peekLetter()) {
            return parseVariable(cursor);
        }
        throw cursor.atCurrent("Unexpected token at position ");
    }

    private Expr parseNumber(Cursor cursor) {
        int start = cursor.position();
        while (cursor.peekDigit()) {
            cursor.advance();
        }
        if (cursor.peek('.')) {
            int decimalPoint = cursor.position();
            cursor.advance();
            if (!cursor.peekDigit()) {
                throw cursor.diagnostic("Decimal point must be followed by a digit at position "
                    + (cursor.offset + decimalPoint), decimalPoint, decimalPoint + 1, null);
            }
            while (cursor.peekDigit()) {
                cursor.advance();
            }
        }

        int end = cursor.position();
        String sourceLexeme = cursor.slice(start, end);
        if (!cursor.retainsExactLiterals()) {
            try {
                return new NumberExpr(exactRationalDomain.parseValue(sourceLexeme));
            } catch (IllegalArgumentException invalid) {
                throw cursor.diagnostic("Numeric literal at position " + (cursor.offset + start)
                    + ": " + invalid.getMessage(), start, end, invalid);
            }
        }
        ExactRationalParseEvidence evidence =
            exactRationalDomain.parse(sourceLexeme);
        if (!evidence.exact()) {
            throw cursor.diagnostic("Numeric literal is outside the exact rational domain at position "
                + (cursor.offset + start) + ": " + evidence.detailCode(), start, end, null);
        }

        NumberExpr number = cursor.retainRange(
            new NumberExpr(evidence.value().orElseThrow()),
            start,
            end);
        if (cursor.retainsExactLiterals()) {
            cursor.retainExactLiteral(
            number,
            start,
            end,
            sourceLexeme,
            evidence);
        }
        return number;
    }

    private Expr parseVariable(Cursor cursor) {
        int start = cursor.position();
        while (cursor.peekLetterOrDigitOrUnderscore()) {
            cursor.advance();
        }
        int nameEnd = cursor.position();
        String name = cursor.slice(start, nameEnd);
        cursor.skipWhitespace();
        if (cursor.peek('(')) {
            cursor.advance();
            List<Expr> arguments = new ArrayList<>();
            cursor.skipWhitespace();
            if (!cursor.peek(')')) {
                arguments.add(parseExpression(cursor));
                cursor.skipWhitespace();
                while (cursor.consume(',')) {
                    arguments.add(parseExpression(cursor));
                    cursor.skipWhitespace();
                }
            }
            if (!cursor.consume(')')) {
                throw cursor.atCurrent("Missing closing ')' after function arguments at position ");
            }
            return cursor.retainRange(
                new FunctionExpr(name, arguments),
                start,
                cursor.position());
        }
        VariableExpr variable;
        try {
            variable = new VariableExpr(name);
        } catch (IllegalArgumentException invalid) {
            throw cursor.diagnostic(invalid.getMessage(), start, nameEnd, invalid);
        }
        return cursor.retainRange(variable, start, nameEnd);
    }

    private static final class Cursor {
        private final String value;
        private final List<ExactParsedTerm.LiteralOccurrence> exactLiterals;
        private final Map<Expr, ExactParsedTerm.SourceRange> exactSourceRanges;
        private int position;

        private final int offset;

        private Cursor(String value, boolean retainExactEvidence, int offset) {
            this.value = value;
            this.offset = offset;
            this.exactLiterals = retainExactEvidence
                ? new ArrayList<>()
                : null;
            this.exactSourceRanges = retainExactEvidence
                ? new IdentityHashMap<>()
                : null;
            this.position = 0;
        }

        private static Cursor exact(String value) {
            return new Cursor(value, true, 0);
        }

        private Diagnostic atCurrent(String message) {
            int end = isAtEnd() ? position : value.offsetByCodePoints(position, 1);
            return diagnostic(message + (offset + position), position, end, null);
        }

        private Diagnostic diagnostic(String message, int start, int end, Throwable cause) {
            return new Diagnostic(message, offset + start, offset + end, cause);
        }

        private int position() {
            return position;
        }

        private void skipWhitespace() {
            while (!isAtEnd()
                    && Character.isWhitespace(value.charAt(position))) {
                position++;
            }
        }

        private boolean isAtEnd() {
            return position >= value.length();
        }

        private boolean consume(char c) {
            if (!isAtEnd() && value.charAt(position) == c) {
                position++;
                return true;
            }
            return false;
        }

        private boolean peek(char c) {
            return !isAtEnd() && value.charAt(position) == c;
        }

        private boolean peekDigit() {
            return !isAtEnd()
                && Character.isDigit(value.charAt(position));
        }

        private boolean peekLetter() {
            return !isAtEnd()
                && Character.isLetter(value.charAt(position));
        }

        private boolean peekLetterOrDigitOrUnderscore() {
            return !isAtEnd()
                && (Character.isLetterOrDigit(value.charAt(position))
                    || value.charAt(position) == '_');
        }

        private void advance() {
            if (!isAtEnd()) {
                position++;
            }
        }

        private String slice(int from, int to) {
            return value.substring(from, to);
        }

        private boolean retainsExactLiterals() {
            return exactLiterals != null;
        }

        private <T extends Expr> T retainRange(
            T node,
            int startInclusive,
            int endExclusive
        ) {
            if (exactSourceRanges == null) {
                return node;
            }
            ExactParsedTerm.SourceRange range =
                new ExactParsedTerm.SourceRange(
                    startInclusive,
                    endExclusive);
            ExactParsedTerm.SourceRange previous =
                exactSourceRanges.put(node, range);
            if (previous != null && !range.contains(previous)) {
                throw new IllegalStateException(
                    "AST source range replacement must retain the prior range");
            }
            return node;
        }

        private BinaryExpr retainCombinedRange(
            BinaryExpr node,
            Expr left,
            Expr right
        ) {
            if (exactSourceRanges == null) {
                return node;
            }
            ExactParsedTerm.SourceRange leftRange = requireRange(left);
            ExactParsedTerm.SourceRange rightRange = requireRange(right);
            return retainRange(
                node,
                leftRange.startInclusive(),
                rightRange.endExclusive());
        }

        private int rangeEnd(Expr node) {
            return exactSourceRanges == null
                ? position
                : requireRange(node).endExclusive();
        }

        private ExactParsedTerm.SourceRange requireRange(Expr node) {
            ExactParsedTerm.SourceRange range = exactSourceRanges.get(node);
            if (range == null) {
                throw new IllegalStateException(
                    "source-backed AST node lacks a parser range");
            }
            return range;
        }

        private void retainExactLiteral(
            NumberExpr node,
            int startInclusive,
            int endExclusive,
            String sourceLexeme,
            ExactRationalParseEvidence evidence
        ) {
            if (exactLiterals == null) {
                throw new IllegalStateException(
                    "legacy parser path cannot retain exact literals");
            }
            exactLiterals.add(new ExactParsedTerm.LiteralOccurrence(
                node,
                startInclusive,
                endExclusive,
                sourceLexeme,
                evidence));
        }

        private List<ExactParsedTerm.LiteralOccurrence> exactLiterals() {
            if (exactLiterals == null) {
                throw new IllegalStateException(
                    "legacy parser path has no exact literals");
            }
            // ExactParsedTerm performs the single defensive copy at the
            // ownership boundary.
            return exactLiterals;
        }

        private Map<Expr, ExactParsedTerm.SourceRange> exactSourceRanges() {
            if (exactSourceRanges == null) {
                throw new IllegalStateException(
                    "legacy parser path has no AST source ranges");
            }
            // ExactParsedTerm performs the single identity-preserving copy at
            // the ownership boundary.
            return exactSourceRanges;
        }
    }
}
