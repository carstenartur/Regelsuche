package de.regelsuche.math.sympy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact JSON wire contract between canonical Regelsuche polynomials and SymPy.
 * The payload contains no rendered expression and no parser-controlled syntax.
 */
final class SymPyFactorizationCodec<C> {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DomainAdapter<C> domain;

    private SymPyFactorizationCodec(DomainAdapter<C> domain) {
        this.domain = domain;
    }

    static SymPyFactorizationCodec<BigInteger> integers() {
        return new SymPyFactorizationCodec<>(new IntegerAdapter());
    }

    static SymPyFactorizationCodec<ExactRational> rationals() {
        return new SymPyFactorizationCodec<>(new RationalAdapter());
    }

    String coefficientDomainId() {
        return domain.coefficientDomainId();
    }

    String wireDomainId() {
        return domain.wireDomainId();
    }

    Encoded encode(FactorizationRequest<C> request) {
        Objects.requireNonNull(request, "request");
        ObjectNode root = JSON.createObjectNode();
        root.put("protocol", SymPyScript.PROTOCOL);
        root.put("domain", wireDomainId());
        root.put(
            "variableCount",
            request.source().ring().variableCount());
        ArrayNode terms = root.putArray("terms");
        request.source().terms().forEach((monomial, coefficient) -> {
            ObjectNode term = terms.addObject();
            ArrayNode exponents = term.putArray("exponents");
            monomial.exponents().forEach(exponents::add);
            domain.write(term, coefficient);
        });
        try {
            String payload = JSON.writeValueAsString(root);
            return new Encoded(
                payload,
                payload.getBytes(StandardCharsets.UTF_8).length,
                request.source().termCount());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "exact SymPy input cannot be encoded",
                exception);
        }
    }

    Decoded<C> decode(
        String output,
        PolynomialRing<C> ring,
        SymPyFactorizationPolicy policy
    ) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(ring, "ring");
        Objects.requireNonNull(policy, "policy");
        JsonNode root;
        try {
            root = JSON.readTree(output);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "SymPy output is not valid JSON",
                exception);
        }
        require(root != null && root.isObject(),
            "SymPy output must be an object");
        require(SymPyScript.PROTOCOL.equals(text(root, "protocol")),
            "SymPy protocol mismatch");
        require(wireDomainId().equals(text(root, "domain")),
            "SymPy coefficient domain mismatch");

        String symPyVersion = text(root, "sympyVersion");
        String pythonImplementation = text(root, "pythonImplementation");
        String pythonVersion = text(root, "pythonVersion");
        long factorNanos = nonNegativeLong(root, "factorNanos");
        long totalNanos = nonNegativeLong(root, "totalNanos");
        require(factorNanos <= totalNanos,
            "SymPy factor timing exceeds total timing");

        C unit = coefficient(root.get("unit"), policy);
        JsonNode factorNodes = root.get("factors");
        require(factorNodes != null && factorNodes.isArray(),
            "SymPy factors must be an array");
        require(factorNodes.size() >= 1,
            "SymPy nonconstant result requires factors");
        require(factorNodes.size() <= policy.maxFactors(),
            "SymPy factor count exceeds policy");

        ArrayList<PolynomialFactor<C>> factors = new ArrayList<>();
        int totalTerms = 0;
        for (JsonNode factorNode : factorNodes) {
            require(factorNode.isObject(),
                "SymPy factor must be an object");
            int multiplicity = positiveInt(factorNode, "multiplicity");
            JsonNode termNodes = factorNode.get("terms");
            require(termNodes != null && termNodes.isArray(),
                "SymPy factor terms must be an array");
            require(termNodes.size() >= 1,
                "SymPy factor must contain terms");
            require(termNodes.size() <= policy.maxTermsPerFactor(),
                "SymPy factor term count exceeds policy");
            totalTerms = Math.addExact(totalTerms, termNodes.size());
            require(totalTerms <= policy.maxTotalFactorTerms(),
                "SymPy total factor term count exceeds policy");

            Map<Monomial, C> terms = new LinkedHashMap<>();
            for (JsonNode termNode : termNodes) {
                require(termNode.isObject(),
                    "SymPy term must be an object");
                Monomial monomial = monomial(
                    termNode.get("exponents"),
                    ring.variableCount());
                C coefficient = coefficient(termNode, policy);
                require(!ring.coefficientDomain().isZero(coefficient),
                    "SymPy term coefficient must be nonzero");
                require(terms.put(monomial, coefficient) == null,
                    "SymPy output contains duplicate monomial");
            }
            SparsePolynomial<C> polynomial =
                new SparsePolynomial<>(ring, terms);
            factors.add(new PolynomialFactor<>(polynomial, multiplicity));
        }

        return new Decoded<>(
            unit,
            factors,
            symPyVersion,
            pythonImplementation,
            pythonVersion,
            factorNanos,
            totalNanos,
            totalTerms);
    }

    private C coefficient(
        JsonNode node,
        SymPyFactorizationPolicy policy
    ) {
        require(node != null && node.isObject(),
            "SymPy coefficient must be an object");
        BigInteger numerator = integerText(node, "numerator");
        BigInteger denominator = integerText(node, "denominator");
        require(denominator.signum() > 0,
            "SymPy denominator must be positive");
        int bitLength;
        try {
            bitLength = Math.addExact(
                numerator.abs().bitLength(),
                denominator.bitLength());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                "SymPy coefficient bit length overflow",
                exception);
        }
        require(bitLength <= policy.maxCoefficientBitLength(),
            "SymPy coefficient bit length exceeds policy");
        return domain.read(numerator, denominator);
    }

    private static Monomial monomial(
        JsonNode node,
        int variableCount
    ) {
        require(node != null && node.isArray(),
            "SymPy exponent vector must be an array");
        require(node.size() == variableCount,
            "SymPy exponent vector arity mismatch");
        int[] exponents = new int[variableCount];
        for (int index = 0; index < variableCount; index++) {
            JsonNode exponent = node.get(index);
            require(exponent.isIntegralNumber()
                    && exponent.canConvertToInt()
                    && exponent.intValue() >= 0,
                "SymPy exponent is invalid");
            exponents[index] = exponent.intValue();
        }
        return Monomial.of(exponents);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual()
                && !value.textValue().isBlank(),
            "SymPy field " + field + " must be nonblank text");
        return value.textValue();
    }

    private static BigInteger integerText(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return new BigInteger(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "SymPy field " + field + " is not an integer",
                exception);
        }
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isIntegralNumber()
                && value.canConvertToLong()
                && value.longValue() >= 0,
            "SymPy field " + field + " must be nonnegative");
        return value.longValue();
    }

    private static int positiveInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isIntegralNumber()
                && value.canConvertToInt()
                && value.intValue() > 0,
            "SymPy field " + field + " must be positive");
        return value.intValue();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    record Encoded(
        String payload,
        int byteLength,
        int sourceTerms
    ) {
    }

    record Decoded<C>(
        C unit,
        List<PolynomialFactor<C>> factors,
        String symPyVersion,
        String pythonImplementation,
        String pythonVersion,
        long factorNanos,
        long totalNanos,
        int factorTerms
    ) {
        Decoded {
            Objects.requireNonNull(unit, "unit");
            factors = List.copyOf(factors);
            if (symPyVersion == null
                    || symPyVersion.isBlank()
                    || pythonImplementation == null
                    || pythonImplementation.isBlank()
                    || pythonVersion == null
                    || pythonVersion.isBlank()
                    || factorNanos < 0
                    || totalNanos < factorNanos
                    || factorTerms < 1) {
                throw new IllegalArgumentException(
                    "decoded SymPy result is invalid");
            }
        }

        String canonicalMaterial(PolynomialRing<C> ring) {
            StringBuilder result = new StringBuilder();
            SymPyEvidence.append(result, symPyVersion);
            SymPyEvidence.append(result, pythonImplementation);
            SymPyEvidence.append(result, pythonVersion);
            SymPyEvidence.append(
                result,
                ring.coefficientDomain().canonicalText(unit));
            factors.forEach(factor -> {
                SymPyEvidence.append(
                    result,
                    Integer.toString(factor.multiplicity()));
                SymPyEvidence.append(
                    result,
                    factor.polynomial().canonicalMaterial());
            });
            return result.toString();
        }
    }

    private interface DomainAdapter<C> {
        String coefficientDomainId();

        String wireDomainId();

        void write(ObjectNode node, C coefficient);

        C read(BigInteger numerator, BigInteger denominator);
    }

    private static final class IntegerAdapter
            implements DomainAdapter<BigInteger> {
        @Override
        public String coefficientDomainId() {
            return BigIntegerDomain.DOMAIN_ID;
        }

        @Override
        public String wireDomainId() {
            return "ZZ";
        }

        @Override
        public void write(ObjectNode node, BigInteger coefficient) {
            node.put("numerator", coefficient.toString());
            node.put("denominator", "1");
        }

        @Override
        public BigInteger read(
            BigInteger numerator,
            BigInteger denominator
        ) {
            require(BigInteger.ONE.equals(denominator),
                "ZZ coefficient must be integral");
            return numerator;
        }
    }

    private static final class RationalAdapter
            implements DomainAdapter<ExactRational> {
        @Override
        public String coefficientDomainId() {
            return ExactRationalField.DOMAIN_ID;
        }

        @Override
        public String wireDomainId() {
            return "QQ";
        }

        @Override
        public void write(ObjectNode node, ExactRational coefficient) {
            node.put("numerator", coefficient.numerator().toString());
            node.put("denominator", coefficient.denominator().toString());
        }

        @Override
        public ExactRational read(
            BigInteger numerator,
            BigInteger denominator
        ) {
            return new ExactRational(numerator, denominator);
        }
    }
}
