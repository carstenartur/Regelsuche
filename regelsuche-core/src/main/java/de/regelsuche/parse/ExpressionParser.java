package de.regelsuche.parse;

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

public class ExpressionParser {
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
        for (String candidate : input.rawInput().split("[;\\n]+")) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty()) {
                equations.add(parseEquation(trimmed));
            }
        }
        return new ParsedInput(List.of(), equations);
    }

    /**
     * Parses one term through the allocation-minimal legacy AST path.
     * Numerals that cannot round-trip through the current numeric leaf are
     * rejected instead of silently rounded. This does not make arithmetic exact.
     * Exact source certificates and node ranges are created only by
     * {@link #parseExactTerm}.
     */
    public Expr parseTerm(String term) {
        String source = Objects.requireNonNull(term, "term");
        Cursor cursor = Cursor.legacy(source);
        Expr expr = parseExpression(cursor);
        requireEnd(cursor);
        return expr;
    }

    /**
     * Parses one term and retains source positions plus exact evidence for each
     * integer or finite-decimal token. Every source-backed AST node also receives
     * a parser-issued half-open source range. The ordinary AST remains the same
     * legacy {@link NumberExpr} tree.
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
            throw new IllegalArgumentException(
                "Unexpected token at position " + cursor.position());
        }
    }

    public Equation parseEquation(String equation) {
        int idx = equation.indexOf('=');
        if (idx < 1 || idx == equation.length() - 1) {
            throw new IllegalArgumentException(
                "Equation must contain exactly one '=' with both sides present");
        }
        String left = equation.substring(0, idx);
        String right = equation.substring(idx + 1);
        if (equation.indexOf('=', idx + 1) >= 0) {
            throw new IllegalArgumentException(
                "Equation must contain exactly one '='");
        }
        return new Equation(parseTerm(left), parseTerm(right));
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
                throw new IllegalArgumentException(
                    "Missing closing ')' at position " + cursor.position());
            }
            return cursor.retainRange(inner, start, cursor.position());
        }
        if (cursor.peek('.')) {
            throw new IllegalArgumentException(
                "Decimal point must be preceded by a digit at position "
                    + cursor.position());
        }
        if (cursor.peekDigit()) {
            return parseNumber(cursor);
        }
        if (cursor.peekLetter()) {
            return parseVariable(cursor);
        }
        throw new IllegalArgumentException(
            "Unexpected token at position " + cursor.position());
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
                throw new IllegalArgumentException(
                    "Decimal point must be followed by a digit at position "
                        + decimalPoint);
            }
            while (cursor.peekDigit()) {
                cursor.advance();
            }
        }

        int end = cursor.position();
        String sourceLexeme = cursor.slice(start, end);
        if (!cursor.retainsExactLiterals()) {
            return cursor.retainRange(
                new NumberExpr(parseRoundTrippingLegacyValue(sourceLexeme, start)),
                start,
                end);
        }

        ExactRationalParseEvidence evidence =
            exactRationalDomain.parse(sourceLexeme);
        if (!evidence.exact()) {
            throw new IllegalArgumentException(
                "Numeric literal is outside the exact rational domain at "
                    + "position " + start + ": " + evidence.detailCode());
        }

        double legacyValue = parseFiniteLegacyValue(sourceLexeme, start);
        if (legacyValue == 0.0d
                && !evidence.value().orElseThrow().isZero()) {
            throw new IllegalArgumentException(
                "Exact numeric literal cannot be represented safely by the "
                    + "legacy AST at position " + start);
        }

        NumberExpr number = cursor.retainRange(
            new NumberExpr(legacyValue),
            start,
            end);
        cursor.retainExactLiteral(
            number,
            start,
            end,
            sourceLexeme,
            evidence);
        return number;
    }

    /**
     * Until numeric AST values are exact, ordinary parsing must not silently
     * change the decimal value of a source token. The exact entry point keeps
     * its independent source certificate and does not use this admission gate.
     */
    private static double parseRoundTrippingLegacyValue(
        String sourceLexeme,
        int start
    ) {
        double value = parseFiniteLegacyValue(sourceLexeme, start);
        // Every unsigned integer of at most 15 digits round-trips unchanged.
        if (sourceLexeme.length() <= 15 && sourceLexeme.indexOf('.') < 0) {
            return value;
        }
        if (new java.math.BigDecimal(sourceLexeme).compareTo(
                java.math.BigDecimal.valueOf(value)) != 0) {
            throw new IllegalArgumentException(
                "Numeric literal would lose precision in the ordinary AST at "
                    + "position " + start + "; use the exact parsing path");
        }
        return value;
    }

    private static double parseFiniteLegacyValue(
        String sourceLexeme,
        int start
    ) {
        double legacyValue;
        try {
            legacyValue = Double.parseDouble(sourceLexeme);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "Numeric literal is not representable by the legacy AST at "
                    + "position " + start,
                exception);
        }
        if (!Double.isFinite(legacyValue)) {
            throw new IllegalArgumentException(
                "Numeric literal is not representable by the legacy AST at "
                    + "position " + start);
        }
        return legacyValue;
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
                throw new IllegalArgumentException(
                    "Missing closing ')' after function arguments at position "
                        + cursor.position());
            }
            return cursor.retainRange(
                new FunctionExpr(name, arguments),
                start,
                cursor.position());
        }
        return cursor.retainRange(
            new VariableExpr(name),
            start,
            nameEnd);
    }

    private static final class Cursor {
        private final String value;
        private final List<ExactParsedTerm.LiteralOccurrence> exactLiterals;
        private final Map<Expr, ExactParsedTerm.SourceRange> exactSourceRanges;
        private int position;

        private Cursor(String value, boolean retainExactEvidence) {
            this.value = value;
            this.exactLiterals = retainExactEvidence
                ? new ArrayList<>()
                : null;
            this.exactSourceRanges = retainExactEvidence
                ? new IdentityHashMap<>()
                : null;
            this.position = 0;
        }

        private static Cursor legacy(String value) {
            return new Cursor(value, false);
        }

        private static Cursor exact(String value) {
            return new Cursor(value, true);
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
