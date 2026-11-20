package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.scalar.ExactRational;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A canonical sparse rational polynomial with separate declared degrees for one or two variables. */
public record SparsePolynomial(List<Integer> degrees, Map<Exponent, ExactRational> terms) {
    public record Exponent(int x, int y) implements Comparable<Exponent> {
        public Exponent {
            if (x < 0 || y < 0) throw new IllegalArgumentException("NEGATIVE_EXPONENT");
        }

        @Override public int compareTo(Exponent other) {
            int first = Integer.compare(x, other.x);
            return first == 0 ? Integer.compare(y, other.y) : first;
        }
    }

    public SparsePolynomial {
        degrees = List.copyOf(degrees);
        if (degrees.isEmpty() || degrees.size() > 2 || degrees.stream().anyMatch(d -> d < 0)) {
            throw new IllegalArgumentException("INVALID_DEGREES");
        }
        int dy = degrees.size() == 2 ? degrees.get(1) : 0;
        var normalized = new TreeMap<Exponent, ExactRational>();
        for (var term : terms.entrySet()) {
            var exponent = term.getKey();
            if (exponent.x() > degrees.get(0) || exponent.y() > dy) {
                throw new IllegalArgumentException("EXPONENT_EXCEEDS_DEGREE");
            }
            if (!term.getValue().isZero()) normalized.put(exponent, term.getValue());
        }
        terms = Collections.unmodifiableMap(normalized);
    }

    int degreeY() { return degrees.size() == 2 ? degrees.get(1) : 0; }
}
