package de.regelsuche.demo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.web.WebWorkbenchServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PruningDecisionsExportTest {

    private WebWorkbenchServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        var graph = new InMemoryExpressionGraphStore();
        var inv = new InMemoryRuleInventoryRepository();
        server = new WebWorkbenchServer("127.0.0.1", 0, graph, inv, new DefaultTransformationExportService());
        server.start();
        port = server.boundPort();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void pruningDecisionsAreExported() throws IOException {
        // The macro-learning demo runs four DISCOVERY_PLUS searches; the
        // transposition table records re-visits as pruning decisions which
        // must surface in the bundle's pruning-decisions.json.
        post("/api/demo/macro-learning");
        byte[] zipBytes = getBytes("/api/exports/bundle.zip");
        String pruning = readZipEntry(zipBytes, "pruning-decisions.json");
        assertTrue(pruning != null && pruning.contains("\"decisions\""),
            "bundle must contain pruning-decisions.json with a decisions array: " + pruning);
        // Decisions list is non-empty after a DISCOVERY_PLUS demo run.
        assertTrue(pruning.contains("\"reason\":"),
            "expected at least one pruning decision; got: " + pruning);
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
