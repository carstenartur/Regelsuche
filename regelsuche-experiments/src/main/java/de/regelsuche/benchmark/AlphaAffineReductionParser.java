package de.regelsuche.benchmark;

import de.regelsuche.ast.iteration.AffineReduction;
import de.regelsuche.ast.iteration.AffineReduction.Form;
import de.regelsuche.ast.iteration.AffineReduction.Operator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Source-only importer for a single affine reduction over a one-dimensional input. */
final class AlphaAffineReductionParser {
    private static final int SOURCE_LIMIT = 16_384;
    private static final Set<String> KEYWORDS = Set.of("affine", "inputs", "outputs", "let", "reduce", "max", "and");
    private static final Pattern TOKEN = Pattern.compile("\\G(?:\\s+|//[^\\r\\n]*|"
        + "([A-Za-z_][A-Za-z_0-9]*|[0-9]+|->|<=|>=|[-+*<>=\\[\\]{}(),.:;]))");

    record Imported(String name, AffineReduction source) {}

    private record ArrayDeclaration(String name, List<String> coordinates, List<Form> domain) {}

    private record Reduction(Operator operator, int rank, List<Form> domain, int inputIndex) {}

    private final List<String> tokens;
    private int cursor;

    private AlphaAffineReductionParser(List<String> tokens) {
        this.tokens = tokens;
    }

    static Imported parse(String source) {
        try {
            return new AlphaAffineReductionParser(tokenize(source)).program();
        } catch (ArithmeticException | NumberFormatException overflow) {
            throw new IllegalArgumentException("ALPHA_AFFINE_COEFFICIENT_LIMIT", overflow);
        }
    }

    private Imported program() {
        expect("affine");
        String name = identifier();
        String parameter = coordinates(1, List.of()).getFirst();
        expect("->");
        expect("{");
        expect(":");
        List<Form> parameters = domain(parameter, List.of(), "}");
        expect("inputs");
        ArrayDeclaration input = array(parameter, 1, List.of(parameter));
        expect("outputs");
        ArrayDeclaration output = array(parameter, 2, List.of(parameter, input.name()));
        expect("let");
        expect(output.name());
        if (!coordinates(2, List.of(parameter, input.name(), output.name())).equals(output.coordinates())) {
            throw new IllegalArgumentException("ALPHA_OUTPUT_INDEX_MISMATCH");
        }
        expect("=");
        Reduction reduction = reduction(parameter, input, output);
        expect(";");
        expect(".");
        if (cursor != tokens.size()) throw new IllegalArgumentException("ALPHA_TRAILING_PROGRAM");
        return new Imported(name, new AffineReduction(reduction.operator(), parameters, input.domain(),
            output.coordinates().size(), output.domain(), reduction.rank(), reduction.domain(), reduction.inputIndex()));
    }

    private ArrayDeclaration array(String parameter, int maxRank, List<String> taken) {
        String name = identifier();
        if (taken.contains(name)) throw new IllegalArgumentException("ALPHA_DUPLICATE_IDENTIFIER");
        expect(":");
        expect("{");
        var bound = new ArrayList<>(taken);
        bound.add(name);
        List<String> variables = coordinates(maxRank, bound);
        expect(":");
        return new ArrayDeclaration(name, variables, domain(parameter, variables, "}"));
    }

    private Reduction reduction(String parameter, ArrayDeclaration input, ArrayDeclaration output) {
        expect("reduce");
        expect("(");
        Operator operator;
        if (accept("+")) operator = Operator.SUM;
        else if (accept("max")) operator = Operator.MAX;
        else throw new IllegalArgumentException("ALPHA_REDUCTION_OPERATOR");
        expect(",");
        var taken = new ArrayList<>(output.coordinates());
        taken.addAll(List.of(parameter, input.name(), output.name()));
        List<String> reduced = coordinates(2, taken);
        expect(",");
        expect("{");
        expect(":");
        var variables = new ArrayList<>(output.coordinates());
        variables.addAll(reduced);
        List<Form> domain = domain(parameter, variables, "}");
        expect(":");
        expect(input.name());
        expect("[");
        int inputIndex = reduced.indexOf(identifier());
        if (inputIndex < 0) throw new IllegalArgumentException("ALPHA_UNSUPPORTED_INPUT_ACCESS");
        expect("]");
        expect(")");
        return new Reduction(operator, reduced.size(), domain, inputIndex);
    }

    private List<String> coordinates(int limit, List<String> taken) {
        expect("[");
        var names = new ArrayList<String>();
        do {
            String name = identifier();
            if (taken.contains(name) || names.contains(name)) {
                throw new IllegalArgumentException("ALPHA_DUPLICATE_IDENTIFIER");
            }
            if (names.size() >= limit) throw new IllegalArgumentException("ALPHA_COORDINATE_LIMIT");
            names.add(name);
        } while (accept(","));
        expect("]");
        return List.copyOf(names);
    }

    private List<Form> domain(String parameter, List<String> variables, String terminator) {
        var constraints = new ArrayList<Form>();
        if (accept(terminator)) return List.of();
        do {
            int[] left = expression(parameter, variables);
            do {
                String relation = peek();
                if (!isRelation(relation)) throw new IllegalArgumentException("ALPHA_EXPECTED_AFFINE_RELATION");
                cursor++;
                int[] right = expression(parameter, variables);
                appendConstraint(constraints, relation, left, right);
                left = right;
            } while (isRelation(peek()));
        } while (accept("and"));
        expect(terminator);
        return List.copyOf(constraints);
    }

    private static boolean isRelation(String symbol) {
        return switch (symbol) {
            case "<", "<=", ">", ">=", "=" -> true;
            default -> false;
        };
    }

    private static void appendConstraint(List<Form> constraints, String relation, int[] left, int[] right) {
        switch (relation) {
            case "<" -> constraints.add(difference(right, left, 1));
            case "<=" -> constraints.add(difference(right, left, 0));
            case ">" -> constraints.add(difference(left, right, 1));
            case ">=" -> constraints.add(difference(left, right, 0));
            case "=" -> {
                constraints.add(difference(right, left, 0));
                constraints.add(difference(left, right, 0));
            }
            default -> throw new IllegalArgumentException("ALPHA_EXPECTED_AFFINE_RELATION");
        }
    }

    private static Form difference(int[] positive, int[] negative, int strict) {
        var coefficients = new ArrayList<Integer>();
        for (int i = 2; i < positive.length; i++) {
            coefficients.add(Math.subtractExact(positive[i], negative[i]));
        }
        // A comparison is stored as constant + parameter term + coordinate terms >= 0.
        int constant = Math.toIntExact((long) positive[0] - negative[0] - strict);
        return new Form(constant, Math.subtractExact(positive[1], negative[1]), coefficients);
    }

    private int[] expression(String parameter, List<String> variables) {
        var result = new int[variables.size() + 2];
        atom(result, parameter, variables, sign());
        while ("+".equals(peek()) || "-".equals(peek())) atom(result, parameter, variables, sign());
        return result;
    }

    private int sign() {
        if (accept("-")) return -1;
        accept("+");
        return 1;
    }

    private void atom(int[] coefficients, String parameter, List<String> variables, int sign) {
        String token = peek();
        int multiplier = sign;
        if (!token.isEmpty() && Character.isDigit(token.charAt(0))) {
            cursor++;
            multiplier = Math.toIntExact(Math.multiplyExact(Long.parseLong(token), sign));
            if (!accept("*") && !isIdentifier(peek())) {
                coefficients[0] = Math.addExact(coefficients[0], multiplier);
                return;
            }
        }
        String variable = identifier();
        int index = 1;
        if (!variable.equals(parameter)) {
            int coordinate = variables.indexOf(variable);
            if (coordinate < 0) throw new IllegalArgumentException("ALPHA_UNDECLARED_INDEX");
            index = coordinate + 2;
        }
        coefficients[index] = Math.addExact(coefficients[index], multiplier);
    }

    private static List<String> tokenize(String source) {
        if (source.length() > SOURCE_LIMIT) throw new IllegalArgumentException("ALPHA_SOURCE_LIMIT");
        var tokens = new ArrayList<String>();
        var matcher = TOKEN.matcher(source);
        int consumed = 0;
        while (matcher.find()) {
            if (matcher.group(1) != null) tokens.add(matcher.group(1));
            consumed = matcher.end();
        }
        if (consumed != source.length()) throw new IllegalArgumentException("ALPHA_UNSUPPORTED_TOKEN");
        return tokens;
    }

    private String identifier() {
        String token = peek();
        if (!isIdentifier(token)) throw new IllegalArgumentException("ALPHA_EXPECTED_IDENTIFIER");
        cursor++;
        return token;
    }

    private static boolean isIdentifier(String token) {
        return !token.isEmpty() && (Character.isLetter(token.charAt(0)) || token.charAt(0) == '_')
            && !KEYWORDS.contains(token);
    }

    private String peek() {
        return cursor < tokens.size() ? tokens.get(cursor) : "";
    }

    private boolean accept(String token) {
        if (!token.equals(peek())) return false;
        cursor++;
        return true;
    }

    private void expect(String token) {
        if (!accept(token)) throw new IllegalArgumentException("ALPHA_EXPECTED_TOKEN: " + token);
    }
}
