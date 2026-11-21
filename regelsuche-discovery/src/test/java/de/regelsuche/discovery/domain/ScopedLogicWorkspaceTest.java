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
import java.util.List;
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
        new DomainDiscoveryExport().write(directory, source);
        var workspace = DomainExportWorkspace.fromVerified(new DomainDiscoveryExportVerifier().requireVerified(directory));
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
