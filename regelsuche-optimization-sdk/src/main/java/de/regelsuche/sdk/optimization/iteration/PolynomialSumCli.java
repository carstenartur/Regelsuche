package de.regelsuche.sdk.optimization.iteration;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.ast.iteration.IntegerArithmetic;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.symbol.SymbolId;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/** Standalone JSON entry point for verified Newton plans of counted sums with constant start and stride. */
public final class PolynomialSumCli {
    private static final int VERSION = 1;
    private static final int MAX_REQUEST_BYTES = 4_096;
    private static final int MAX_INTEGER_TOKEN_LENGTH = 1_235; // 4096 coefficient bits in decimal plus a sign
    private static final Set<String> REQUEST_FIELDS = Set.of(
        "version", "kind", "bits", "overflow", "start", "stride", "initial", "term");
    private static final Set<String> INDEX_FIELDS = Set.of("op");
    private static final Set<String> INTEGER_FIELDS = Set.of("op", "value");
    private static final Set<String> BINARY_FIELDS = Set.of("op", "left", "right");
    private static final SymbolId INDEX = new SymbolId(new UUID(0L, 0L), 1);
    private static final OptimizationBudget BUDGET = new OptimizationBudget(100_000, 10, 10, 5_000);
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(FiniteIteration.MAX_DEPTH + 1) // includes the request object
            .maxNumberLength(MAX_INTEGER_TOKEN_LENGTH)
            .maxNameLength(32)
            .maxStringLength(MAX_INTEGER_TOKEN_LENGTH) // Jackson also uses this buffer for large integers
            .build())
        .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private PolynomialSumCli() {}

    /** Reads one UTF-8 request from standard input and writes a response only after verification. */
    public static void main(String[] args) {
        int status = run(args, System.in, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args, InputStream input, PrintStream output, PrintStream error) {
        if (args.length != 0) {
            return reject(error, "expected one JSON request on stdin and no command-line arguments");
        }
        try {
            byte[] bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) {
                return reject(error, "request exceeds " + MAX_REQUEST_BYTES + " bytes");
            }
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            ObjectNode response = prove(JSON.readTree(text));
            output.println(JSON.writeValueAsString(response));
            return output.checkError() ? reject(error, "could not write response") : 0;
        } catch (CharacterCodingException exception) {
            return reject(error, "request is not UTF-8");
        } catch (StreamConstraintsException exception) {
            return reject(error, "JSON request exceeds parser limits");
        } catch (JsonProcessingException exception) {
            return reject(error, "invalid JSON; expected a single document with no duplicate fields");
        } catch (IllegalArgumentException exception) {
            return reject(error, exception.getMessage());
        } catch (IOException exception) {
            return reject(error, "could not read request from stdin");
        }
    }

    private static ObjectNode prove(JsonNode request) {
        requireFields(request, REQUEST_FIELDS, "request");
        if (smallInteger(request, "version") != VERSION) {
            throw new IllegalArgumentException("unsupported request version");
        }
        if (!text(request, "kind").equals("counted-sum")) {
            throw new IllegalArgumentException("unsupported request kind; expected counted-sum");
        }
        int bits = smallInteger(request, "bits");
        IntegerArithmetic arithmetic = arithmetic(bits, text(request, "overflow"));
        var source = new PolynomialSum(INDEX, Set.of(), number(request, "start", bits), integer(request, "stride"),
            term(request.path("term"), bits), number(request, "initial", bits), arithmetic);

        var search = new PolynomialSumOptimizer().search(source, BUDGET, CancellationToken.NONE);
        var plan = search.plan().orElseThrow(() -> new IllegalArgumentException("search rejected: " + search.reason()));
        var proof = new PolynomialSumVerifier().verify(source, plan, BUDGET, CancellationToken.NONE);
        if (!proof.verified()) {
            throw new IllegalArgumentException("verification rejected: " + proof.reason());
        }

        var response = JSON.createObjectNode().put("version", VERSION);
        response.set("request", request);
        response.put("initial", constant(plan.initial()));
        var coefficients = response.putArray("coefficients");
        for (var coefficient : plan.coefficients()) {
            coefficients.add(constant(coefficient));
        }
        return response;
    }

    private static IntegerArithmetic arithmetic(int bits, String overflow) {
        if (bits == 0 && overflow.equals("exact")) {
            return IntegerArithmetic.EXACT;
        }
        if (bits == 32 || bits == 64) {
            if (overflow.equals("checked")) {
                return IntegerArithmetic.EXACT;
            }
            if (overflow.equals("wrap")) {
                return bits == 32 ? IntegerArithmetic.JAVA_INT : IntegerArithmetic.JAVA_LONG;
            }
        }
        throw new IllegalArgumentException("unsupported arithmetic; expected 32 or 64 with wrap or checked, or 0 with exact");
    }

    private static Expr term(JsonNode node, int bits) {
        return switch (text(node, "op")) {
            case "index" -> {
                requireFields(node, INDEX_FIELDS, "index term");
                yield VariableExpr.scoped(INDEX);
            }
            case "integer" -> {
                requireFields(node, INTEGER_FIELDS, "integer term");
                yield number(node, "value", bits);
            }
            case "add" -> binary(node, bits, BinaryOperator.ADD);
            case "subtract" -> binary(node, bits, BinaryOperator.SUB);
            case "multiply" -> binary(node, bits, BinaryOperator.MUL);
            default -> throw new IllegalArgumentException("unsupported term operator");
        };
    }

    private static Expr binary(JsonNode node, int bits, BinaryOperator operator) {
        requireFields(node, BINARY_FIELDS, "binary term");
        return new BinaryExpr(term(node.path("left"), bits), operator, term(node.path("right"), bits));
    }

    private static NumberExpr number(JsonNode object, String field, int bits) {
        var value = integer(object, field);
        if (bits != 0 && value.bitLength() >= bits) {
            throw new IllegalArgumentException(field + " does not fit signed " + bits + "-bit arithmetic");
        }
        return new NumberExpr(ExactRational.integer(value));
    }

    private static int smallInteger(JsonNode object, String field) {
        var value = integer(object, field);
        if (value.bitLength() >= Integer.SIZE) {
            throw new IllegalArgumentException(field + " is out of range");
        }
        return value.intValue();
    }

    private static BigInteger integer(JsonNode object, String field) {
        var value = object.path(field);
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " must be a JSON integer");
        }
        return value.bigIntegerValue();
    }

    private static String text(JsonNode object, String field) {
        var value = object.path(field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a JSON string");
        }
        return value.textValue();
    }

    private static void requireFields(JsonNode object, Set<String> fields, String context) {
        if (object == null || !object.isObject() || object.size() != fields.size()
            || fields.stream().anyMatch(field -> !object.has(field))) {
            throw new IllegalArgumentException(context + " has missing or unexpected fields; expected a JSON object");
        }
    }

    private static BigInteger constant(Expr expression) {
        if (expression instanceof NumberExpr number && number.value().isInteger()) {
            return number.value().numerator();
        }
        throw new IllegalArgumentException("verified plan contains a nonconstant or noninteger coefficient");
    }

    private static int reject(PrintStream error, String reason) {
        error.println("PolynomialSumCli: " + reason);
        return 1;
    }
}
