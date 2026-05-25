package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionSignature;
import java.util.List;

/**
 * Immutable key that uniquely identifies a proof obligation for caching.
 *
 * <p>Two obligations are considered identical (and thus share a cached result)
 * when:</p>
 * <ol>
 *   <li>The canonical left- and right-hand side patterns match.</li>
 *   <li>The sorted set of assumption expressions matches.</li>
 *   <li>The prover version string matches — guaranteeing that a result cached
 *       with an older prover is not accidentally reused after an upgrade.</li>
 * </ol>
 *
 * <p>Use {@link #of(String, String, List, String)} for construction; the
 * factory sorts and normalises the assumptions automatically.</p>
 */
public record ProofCacheKey(
    String canonicalLeft,
    String canonicalRight,
    String assumptionsSorted,
    String proverVersion
) {
    public ProofCacheKey {
        if (canonicalLeft == null || canonicalRight == null) {
            throw new IllegalArgumentException("patterns must not be null");
        }
        assumptionsSorted = assumptionsSorted == null ? "" : assumptionsSorted;
        proverVersion = proverVersion == null ? "unknown" : proverVersion;
    }

    /**
     * Factory that sorts and joins {@code assumptions} so key equality is
     * independent of the order in which assumptions were supplied.
     */
    public static ProofCacheKey of(String left, String right,
                                   List<Assumption> assumptions, String proverVersion) {
        String sorted = AssumptionSignature.ofAssumptions(assumptions).fingerprint();
        return new ProofCacheKey(left, right, sorted, proverVersion);
    }
}
