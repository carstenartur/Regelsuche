package de.regelsuche.evolution;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compiles an accepted genome into independently executable rewrite rules. */
public final class EvolutionGenomeCompiler {
    private final EvolutionGenomeValidator validator;

    public EvolutionGenomeCompiler() {
        this(new EvolutionGenomeValidator());
    }

    public EvolutionGenomeCompiler(EvolutionGenomeValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public CompiledProgram compile(EvolutionGenome genome) {
        Objects.requireNonNull(genome, "genome");
        EvolutionGenomeValidator.ValidationReport report = validator.validate(genome);
        if (!report.accepted()) {
            throw new IllegalArgumentException(
                "genome failed preflight: " + report.blockerCodes());
        }
        List<RewriteRule> rules = genome.rewrites().stream()
            .map(gene -> (RewriteRule) new CompiledGenomeRule(genome, gene))
            .toList();
        return new CompiledProgram(
            genome.contentHash(),
            genome.alphaStructuralHash(),
            genome.objective(),
            genome.budget(),
            rules);
    }

    static PatternExpr parsePattern(String pattern) {
        return new PatternParser(pattern).parse();
    }

    static int nodeCount(PatternExpr expression) {
        if (expression instanceof PatternExpr.Operation operation) {
            return 1 + nodeCount(operation.left()) + nodeCount(operation.right());
        }
        if (expression instanceof PatternExpr.Function function) {
            return 1 + function.arguments().stream()
                .mapToInt(EvolutionGenomeCompiler::nodeCount)
                .sum();
        }
        return 1;
    }

    static String renderPattern(PatternExpr expression) {
        if (expression instanceof PatternExpr.Placeholder placeholder) {
            return "?" + placeholder.name();
        }
        if (expression instanceof PatternExpr.LiteralNumber number) {
            double value = number.value();
            return value == Math.rint(value)
                ? Long.toString((long) value)
                : Double.toString(value);
        }
        if (expression instanceof PatternExpr.LiteralVariable variable) {
            return variable.name();
        }
        if (expression instanceof PatternExpr.Operation operation) {
            return "(" + renderPattern(operation.left())
                + operatorSymbol(operation.operator())
                + renderPattern(operation.right()) + ")";
        }
        PatternExpr.Function function = (PatternExpr.Function) expression;
        return function.name() + "(" + function.arguments().stream()
            .map(EvolutionGenomeCompiler::renderPattern)
            .collect(java.util.stream.Collectors.joining(",")) + ")";
    }

    private static String operatorSymbol(BinaryOperator operator) {
        return switch (operator) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case POW -> "^";
        };
    }

    public record CompiledProgram(
        String genomeHash,
        String alphaStructuralHash,
        EvolutionGenome.Objective objective,
        EvolutionGenome.ResourceBudget budget,
        List<RewriteRule> rules
    ) {
        public CompiledProgram {
            EvolutionGenome.requireSha256(genomeHash, "genomeHash");
            EvolutionGenome.requireSha256(alphaStructuralHash, "alphaStructuralHash");
            Objects.requireNonNull(objective, "objective");
            Objects.requireNonNull(budget, "budget");
            rules = List.copyOf(rules);
            if (rules.isEmpty()) {
                throw new IllegalArgumentException("compiled program must contain rules");
            }
        }
    }

    private static final class CompiledGenomeRule implements RewriteRule {
        private final String id;
        private final EvolutionGenome.RewriteGene gene;
        private final PatternExpr source;
        private final PatternExpr target;

        private CompiledGenomeRule(
            EvolutionGenome genome,
            EvolutionGenome.RewriteGene gene
        ) {
            this.id = "evo_"
                + genome.alphaStructuralHash().substring("sha256:".length(), 19)
                + "_" + gene.geneId();
            this.gene = gene;
            this.source = parsePattern(gene.sourcePattern());
            this.target = parsePattern(gene.targetPattern());
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public RewriteKind kind() {
            return gene.kind();
        }

        @Override
        public boolean mayIncreaseComplexity() {
            return gene.maxAstGrowth() > 0;
        }

        @Override
        public int estimatedCostDelta() {
            return gene.estimatedCostDelta();
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return false;
        }

        @Override
        public boolean matches(Expr subtree) {
            return source.match(subtree, new HashMap<>());
        }

        @Override
        public Expr apply(Expr subtree) {
            Map<String, Expr> bindings = new HashMap<>();
            if (!source.match(subtree, bindings)) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return target.instantiate(bindings);
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            if (gene.assumptions().isEmpty()) {
                return List.of();
            }
            Map<String, Expr> bindings = new HashMap<>();
            if (!source.match(subtree, bindings)) {
                return List.of();
            }
            return gene.assumptions().stream()
                .map(template -> instantiate(template, bindings))
                .toList();
        }

        private static Assumption instantiate(
            EvolutionGenome.AssumptionTemplate template,
            Map<String, Expr> bindings
        ) {
            String expression = substitute(template.expression(), bindings);
            List<String> symbols = template.symbols().stream()
                .map(symbol -> substitute(symbol, bindings))
                .toList();
            return new Assumption(template.kind(), expression, symbols);
        }

        private static String substitute(String value, Map<String, Expr> bindings) {
            return EvolutionGenome.transformPlaceholders(value, token -> {
                Expr binding = bindings.get(token.substring(1));
                if (binding == null) {
                    throw new IllegalArgumentException(
                        "Missing binding for assumption placeholder " + token);
                }
                return ExpressionFormatter.format(binding);
            });
        }
    }

    private static final class PatternParser {
        private final String input;
        private int position;

        private PatternParser(String input) {
            this.input = EvolutionGenome.normalizePattern(input);
        }

        private PatternExpr parse() {
            PatternExpr expression = parseExpression();
            if (!isAtEnd()) {
                throw error("Unexpected token");
            }
            return expression;
        }

        private PatternExpr parseExpression() {
            PatternExpr expression = parseTerm();
            while (peek('+') || peek('-')) {
                char operator = consume();
                PatternExpr right = parseTerm();
                expression = PatternExpr.op(
                    operator == '+' ? BinaryOperator.ADD : BinaryOperator.SUB,
                    expression,
                    right);
            }
            return expression;
        }

        private PatternExpr parseTerm() {
            PatternExpr expression = parseUnary();
            while (peek('*') || peek('/')) {
                char operator = consume();
                PatternExpr right = parseUnary();
                expression = PatternExpr.op(
                    operator == '*' ? BinaryOperator.MUL : BinaryOperator.DIV,
                    expression,
                    right);
            }
            return expression;
        }

        private PatternExpr parseUnary() {
            if (peek('-')) {
                consume();
                return PatternExpr.op(
                    BinaryOperator.SUB,
                    PatternExpr.num(0),
                    parseUnary());
            }
            return parsePower();
        }

        private PatternExpr parsePower() {
            PatternExpr expression = parsePrimary();
            if (peek('^')) {
                consume();
                return PatternExpr.op(BinaryOperator.POW, expression, parseUnary());
            }
            return expression;
        }

        private PatternExpr parsePrimary() {
            if (peek('(')) {
                consume();
                PatternExpr expression = parseExpression();
                expect(')');
                return expression;
            }
            if (peek('?')) {
                consume();
                String name = readIdentifier();
                if (name.isEmpty()) {
                    throw error("Expected placeholder name");
                }
                return PatternExpr.var(name);
            }
            if (peekDigit() || peek('.')) {
                return PatternExpr.num(readNumber());
            }
            String identifier = readIdentifier();
            if (!identifier.isEmpty()) {
                if (peek('(')) {
                    consume();
                    List<PatternExpr> arguments = new ArrayList<>();
                    if (!peek(')')) {
                        do {
                            arguments.add(parseExpression());
                        } while (consumeIf(','));
                    }
                    expect(')');
                    return PatternExpr.fn(
                        identifier,
                        arguments.toArray(PatternExpr[]::new));
                }
                return PatternExpr.variable(identifier);
            }
            throw error("Expected expression");
        }

        private String readIdentifier() {
            int start = position;
            if (!isAtEnd()
                    && (Character.isLetter(input.charAt(position))
                        || input.charAt(position) == '_')) {
                position++;
                while (!isAtEnd()
                        && (Character.isLetterOrDigit(input.charAt(position))
                            || input.charAt(position) == '_')) {
                    position++;
                }
            }
            return input.substring(start, position);
        }

        private double readNumber() {
            int start = position;
            boolean decimal = false;
            while (!isAtEnd()) {
                char current = input.charAt(position);
                if (Character.isDigit(current)) {
                    position++;
                } else if (current == '.' && !decimal) {
                    decimal = true;
                    position++;
                } else {
                    break;
                }
            }
            String token = input.substring(start, position);
            if (token.equals(".")) {
                throw error("Invalid number");
            }
            try {
                return Double.parseDouble(token);
            } catch (NumberFormatException exception) {
                throw error("Invalid number");
            }
        }

        private boolean consumeIf(char expected) {
            if (peek(expected)) {
                consume();
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consumeIf(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private char consume() {
            if (isAtEnd()) {
                throw error("Unexpected end of pattern");
            }
            return input.charAt(position++);
        }

        private boolean peek(char expected) {
            return !isAtEnd() && input.charAt(position) == expected;
        }

        private boolean peekDigit() {
            return !isAtEnd() && Character.isDigit(input.charAt(position));
        }

        private boolean isAtEnd() {
            return position >= input.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(
                message + " at position " + position + " in " + input);
        }
    }
}
