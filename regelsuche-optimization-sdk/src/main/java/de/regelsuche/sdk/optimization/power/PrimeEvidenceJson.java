package de.regelsuche.sdk.optimization.power;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.PrimeEvidence;
import de.regelsuche.math.algorithms.modular.PrimeEvidenceVerifier;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Optional;

/** Bounded import of modulus-bound exact prime evidence. All integers except multiplicities are decimal strings. */
public final class PrimeEvidenceJson {
    private static final String SCHEMA = "regelsuche-prime-evidence-v1";
    private static final int MAX_BYTES = 65_536;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_DECIMAL_LENGTH = 1235; // 4096 bits and optional minus
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3 * MAX_DEPTH + 1)
                    .maxNumberLength(10).maxStringLength(MAX_DECIMAL_LENGTH).maxNameLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final ObjectMapper JSON = JsonMapper.builder(FACTORY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private PrimeEvidenceJson() {}

    public record Result(Optional<PrimeEvidence.Certificate> certificate, String reason, long work) {}

    /** Parse a tree without references and replay it independently. Import and replay share one budget. */
    public static Result read(BigInteger expectedModulus, byte[] json, OptimizationBudget budget, CancellationToken cancellation) {
        var limits = new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
        var work = new CertificateWork(limits, cancellation::isCancelled);
        try {
            work.charge(1);
            require(json != null, "MISSING_PRIME_JSON");
            require(json.length <= MAX_BYTES, "PRIME_JSON_SIZE_LIMIT");
            work.charge(2L * json.length); // Two bounded parser passes over the same immutable snapshot.
            var snapshot = json.clone();
            int retained = preflight(snapshot, work);
            var proof = decode(snapshot, work);
            var checked = PrimeEvidenceVerifier.verify(expectedModulus, proof, work.remainingLimits(retained), cancellation::isCancelled);
            work.chargeCompleted(checked.work());
            work.charge(0);
            return new Result(checked.valid() ? Optional.of(proof) : Optional.empty(), checked.reason(), work.used());
        } catch (IOException malformed) {
            return new Result(Optional.empty(), "MALFORMED_PRIME_JSON", work.used());
        } catch (CertificateWork.Stop | IllegalArgumentException invalid) {
            return new Result(Optional.empty(), invalid.getMessage(), work.used());
        }
    }

    /** Reserve the tree and its typed copy before databind allocates them, including for malformed wide packets. */
    private static int preflight(byte[] snapshot, CertificateWork work) throws IOException {
        int values = 0;
        int containers = 0;
        try (var parser = FACTORY.createParser(snapshot)) {
            for (JsonToken token; (token = parser.nextToken()) != null;) {
                if (token.isStructStart()) containers++;
                if (token.isScalarValue() || token.isStructStart()) values++;
                work.cells((long) values + containers);
            }
        }
        // Scalar and container values also conservatively cover all typed proof fields retained during replay.
        return values;
    }

    private static PrimeEvidence.Certificate decode(byte[] snapshot, CertificateWork work) throws IOException {
        var document = JSON.readTree(snapshot);
        fields(document, "schema", "proof");
        require(SCHEMA.equals(document.get("schema").textValue()), "UNKNOWN_PRIME_SCHEMA");
        return proof(document.get("proof"), 1, work);
    }

    private static PrimeEvidence.Certificate proof(JsonNode node, int depth, CertificateWork work) {
        work.charge(1);
        require(depth <= MAX_DEPTH, "PRIME_JSON_DEPTH_LIMIT");
        require(node != null && node.isObject() && node.path("kind").isTextual(), "INVALID_PRIME_JSON_FIELDS");
        return switch (node.get("kind").textValue()) {
            case "small" -> {
                fields(node, "kind", "modulus");
                yield new PrimeEvidence.SmallPrime(integer(node, "modulus"));
            }
            case "lucas" -> {
                fields(node, "kind", "modulus", "witness", "factors");
                var array = node.get("factors");
                require(array.isArray(), "INVALID_PRIME_JSON_FIELDS");
                require(array.size() <= 256, "PRIME_FACTOR_LIMIT");
                var factors = new ArrayList<PrimeEvidence.Factor>(array.size());
                for (var factor : array) {
                    fields(factor, "prime", "exponent");
                    var exponent = factor.get("exponent");
                    require(exponent.isIntegralNumber() && exponent.canConvertToInt(), "INVALID_MULTIPLICITY");
                    factors.add(new PrimeEvidence.Factor(proof(factor.get("prime"), depth + 1, work), exponent.intValue()));
                }
                yield new PrimeEvidence.Lucas(integer(node, "modulus"), integer(node, "witness"), factors);
            }
            case "elliptic-order" -> {
                fields(node, "kind", "modulus", "a", "b", "x", "y", "orderPrime");
                yield new PrimeEvidence.EllipticOrder(integer(node, "modulus"), integer(node, "a"), integer(node, "b"),
                        integer(node, "x"), integer(node, "y"), proof(node.get("orderPrime"), depth + 1, work));
            }
            default -> throw new IllegalArgumentException("UNKNOWN_PRIME_PROOF_KIND");
        };
    }

    private static BigInteger integer(JsonNode object, String field) {
        String decimal = object.get(field).textValue();
        require(decimal != null && !decimal.isEmpty() && decimal.length() <= MAX_DECIMAL_LENGTH, "INVALID_PRIME_INTEGER");
        if (decimal.equals("0")) return BigInteger.ZERO;
        int start = decimal.charAt(0) == '-' ? 1 : 0;
        require(start < decimal.length() && decimal.charAt(start) != '0', "INVALID_PRIME_INTEGER");
        for (int i = start; i < decimal.length(); i++) {
            char digit = decimal.charAt(i);
            require(digit >= '0' && digit <= '9', "INVALID_PRIME_INTEGER");
        }
        return new BigInteger(decimal);
    }

    private static void fields(JsonNode node, String... names) {
        require(node != null && node.isObject() && node.size() == names.length, "INVALID_PRIME_JSON_FIELDS");
        for (var name : names) require(node.hasNonNull(name), "INVALID_PRIME_JSON_FIELDS");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
