package de.regelsuche.sdk.discovery.signal;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Outcome;
import de.regelsuche.discovery.signal.FourierQuery;
import de.regelsuche.discovery.signal.PeriodicFourier.Plan;
import de.regelsuche.discovery.signal.PeriodicSignal;
import de.regelsuche.discovery.signal.PeriodicSignal.Term;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.discovery.RegelsucheDiscovery;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PeriodicSignalDomainTest {
    private static final DiscoveryBudget BUDGET = new DiscoveryBudget(1, 4, 4, 2, 2, 128);

    @Test
    void choosesOnTrainingAndReusesFrozenPlanOnUnseenLengthsAndWeights() {
        var study = new PeriodicSignalStudy(List.of(repeated(12, 3, 12)),
            List.of(repeated(20, 5, 8)));
        var run = RegelsucheDiscovery.forDomain(PeriodicSignalDomain.typedDomain())
            .campaign("periodic-transfer").seed("train-and-holdout", study, "test/periodic/v1")
            .budget(BUDGET).run();
        assertEquals(Outcome.CONFIRMED, run.evidence().outcome());
        assertEquals(Plan.MERGE_PERIODS, run.selectedCandidate().orElseThrow().plan());
        var certificate = run.selectedCertificate().orElseThrow();
        assertEquals("EXACT_FINITE_DFT_WITNESS_NOT_UNIVERSAL_PROOF", certificate.evidenceStrength());
        assertEquals(20, certificate.holdoutChecks());
        assertTrue(certificate.selectionWork().units() > 0);
        assertTrue(certificate.verificationWork().units() > 0);
        assertTrue(certificate.comparison().selectedWork().units()
            < certificate.comparison().directWork().units());
        assertEquals(certificate.comparison().mergedWork(), certificate.comparison().selectedWork());
        assertEquals("NOT_EVALUATED", run.evidence().proofStatus());
        assertEquals("NOT_EVALUATED", run.evidence().externalNoveltyStatus());
        run.evidence().resources().forEach(line -> assertEquals(line.configured(),
            line.executed() + line.skipped() + line.remaining()));
    }

    @Test
    void holdoutCannotInfluenceSelectionAndRegressionAgainstAFixedPlanIsVisible() {
        var training = List.of(repeated(12, 3, 12));
        var sparseHoldout = new FourierQuery(new PeriodicSignal(7,
            List.of(new Term(7, ExactRational.ONE))), List.of(0));
        var a = run(new PeriodicSignalStudy(training, List.of(repeated(20, 5, 8))));
        var b = run(new PeriodicSignalStudy(training, List.of(sparseHoldout)));
        assertEquals(a.selectedCandidate().orElseThrow().plan(), b.selectedCandidate().orElseThrow().plan());
        assertTrue(b.selectedCertificate().orElseThrow().comparison().selectedWork().units()
            > b.selectedCertificate().orElseThrow().comparison().directWork().units());
    }

    @Test
    void exhaustedAuditBudgetIsInconclusiveAndProducesNoCertificate() {
        var run = RegelsucheDiscovery.forDomain(PeriodicSignalDomain.typedDomain())
            .campaign("periodic-audit-limit")
            .seed("limited", new PeriodicSignalStudy(List.of(repeated(12, 3, 12)),
                List.of(repeated(20, 5, 8))), "test/periodic/v1")
            .budget(new DiscoveryBudget(1, 4, 4, 2, 2, 1)).run();
        assertEquals(Outcome.INCONCLUSIVE, run.evidence().outcome());
        assertTrue(run.selectedCertificate().isEmpty());
    }

    @Test
    void payloadRoundTripsAndEvidenceIsDeterministic() {
        var study = new PeriodicSignalStudy(List.of(repeated(12, 3, 12)),
            List.of(repeated(20, 5, 8)));
        var codec = PeriodicSignalDomain.typedDomain().inputCodec();
        assertEquals(study, codec.decode(codec.encode(study)));
        assertEquals(run(study).evidence().toCanonicalJson(), run(study).evidence().toCanonicalJson());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(codec.encode(study) + "\nextra"));
        assertThrows(IllegalArgumentException.class, () -> new PeriodicSignalStudy(List.of(), study.holdout()));
        assertThrows(IllegalArgumentException.class, () -> new PeriodicSignalStudy(study.training(), study.training()));
    }

    @Test
    void constrainedSearchConfirmsCorrectnessWithoutClaimingCostOptimality() {
        var run = RegelsucheDiscovery.forDomain(PeriodicSignalDomain.typedDomain())
            .campaign("periodic-pruned")
            .seed("one-successor", new PeriodicSignalStudy(List.of(repeated(12, 3, 12)),
                List.of(repeated(20, 5, 8))), "test/periodic/v1")
            .budget(new DiscoveryBudget(1, 4, 4, 1, 2, 128)).run();
        assertEquals(Outcome.CONFIRMED, run.outcome());
        assertEquals(Plan.DIRECT, run.selectedCandidate().orElseThrow().plan());
        assertEquals(1, run.evidence().transitions().size());
        assertTrue(run.selectedCertificate().orElseThrow().comparison().mergedWork().units()
            < run.selectedCertificate().orElseThrow().comparison().selectedWork().units());
    }

    @Test
    void choosesTheDirectPlanWhenMergingDoesNotPayForItsConstruction() {
        var train = new FourierQuery(new PeriodicSignal(7,
            List.of(new Term(7, ExactRational.ONE))), List.of(0));
        var run = run(new PeriodicSignalStudy(List.of(train), List.of(repeated(20, 5, 8))));
        assertEquals(Plan.DIRECT, run.selectedCandidate().orElseThrow().plan());
    }

    private static de.regelsuche.sdk.discovery.DiscoveryRun<PeriodicSignalDomain.Candidate,
            PeriodicSignalDomain.Certificate> run(PeriodicSignalStudy study) {
        return RegelsucheDiscovery.forDomain(PeriodicSignalDomain.typedDomain())
            .campaign("periodic-repeat").seed("study", study, "test/periodic/v1")
            .budget(BUDGET).run();
    }

    private static FourierQuery repeated(int length, int period, int count) {
        return new FourierQuery(new PeriodicSignal(length, IntStream.range(0, count)
            .mapToObj(i -> new Term(period, ExactRational.integer(i + 1))).toList()),
            IntStream.range(0, length).boxed().toList());
    }
}
