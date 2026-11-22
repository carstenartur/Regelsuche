package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/** Polynomial potential search for a simultaneous transition and a caller-supplied active domain. */
public final class PolynomialPotential {
    private PolynomialPotential() {}

    /**
     * Unassigned variables are unchanged. Confirmation asserts that every active state is in the
     * supplied domain and every actual step follows the specified transition (including frame variables).
     */
    public record Request(Map<String, Polynomial> transition, SosSynthesis.Template domain, List<Monomial> basis,
            List<ExactRational> coefficients, ExactRational minimumDecrease, boolean transitionDomainConfirmed) {
        public Request {
            var snapshot = new TreeMap<String, Polynomial>();
            for (var entry : transition.entrySet()) {
                var name = Objects.requireNonNull(entry.getKey());
                if (name.isBlank()) throw new IllegalArgumentException("EMPTY_VARIABLE");
                if (snapshot.put(name, Objects.requireNonNull(entry.getValue())) != null) {
                    throw new IllegalArgumentException("DUPLICATE_VARIABLE");
                }
            }
            transition = Collections.unmodifiableNavigableMap(snapshot);
            Objects.requireNonNull(domain);
            basis = SosSynthesis.checkedBasis(basis);
            coefficients = SosSynthesis.checkedValues(coefficients);
            if (minimumDecrease.signum() <= 0) throw new IllegalArgumentException("NONPOSITIVE_DECREASE");
        }
    }

    public record Certificate(Request source, Polynomial potential, SosSynthesis.Certificate nonnegative,
            SosSynthesis.Certificate decrease) {
        public Certificate {
            Objects.requireNonNull(source);
            Objects.requireNonNull(potential);
            Objects.requireNonNull(nonnegative);
            Objects.requireNonNull(decrease);
        }
    }
    public record Result(SosSynthesis.Status status, Optional<Certificate> certificate, String reason, long work) {}
    public record Verification(SosSynthesis.Status status, boolean terminationUnderContract, String reason, long work) {
        public boolean valid() { return status == SosSynthesis.Status.VERIFIED; }
    }

    public static Result search(Request request, IterationLimits limits, BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            validate(request, work);
            var digits = new int[request.basis().size()];
            do {
                var terms = new HashMap<Monomial, ExactRational>();
                for (int i = 0; i < digits.length; i++) {
                    var coefficient = request.coefficients().get(digits[i]);
                    work.charge(1);
                    if (!coefficient.isZero()) terms.put(request.basis().get(i), coefficient);
                }
                var potential = SosPolynomials.fromTerms(terms, work);
                var result = tryPotential(request, potential, work, cancellation);
                if (result.isPresent()) return new Result(SosSynthesis.Status.VERIFIED, result, "VERIFIED", work.used());
            } while (SosSynthesis.advance(digits, request.coefficients().size(), work));
            return new Result(SosSynthesis.Status.INCONCLUSIVE, Optional.empty(), "SEARCH_SPACE_EXHAUSTED", work.used());
        } catch (CertificateWork.Stop stop) {
            return new Result(SosSynthesis.Status.INCONCLUSIVE, Optional.empty(), stop.getMessage(), work.used());
        }
    }

    private static Optional<Certificate> tryPotential(Request request, Polynomial potential, CertificateWork work,
            BooleanSupplier cancellation) {
        var first = new SosSynthesis.Request(potential, request.domain());
        var nonnegative = SosSynthesis.find(first, work, cancellation);
        if (nonnegative.isEmpty()) return Optional.empty();
        var second = decreaseRequest(request, potential, work);
        var decrease = SosSynthesis.find(second, work, cancellation);
        if (decrease.isEmpty()) return Optional.empty();
        var proof = new Certificate(request, potential, nonnegative.orElseThrow(), decrease.orElseThrow());
        if (replay(request, proof, work, cancellation).valid()) return Optional.of(proof);
        return Optional.empty();
    }

    public static Verification verify(Request request, Certificate proof, IterationLimits limits,
            BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            validate(request, work);
            return replay(request, proof, work, cancellation);
        } catch (CertificateWork.Stop stop) {
            return new Verification(SosSynthesis.Status.INCONCLUSIVE, false, stop.getMessage(), work.used());
        }
    }

    private static Verification replay(Request request, Certificate proof, CertificateWork work,
            BooleanSupplier cancellation) {
        if (proof.source() != request) {
            validate(proof.source(), work);
            if (!sameSource(request, proof.source(), work)) return invalid("SOURCE_MISMATCH", work);
        }
        var potential = proof.potential();
        SosPolynomials.polynomialSize(potential, work);
        if (!inCandidateSpace(request, potential, work)) return invalid("POTENTIAL_BASIS", work);
        var first = new SosSynthesis.Request(potential, request.domain());
        var nonnegative = SosVerifier.replay(first, proof.nonnegative(), work, cancellation);
        if (!nonnegative.valid()) return invalid(nonnegative.reason(), work);
        var claimed = proof.decrease().source().target();
        if (!PotentialIdentity.matches(request, potential, claimed, work)) return invalid("DECREASE_IDENTITY", work);
        var decrease = SosVerifier.replay(new SosSynthesis.Request(claimed, request.domain()), proof.decrease(),
            work, cancellation);
        if (!decrease.valid()) return invalid(decrease.reason(), work);
        work.charge(0);
        return new Verification(SosSynthesis.Status.VERIFIED, request.transitionDomainConfirmed(), "VERIFIED", work.used());
    }

    private static boolean sameSource(Request a, Request b, CertificateWork work) {
        if (a.transitionDomainConfirmed() != b.transitionDomainConfirmed()
                || !work.check(a.minimumDecrease()).equals(b.minimumDecrease())
                || a.transition().size() != b.transition().size()
                || !SosSourceComparison.basis(a.basis(), b.basis(), work)
                || !SosSourceComparison.values(a.coefficients(), b.coefficients(), work)
                || !SosSourceComparison.template(a.domain(), b.domain(), work)) return false;
        var right = b.transition().entrySet().iterator();
        for (var left : a.transition().entrySet()) {
            work.charge(1);
            var other = right.next();
            if (SosSourceComparison.compareNames(left.getKey(), other.getKey(), work) != 0
                    || !SosSourceComparison.polynomial(left.getValue(), other.getValue(), work)) return false;
        }
        return true;
    }

    private static boolean inCandidateSpace(Request request, Polynomial potential, CertificateWork work) {
        int nonzero = 0;
        for (var monomial : request.basis()) {
            var value = potential.terms().getOrDefault(monomial, Rational.ZERO).exactValue();
            work.charge(request.coefficients().size());
            if (!request.coefficients().contains(value)) return false;
            if (!value.isZero()) nonzero++;
        }
        return nonzero == potential.termCount();
    }

    private static SosSynthesis.Request decreaseRequest(Request request, Polynomial potential, CertificateWork work) {
        var target = SosPolynomials.decrease(potential, request.transition(), request.minimumDecrease(), work);
        return new SosSynthesis.Request(target, request.domain());
    }

    private static void validate(Request request, CertificateWork work) {
        work.check(request.minimumDecrease());
        long cells = SosPolynomials.basisSize(request.basis(), work) + request.transition().size();
        work.cells(cells + request.coefficients().size());
        for (var value : request.coefficients()) work.check(value);
        for (var entry : request.transition().entrySet()) {
            work.charge(1L + entry.getKey().length() / 64);
            cells += SosPolynomials.polynomialSize(entry.getValue(), work);
            work.cells(cells);
        }
        var domain = request.domain();
        SosPolynomials.validate(new SosSynthesis.Request(Polynomial.zero(), domain), work);
        long n = domain.basis().size();
        long scalarEntries = n*n;
        work.cells(2*scalarEntries);
        for (var premise : domain.premises()) {
            n = premise.basis().size();
            scalarEntries += premise.relation() == SosSynthesis.Relation.ZERO ? n : n*n;
            work.cells(2*scalarEntries);
        }
    }

    private static Verification invalid(String reason, CertificateWork work) {
        work.charge(0);
        return new Verification(SosSynthesis.Status.INVALID_CERTIFICATE, false, reason, work.used());
    }
}
