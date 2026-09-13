package de.regelsuche.math.sympy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Separately pinned source-only competitors; never a rewrite rule or equivalence certificate. */
public final class SymPyNamedOperationEngine implements AutoCloseable {
    public static final String PROTOCOL = "regelsuche.sympy-named-operation/v1";
    public enum Operation { TRIGSIMP, FU, FACTOR, CANCEL, TOGETHER, APART }
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String SCRIPT = loadScript();
    private GraalPySymPyRuntime runtime;
    private boolean closed;

    public record Outcome(String status, String output, String configurationHash, String canonicalJson, String contentHash) { }

    public static Map<String, Object> configuration(Operation operation) {
        return Map.of("protocol", PROTOCOL, "operation", operation.name(), "sympyVersion", "1.14.0",
            "graalpyVersion", "25.1.3", "mpmathVersion", "1.3.0", "scriptHash", SymPyEvidence.sha256(SCRIPT),
            "timeoutMillis", TIMEOUT.toMillis(), "sourceDomain", "SCALAR_COMPLEX_EXPLICIT_NONZERO",
            "preprocessing", "CLOSED_STRUCTURAL_IR_EVALUATE_FALSE_EXACT_RATIONAL_LITERALS",
            "selection", "ONE_PREDECLARED_OPERATION_NO_FALLBACK");
    }

    public static String configurationHash(Operation operation) { return SymPyEvidence.sha256(json(configuration(operation))); }
    static String script() { return SCRIPT; }

    public synchronized Outcome execute(Operation operation, String expression, List<String> assumptions) {
        if (operation == null || expression == null || expression.length() > 4096 || assumptions == null || assumptions.size() > 32)
            throw new IllegalArgumentException("bounded named-operation input required");
        checkNesting(expression);
        var parsed = new ExpressionParser().parseTerm(expression);
        var denominators = new ArrayList<String>();
        Object ir = ir(parsed, 0, new int[]{0}, denominators);
        var declarations = new java.util.TreeSet<String>();
        var nonzeroSymbols = new java.util.TreeSet<String>();
        for (String assumption : assumptions) {
            if (assumption == null || assumption.length() > 512) throw new IllegalArgumentException("invalid scalar assumption");
            var match = java.util.regex.Pattern.compile("^(.+?)\\s*(!=|==|=)\\s*0$").matcher(assumption.trim());
            if (!match.matches()) throw new IllegalArgumentException("only scalar zero/nonzero declarations supported");
            checkNesting(match.group(1));
            Expr subject = new ExpressionParser().parseTerm(match.group(1));
            ir(subject, 0, new int[]{0}, new ArrayList<>());
            String relation = match.group(2).equals("!=") ? " != 0" : " = 0";
            declarations.add(ExpressionFormatter.format(subject) + relation);
            if (subject instanceof VariableExpr symbol && relation.equals(" != 0")) nonzeroSymbols.add(symbol.name());
        }
        var input = new TreeMap<String, Object>();
        input.put("protocol", PROTOCOL); input.put("operation", operation.name()); input.put("sourceIr", ir);
        input.put("sourceExpression", ExpressionFormatter.format(parsed));
        input.put("nonzeroSymbols", nonzeroSymbols); input.put("declaredAssumptions", declarations);
        boolean conflict = declarations.stream().anyMatch(value -> value.endsWith(" != 0") && declarations.contains(value.replace(" != 0", " = 0")));
        if (conflict || declarations.stream().anyMatch(value -> value.endsWith(" = 0"))
            || denominators.contains("0") || !denominators.stream().allMatch(value -> declarations.contains(value + " != 0")))
            return outcome(operation, input, "UNSUPPORTED", "", "DECLARED_SOURCE_DOMAIN_UNKNOWN_FALSE_OR_CONFLICT", "", Map.of());
        if (closed) return outcome(operation, input, "UNAVAILABLE", "", "RUNTIME_CLOSED", "", Map.of());
        try {
            if (runtime == null) runtime = new GraalPySymPyRuntime(SCRIPT, "operation_payload");
            var invoked = runtime.invoke(json(input), TIMEOUT);
            if (invoked.status() != SymPyInvocation.Status.COMPLETED)
                return outcome(operation, input, invoked.status().name(), "", invoked.detailCode(), invoked.runtimeVersion(), Map.of());
            if (!invoked.runtimeVersion().endsWith("/sympy-1.14.0") || invoked.output().length() > 1_000_000)
                return outcome(operation, input, "TECHNICAL_FAILURE", "", "PINNED_RUNTIME_OR_OUTPUT_BOUNDARY_MISMATCH", invoked.runtimeVersion(), Map.of());
            var payload = JSON.readTree(invoked.output());
            if (!PROTOCOL.equals(payload.path("protocol").asText()) || !operation.name().equals(payload.path("operation").asText())
                || !"1.14.0".equals(payload.path("sympyVersion").asText())) throw new IllegalStateException("operation response binding differs");
            return outcome(operation, input, payload.path("status").asText(), payload.path("output").asText(),
                payload.path("detail").asText(), invoked.runtimeVersion(), JSON.convertValue(payload, Map.class));
        } catch (RuntimeException | java.io.IOException | LinkageError failure) {
            return outcome(operation, input, "TECHNICAL_FAILURE", "", "NAMED_OPERATION_" + failure.getClass().getSimpleName(), "", Map.of());
        }
    }

    private static Outcome outcome(Operation operation, Map<String, Object> input, String status, String output,
                                   String detail, String runtime, Map<String, Object> payload) {
        String configurationHash = configurationHash(operation);
        var receipt = new TreeMap<String, Object>();
        receipt.put("schema", PROTOCOL); receipt.put("configuration", configuration(operation)); receipt.put("configurationHash", configurationHash);
        receipt.put("input", input); receipt.put("inputHash", SymPyEvidence.sha256(json(input))); receipt.put("status", status);
        receipt.put("output", output); receipt.put("detail", detail); receipt.put("runtime", runtime); receipt.put("payload", payload);
        receipt.put("primitiveProof", "UNAVAILABLE"); receipt.put("internalWork", "UNAVAILABLE");
        receipt.put("comparativeMatchedWorkGate", "BLOCKED_UNAVAILABLE_INTERNAL_WORK");
        String canonical = json(receipt);
        return new Outcome(status, output, configurationHash, canonical, SymPyEvidence.sha256(canonical));
    }

    private static Object ir(Expr expression, int depth, int[] count, List<String> denominators) {
        if (depth > 48 || ++count[0] > 256) throw new IllegalArgumentException("scalar IR bound exceeded");
        if (expression instanceof NumberExpr number) return List.of("number", number.value().numerator().toString(), number.value().denominator().toString());
        if (expression instanceof VariableExpr symbol) return List.of("symbol", symbol.name());
        if (expression instanceof FunctionExpr function) {
            if (!List.of("sin", "cos").contains(function.name()) || function.arguments().size() != 1)
                throw new IllegalArgumentException("unsupported scalar function");
            return List.of(function.name(), ir(function.arguments().getFirst(), depth + 1, count, denominators));
        }
        var binary = (BinaryExpr) expression;
        if (binary.operator() == BinaryOperator.POW && (!(binary.right() instanceof NumberExpr number)
            || !number.value().isInteger() || number.value().numerator().signum() < 0 || number.value().numerator().bitLength() > 5))
            throw new IllegalArgumentException("unsupported scalar power");
        if (binary.operator() == BinaryOperator.DIV) {
            if (binary.right() instanceof NumberExpr number) {
                if (number.value().isZero()) denominators.add("0");
            } else denominators.add(ExpressionFormatter.format(binary.right()));
        }
        return List.of(binary.operator().name(), ir(binary.left(), depth + 1, count, denominators), ir(binary.right(), depth + 1, count, denominators));
    }

    private static void checkNesting(String expression) {
        int depth = 0;
        for (char c : expression.toCharArray()) {
            if (c == '(' && ++depth > 48) throw new IllegalArgumentException("source nesting exceeded");
            if (c == ')') depth--;
        }
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("cannot encode operation observation", failure); }
    }
    private static String loadScript() {
        try (var stream = SymPyNamedOperationEngine.class.getResourceAsStream("named_operations_v1.py")) {
            if (stream == null) throw new IllegalStateException("named operation script missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) { throw new IllegalStateException("named operation script unavailable", failure); }
    }
    @Override public synchronized void close() { closed = true; if (runtime != null) runtime.close(); }
}
