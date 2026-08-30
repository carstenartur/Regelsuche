package de.regelsuche.mining;

import de.regelsuche.transform.PolynomialDerivedMacroCache;
import de.regelsuche.transform.PolynomialTheorySubsumptionClassifier;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit post-formation adapter for exact polynomial theory classification.
 *
 * <p>The caller chooses the classifier engine before constructing this
 * observer. The adapter never mutates or promotes a candidate. It is the sole
 * owner of the bounded derived-macro handoff and always records the resulting
 * positive, negative, unsupported, budget or technical outcome in the
 * configured ledger. Ordinary miner constructors do not install this observer;
 * activation therefore remains an explicit caller-owned policy.</p>
 */
public final class PolynomialTheoryCandidateObserver
        implements RuleCandidateFormationObserver {
    public static final String OBSERVER_ID =
        "regelsuche.polynomial-theory-candidate-observer/v1";

    private final PolynomialTheorySubsumptionClassifier classifier;
    private final PolynomialDerivedMacroCache macroCache;
    private final PolynomialTheoryFormationOutcomeLedger outcomeLedger;

    public PolynomialTheoryCandidateObserver(
        PolynomialTheorySubsumptionClassifier classifier,
        PolynomialDerivedMacroCache macroCache,
        PolynomialTheoryFormationOutcomeLedger outcomeLedger
    ) {
        this.classifier = Objects.requireNonNull(
            classifier,
            "classifier");
        this.macroCache = Objects.requireNonNull(
            macroCache,
            "macroCache");
        this.outcomeLedger = Objects.requireNonNull(
            outcomeLedger,
            "outcomeLedger");
    }

    @Override
    public synchronized void onCandidateFormed(
        RuleCandidate candidate,
        Evidence evidence
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Evidence checkedEvidence = Objects.requireNonNull(
            evidence,
            "evidence");
        if (!candidate.equivalenceVerified()
                || candidate.proofStatus() == null
                || !candidate.proofStatus().atLeast(
                    CandidateProofStatus.VALIDATED_BY_EXAMPLES)) {
            throw new IllegalArgumentException(
                "polynomial theory observer requires a positively "
                    + "validated candidate");
        }
        if (checkedEvidence.sourceProvenance().isEmpty()) {
            throw new IllegalArgumentException(
                "polynomial theory observer requires source provenance");
        }

        PolynomialTheorySubsumptionClassifier.Classification classification =
            classifier.classify(
                candidate.leftPattern(),
                candidate.rightPattern());
        Optional<String> macroEntryId = Optional.empty();
        if (classification.subsumed()) {
            PolynomialDerivedMacroCache.Entry retained = macroCache.retain(
                classification,
                List.of(classification.theoryMethodId()),
                checkedEvidence.sourceProvenance());
            macroEntryId = Optional.of(retained.id());
        }
        outcomeLedger.retain(
            candidate,
            checkedEvidence,
            classification,
            macroEntryId);
    }
}
