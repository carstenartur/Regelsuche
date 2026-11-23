package de.regelsuche.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchProfile;
import de.regelsuche.search.memory.PruningReason;
import de.regelsuche.search.memory.SearchMemory;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.web.WebSecurityConfig;
import de.regelsuche.web.WebWorkbenchServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PruningDecisionsExportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private WebWorkbenchServer server;
    private SearchMemory memory;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        var graph = new InMemoryExpressionGraphStore();
        var inv = new InMemoryRuleInventoryRepository();
        memory = new SearchMemory();
        server = new WebWorkbenchServer("127.0.0.1", 0, graph, inv,
            new DefaultTransformationExportService(), WebSecurityConfig.none(), memory);
        server.start();
        port = server.boundPort();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void genuineBeamPruningDecisionsAreExported() throws IOException {
        var engine = new AstRewriteTransformationEngine(
            AstRewriteTransformationEngine.defaultRules().stream()
                .filter(rule -> rule.id().equals("ast_add_zero_right")).toList());
        var problem = new SearchProblem("x + 0", expression -> {
            var proposals = engine.transform(expression);
            return Stream.concat(proposals.stream(), proposals.stream()).toList();
        }, new ExpressionScorer(),
            new ExpressionCanonicalizer(), SearchProfile.TEACHING.heuristic()).withMemory(memory);
        var strategy = SearchProfile.TEACHING.newStrategy();

        var states = strategy.search(problem);

        assertEquals(List.of("x + 0", "x"), states.stream().map(SearchState::expression).toList(),
            "Beam keeps one of the two identical proposals in the current run");
        assertEquals(1, memory.decisions().size());
        var decision = memory.decisions().getFirst();
        assertEquals(PruningReason.ALREADY_KNOWN_EQUAL, decision.reason());

        JsonNode pruning = pruningExport();
        assertEquals(1, pruning.path("count").asInt());
        assertTrue(pruning.path("decisions").isArray());
        assertEquals(1, pruning.path("decisions").size());
        JsonNode exported = pruning.path("decisions").get(0);
        assertEquals("x", exported.path("expression").asText());
        assertEquals(states.getLast().canonicalHash(), exported.path("canonicalHash").asText());
        assertEquals(decision.reason().name(), exported.path("reason").asText());
        assertEquals(decision.explanation(), exported.path("explanation").asText());
        assertEquals(JSON.readTree(getBytes("/api/memory/pruning")), pruning);
    }

    @Test
    void bestFirstDemoExportsObservationsWithoutPhantomPruning() throws IOException {
        // DISCOVERY_PLUS runs BestFirst: durable value observations do not
        // certify that the current run's continuations can be skipped.
        post("/api/demo/macro-learning");
        assertFalse(memory.table().entries().isEmpty(), "The demo must record real search observations");
        assertTrue(memory.decisions().isEmpty());

        JsonNode pruning = pruningExport();
        assertEquals(0, pruning.path("count").asInt());
        assertTrue(pruning.path("decisions").isArray());
        assertTrue(pruning.path("decisions").isEmpty());
        assertEquals(JSON.readTree(getBytes("/api/memory/pruning")), pruning);
    }

    private JsonNode pruningExport() throws IOException {
        String pruning = readZipEntry(getBytes("/api/exports/bundle.zip"), "pruning-decisions.json");
        assertNotNull(pruning, "bundle must contain pruning-decisions.json");
        return JSON.readTree(pruning);
    }

    private void post(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path)
            .toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.getOutputStream().close();
        conn.getInputStream().readAllBytes();
        conn.disconnect();
    }

    private byte[] getBytes(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path)
            .toURL().openConnection();
        return conn.getInputStream().readAllBytes();
    }

    private String readZipEntry(byte[] zipBytes, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
