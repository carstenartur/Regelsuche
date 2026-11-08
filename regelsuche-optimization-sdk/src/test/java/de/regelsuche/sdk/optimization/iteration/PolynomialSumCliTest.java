package de.regelsuche.sdk.optimization.iteration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolynomialSumCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INDEX = "{\"op\":\"index\"}";
    private static final String ROSLYN = """
        {"version":1,"kind":"counted-sum","bits":32,"overflow":"wrap","start":0,"stride":1,"initial":0,"term":{"op":"index"}}""";

    @Test void existingRoslynRequestsKeepTheirResponseShape() throws Exception {
        assertProof(ROSLYN, 0, "[0,1]");
        assertProof(ROSLYN.replace("wrap", "checked"), 0, "[0,1]");
    }

    @Test void verifiesQuadraticWithDescendingStrideAndNonzeroInitial() throws Exception {
        var request = request(64, "checked", "3", "-2", "7", """
            {"op":"subtract","left":{"op":"multiply","left":{"op":"index"},"right":{"op":"index"}},
             "right":{"op":"add","left":{"op":"index"},"right":{"op":"integer","value":2}}}""");
        // i^2 - i - 2 at i = 3, 1, -1 is 4, -2, 0; the differences are -6 and 8.
        assertProof(request, 7, "[4,-6,8]");
    }

    @Test void wrapPlanKeepsExactCoefficientsOutsideSignedLongRange() throws Exception {
        var request = request(64, "wrap", "9223372036854775807", "1", "-9223372036854775808", """
            {"op":"multiply","left":{"op":"index"},"right":{"op":"integer","value":2}}""");
        assertProof(request, Long.MIN_VALUE, "[18446744073709551614,2]");
    }

    @Test void exactModeAcceptsUnboundedIntegerLeavesWithinTheProofLimit() throws Exception {
        var request = request(0, "exact", "0", "1", "0", """
            {"op":"integer","value":9223372036854775808}""");
        assertProof(request, 0, "[9223372036854775808]");
    }

    @Test void rejectsInvalidJsonWithoutAResponse() {
        assertRejected(ROSLYN + " {}", "JSON");
        assertRejected(ROSLYN.replace("\"version\":1", "\"version\":1,\"version\":1"), "JSON");
        assertRejected(ROSLYN.replace(INDEX, "{\"op\":\"index\",\"op\":\"index\"}"), "JSON");
        assertRejected(ROSLYN.replace("\"version\":1", "/* comment */\"version\":1"), "JSON");
        assertRejected(run(new byte[] {(byte) 0xff}), "UTF-8");
    }

    @Test void rejectsUnknownFieldsOperatorsAndNonintegerTokens() {
        assertRejected(ROSLYN.replace("\"initial\":0", "\"extra\":0"), "fields");
        assertRejected(ROSLYN.replace(INDEX, "{\"op\":\"index\",\"value\":0}"), "fields");
        assertRejected(ROSLYN.replace(INDEX, "{\"op\":\"divide\"}"), "operator");
        assertRejected(ROSLYN.replace("\"stride\":1", "\"stride\":1e0"), "integer");
        assertRejected(ROSLYN.replace("\"bits\":32", "\"bits\":\"32\""), "integer");
        assertRejected(ROSLYN.replace("\"version\":1", "\"version\":2"), "version");
    }

    @Test void rejectsInvalidArithmeticAndOutOfWidthValues() {
        assertRejected(request(0, "wrap", "0", "1", "0", INDEX), "arithmetic");
        assertRejected(request(32, "exact", "0", "1", "0", INDEX), "arithmetic");
        assertRejected(request(32, "checked", "2147483648", "1", "0", INDEX), "signed 32-bit");
        assertRejected(request(64, "wrap", "0", "1", "-9223372036854775809", INDEX), "signed 64-bit");
        assertRejected(request(32, "wrap", "0", "1", "0", "{\"op\":\"integer\",\"value\":2147483648}"),
            "signed 32-bit");
        assertRejected(request(32, "wrap", "0", "0", "0", INDEX), "INVALID_STRIDE");
    }

    @Test void rejectsOversizedRequestsAndDeepTerms() {
        assertRejected(" ".repeat(4096) + ROSLYN, "4096 bytes");
        var nested = INDEX;
        for (int i = 0; i < 64; i++) {
            nested = binary("add", nested, INDEX);
        }
        assertRejected(request(32, "wrap", "0", "1", "0", nested), "limit");
    }

    @Test void rejectsValidJsonWhenPolynomialProofExceedsKernelLimits() {
        var ninthPower = INDEX;
        for (int i = 1; i < 9; i++) {
            ninthPower = binary("multiply", ninthPower, INDEX);
        }
        assertRejected(request(32, "wrap", "0", "1", "0", ninthPower), "INDEX_DEGREE_LIMIT");

        String large = "{\"op\":\"integer\",\"value\":" + BigInteger.ONE.shiftLeft(2100) + "}";
        assertRejected(request(0, "exact", "0", "1", "0", binary("multiply", large, large)),
            "COEFFICIENT_LIMIT");
    }

    private static String request(int bits, String overflow, String start, String stride, String initial, String term) {
        return """
            {"version":1,"kind":"counted-sum","bits":%d,"overflow":"%s","start":%s,"stride":%s,"initial":%s,"term":%s}
            """.formatted(bits, overflow, start, stride, initial, term);
    }

    private static String binary(String op, String left, String right) {
        return "{\"op\":\"%s\",\"left\":%s,\"right\":%s}".formatted(op, left, right);
    }

    private static void assertProof(String request, long initial, String coefficients) throws Exception {
        var result = run(request.getBytes(StandardCharsets.UTF_8));
        assertEquals(0, result.status(), result.error());
        assertEquals("", result.error());
        var expected = "{\"version\":1,\"request\":%s,\"initial\":%d,\"coefficients\":%s}"
            .formatted(request, initial, coefficients);
        assertEquals(JSON.readTree(expected), JSON.readTree(result.output()));
    }

    private static void assertRejected(String request, String reason) {
        assertRejected(run(request.getBytes(StandardCharsets.UTF_8)), reason);
    }

    private static void assertRejected(Result result, String reason) {
        assertNotEquals(0, result.status());
        assertEquals("", result.output());
        assertTrue(result.error().contains(reason), result.error());
    }

    private static Result run(byte[] request) {
        var input = new ByteArrayInputStream(request);
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        try (var output = new PrintStream(stdout, false, StandardCharsets.UTF_8);
             var error = new PrintStream(stderr, false, StandardCharsets.UTF_8)) {
            int status = PolynomialSumCli.run(new String[0], input, output, error);
            return new Result(status, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        }
    }

    private record Result(int status, String output, String error) {}
}
