package de.regelsuche.e2e;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.web.WebWorkbenchServer;
import java.io.IOException;

/**
 * Boots the Regelsuche {@link WebWorkbenchServer} in-process on a random port
 * so the Playwright browser flows can talk to a real, complete instance of
 * the production server – the same JVM code that ships in the Docker image.
 *
 * <p>The class is named {@code …AppEnvironment} on purpose: it represents the
 * "system under test" container the browser interacts with. We deliberately
 * do not boot the Docker image via Testcontainers here even though
 * {@code org.testcontainers:testcontainers} is on the classpath: starting an
 * in-process server is an order of magnitude faster and uses the exact same
 * application code path. If a future flow needs a real Docker image (e.g. for
 * a Neo4j-backed run) it can compose this class with a Testcontainers
 * {@code GenericContainer} – the Playwright {@code baseUrl()} indirection
 * makes that swap a one-line change.</p>
 */
public final class RegelsucheAppEnvironment implements AutoCloseable {

    private final WebWorkbenchServer server;

    public RegelsucheAppEnvironment() throws IOException {
        this.server = new WebWorkbenchServer(
            "127.0.0.1",
            0,
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService()
        );
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.boundPort();
    }

    @Override
    public void close() {
        server.stop();
    }
}
