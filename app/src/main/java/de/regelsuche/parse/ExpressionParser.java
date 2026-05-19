package de.regelsuche.parse;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import java.util.ArrayList;
import java.util.List;

public class ExpressionParser {
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

    public Expr parseTerm(String term) {
        Cursor cursor = new Cursor(term);
        Expr expr = parseExpression(cursor);
        cursor.skipWhitespace();
        if (!cursor.isAtEnd()) {
            throw new IllegalArgumentException("Unexpected token at position " + cursor.position());
        }
        return expr;
    }

    public Equation parseEquation(String equation) {
        int idx = equation.indexOf('=');
        if (idx < 1 || idx == equation.length() - 1) {
            throw new IllegalArgumentException("Equation must contain exactly one '=' with both sides present");
        }
        String left = equation.substring(0, idx);
        String right = equation.substring(idx + 1);
        if (equation.indexOf('=', idx + 1) >= 0) {
            throw new IllegalArgumentException("Equation must contain exactly one '='");
        }
        return new Equation(parseTerm(left), parseTerm(right));
    }

    private Expr parseExpression(Cursor cursor) {
        Expr result = parseTermInternal(cursor);
        while (true) {
            cursor.skipWhitespace();
            if (cursor.consume('+')) {
                result = new BinaryExpr(result, BinaryOperator.ADD, parseTermInternal(cursor));
            } else if (cursor.consume('-')) {
                result = new BinaryExpr(result, BinaryOperator.SUB, parseTermInternal(cursor));
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
                result = new BinaryExpr(result, BinaryOperator.MUL, parseUnary(cursor));
            } else if (cursor.consume('/')) {
                result = new BinaryExpr(result, BinaryOperator.DIV, parseUnary(cursor));
            } else {
                return result;
            }
        }
    }

    private Expr parseUnary(Cursor cursor) {
        cursor.skipWhitespace();
        if (cursor.consume('-')) {
            return new BinaryExpr(new NumberExpr(0), BinaryOperator.SUB, parseUnary(cursor));
        }
        return parsePower(cursor);
    }

    private Expr parsePower(Cursor cursor) {
        Expr left = parsePrimary(cursor);
        cursor.skipWhitespace();
        if (cursor.consume('^')) {
            return new BinaryExpr(left, BinaryOperator.POW, parseUnary(cursor));
        }
        return left;
    }

    private Expr parsePrimary(Cursor cursor) {
        cursor.skipWhitespace();
        if (cursor.consume('(')) {
            Expr inner = parseExpression(cursor);
            cursor.skipWhitespace();
            if (!cursor.consume(')')) {
                throw new IllegalArgumentException("Missing closing ')' at position " + cursor.position());
            }
            return inner;
        }
        if (cursor.peekDigit()) {
            return parseNumber(cursor);
        }
        if (cursor.peekLetter()) {
            return parseVariable(cursor);
        }
        throw new IllegalArgumentException("Unexpected token at position " + cursor.position());
    }

    private Expr parseNumber(Cursor cursor) {
        int start = cursor.position();
        while (cursor.peekDigit()) {
            cursor.advance();
        }
        if (cursor.peek('.') ) {
            cursor.advance();
            while (cursor.peekDigit()) {
                cursor.advance();
            }
        }
        return new NumberExpr(Double.parseDouble(cursor.slice(start, cursor.position())));
    }

    private Expr parseVariable(Cursor cursor) {
        int start = cursor.position();
        while (cursor.peekLetterOrDigitOrUnderscore()) {
            cursor.advance();
        }
        return new VariableExpr(cursor.slice(start, cursor.position()));
    }

    private static final class Cursor {
        private final String value;
        private int position;

        private Cursor(String value) {
            this.value = value;
            this.position = 0;
        }

        private int position() {
            return position;
        }

        private void skipWhitespace() {
            while (!isAtEnd() && Character.isWhitespace(value.charAt(position))) {
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
            return !isAtEnd() && Character.isDigit(value.charAt(position));
        }

        private boolean peekLetter() {
            return !isAtEnd() && Character.isLetter(value.charAt(position));
        }

        private boolean peekLetterOrDigitOrUnderscore() {
            return !isAtEnd() && (Character.isLetterOrDigit(value.charAt(position)) || value.charAt(position) == '_');
        }

        private void advance() {
            if (!isAtEnd()) {
                position++;
            }
        }

        private String slice(int from, int to) {
            return value.substring(from, to);
        }
    }
}
