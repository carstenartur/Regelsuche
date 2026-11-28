package de.regelsuche.discovery.representation;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A reusable exact four-operation program for one certified single-definition overlay. */
public final class ExactSharingArithmetic {
    public record Evaluation(ExactRational value, int arithmeticOperations, int referenceUses) { }

    private sealed interface Instruction { }
    private record Literal(ExactRational value) implements Instruction { }
    private record Input(String name) implements Instruction { }
    private record Operation(BinaryOperator operator, int left, int right) implements Instruction { }
    private record Reference(int definition) implements Instruction { }

    private final List<Instruction> instructions;

    private ExactSharingArithmetic(List<Instruction> instructions) {
        this.instructions = List.copyOf(instructions);
    }

    static ExactSharingArithmetic prepare(ExactSharingRepresentation overlay) {
        var builder = new Builder(Set.copyOf(overlay.referencePaths()));
        builder.compile(new ExpressionParser().parseTerm(overlay.sourceExpression()), ExpressionOccurrencePath.root());
        return new ExactSharingArithmetic(builder.instructions);
    }

    /** Counts actual arithmetic and every certified reference read, including the first. */
    public Evaluation evaluate(Map<String, ExactRational> inputs) {
        var registers = new ExactRational[instructions.size()];
        int arithmetic = 0;
        int references = 0;
        for (int index = 0; index < instructions.size(); index++) {
            registers[index] = switch (instructions.get(index)) {
                case Literal literal -> literal.value();
                case Input input -> {
                    var value = inputs.get(input.name());
                    if (value == null) throw new IllegalArgumentException("Missing input: " + input.name());
                    yield value;
                }
                case Reference reference -> {
                    references++;
                    yield registers[reference.definition()];
                }
                case Operation op -> {
                    var left = registers[op.left()];
                    var right = registers[op.right()];
                    arithmetic++;
                    yield switch (op.operator()) {
                        case ADD -> left.add(right);
                        case SUB -> left.subtract(right);
                        case MUL -> left.multiply(right);
                        case DIV -> left.divide(right);
                        case POW -> throw new IllegalStateException("Power cannot be prepared");
                    };
                }
            };
        }
        return new Evaluation(registers[registers.length - 1], arithmetic, references);
    }

    private static final class Builder {
        final Set<ExpressionOccurrencePath> paths;
        final List<Instruction> instructions = new ArrayList<>();
        int definition = -1;

        Builder(Set<ExpressionOccurrencePath> paths) { this.paths = paths; }

        int compile(Expr expr, ExpressionOccurrencePath path) {
            if (paths.contains(path)) {
                if (definition < 0) definition = emit(expr, path);
                return add(new Reference(definition));
            }
            return emit(expr, path);
        }

        private int emit(Expr expr, ExpressionOccurrencePath path) {
            return switch (expr) {
                case NumberExpr number -> add(new Literal(number.value()));
                case VariableExpr variable -> add(new Input(variable.name()));
                case BinaryExpr binary when binary.operator() != BinaryOperator.POW -> add(new Operation(
                    binary.operator(), compile(binary.left(), path.append(0)), compile(binary.right(), path.append(1))));
                default -> throw new IllegalArgumentException("Exact sharing arithmetic supports only +, -, * and /");
            };
        }

        private int add(Instruction instruction) {
            instructions.add(instruction);
            return instructions.size() - 1;
        }
    }
}
