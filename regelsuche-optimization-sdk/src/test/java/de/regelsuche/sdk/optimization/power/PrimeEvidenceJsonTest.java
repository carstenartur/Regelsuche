package de.regelsuche.sdk.optimization.power;

import de.regelsuche.math.algorithms.modular.*;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import static de.regelsuche.math.algorithms.modular.QuadraticRootProgram.Step.*;
import static java.math.BigInteger.*;
import static org.junit.jupiter.api.Assertions.*;

class PrimeEvidenceJsonTest {
    // Ninth Mersenne prime, documented in https://www.mersenne.org/primes/.
    // GNU factor(p-1) supplies all multiplicities; 37 passes each prime-divisor order test.
    private static final BigInteger P = ONE.shiftLeft(61).subtract(ONE);
    private static final BigInteger BLS381 = new BigInteger(
            "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab", 16);
    private static final OptimizationBudget BUDGET = new OptimizationBudget(2_000_000, 8192, 64, 30_000);
    private static final String SCHEMA = "regelsuche-prime-evidence-v1";

    private static String fixture() throws IOException {
        try (var in = Objects.requireNonNull(PrimeEvidenceJsonTest.class.getResourceAsStream("/prime-evidence/mersenne-61.json"))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String packet(String proof) {
        return "{\"schema\":\"" + SCHEMA + "\",\"proof\":" + proof + "}";
    }

    private static PrimeEvidenceJson.Result decode(BigInteger expected, String input) {
        return PrimeEvidenceJson.read(expected, input.getBytes(StandardCharsets.UTF_8), BUDGET, CancellationToken.NONE);
    }

    @Test void preparedEvaluationOnCatalogAndImportedFieldsPaysOnlyForTheConcreteCall(TestReporter reporter) throws IOException {
        assertTrue(PrimeEvidenceProvider.catalog().forModulus(P).isEmpty());
        var imported = PrimeEvidenceJson.read(P, fixture().getBytes(StandardCharsets.UTF_8), OptimizationBudget.DEFAULT,
                CancellationToken.NONE);
        var provider = PrimeEvidenceProvider.supplied(imported.certificate().orElseThrow());
        reportWork(reporter, "M61 import work", Long.toString(imported.work()));
        for (var modulus : List.of(valueOf(43), P, BLS381)) {
            var contract = contract(modulus, provider);
            var search = QuadraticRootSdk.search(contract, new SharedPowerPlan.Weights(1, 1, 1000), BUDGET, CancellationToken.NONE);
            var certificate = search.certificate().orElseThrow();
            var denied = QuadraticRootSdk.prepare(contract, certificate, BUDGET, () -> true);
            assertEquals("CANCELLED", denied.reason());
            assertTrue(denied.plan().isEmpty());
            var preparation = QuadraticRootSdk.prepare(contract, certificate, BUDGET, CancellationToken.NONE);
            var plan = preparation.plan().orElseThrow();
            for (var norm : List.of(TWO, modulus.subtract(TWO))) {
                var execution = QuadraticRootSdk.evaluate(plan, ZERO, TWO, norm, BUDGET, CancellationToken.NONE);
                var replay = QuadraticRootSdk.evaluate(contract, certificate, ZERO, TWO, norm, BUDGET, CancellationToken.NONE);
                assertEquals(new QuadraticRootEvaluation.Pair(ONE, ONE), replay.pair().orElseThrow());
                assertEquals(replay.pair(), execution.pair());
                assertEquals(preparation.work() + execution.work(), replay.work());
                assertTrue(execution.work() * 10 < replay.work());
                reportWork(reporter, "modulus " + modulus + ", norm " + norm,
                        "prepare=" + preparation.work() + ", execute=" + execution.work() + ", replay=" + replay.work());
            }
            assertEquals("WORK_LIMIT", QuadraticRootSdk.evaluate(plan, ZERO, TWO, TWO,
                    new OptimizationBudget(1, 8192, 64, 30_000), CancellationToken.NONE).reason());
        }
    }

    private static void reportWork(TestReporter reporter, String operation, String work) {
        reporter.publishEntry(operation, work);
        // Surefire 3.5.4 records standard output in XML but omits JUnit reporting entries.
        System.out.println(operation + " => " + work);
    }

    private static QuadraticRootContract contract(BigInteger modulus, PrimeEvidenceProvider provider) {
        var half = modulus.add(ONE).shiftRight(1);
        var e = half.shiftRight(1);
        var original = new QuadraticRootProgram(List.of(constant(half), add(0, 2), multiply(4, 3), power(5, e),
                square(6)), 7, 5, new QuadraticRootProgram.Arm(List.of(multiply(1, 3), inverse(6), multiply(8, 9)), 6, 10, false),
                new QuadraticRootProgram.Arm(List.of(subtract(0, 2), multiply(8, 3), power(9, e),
                        multiply(1, 3), inverse(10), multiply(11, 12)), 10, 13, false));
        return new QuadraticRootContract("prepared-comparison", modulus, provider.forModulus(modulus).orElseThrow(), original,
                QuadraticRootContract.Observation.EXACT_PAIR);
    }

    @Test void importAuthenticatesTheObservedModulusAndAllOrderEvidence() throws IOException {
        var data = fixture();
        assertEquals("MODULUS_MISMATCH", decode(P.subtract(TWO), data).reason());
        assertEquals("LUCAS_ORDER_MISMATCH", decode(P, data.replace("\"37\"", "\"1\"")).reason());
        assertEquals("FACTORIZATION_MISMATCH", decode(P, data.replace("\"exponent\": 2", "\"exponent\": 1")).reason());
        assertTrue(decode(P, data.replace("\"1321\"", "\"1323\"")).certificate().isEmpty());
    }

    @Test void providerSnapshotsSuppliedModuliAndFallsBackToCatalog() throws IOException {
        var external = decode(P, fixture()).certificate().orElseThrow();
        var secondary = new PrimeEvidence.SmallPrime(valueOf(17));
        var input = new ArrayList<>(List.of(external, secondary));
        var provider = PrimeEvidenceProvider.supplied(input);
        input.clear();
        assertSame(external, provider.forModulus(P).orElseThrow());
        assertSame(secondary, provider.forModulus(valueOf(17)).orElseThrow());
        assertEquals(new PrimeEvidence.SmallPrime(valueOf(43)), provider.forModulus(valueOf(43)).orElseThrow());
        assertTrue(provider.forModulus(P.add(TWO)).isEmpty());
        assertTrue(provider.forModulus(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> PrimeEvidenceProvider.supplied(List.of(external, external)));
        var oversized = java.util.stream.IntStream.range(2, 67)
                .mapToObj(i -> new PrimeEvidence.SmallPrime(valueOf(i))).toList();
        assertThrows(IllegalArgumentException.class, () -> PrimeEvidenceProvider.supplied(oversized));
    }

    @Test void ellipticOrderImportsCheckTheCurvePointAsWellAsTheNestedPrime() {
        // y²=x³+2 over F19 has 13 points including infinity. (4,3) is a nonidentity point.
        var json = packet("""
                {"kind":"elliptic-order","modulus":"19","a":"0","b":"2","x":"4","y":"3",
                 "orderPrime":{"kind":"small","modulus":"13"}}
                """);
        assertTrue(decode(valueOf(19), json).certificate().isPresent());
        assertEquals("OFF_CURVE", decode(valueOf(19), json.replace("\"3\"", "\"4\"")).reason());
        assertEquals("COMPOSITE_LEAF", decode(valueOf(19), json.replace("\"13\"", "\"15\"")).reason());
    }

    @Test void rejectsCyclesViaReferencesUnknownVersionsDuplicatesAndTrailingInput() {
        var small = "{\"kind\":\"small\",\"modulus\":\"7\"}";
        for (String input : List.of(packet("{\"$ref\":\"#\"}"), packet(small).replace(SCHEMA, "v2"),
                packet(small.replace("\"7\"", "\"7\",\"modulus\":\"7\"")),
                packet(small) + "{}", packet(small.replace("\"7\"", "7")),
                packet(small.replace("\"7\"", "\"07\"")), packet(small.replace("\"7\"", "\"7\",\"extra\":1")))) {
            assertTrue(decode(valueOf(7), input).certificate().isEmpty(), input);
        }
    }

    @Test void parsingAndIndependentReplayShareResourceLimits() throws IOException {
        var data = fixture().getBytes(StandardCharsets.UTF_8);
        var accepted = PrimeEvidenceJson.read(P, data, BUDGET, CancellationToken.NONE);
        assertTrue(accepted.certificate().isPresent(), accepted.reason());
        var tight = new OptimizationBudget(accepted.work() - 1, 8192, 64, 30_000);
        assertEquals("WORK_LIMIT", PrimeEvidenceJson.read(P, data, tight, CancellationToken.NONE).reason());
        assertEquals("TERM_LIMIT", PrimeEvidenceJson.read(P, data, new OptimizationBudget(2_000_000, 3, 1, 5000),
                CancellationToken.NONE).reason());
        assertEquals("CANCELLED", PrimeEvidenceJson.read(P, data, BUDGET, () -> true).reason());
        assertEquals("PRIME_JSON_SIZE_LIMIT", PrimeEvidenceJson.read(P, new byte[65_537], BUDGET, CancellationToken.NONE).reason());
        var nested = "{\"kind\":\"small\",\"modulus\":\"2\"}";
        for (int i = 0; i < 64; i++) {
            nested = "{\"kind\":\"elliptic-order\",\"modulus\":\"7\",\"a\":\"0\",\"b\":\"1\",\"x\":\"0\",\"y\":\"1\",\"orderPrime\":"
                    + nested + "}";
        }
        assertEquals("PRIME_JSON_DEPTH_LIMIT", decode(valueOf(7), packet(nested)).reason());
        var factor = "{\"prime\":{\"kind\":\"small\",\"modulus\":\"2\"},\"exponent\":1}";
        var many = String.join(",", Collections.nCopies(257, factor));
        assertEquals("PRIME_FACTOR_LIMIT", decode(valueOf(7),
                packet("{\"kind\":\"lucas\",\"modulus\":\"7\",\"witness\":\"3\",\"factors\":[" + many + "]}")).reason());
        assertTrue(decode(P, packet("{\"kind\":\"small\",\"modulus\":\"" + "9".repeat(1300) + "\"}")).certificate().isEmpty());
    }
}
