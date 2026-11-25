package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.discovery.domain.*;
import de.regelsuche.discovery.domain.DiscoveryDomain.*;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Runs inside the pinned proof worker with separate kernel and rebuild projects. */
@EnabledIfEnvironmentVariable(named = "REGELSUCHE_REAL_LEAN", matches = "true")
class ScopedCandidateLeanHttpTest {
    @Test void confirmsOverHttpAndReopensIdenticalBytesAfterDisablingTheLiveReceiver(@TempDir Path directory)
            throws Exception {
        var p = new Symbol("p");
        var q = new Symbol("q");
        var context = Obligation.create("receipt-http",
            List.of(new SymbolDeclaration("p", Sort.PROPOSITION), new SymbolDeclaration("q", Sort.PROPOSITION)),
            List.of(Theory.SCOPED_ANALYSIS),
            List.of(Predicate.holds("given", p), Predicate.holds("implication", new Call("implies", List.of(p, q)))),
            Goal.holds(p), RequestedEvidence.FORMAL_PROOF, new SourceProvenance("test", "logic", SolverIr.sha256("receipt-http")));
        var domain = new ScopedLogicDiscoveryDomain(dag -> { throw new AssertionError("source formation called Lean"); }, 1024);
        var seed = DiscoverySeed.create("context", domain.domainId(), context.toCanonicalJson(), "HTTP control");
        var evidence = new DomainDiscoveryRunner().run("formal-http", domain, seed, new DiscoveryBudget(2, 4, 4, 4, 4, 4)).evidence();
        Path exported = directory.resolve("export");
        new DomainDiscoveryExport().write(exported, evidence);
        var workspace = new DomainExportWorkspaceRepository(directory.resolve("runs-domain-exports"))
            .retain(new DomainDiscoveryExportVerifier().requireVerified(exported));
        String kernel = "regelsuche.lean.project", rebuild = "regelsuche.lean.rebuild.project";
        String runs = "regelsuche.discovery.runs.directory", artifacts = "regelsuche.proof.evidence";
        String pinnedKernel = System.getenv("REGELSUCHE_LEAN_PROJECT");
        String pinnedRebuild = System.getenv("REGELSUCHE_LEAN_REBUILD_PROJECT");
        assertNotNull(pinnedKernel, "missing pinned Lean project");
        assertNotNull(pinnedRebuild, "missing independent pinned Lean project");
        String oldRuns = System.setProperty(runs, directory.resolve("runs").toString());
        String oldArtifacts = System.setProperty(artifacts, Path.of("target", "checked-proof", "scoped-http").toAbsolutePath().toString());
        String oldKernel = System.setProperty(kernel, pinnedKernel);
        String oldRebuild = System.setProperty(rebuild, pinnedRebuild);
        WebWorkbenchServer server = null;
        try (var client = HttpClient.newHttpClient()) {
            server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
                new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), WebSecurityConfig.none());
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.boundPort());
            String path = DomainExportWorkspaceActions.BASE + "/" + workspace.runId().substring(7) + "/scoped-check";
            var json = new JsonMapper();
            String candidate = evidence.candidateAttempts().getFirst().candidateHash();
            String bound = json.createObjectNode().put("expectedWorkspaceHash", workspace.contentHash())
                .put("expectedEvidenceHash", evidence.contentHash()).put("candidateHash", candidate).toString();
            var post = HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bound)).build();
            var checked = client.send(post, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, checked.statusCode());
            var receipt = json.readTree(checked.body());
            assertEquals("CONFIRMED", receipt.at("/proof/status").asText(), receipt::toString);
            assertEquals(candidate, receipt.at("/source/candidateHash").asText());
            for (String field : List.of("kernelHash", "rebuildHash"))
                assertTrue(receipt.at("/proof/" + field).asText().matches("sha256:[0-9a-f]{64}"), field);
            String location = checked.headers().firstValue("Location").orElseThrow();
            System.setProperty(rebuild, "");
            var disabled = client.send(post, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, disabled.statusCode());
            assertEquals("UNSUPPORTED", json.readTree(disabled.body()).at("/proof/status").asText());
            var historical = client.send(HttpRequest.newBuilder(base.resolve(location)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, historical.statusCode());
            assertArrayEquals(checked.body(), historical.body());
        } finally {
            if (server != null) server.stop();
            restore(runs, oldRuns); restore(artifacts, oldArtifacts); restore(kernel, oldKernel); restore(rebuild, oldRebuild);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }
}
