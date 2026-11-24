package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DomainDiscoveryExport.ArtifactRole;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.*;
import de.regelsuche.solver.ir.ScopedProofDag;
import de.regelsuche.solver.ir.ScopedProofExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopedLogicWorkspaceTest {
    @TempDir Path directory;
    private final JsonMapper json = new JsonMapper();

    @Test void retainsLogicalSourceAndOriginalBytesWithoutExecutingAReceiver() throws Exception {
        var source = source();
        var manifest = new DomainDiscoveryExport().write(directory, source);
        Path manifestFile = directory.resolve(DomainDiscoveryExport.MANIFEST_FILE_NAME);
        Files.writeString(manifestFile, Files.readString(manifestFile) + "\r\n");
        var verified = new DomainDiscoveryExportVerifier().requireVerified(directory);
        var workspace = assertDoesNotThrow(() -> DomainExportWorkspace.fromVerified(verified));
        var view = json.readTree(workspace.toCanonicalJson());
        assertEquals(manifest.contentHash(), workspace.runId());
        assertEquals(source.toCanonicalJson(), workspace.evidence().toCanonicalJson());
        assertEquals("scoped-logic", view.path("domainId").asText());
        assertEquals("v1", view.path("domainRevision").asText());
        assertEquals(json.readTree(DiscoveryLifecycleHandoff.from(source).toCanonicalJson()), view.path("lifecycleHandoff"));
        assertArrayEquals(Files.readAllBytes(manifestFile), workspace.originalManifestBytes());
        for (var role : ArtifactRole.values()) {
            assertArrayEquals(Files.readAllBytes(directory.resolve(role.fileName())), workspace.originalArtifactBytes(role));
        }
        assertFalse(workspace.replaySupported());
        var replay = view.path("artifacts").findParents("role").stream()
            .filter(row -> "PATH_REPLAY".equals(row.path("role").asText())).findFirst().orElseThrow();
        assertEquals("NOT_PRODUCED", replay.path("status").asText());
        assertEquals("SCOPED_PROOF_REPLAY_NOT_CONFIGURED", replay.path("detail").asText());
        assertTrue(view.path("claimBoundary").asText().contains("scoped logical"));
        assertThrows(IllegalStateException.class, workspace::replay);
    }

    @Test void assessmentAndSequenceConsumersCannotPromoteAnImportedLogicalSource() throws Exception {
        new DomainDiscoveryExport().write(directory, source());
        var workspace = DomainExportWorkspace.fromVerified(new DomainDiscoveryExportVerifier().requireVerified(directory));
        var assessment = json.readTree(DomainSnapshotAssessment.assess(workspace).toCanonicalJson());
        assertEquals("REPLAY_NOT_CONFIGURED", assessment.path("replayStatus").asText());
        assertEquals("NOT_EVALUATED", assessment.path("proof").path("status").asText());
        assertEquals("SOURCE_NOT_REPRODUCED", assessment.path("lifecycle").path("status").asText());
        for (String gate : List.of("release", "promotion", "publicEvidence")) {
            assertEquals("BLOCKED", assessment.path(gate).path("status").asText());
        }
        assertEquals("UNSUPPORTED", json.readTree(DomainDownstreamValidation.validate(workspace).toCanonicalJson()).path("status").asText());
        assertEquals("UNSUPPORTED", json.readTree(DomainModelProof.prove(workspace).toCanonicalJson()).path("status").asText());
    }

    @Test void importedConfirmedClaimsNeverBecomeFreshProofOrNoveltyAuthority() throws Exception {
        var source = untrustedConfirmedSource();
        var workspace = workspace(source);
        assertEquals(source.toCanonicalJson(), workspace.evidence().toCanonicalJson());
        assertFalse(workspace.replaySupported());
        var assessment = json.readTree(DomainSnapshotAssessment.assess(workspace).toCanonicalJson());
        assertEquals("NOT_EVALUATED", assessment.at("/proof/status").asText());
        assertEquals("NO_VERIFIED_SUBJECT", assessment.at("/externalNovelty/status").asText());
        assertFalse(assessment.path("externalNovelty").has("subject"));
        assertFalse(assessment.has("certificate"));
        assertFalse(assessment.has("resources"));
        for (var receipt : List.of(DomainDownstreamValidation.validate(workspace).toCanonicalJson(),
                DomainModelProof.prove(workspace).toCanonicalJson())) {
            var rejected = json.readTree(receipt);
            assertEquals("UNSUPPORTED", rejected.path("status").asText());
            assertEquals("SCOPED_PROOF_REPLAY_NOT_CONFIGURED", rejected.path("detail").asText());
            assertEquals(0, rejected.at("/replay/admission/executed").asInt(-1));
        }
    }

    @Test void rechecksReconstructedCandidateWithFreshReceiverAndBoundSource() throws Exception {
        var source = untrustedConfirmedSource();
        var workspace = workspace(source);
        var seen = new ArrayList<ScopedProofDag>();
        var receiver = new ScopedProofExecutor() {
            public boolean supports(Obligation obligation) { return true; }
            public VerifiedReplay verify(ScopedProofDag proposal) {
                seen.add(proposal);
                return new VerifiedReplay(proposal.hash(), ResultStatus.CONFIRMED, "fresh receiver control",
                    SolverIr.sha256("fresh kernel"), directory.resolve("fresh-kernel"),
                    SolverIr.sha256("fresh rebuild"), directory.resolve("fresh-rebuild"));
            }
        };
        var checked = ScopedLogicWorkspaceCheck.check(workspace, source.selectedCandidateHash(), receiver);
        assertEquals(List.of(checked.proposal()), seen);
        assertEquals(new Symbol("q"), checked.proposal().obligation().goal().left());
        assertEquals(2, checked.proposal().obligation().assumptions().size());
        assertEquals(workspace.runId(), checked.source().runId());
        assertEquals(workspace.contentHash(), checked.source().workspaceHash());
        assertEquals(source.contentHash(), checked.source().evidenceHash());
        assertEquals(source.seed().contentHash(), checked.source().seedHash());
        assertEquals(source.selectedCandidateHash(), checked.source().candidateHash());
        assertEquals(source.states().getLast().stateHash(), checked.source().stateHash());
        assertEquals(DiscoveryDomain.CounterexampleStatus.NONE_FOUND, checked.counterexample().status());
        assertEquals(ScopedLogicWorkspaceCheck.RetainedWitness.NOT_RECORDED, checked.retainedWitness());
        var proof = checked.freshProof().orElseThrow();
        assertEquals(DiscoveryDomain.EvaluationStatus.CONFIRMED, proof.status());
        assertEquals(SolverIr.sha256("fresh kernel"), proof.certificate().kernelHash());
        assertEquals(SolverIr.sha256("fresh rebuild"), proof.certificate().rebuildHash());
        assertFalse(source.certificate().rendered().contains(proof.certificate().kernelHash()));
        assertFalse(workspace.replaySupported());
    }

    @Test void rechecksHistoricalV1ProposalWithMixedAssumptions() throws Exception {
        // Original four-file export produced before conjunction and existential elimination were introduced (eecb847).
        var files = new ArrayList<>(List.of(DomainDiscoveryExport.MANIFEST_FILE_NAME));
        for (var role : ArtifactRole.values()) files.add(role.fileName());
        for (String name : files) {
            try (var resource = getClass().getResourceAsStream("historical-scoped-v1/" + name)) {
                assertNotNull(resource, name);
                Files.copy(resource, directory.resolve(name));
            }
        }
        var workspace = DomainExportWorkspace.fromVerified(new DomainDiscoveryExportVerifier().requireVerified(directory));
        String candidate = workspace.evidence().candidateAttempts().getFirst().candidateHash();
        var received = new ArrayList<ScopedProofDag>();
        var receiver = new ScopedProofExecutor() {
            @Override public boolean supports(Obligation obligation) { return true; }
            @Override public VerifiedReplay verify(ScopedProofDag dag) {
                received.add(dag);
                return new VerifiedReplay(dag.hash(), ResultStatus.UNSUPPORTED, "fixture control", "", null, "", null);
            }
        };
        var checked = ScopedLogicWorkspaceCheck.check(workspace, candidate, receiver);
        assertEquals(List.of(checked.proposal()), received);
        assertEquals(new Symbol("q"), checked.proposal().obligation().goal().left());
        assertEquals(candidate, checked.source().candidateHash());
        assertEquals(workspace.evidence().states().getLast().stateHash(), checked.source().stateHash());
    }

    @Test void rejectsEditedTransitionBeforeCallingTheReceiver() throws Exception {
        var source = untrustedConfirmedSource();
        var trace = source.transitions().getFirst();
        var forged = new DomainDiscoveryEvidence.TransitionTrace(trace.sequence(), trace.fromStateHash(), trace.toStateHash(),
            trace.actionId(), trace.cost() + 1, trace.semanticsPreserving(), trace.assumptions(), trace.metadata(),
            trace.accepted(), trace.blockers());
        var altered = new DomainDiscoveryEvidence(source.campaignId(), source.descriptor(), source.seed(), source.budget(),
            source.outcome(), source.states(), List.of(forged), source.candidateAttempts(), source.resources(),
            source.selectedCandidateHash(), source.certificate(), source.domainEvidence());
        var workspace = workspace(altered);
        var failure = assertThrows(IllegalArgumentException.class,
            () -> ScopedLogicWorkspaceCheck.check(workspace, source.selectedCandidateHash(), unexpectedReceiver()));
        assertTrue(failure.getMessage().contains("transition"));
        assertThrows(IllegalArgumentException.class,
            () -> ScopedLogicWorkspaceCheck.check(workspace, SolverIr.sha256("foreign candidate"), unexpectedReceiver()));
    }

    @Test void unsupportedReceiverLeavesProofUnevaluatedDespiteRetainedConfirmation() throws Exception {
        var source = untrustedConfirmedSource();
        var checked = ScopedLogicWorkspaceCheck.check(workspace(source), source.selectedCandidateHash(),
            proposal -> { throw new AssertionError("unsupported obligation cannot reach the proof receiver"); });
        assertEquals(DiscoveryDomain.CounterexampleStatus.UNSUPPORTED, checked.counterexample().status());
        assertTrue(checked.freshProof().isEmpty());
    }

    @Test void claimedCounterexampleCannotBecomeAFreshWitness() throws Exception {
        var source = untrustedConfirmedSource();
        var recorded = new DomainDiscoveryEvidence.CandidateAttempt(1, source.selectedCandidateHash(),
            DomainDiscoveryEvidence.AttemptDisposition.REFUTED_BY_COUNTEREXAMPLE, DiscoveryDomain.CounterexampleStatus.FOUND,
            1, "{p=TRUE, q=FALSE}", DomainDiscoveryEvidence.EvaluationDisposition.NOT_RUN, "", "", Map.of());
        var resources = source.resources().stream().map(line -> switch (line.resource()) {
            case CANDIDATE_EVALUATIONS -> new DomainDiscoveryEvidence.ResourceLine(line.resource(), line.configured(), 0, 1, line.configured() - 1);
            case CERTIFICATE_ATTEMPTS -> new DomainDiscoveryEvidence.ResourceLine(line.resource(), line.configured(), 0, 0, line.configured());
            case COUNTEREXAMPLE_ATTEMPTS -> new DomainDiscoveryEvidence.ResourceLine(line.resource(), line.configured(), 1, 0, line.configured() - 1);
            default -> line;
        }).toList();
        var altered = new DomainDiscoveryEvidence(source.campaignId(), source.descriptor(), source.seed(), source.budget(),
            DomainDiscoveryEvidence.Outcome.REFUTED, source.states(), source.transitions(), List.of(recorded), resources,
            "", null, source.domainEvidence());
        var receiver = new ScopedProofExecutor() {
            public boolean supports(Obligation obligation) { return true; }
            public VerifiedReplay verify(ScopedProofDag dag) {
                return new VerifiedReplay(dag.hash(), ResultStatus.ERROR, "receiver failure control", "", null, "", null);
            }
        };
        var checked = ScopedLogicWorkspaceCheck.check(workspace(altered), recorded.candidateHash(), receiver);
        assertEquals(ScopedLogicWorkspaceCheck.RetainedWitness.DIFFERS, checked.retainedWitness());
        assertEquals(DiscoveryDomain.CounterexampleStatus.NONE_FOUND, checked.counterexample().status());
        assertTrue(checked.counterexample().witness().isEmpty());
        assertEquals(DiscoveryDomain.EvaluationStatus.INCONCLUSIVE, checked.freshProof().orElseThrow().status());
        assertNull(checked.freshProof().orElseThrow().certificate());
    }

    private static ScopedProofExecutor unexpectedReceiver() {
        return new ScopedProofExecutor() {
            public boolean supports(Obligation obligation) { throw new AssertionError("invalid source reached the receiver"); }
            public VerifiedReplay verify(ScopedProofDag proposal) { throw new AssertionError("invalid source reached the receiver"); }
        };
    }

    private DomainExportWorkspace workspace(DomainDiscoveryEvidence source) throws Exception {
        new DomainDiscoveryExport().write(directory, source);
        return DomainExportWorkspace.fromVerified(new DomainDiscoveryExportVerifier().requireVerified(directory));
    }

    private DomainDiscoveryEvidence untrustedConfirmedSource() {
        // Deliberately untrusted receipt assertions: no kernel or reconstruction is executed.
        var receiver = new ScopedProofExecutor() {
            public boolean supports(Obligation obligation) { return true; }
            public VerifiedReplay verify(ScopedProofDag proposal) {
                return new VerifiedReplay(proposal.hash(), ResultStatus.CONFIRMED, "untrusted imported assertion",
                    SolverIr.sha256("claimed kernel"), directory.resolve("unexecuted-kernel"),
                    SolverIr.sha256("claimed rebuild"), directory.resolve("unexecuted-rebuild"));
            }
        };
        var domain = new ScopedLogicDiscoveryDomain(receiver, 1024);
        var p = new Symbol("p"); var q = new Symbol("q");
        var context = Obligation.create("untrusted-receipt-source",
            List.of(new SymbolDeclaration("p", Sort.PROPOSITION), new SymbolDeclaration("q", Sort.PROPOSITION)),
            List.of(Theory.SCOPED_ANALYSIS), List.of(Predicate.holds("given", p),
                Predicate.holds("implication", new Call("implies", List.of(p, q)))), Goal.holds(p),
            RequestedEvidence.FORMAL_PROOF, new SourceProvenance("untrusted-import", "negative-control", SolverIr.sha256("claimed context")));
        var source = new DomainDiscoveryRunner().run("untrusted-claimed-confirmation", domain,
            DiscoverySeed.create("context", domain.domainId(), context.toCanonicalJson(), "untrusted source assertion"),
            new DiscoveryBudget(2, 16, 16, 16, 4, 8)).evidence();
        assertEquals(DomainDiscoveryEvidence.Outcome.CONFIRMED, source.outcome());
        assertNotNull(source.certificate());
        return source;
    }

    static DomainDiscoveryEvidence source() {
        var domain = new ScopedLogicDiscoveryDomain(proposal -> {
            throw new AssertionError("source retention must never execute a proof receiver");
        }, 0);
        var proposition = new Symbol("p");
        var context = Obligation.create("retained-logical-context", List.of(new SymbolDeclaration("p", Sort.PROPOSITION)),
            List.of(Theory.SCOPED_ANALYSIS), List.of(Predicate.holds("given", proposition)), Goal.holds(proposition),
            RequestedEvidence.FORMAL_PROOF, new SourceProvenance("public-control", "retained-source", SolverIr.sha256("public context")));
        return new DomainDiscoveryRunner().run("retained-logical-source", domain,
            DiscoverySeed.create("logical-context", domain.domainId(), context.toCanonicalJson(), "public workspace control"),
            new DiscoveryBudget(0, 4, 4, 4, 4, 4)).evidence();
    }
}
