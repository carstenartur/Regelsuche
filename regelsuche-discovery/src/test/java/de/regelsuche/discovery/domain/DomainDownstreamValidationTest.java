package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.discovery.domain.DiscoveryDomain.*;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainDownstreamValidationTest {
    @TempDir Path directory;

    @Test void bothExistingDomainsProduceBoundIndependentFiniteReceiptsWithoutChangingTheSource() {
        for (boolean recurrence : new boolean[]{false,true}) {
            var workspace = workspace(source(recurrence, false));
            String original = workspace.toCanonicalJson();
            var receipt = DomainDownstreamValidation.validate(workspace);
            var result = DomainExportWorkspace.read(receipt.toCanonicalJson());
            assertEquals("CONFIRMED_FINITE_DATA",result.path("status").asText());
            assertEquals("IDENTICAL_CANONICAL_EVIDENCE",result.path("replay").path("status").asText());
            assertEquals(workspace.contentHash(),result.path("source").path("workspaceHash").asText());
            assertEquals(workspace.runId(),result.path("source").path("manifestHash").asText());
            assertEquals(workspace.snapshot().verification().manifestByteHash(),result.path("source").path("manifestByteHash").asText());
            assertEquals(workspace.evidence().selectedCandidateHash(),result.path("verifiedCandidateHash").asText());
            assertEquals(workspace.evidence().certificate().certificateObjectHash(),result.path("verifiedCertificateObjectHash").asText());
            assertEquals(workspace.evidence().certificate().contentHash(),result.path("verifiedRenderedCertificateHash").asText());
            assertTrue(result.path("check").path("complete").asBoolean());
            assertEquals(recurrence ? DomainFiniteDataCheck.RESIDUAL_CONTRACT : DomainFiniteDataCheck.NEWTON_CONTRACT,
                result.path("check").path("checkerContract").asText());
            assertEquals("NOT_PRODUCED",result.path("universalProofStatus").asText());
            assertEquals("NOT_EVALUATED",result.path("promotionStatus").asText());
            assertEquals(original,workspace.toCanonicalJson());
            assertEquals(receipt.toCanonicalJson(),DomainDownstreamValidation.validate(workspace).toCanonicalJson());
        }
    }

    @Test void fullReplayRejectsFreshlyRehashedFalseExecutionEvidenceBeforeIndependentChecking() {
        var source = source(false,false);
        var states = new ArrayList<>(source.states());
        var first = states.getFirst();
        states.set(0,new DomainDiscoveryEvidence.StateTrace(first.sequence(),first.stateHash(),first.canonicalState(),first.depth(),
            first.objectiveScore()+1,first.candidateReady(),first.parentStateHash(),first.actionId(),first.objectiveMetrics()));
        var altered = new DomainDiscoveryEvidence(source.campaignId(),source.descriptor(),source.seed(),source.budget(),source.outcome(),
            states,source.transitions(),source.candidateAttempts(),source.resources(),source.selectedCandidateHash(),source.certificate(),source.domainEvidence());
        var result = DomainExportWorkspace.read(DomainDownstreamValidation.validate(workspace(altered)).toCanonicalJson());
        assertEquals("REPLAY_MISMATCH",result.path("status").asText());
        assertEquals("MISMATCH",result.path("replay").path("status").asText());
        assertTrue(result.path("check").isNull());
        assertTrue(result.path("verifiedCandidateHash").isNull());
        assertEquals(5,result.path("replay").path("resources").size());
    }

    @Test void negativeRunsDoNotInventMissingCandidateBodiesOrUniversalProofs() {
        for (boolean recurrence : new boolean[]{false,true}) {
            var result = DomainExportWorkspace.read(DomainDownstreamValidation.validate(workspace(source(recurrence,true))).toCanonicalJson());
            assertEquals("REFUTED",result.path("sourceOutcome").asText());
            assertEquals("NOT_EVALUATED_NO_SELECTED_CANDIDATE",result.path("status").asText());
            assertTrue(result.path("check").isNull());
            assertEquals("NOT_EVALUATED",result.path("evaluationStatus").asText());
            assertEquals("NOT_PRODUCED",result.path("universalProofStatus").asText());
        }
    }

    @Test void replayAdmissionAndPartialIndependentChecksNeverAcquireConfirmation() {
        var workspace = workspace(source(false,false));
        var blocked = DomainExportWorkspace.read(DomainDownstreamValidation.validate(workspace,
            new DomainDownstreamValidation.Budget(0,512,DomainFiniteDataCheck.Budget.defaults())).toCanonicalJson());
        assertEquals("INCONCLUSIVE",blocked.path("status").asText());
        assertEquals("NOT_EVALUATED",blocked.path("replay").path("status").asText());
        assertEquals(0,blocked.path("replay").path("admission").path("executed").asInt());
        var partial = DomainExportWorkspace.read(DomainDownstreamValidation.validate(workspace,
            new DomainDownstreamValidation.Budget(1,512,new DomainFiniteDataCheck.Budget(2,1000,1024))).toCanonicalJson());
        assertEquals("INCONCLUSIVE",partial.path("status").asText());
        assertEquals("IDENTICAL_CANONICAL_EVIDENCE",partial.path("replay").path("status").asText());
        assertEquals(2,partial.path("check").path("rows").size());
        assertEquals("INCONCLUSIVE",partial.path("evaluationStatus").asText());
    }

    @Test void incompleteSourceAndPayloadAdmissionRemainSeparateFromIndependentValidation() {
        var domain = new FiniteDifferenceSequenceDomain();
        var source = new DomainDiscoveryRunner().run("small-prefix-audit",domain,
            DiscoverySeed.create("public-source",domain.domainId(),"observed=1,4,9,16;holdout=25,36","unit control"),
            new DiscoveryBudget(4,20,20,10,5,2)).evidence();
        assertEquals(DomainDiscoveryEvidence.Outcome.INCONCLUSIVE,source.outcome());
        var receipt = DomainExportWorkspace.read(DomainDownstreamValidation.validate(workspace(source)).toCanonicalJson());
        assertEquals("INCONCLUSIVE",receipt.path("sourceOutcome").asText());
        assertEquals("NOT_EVALUATED_NO_SELECTED_CANDIDATE",receipt.path("status").asText());
        assertTrue(receipt.path("check").isNull());
        var withheld = DomainExportWorkspace.read(DomainDownstreamValidation.validate(workspace(source(false,false)),
            new DomainDownstreamValidation.Budget(1,0,DomainFiniteDataCheck.Budget.defaults())).toCanonicalJson());
        assertEquals(0,withheld.path("replay").path("admission").path("executed").asInt());
        assertTrue(withheld.path("replay").path("resources").isNull());
    }

    private DomainExportWorkspace workspace(DomainDiscoveryEvidence source) {
        new DomainDiscoveryExport().write(directory,source);
        return DomainExportWorkspace.fromVerified(new DomainDiscoveryExportVerifier().requireVerified(directory));
    }
    static DomainDiscoveryEvidence source(boolean recurrence, boolean refuted) {
        if (recurrence) {
            var domain = new LinearRecurrenceSequenceDomain();
            return new DomainDiscoveryRunner().run("public-recurrence",domain,
                DiscoverySeed.create("public-source",domain.domainId(),"observed=2,3,5,8,13,21;holdout="+(refuted ? "35,55,89" : "34,55,89")+";maximumOrder=3","unit control"),
                new DiscoveryBudget(4,16,32,8,8,32)).evidence();
        }
        return DomainExportWorkspaceTest.sequence("observed=1,4,9,16;holdout="+(refuted ? "26" : "25,36"));
    }
}
