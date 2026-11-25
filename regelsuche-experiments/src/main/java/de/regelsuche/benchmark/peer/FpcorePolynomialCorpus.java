package de.regelsuche.benchmark.peer;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.math.algorithms.equivalence.RationalFunctionPlanner;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.parse.ExpressionParser;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit rational-polynomial projection; never an IEEE equivalence claim. */
public final class FpcorePolynomialCorpus {
    public enum Status { SUPPORTED, UNSUPPORTED }
    public record Entry(int ordinal, String name, String source, Status status, String detail) { }
    private FpcorePolynomialCorpus() { }

    /** Every top-level program produces a row, including unsupported bodies. */
    public static List<Entry> read(String text) {
        return read(text, false);
    }

    /** Separate bounded rational-domain projection; historical polynomial admission is unchanged. */
    public static List<Entry> readRational(String text) {
        return read(text, true);
    }

    private static List<Entry> read(String text, boolean rational) {
        var parser = new Reader(text);
        var entries = new ArrayList<Entry>();
        while (parser.hasNext()) {
            Form program = form(parser.read(0));
            if (program.values().size() < 3 || !atom(program.values().getFirst()).equals("FPCore")) {
                throw new IllegalArgumentException("expected a complete FPCore program");
            }
            int variablesAt = program.values().get(1) instanceof Form ? 1 : 2;
            String name = variablesAt == 2 ? atom(program.values().get(1)) : "program-" + (entries.size() + 1);
            for (int index = variablesAt + 1; index < program.values().size() - 1; index += 2) {
                if (index + 1 >= program.values().size() - 1) throw new IllegalArgumentException("unpaired FPCore metadata");
                if (atom(program.values().get(index)).equals(":name")) name = atom(program.values().get(index + 1));
            }
            int ordinal = entries.size() + 1;
            try {
                Form variables = form(program.values().get(variablesAt));
                if (variables.values().size() > 4) throw new IllegalArgumentException("VARIABLE_LIMIT");
                var environment = new LinkedHashMap<String, String>();
                for (Node variable : variables.values()) {
                    String symbol = atom(variable);
                    if (!symbol.matches("[A-Za-z][A-Za-z0-9_]*") || environment.putIfAbsent(symbol, symbol) != null) {
                        throw new IllegalArgumentException("VARIABLE_DECLARATION_UNSUPPORTED");
                    }
                }
                String source = expand(program.values().getLast(), environment, rational);
                var parsed = new ExpressionParser().parseExactTerm(source);
                if (nodes(parsed.expression()) > 256) throw new IllegalArgumentException("AST_NODE_LIMIT");
                if (rational) {
                    var admitted = RationalFunctionPlanner.simplify(source, List.of(),
                        new IterationLimits(2_000_000, 20_000, 5_000), () -> false);
                    if (admitted.status() == RationalFunctionPlanner.Status.UNAVAILABLE)
                        throw new IllegalArgumentException(admitted.reason());
                } else new ExactPolynomialAnalysis().normalForm(source);
                entries.add(new Entry(ordinal, name, source, Status.SUPPORTED,
                    rational ? "EXACT_RATIONAL_DOMAIN_PROJECTION" : "EXACT_RATIONAL_PROJECTION"));
            } catch (IllegalArgumentException | ArithmeticException unsupported) {
                entries.add(new Entry(ordinal, name, "", Status.UNSUPPORTED,
                    unsupported.getMessage() == null ? unsupported.getClass().getSimpleName() : unsupported.getMessage()));
            }
        }
        return List.copyOf(entries);
    }

    /** The bridge receives only the selected source; it does not receive corpus metadata. */
    public static String toPrefix(String source) {
        var parsed = new ExpressionParser().parseExactTerm(source);
        return prefix(parsed.expression(), parsed);
    }

    public static String fromPrefix(String source) {
        var reader = new Reader(source);
        Node node = reader.read(0);
        if (reader.hasNext()) throw new IllegalArgumentException("trailing peer expression");
        var variables = new HashMap<String, String>();
        collectVariables(node, variables);
        return expand(node, variables, false);
    }

    private static void collectVariables(Node node, Map<String, String> result) {
        if (node instanceof Atom value) {
            if (!value.quoted() && value.value().matches("[A-Za-z][A-Za-z0-9_]*")) result.put(value.value(), value.value());
        } else {
            var children = form(node).values();
            for (int i = 1; i < children.size(); i++) collectVariables(children.get(i), result);
        }
    }

    private static String prefix(Expr expression, de.regelsuche.parse.ExactParsedTerm parsed) {
        if (expression instanceof VariableExpr variable) return variable.name();
        if (expression instanceof NumberExpr number) {
            var value = parsed.literalFor(number).map(de.regelsuche.parse.ExactParsedTerm.LiteralOccurrence::exactValue)
                .orElse(de.regelsuche.scalar.ExactRational.ZERO);
            return value.toString();
        }
        if (expression instanceof BinaryExpr binary) {
            String operator = switch (binary.operator()) {
                case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
                default -> throw new IllegalArgumentException("peer operator unsupported");
            };
            return "(" + operator + " " + prefix(binary.left(), parsed) + " " + prefix(binary.right(), parsed) + ")";
        }
        throw new IllegalArgumentException("peer expression unsupported");
    }

    private static int nodes(Expr expression) {
        return expression instanceof BinaryExpr binary ? 1 + nodes(binary.left()) + nodes(binary.right()) : 1;
    }

    private static String expand(Node node, Map<String, String> environment, boolean rational) {
        if (node instanceof Atom value) {
            if (value.quoted()) throw new IllegalArgumentException("STRING_EXPRESSION_UNSUPPORTED");
            String bound = environment.get(value.value());
            if (bound != null) return bound;
            return number(value.value());
        }
        List<Node> values = form(node).values();
        if (values.isEmpty()) throw new IllegalArgumentException("EMPTY_EXPRESSION");
        String operator = atom(values.getFirst());
        if (operator.equals("let") || operator.equals("let*")) {
            if (values.size() != 3) throw new IllegalArgumentException("LET_ARITY");
            var nested = new HashMap<>(environment);
            var names = new java.util.HashSet<String>();
            for (Node binding : form(values.get(1)).values()) {
                var pair = form(binding).values();
                if (pair.size() != 2) throw new IllegalArgumentException("BINDING_ARITY");
                String name = atom(pair.getFirst());
                if (!name.matches("[A-Za-z][A-Za-z0-9_*]*") || !names.add(name)) {
                    throw new IllegalArgumentException("BINDING_NAME");
                }
                nested.put(name, expand(pair.get(1), operator.equals("let*") ? nested : environment, rational));
            }
            return expand(values.get(2), nested, rational);
        }
        if (!List.of("+", "-", "*", "/", "~").contains(operator)) {
            throw new IllegalArgumentException("OPERATOR_UNSUPPORTED:" + operator);
        }
        if ((operator.equals("-") || operator.equals("~")) && values.size() == 2) {
            return bounded("(-" + expand(values.get(1), environment, rational) + ")");
        }
        if (values.size() != 3 || operator.equals("~")) throw new IllegalArgumentException("OPERATOR_ARITY");
        String left = expand(values.get(1), environment, rational);
        String right = expand(values.get(2), environment, rational);
        if (operator.equals("/") && !rational) {
            Expr denominator = new ExpressionParser().parseExactTerm(right).expression();
            if (hasVariable(denominator)) throw new IllegalArgumentException("VARIABLE_DENOMINATOR");
            new ExactPolynomialAnalysis().normalForm("1/(" + right + ")");
        }
        return bounded("(" + left + operator + right + ")");
    }

    private static boolean hasVariable(Expr expression) {
        return expression instanceof VariableExpr || expression instanceof BinaryExpr binary
            && (hasVariable(binary.left()) || hasVariable(binary.right()));
    }

    private static String number(String token) {
        if (token.length() > 96) throw new IllegalArgumentException("LITERAL_LENGTH_LIMIT");
        BigInteger numerator;
        BigInteger denominator;
        if (token.matches("-?[0-9]+/[0-9]+")) {
            String[] pieces = token.split("/", -1);
            numerator = new BigInteger(pieces[0]); denominator = new BigInteger(pieces[1]);
        } else {
            BigDecimal value;
            try { value = new BigDecimal(token); }
            catch (NumberFormatException unsupported) { throw new IllegalArgumentException("UNBOUND_OR_UNSUPPORTED_ATOM:" + token); }
            if (Math.abs((long) value.scale()) > 100 || value.precision() > 64) {
                throw new IllegalArgumentException("LITERAL_SCALE_LIMIT");
            }
            numerator = value.unscaledValue();
            denominator = BigInteger.TEN.pow(Math.max(0, value.scale()));
            if (value.scale() < 0) numerator = numerator.multiply(BigInteger.TEN.pow(-value.scale()));
        }
        if (denominator.signum() == 0) throw new IllegalArgumentException("ZERO_DENOMINATOR");
        BigInteger divisor = numerator.gcd(denominator);
        numerator = numerator.divide(divisor); denominator = denominator.divide(divisor);
        requireWorkerLiteral(numerator); requireWorkerLiteral(denominator);
        return denominator.equals(BigInteger.ONE) ? numerator.toString() : "(" + numerator + "/" + denominator + ")";
    }

    private static void requireWorkerLiteral(BigInteger integer) {
        BigInteger magnitude = integer.abs();
        if (magnitude.bitLength() > 63) {
            throw new IllegalArgumentException("LITERAL_NOT_EXACT_IN_BOTH_WORKER_BRIDGES");
        }
    }

    private static String bounded(String source) {
        if (source.length() > 2048) throw new IllegalArgumentException("SOURCE_LENGTH_LIMIT");
        return source;
    }

    private sealed interface Node permits Atom, Form { }
    private record Atom(String value, boolean quoted) implements Node { }
    private record Form(List<Node> values) implements Node { }
    private static String atom(Node node) {
        if (node instanceof Atom value) return value.value();
        throw new IllegalArgumentException("expected atom");
    }
    private static Form form(Node node) {
        if (node instanceof Form value) return value;
        throw new IllegalArgumentException("expected list");
    }

    private static final class Reader {
        private final String text;
        private int at;
        Reader(String text) {
            if (text == null || text.length() > 1_000_000) throw new IllegalArgumentException("document length limit");
            this.text = text;
        }
        boolean hasNext() { skip(); return at < text.length(); }
        Node read(int depth) {
            if (depth > 128 || !hasNext()) throw new IllegalArgumentException("incomplete or excessively nested expression");
            char first = text.charAt(at++);
            if (first == '(' || first == '[') {
                char close = first == '(' ? ')' : ']';
                var children = new ArrayList<Node>();
                while (hasNext() && text.charAt(at) != close) children.add(read(depth + 1));
                if (!hasNext()) throw new IllegalArgumentException("unclosed expression");
                at++;
                return new Form(List.copyOf(children));
            }
            if (first == ')' || first == ']') throw new IllegalArgumentException("unmatched delimiter");
            if (first == '"') {
                var value = new StringBuilder();
                while (at < text.length()) {
                    char next = text.charAt(at++);
                    if (next == '"') return new Atom(value.toString(), true);
                    if (next == '\\') {
                        if (at == text.length()) break;
                        next = text.charAt(at++);
                    }
                    value.append(next);
                }
                throw new IllegalArgumentException("unclosed string");
            }
            int start = at - 1;
            while (at < text.length() && !Character.isWhitespace(text.charAt(at))
                    && "()[];".indexOf(text.charAt(at)) < 0) at++;
            return new Atom(text.substring(start, at), false);
        }
        private void skip() {
            while (at < text.length()) {
                if (Character.isWhitespace(text.charAt(at))) at++;
                else if (text.charAt(at) == ';') {
                    while (at < text.length() && text.charAt(at) != '\n') at++;
                } else break;
            }
        }
    }
}
