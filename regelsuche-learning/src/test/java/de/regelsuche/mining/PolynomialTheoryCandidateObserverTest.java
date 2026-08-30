package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.transform.PolynomialDerivedMacroCache;
import de.regelsuche.transform.PolynomialTheorySubsumptionClassifier;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolynomialTheoryCandidateObserverTest {
    private static final String TEST_HASH =
        "sha256:" + "0".repeat(64);

    @Test
    void retainsNotSubsumedOutcomeWithoutChangingTheCandidateOrCache() {
        StubEngine engine = StubEngine.noCandidate();
        PolynomialDerivedMacroCache cache =
            new PolynomialDerivedMacroCache(4);
        PolynomialTheoryFormationOutcomeLedger ledger =
            new PolynomialTheoryFormationOutcomeLedger(4);
        PolynomialTheoryCandidateObserver observer = observer(
            engine,
            cache,
            ledger);
        RuleCandidate candidate = candidate("candidate:not-subsumed", true);
        RuleCandidateFormationObserver.Evidence evidence = evidence(
            "path:not-subsumed");

        observer.onCandidateFormed(candidate, evidence);

        assertEquals(1, engine.calls);
        assertEquals(0, cache.size());
        assertEquals(1, ledger.size());
        PolynomialTheoryFormationOutcomeLedger.Entry retained =
            ledger.entries().getFirst();
        assertEquals(candidate, retained.candidate());
        assertEquals(evidence, retained.formationEvidence());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.NOT_SUBSUMED,
            retained.classification().status());
        assertEquals(
            PolynomialTheoryFormationOutcomeLedger.Disposition
                .RETAINED_NOT_SUBSUMED,
            retained.disposition());
        assertTrue(retained.macroEntryId().isEmpty());
    }

    @Test
    void retainsUnsupportedBudgetAndTechnicalOutcomesSeparately() {
        assertOutcome(
            StubEngine.outcome(
                FactorizationEngine.Outcome.UNSUPPORTED_REQUEST),
            PolynomialTheorySubsumptionClassifier.Status.UNSUPPORTED,
            PolynomialTheoryFormationOutcomeLedger.Disposition
                .RETAINED_UNSUPPORTED);
        assertOutcome(
            StubEngine.outcome(
                FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE),
            PolynomialTheorySubsumptionClassifier.Status.BUDGET_INCONCLUSIVE,
            PolynomialTheoryFormationOutcomeLedger.Disposition
                .RETAINED_BUDGET_INCONCLUSIVE);
        assertOutcome(
            StubEngine.throwing(),
            PolynomialTheorySubsumptionClassifier.Status.TECHNICAL_FAILURE,
            PolynomialTheoryFormationOutcomeLedger.Disposition
                .RETAINED_TECHNICAL_FAILURE);
    }

    @Test
    void rejectsIneligibleCandidateAndMissingProvenanceBeforeClassification() {
        StubEngine engine = StubEngine.noCandidate();
        PolynomialTheoryCandidateObserver observer = observer(
            engine,
            new PolynomialDerivedMacroCache(4),
            new PolynomialTheoryFormationOutcomeLedger(4));

        assertThrows(
            IllegalArgumentException.class,
            () -> observer.onCandidateFormed(
                candidate("candidate:unverified", false),
                evidence("path:unverified")));
        assertThrows(
            IllegalArgumentException.class,
            () -> observer.onCandidateFormed(
                candidate("candidate:no-provenance", true),
                new RuleCandidateFormationObserver.Evidence(
                    List.of("mined-observation"),
                    List.of(),
                    List.of(),
                    List.of("symbolic-check"))));

        assertEquals(0, engine.calls);
    }

    @Test
    void ledgerIsIdempotentBoundedAndFailsClosedOnIdentityCollision() {
        PolynomialTheorySubsumptionClassifier classifier =
            new PolynomialTheorySubsumptionClassifier(
                StubEngine.noCandidate());
        PolynomialTheorySubsumptionClassifier.Classification classification =
            classifier.classify("x^2 - 1", "(x - 1) * (x + 1)");
        RuleCandidate candidate = candidate("candidate:ledger", true);
        RuleCandidateFormationObserver.Evidence firstEvidence =
            evidence("path:first");
        PolynomialTheoryFormationOutcomeLedger ledger =
            new PolynomialTheoryFormationOutcomeLedger(2);

        PolynomialTheoryFormationOutcomeLedger.RetentionResult inserted =
            ledger.retain(
                candidate,
                firstEvidence,
                classification,
                Optional.empty());
        PolynomialTheoryFormationOutcomeLedger.RetentionResult unchanged =
            ledger.retain(
                candidate,
                firstEvidence,
                classification,
                Optional.empty());
        PolynomialTheoryFormationOutcomeLedger.RetentionResult second =
            ledger.retain(
                candidate("candidate:second", true),
                evidence("path:second"),
                classification,
                Optional.empty());
        PolynomialTheoryFormationOutcomeLedger.RetentionResult third =
            ledger.retain(
                candidate("candidate:third", true),
                evidence("path:third"),
                classification,
                Optional.empty());

        assertEquals(
            PolynomialTheoryFormationOutcomeLedger.RetentionStatus.INSERTED,
            inserted.status());
        assertEquals(
            PolynomialTheoryFormationOutcomeLedger.RetentionStatus.UNCHANGED,
            unchanged.status());
        assertEquals(inserted.entry().id(), unchanged.entry().id());
        assertEquals(2, ledger.size());
        assertEquals(
            Optional.of(inserted.entry().id()),
            third.evictedEntryId());
        assertTrue(ledger.find(inserted.entry().id()).isEmpty());
        assertTrue(ledger.find(second.entry().id()).isPresent());
        assertTrue(ledger.find(third.entry().id()).isPresent());
        assertEquals(
            new PolynomialTheoryFormationOutcomeLedger.Stats(2, 3, 1, 1),
            ledger.stats());

        String collisionId = "sha256:" + "f".repeat(64);
        PolynomialTheoryFormationOutcomeLedger collisionLedger =
            new PolynomialTheoryFormationOutcomeLedger(
                2,
                ignored -> collisionId);
        collisionLedger.retain(
            candidate,
            firstEvidence,
            classification,
            Optional.empty());
        IllegalStateException collision = assertThrows(
            IllegalStateException.class,
            () -> collisionLedger.retain(
                candidate("candidate:collision", true),
                evidence("path:collision"),
                classification,
                Optional.empty()));
        assertTrue(collision.getMessage().contains("collision"));
        assertEquals(1, collisionLedger.size());
    }

    private void assertOutcome(
        StubEngine engine,
        PolynomialTheorySubsumptionClassifier.Status expectedStatus,
        PolynomialTheoryFormationOutcomeLedger.Disposition expectedDisposition
    ) {
        PolynomialDerivedMacroCache cache =
            new PolynomialDerivedMacroCache(4);
        PolynomialTheoryFormationOutcomeLedger ledger =
            new PolynomialTheoryFormationOutcomeLedger(4);
        PolynomialTheoryCandidateObserver observer = observer(
            engine,
            cache,
            ledger);

        observer.onCandidateFormed(
            candidate("candidate:" + expectedStatus.name(), true),
            evidence("path:" + expectedStatus.name()));

        PolynomialTheoryFormationOutcomeLedger.Entry retained =
            ledger.entries().getFirst();
        assertEquals(expectedStatus, retained.classification().status());
        assertEquals(expectedDisposition, retained.disposition());
        assertFalse(retained.classification().subsumed());
        assertTrue(retained.macroEntryId().isEmpty());
        assertEquals(0, cache.size());
    }

    private PolynomialTheoryCandidateObserver observer(
        StubEngine engine,
        PolynomialDerivedMacroCache cache,
        PolynomialTheoryFormationOutcomeLedger ledger
    ) {
        return new PolynomialTheoryCandidateObserver(
            new PolynomialTheorySubsumptionClassifier(engine),
            cache,
            ledger);
    }

    private RuleCandidate candidate(String canonicalHash, boolean verified) {
        return new RuleCandidate(
            "x^2 - 1",
            "(x - 1) * (x + 1)",
            1,
            8.0,
            8,
            verified,
            true,
            true,
            List.of("A is the observed expression variable"),
            RuleStatus.NEW,
            verified
                ? CandidateProofStatus.SYMBOLICALLY_VERIFIED
                : CandidateProofStatus.REJECTED,
            canonicalHash,
            List.of("support:" + canonicalHash));
    }

    private RuleCandidateFormationObserver.Evidence evidence(String pathId) {
        return new RuleCandidateFormationObserver.Evidence(
            List.of("mined-observation"),
            List.of(pathId),
            List.of("A != 0"),
            List.of("symbolic-check"));
    }

    private static final class StubEngine
            implements FactorizationEngine<ExactRational> {
        private final Outcome outcome;
        private final boolean throwFailure;
        private int calls;

        private StubEngine(Outcome outcome, boolean throwFailure) {
            this.outcome = outcome;
            this.throwFailure = throwFailure;
        }

        private static StubEngine noCandidate() {
            return outcome(Outcome.NO_CANDIDATE);
        }

        private static StubEngine outcome(Outcome outcome) {
            return new StubEngine(outcome, false);
        }

        private static StubEngine throwing() {
            return new StubEngine(Outcome.TECHNICAL_FAILURE, true);
        }

        @Override
        public String engineId() {
            return "regelsuche.test-polynomial-theory-observer-engine/v1";
        }

        @Override
        public String coefficientDomainId() {
            return ExactRationalField.DOMAIN_ID;
        }

        @Override
        public EngineResult<ExactRational> propose(
            FactorizationRequest<ExactRational> request
        ) {
            calls++;
            if (throwFailure) {
                throw new IllegalStateException("test engine failure");
            }
            return new EngineResult<>(
                engineId(),
                outcome,
                "TEST_" + outcome.name(),
                PolynomialWorkLedger.empty(),
                List.of(),
                BackendClaim.NONE,
                TEST_HASH);
        }
    }
}
