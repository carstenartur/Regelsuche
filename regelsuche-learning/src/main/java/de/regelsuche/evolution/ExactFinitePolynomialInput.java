package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExactExpressionFormatter;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.polynomial.ExactParsedUnivariatePolynomialView;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import java.util.Comparator;

/** One bounded exact identity authority for formation, selection and application. */
final class ExactFinitePolynomialInput {
    static final String REVISION = "regelsuche.exact-finite-polynomial-input/v1";
    static final ExactParsedUnivariatePolynomialView.Budget BUDGET =
        new ExactParsedUnivariatePolynomialView.Budget(64, 4096, 256, 50_000);

    private ExactFinitePolynomialInput() {}

    record Projection(ExactParsedTerm parsed, SparsePolynomial<ExactRational> polynomial,
                      String expression, String identity, long workUnits) {}

    static Projection analyze(ExactParsedTerm parsed) {
        var analysis = new ExactParsedUnivariatePolynomialView(BUDGET).analyze(parsed);
        if (!analysis.supported()) {
            throw new IllegalArgumentException("finite polynomial input " + analysis.status()
                + ": " + analysis.detailCode());
        }
        var polynomial = analysis.polynomial().orElseThrow();
        boolean constantRing = polynomial.ring().variables().isEmpty();
        String canonical = new JsonWriter().beginObject()
            .property("schema", REVISION)
            .property("domain", polynomial.ring().coefficientDomain().id())
            .array("terms", writer -> polynomial.terms().entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> constantRing ? 0 : entry.getKey().exponent(0)))
                .forEach(entry -> writer.objectValue(term -> term
                    .property("exponent", constantRing ? 0 : entry.getKey().exponent(0))
                    .property("coefficient", entry.getValue().canonicalText()))))
            .endObject().toString();
        return new Projection(parsed, polynomial,
            ExactExpressionFormatter.format(parsed.expression(), parsed),
            SchematicProofPlan.hash(canonical), analysis.work().totalWorkUnits());
    }
}
