package de.regelsuche.polynomial;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministic stage-separated work accounting for exact polynomial
 * operations.
 *
 * <p>The ledger is independent of any particular factorization engine so it can
 * be shared by representation operations, gcd, square-free decomposition,
 * modular algorithms and external backend verification.</p>
 */
public record PolynomialWorkLedger(Map<String, Long> stages) {
    public PolynomialWorkLedger {
        Objects.requireNonNull(stages, "stages");
        TreeMap<String, Long> canonical = new TreeMap<>();
        stages.forEach((stage, units) -> {
            if (stage == null
                    || stage.isBlank()
                    || units == null
                    || units < 0) {
                throw new IllegalArgumentException(
                    "polynomial work ledger is invalid");
            }
            canonical.merge(stage, units, Math::addExact);
        });
        checkedTotal(canonical.values());
        stages = Collections.unmodifiableMap(
            new LinkedHashMap<>(canonical));
    }

    public long totalWorkUnits() {
        return checkedTotal(stages.values());
    }

    public long units(String stage) {
        return stages.getOrDefault(stage, 0L);
    }

    public boolean within(long maximum) {
        return maximum >= 0 && totalWorkUnits() <= maximum;
    }

    public String canonicalMaterial() {
        StringBuilder result = new StringBuilder();
        stages.forEach((stage, units) -> {
            PolynomialEvidence.append(result, stage);
            PolynomialEvidence.append(result, Long.toString(units));
        });
        PolynomialEvidence.append(
            result,
            Long.toString(totalWorkUnits()));
        return result.toString();
    }

    public static PolynomialWorkLedger empty() {
        return new PolynomialWorkLedger(Map.of());
    }

    private static long checkedTotal(Iterable<Long> unitsByStage) {
        try {
            long total = 0;
            for (long units : unitsByStage) {
                total = Math.addExact(total, units);
            }
            return total;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                "polynomial work ledger total exceeds long range",
                exception);
        }
    }
}
