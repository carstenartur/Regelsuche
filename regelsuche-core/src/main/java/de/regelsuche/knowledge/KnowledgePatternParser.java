package de.regelsuche.knowledge;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.transform.PatternExpr;

import java.util.ArrayList;
import java.util.List;

final class KnowledgePatternParser {
    private final String input;
    private int pos;

    private KnowledgePatternParser(String input) {
        this.input = input.replaceAll("\\s+", "");
    }

    static PatternExpr parse(String input) {
        KnowledgePatternParser parser = new KnowledgePatternParser(input);
        PatternExpr expr = parser.parseExpression();
        if (!parser.isAtEnd()) {
            throw new IllegalArgumentException(
                "Unexpected token at position " + parser.pos + " in " + input
            );
        }
        return expr;
    }

    private PatternExpr parseExpression() {
        PatternExpr expr = parseTerm();
        while (match('+') || match('-')) {
            char op = input.charAt(pos - 1);
            PatternExpr right = parseTerm();
            expr = PatternExpr.op(
                op == '+' ? BinaryOperator.ADD : BinaryOperator.SUB,
                expr,
                right
            );
        }
        return expr;
    }

    private PatternExpr parseTerm() {
        PatternExpr expr = parseUnary();
        while (match('*') || match('/')) {
            char op = input.charAt(pos - 1);
            PatternExpr right = parseUnary();
            expr = PatternExpr.op(
                op == '*' ? BinaryOperator.MUL : BinaryOperator.DIV,
                expr,
                right
            );
        }
        return expr;
    }

    private PatternExpr parseUnary() {
        if (match('-')) {
            return PatternExpr.op(
                BinaryOperator.SUB,
                PatternExpr.num(0),
                parseUnary()
            );
        }
        return parsePower();
    }

    private PatternExpr parsePower() {
        PatternExpr expr = parsePrimary();
        if (match('^')) {
            return PatternExpr.op(BinaryOperator.POW, expr, parseUnary());
        }
        return expr;
    }

    private PatternExpr parsePrimary() {
        if (match('(')) {
            PatternExpr expr = parseExpression();
            expect(')');
            return expr;
        }
        if (match('?')) {
            String name = readPlainIdentifier();
            if (name.isBlank()) {
                throw new IllegalArgumentException(
                    "Expected pattern variable at position " + pos
                );
            }
            return PatternExpr.var(name);
        }
        String identifier = readPlainIdentifier();
        if (!identifier.isBlank()) {
            if (match('(')) {
                List<PatternExpr> arguments = new ArrayList<>();
                if (!match(')')) {
                    do {
                        arguments.add(parseExpression());
                    } while (match(','));
                    expect(')');
                }
                return functionalExpression(identifier, arguments);
            }
            return PatternExpr.variable(identifier);
        }
        String number = readNumber();
        if (!number.isBlank()) {
            return PatternExpr.num(Double.parseDouble(number));
        }
        throw new IllegalArgumentException(
            "Expected expression at position " + pos + " in " + input
        );
    }

    private PatternExpr functionalExpression(
        String identifier,
        List<PatternExpr> arguments
    ) {
        BinaryOperator operator = switch (identifier) {
            case "add" -> BinaryOperator.ADD;
            case "sub" -> BinaryOperator.SUB;
            case "mul" -> BinaryOperator.MUL;
            case "div" -> BinaryOperator.DIV;
            case "pow" -> BinaryOperator.POW;
            default -> null;
        };
        if (operator == null) {
            return PatternExpr.fn(
                identifier,
                arguments.toArray(PatternExpr[]::new)
            );
        }
        if (arguments.size() != 2) {
            throw new IllegalArgumentException(
                "Functional operator " + identifier
                    + " requires exactly two arguments in " + input
            );
        }
        return PatternExpr.op(operator, arguments.get(0), arguments.get(1));
    }

    private String readPlainIdentifier() {
        int start = pos;
        if (!isAtEnd() && Character.isLetter(input.charAt(pos))) {
            pos++;
            while (!isAtEnd()
                    && Character.isLetterOrDigit(input.charAt(pos))) {
                pos++;
            }
        }
        return input.substring(start, pos);
    }

    private String readNumber() {
        int start = pos;
        while (!isAtEnd()
                && (Character.isDigit(input.charAt(pos))
                    || input.charAt(pos) == '.')) {
            pos++;
        }
        return input.substring(start, pos);
    }

    private boolean match(char expected) {
        if (!isAtEnd() && input.charAt(pos) == expected) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!match(expected)) {
            throw new IllegalArgumentException(
                "Expected '" + expected + "' at position " + pos
                    + " in " + input
            );
        }
    }

    private boolean isAtEnd() {
        return pos >= input.length();
    }
}
