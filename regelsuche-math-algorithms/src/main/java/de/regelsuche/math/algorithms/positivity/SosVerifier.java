package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Reconstruct each full quadratic form and signed ideal term against the expected source. */
public final class SosVerifier {
    private SosVerifier() {}

    public static SosSynthesis.Verification verify(SosSynthesis.Request request, SosSynthesis.Certificate certificate,
            IterationLimits limits, BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            return replay(request, certificate, work, cancellation);
        } catch (CertificateWork.Stop stop) {
            return new SosSynthesis.Verification(SosSynthesis.Status.INCONCLUSIVE, stop.getMessage(), work.used());
        }
    }

    static SosSynthesis.Verification replay(SosSynthesis.Request request, SosSynthesis.Certificate proof,
            CertificateWork work, BooleanSupplier cancellation) {
        SosPolynomials.validate(request, work);
        if (proof.source() != request) {
            SosPolynomials.validate(proof.source(), work);
            if (!SosSourceComparison.template(request.template(), proof.source().template(), work)
                    || !SosSourceComparison.polynomial(request.target(), proof.source().target(), work)) {
                return invalid("SOURCE_MISMATCH", work);
            }
        }
        var premises = request.template().premises();
        int gramCount = 1;
        var basis = request.template().basis();
        long cells = (long) basis.size() * basis.size();
        work.cells(cells);
        for (var premise : premises) {
            long n = premise.basis().size();
            if (premise.relation() == SosSynthesis.Relation.NONNEGATIVE) {
                gramCount++;
                cells += n * n;
            } else {
                cells += n;
            }
            work.cells(cells);
        }
        if (proof.grams().size() != gramCount || proof.multipliers().size() != premises.size() + 1 - gramCount) {
            return invalid("BLOCK_COUNT", work);
        }
        var sum = new SosPolynomials.Accumulator();
        String error = addGram(sum, basis, proof.grams().getFirst(), SosPolynomials.ONE, work, cancellation);
        if (error != null) return invalid(error, work);
        int gram = 1;
        int equality = 0;
        for (var premise : premises) {
            if (premise.relation() == SosSynthesis.Relation.NONNEGATIVE) {
                error = addGram(sum, premise.basis(), proof.grams().get(gram++), premise.polynomial(), work, cancellation);
                if (error != null) return invalid(error, work);
            } else {
                var coefficients = proof.multipliers().get(equality++);
                if (coefficients.size() != premise.basis().size()) return invalid("MULTIPLIER_DIMENSION", work);
                for (int i = 0; i < coefficients.size(); i++) {
                    accumulate(sum, premise.basis().get(i), coefficients.get(i), premise.polynomial(), work);
                }
            }
        }
        if (sum.terms.size() != request.target().termCount()) return invalid("COEFFICIENT_IDENTITY", work);
        for (var term : request.target().terms().entrySet()) {
            var value = work.check(term.getValue().exactValue());
            if (!value.equals(sum.terms.get(term.getKey()))) return invalid("COEFFICIENT_IDENTITY", work);
        }
        work.charge(0);
        return new SosSynthesis.Verification(SosSynthesis.Status.VERIFIED, "VERIFIED", work.used());
    }

    private static String addGram(SosPolynomials.Accumulator sum, List<Monomial> basis, SosSynthesis.Gram gram,
            Polynomial factor, CertificateWork work, BooleanSupplier cancellation) {
        var matrix = gram.matrix();
        if (basis.size() != matrix.size()) return "GRAM_DIMENSION";
        var psd = GramPositivity.checkMatrix(matrix, work.remainingLimits(), cancellation);
        work.charge(psd.work());
        if (psd.classification() == GramPositivity.Classification.UNAVAILABLE) {
            throw new CertificateWork.Stop(psd.reason());
        }
        if (psd.classification() != GramPositivity.Classification.POSITIVE_DEFINITE
                && psd.classification() != GramPositivity.Classification.POSITIVE_SEMIDEFINITE) return psd.reason();
        for (int i = 0; i < basis.size(); i++) {
            for (int j = 0; j < basis.size(); j++) {
                work.charge(1);
                var value = matrix.get(i).get(j);
                if (!value.isZero()) {
                    var product = SosPolynomials.times(basis.get(i), basis.get(j), work);
                    accumulate(sum, product, value, factor, work);
                }
            }
        }
        return null;
    }

    private static void accumulate(SosPolynomials.Accumulator sum, Monomial monomial, ExactRational coefficient,
            Polynomial factor, CertificateWork work) {
        if (work.check(coefficient).isZero()) return;
        for (var term : factor.terms().entrySet()) {
            var product = SosPolynomials.times(monomial, term.getKey(), work);
            sum.add(product, work.mul(coefficient, term.getValue().exactValue()), work);
        }
    }

    private static SosSynthesis.Verification invalid(String reason, CertificateWork work) {
        work.charge(0);
        return new SosSynthesis.Verification(SosSynthesis.Status.INVALID_CERTIFICATE, reason, work.used());
    }
}
