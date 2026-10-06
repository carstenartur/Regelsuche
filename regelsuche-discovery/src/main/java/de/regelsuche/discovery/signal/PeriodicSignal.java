package de.regelsuche.discovery.signal;

import de.regelsuche.scalar.ExactRational;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Rational sums of unshifted divisibility combs; bounded for independent checking. */
public record PeriodicSignal(int length, List<Term> terms) {
    public static final int MAX_LENGTH = 128;
    public static final int MAX_TERMS = 32;
    public static final int MAX_WEIGHT_BITS = 64;

    public PeriodicSignal {
        if (length < 1 || length > MAX_LENGTH) {
            throw new IllegalArgumentException("length must be in [1, " + MAX_LENGTH + "]");
        }
        Objects.requireNonNull(terms, "terms");
        if (terms.size() > MAX_TERMS) throw new IllegalArgumentException("too many signal terms");
        terms = List.copyOf(terms);
        for (var term : terms) {
            if (length % term.period() != 0) {
                throw new IllegalArgumentException("each period must divide the signal length");
            }
        }
    }

    public String canonical() {
        return length + "|" + terms.stream()
            .map(term -> term.period() + "@" + term.weight().canonicalText())
            .collect(Collectors.joining(","));
    }

    public record Term(int period, ExactRational weight) {
        public Term {
            if (period < 1 || period > MAX_LENGTH) {
                throw new IllegalArgumentException("period must be in [1, " + MAX_LENGTH + "]");
            }
            Objects.requireNonNull(weight, "weight");
            if (weight.numerator().abs().bitLength() > MAX_WEIGHT_BITS
                    || weight.denominator().bitLength() > MAX_WEIGHT_BITS) {
                throw new IllegalArgumentException("weight exceeds the 64-bit input envelope");
            }
        }
    }
}
