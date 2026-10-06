package de.regelsuche.discovery.signal;

import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Independent finite checker. Samples s(k), constructs sum(s(k)*z^(-jk))/L,
 * and reduces modulo Phi_L(z). A zero remainder after subtracting the claimed
 * rational value proves equality at a primitive Lth root. No comb-transform
 * shortcut, floating-point tolerance or factorization routine is used.
 */
public final class CyclotomicDftVerifier {
    private CyclotomicDftVerifier() { }

    public static Verification verify(FourierQuery query, List<ExactRational> claimed) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(claimed, "claimed");
        if (claimed.size() != query.frequencies().size()) {
            throw new IllegalArgumentException("one claimed value is required for every requested frequency");
        }
        claimed = List.copyOf(claimed);
        var work = new SignalWorkMeter();
        int length = query.signal().length();
        var cyclotomic = cyclotomic(length, work);
        var samples = zeros(length, work);
        for (int k = 0; k < length; k++) {
            for (var term : query.signal().terms()) {
                work.integer();
                if (k % term.period() == 0) samples[k] = work.add(samples[k], term.weight());
            }
            samples[k] = work.divide(samples[k], ExactRational.integer(length));
        }
        for (int index = 0; index < claimed.size(); index++) {
            int frequency = query.frequencies().get(index);
            var polynomial = zeros(length, work);
            for (int k = 0; k < length; k++) {
                work.integer();
                int exponent = Math.floorMod(-frequency * k, length);
                polynomial[exponent] = work.add(polynomial[exponent], samples[k]);
            }
            polynomial[0] = work.subtract(polynomial[0], claimed.get(index));
            var remainder = remainder(polynomial, cyclotomic, work);
            if (Arrays.stream(remainder).anyMatch(value -> !value.isZero())) {
                return new Verification(false, index + 1,
                    Optional.of(new Counterexample(frequency, claimed.get(index), List.of(remainder))),
                    work.snapshot());
            }
        }
        return new Verification(true, claimed.size(), Optional.empty(), work.snapshot());
    }

    // x^n - 1 = product_{d|n} Phi_d(x). All divisions below are exact and monic.
    private static ExactRational[] cyclotomic(int length, SignalWorkMeter work) {
        var known = new ArrayList<ExactRational[]>();
        known.add(null);
        for (int n = 1; n <= length; n++) {
            var polynomial = zeros(n + 1, work);
            polynomial[0] = ExactRational.NEGATIVE_ONE;
            polynomial[n] = ExactRational.ONE;
            for (int divisor = 1; divisor < n; divisor++) {
                work.integer();
                if (n % divisor == 0) polynomial = exactQuotient(polynomial, known.get(divisor), work);
            }
            known.add(polynomial);
        }
        return known.get(length);
    }

    private static ExactRational[] exactQuotient(ExactRational[] dividend,
                                                ExactRational[] divisor, SignalWorkMeter work) {
        var working = dividend.clone();
        work.cells(working.length);
        var quotient = zeros(dividend.length - divisor.length + 1, work);
        for (int shift = quotient.length - 1; shift >= 0; shift--) {
            var leading = working[shift + divisor.length - 1];
            quotient[shift] = leading;
            eliminate(working, divisor, shift, leading, work);
        }
        if (Arrays.stream(working).anyMatch(value -> !value.isZero())) {
            throw new IllegalStateException("cyclotomic polynomial division was not exact");
        }
        return quotient;
    }

    private static ExactRational[] remainder(ExactRational[] polynomial,
                                             ExactRational[] divisor, SignalWorkMeter work) {
        // The supplied polynomial is local to this coefficient; reduce it in place.
        for (int shift = polynomial.length - divisor.length; shift >= 0; shift--) {
            eliminate(polynomial, divisor, shift, polynomial[shift + divisor.length - 1], work);
        }
        int size = Math.min(polynomial.length, divisor.length - 1);
        work.cells(size);
        return Arrays.copyOf(polynomial, size);
    }

    private static void eliminate(ExactRational[] polynomial, ExactRational[] divisor,
                                  int shift, ExactRational leading, SignalWorkMeter work) {
        if (leading.isZero()) return;
        for (int i = 0; i < divisor.length; i++) {
            if (!divisor[i].isZero()) {
                polynomial[shift + i] = work.subtract(polynomial[shift + i],
                    work.multiply(leading, divisor[i]));
            }
        }
    }

    private static ExactRational[] zeros(int length, SignalWorkMeter work) {
        work.cells(length);
        var values = new ExactRational[length];
        Arrays.fill(values, ExactRational.ZERO);
        return values;
    }

    public record Counterexample(int frequency, ExactRational claimed, List<ExactRational> remainder) {
        public Counterexample { remainder = List.copyOf(remainder); }
    }

    public record Verification(boolean accepted, int checkedCoefficients,
                               Optional<Counterexample> counterexample, SignalWork work) { }
}
