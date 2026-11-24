import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationPolicy;
import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.List;
import java.util.TreeMap;

final class NativeFactorizationWorker {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        byte[] payload = System.in.readAllBytes();
        var input = json.readTree(payload);
        if (!"regelsuche.sympy-factorization/v1".equals(input.path("protocol").textValue())
                || !"QQ".equals(input.path("domain").textValue()) || input.path("variableCount").asInt() != 1) {
            throw new IllegalArgumentException("expected the shared rational univariate input");
        }
        var ring = new PolynomialRing<>(ExactRationalField.INSTANCE, List.of(new PolynomialVariable("x0")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        var terms = new TreeMap<Monomial, ExactRational>(ring.monomialComparator());
        for (var value : input.withArray("terms")) {
            var exponent = value.withArray("exponents");
            var rational = new ExactRational(new BigInteger(value.path("numerator").textValue()),
                new BigInteger(value.path("denominator").textValue()));
            if (exponent.size() != 1 || !exponent.get(0).isIntegralNumber() || rational.isZero()
                    || terms.put(Monomial.of(exponent.get(0).intValue()), rational) != null) {
                throw new IllegalArgumentException("invalid exact term");
            }
        }
        var source = new SparsePolynomial<>(ring, terms);
        var request = FactorizationRequest.verifiedDecomposition(source,
            new FactorizationRequest.StructuralLimits(1, 128, 129, 4096), 1, 20_000_000);
        var engine = NativeUnivariateFactorizationEngine.rationalsWithFastPath(NativeUnivariateFactorizationPolicy.boundedDefaults());
        var report = FactorizationVerifier.execute(engine, request);
        var result = json.createObjectNode().put("inputSha256",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
        result.put("outcome", report.status().name()).put("detail", report.detailCode());
        result.put("nativeWorkUnits", report.work().totalWorkUnits());
        if (report.successful()) {
            var candidate = report.candidates().getFirst();
            rational(result.putObject("unit"), candidate.unit());
            var factors = result.putArray("factors");
            candidate.factors().forEach(factor -> factor(factors, factor.polynomial(), factor.multiplicity()));
            if (!candidate.unresolvedRemainder().isOne()) factor(factors, candidate.unresolvedRemainder(), 1);
        }
        System.out.println(json.writeValueAsString(result));
    }

    private static void factor(ArrayNode factors, SparsePolynomial<ExactRational> polynomial, int multiplicity) {
        var terms = factors.addObject().put("multiplicity", multiplicity).putArray("terms");
        polynomial.terms().forEach((monomial, coefficient) -> {
            var term = terms.addObject();
            term.putArray("exponents").add(monomial.exponent(0));
            rational(term, coefficient);
        });
    }

    private static void rational(ObjectNode node, ExactRational rational) {
        node.put("numerator", rational.numerator().toString());
        node.put("denominator", rational.denominator().toString());
    }
}
