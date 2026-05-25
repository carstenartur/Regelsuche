package de.regelsuche.e2e;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.proof.InMemoryProofCache;
import de.regelsuche.proof.InMemoryProofJobRepository;
import de.regelsuche.proof.JsonFileProofArtifactRepository;
import de.regelsuche.proof.ProofJobScheduler;
import de.regelsuche.proof.ProofWorkbenchService;
import de.regelsuche.proof.ProofWorker;
import de.regelsuche.web.WebWorkbenchServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Boots the Regelsuche {@link WebWorkbenchServer} in-process on a random port
 * so the Playwright browser flows can talk to a real, complete instance of
 * the production server – the same JVM code that ships in the Docker image.
 *
 * <p>The class is named {@code …AppEnvironment} on purpose: it represents the
 * "system under test" container the browser interacts with. We deliberately
 * do not boot the Docker image via Testcontainers here: starting an
 * in-process server is an order of magnitude faster and uses the exact same
 * application code path. For Docker-image-level regression tests (e.g.
 * verifying that static assets under {@code /vendor/} are actually served)
 * see the complementary {@code dockerE2eTest} source set and
 * {@code WebWorkbenchDockerImageTest} / {@code WebWorkbenchDockerImagePlaywrightTest}
 * – those tests caught the May 2026 {@code /vendor/} bug that caused KaTeX
 * to silently fail across multiple PRs. If a future flow needs a real Docker
 * image (e.g. for a Neo4j-backed run) it can compose this class with a
 * Testcontainers {@code GenericContainer} – the Playwright {@code baseUrl()}
 * indirection makes that swap a one-line change.</p>
 *
 * <p>Pass {@code enableProofWorkbench=true} to wire a self-contained, in-process
 * {@link ProofWorkbenchService} backed by an always-succeeding stub worker.
 * This lets the proof-job browser flow exercise the full submit → schedule →
 * artefact-write loop without needing Z3 or Lean on the CI runner.</p>
 */
public final class RegelsucheAppEnvironment implements AutoCloseable {

    private final WebWorkbenchServer server;
    private final ProofJobScheduler proofScheduler;
    private final Path proofArtifactRoot;

    public RegelsucheAppEnvironment() throws IOException {
        this(false);
    }

    public RegelsucheAppEnvironment(boolean enableProofWorkbench) throws IOException {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        ProofWorkbenchService proofService = null;
        if (enableProofWorkbench) {
            this.proofArtifactRoot = Files.createTempDirectory("regelsuche-e2e-proof-");
            JsonFileProofArtifactRepository artifacts =
                new JsonFileProofArtifactRepository(this.proofArtifactRoot);
            InMemoryProofJobRepository jobs = new InMemoryProofJobRepository();
            InMemoryProofCache cache = new InMemoryProofCache();
            ProofWorker worker = new StubAlwaysSucceedsWorker();
            this.proofScheduler = new ProofJobScheduler(
                worker, jobs, cache, inventory, artifacts, Duration.ofSeconds(10));
            this.proofScheduler.start();
            proofService = new ProofWorkbenchService(this.proofScheduler, jobs, artifacts);
        } else {
            this.proofArtifactRoot = null;
            this.proofScheduler = null;
        }
        this.server = new WebWorkbenchServer(
            "127.0.0.1",
            0,
            new InMemoryExpressionGraphStore(),
            inventory,
            new DefaultTransformationExportService(),
            null,
            null,
            null,
            null,
            proofService
        );
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.boundPort();
    }

    @Override
    public void close() {
        server.stop();
        if (proofScheduler != null) {
            proofScheduler.close();
        }
        if (proofArtifactRoot != null) {
            try {
                deleteRecursively(proofArtifactRoot);
            } catch (IOException ignored) {
                // best-effort cleanup; the temp dir will be reclaimed by the OS.
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best-effort */ }
                });
        }
    }

    /**
     * Deterministic proof worker for the browser flow: always returns
     * {@link CandidateProofStatus#FORMALLY_PROVED} and a tiny SMT-LIB artefact
     * so the artefact panel has something to render even when no real prover
     * binary is installed on the runner.
     */
    private static final class StubAlwaysSucceedsWorker implements ProofWorker {
        @Override
        public Result prove(RuleCandidate candidate, List<Assumption> assumptions) {
            String artifact = ";; auto-generated by RegelsucheAppEnvironment stub worker\n"
                + "(declare-fun a () Real)\n"
                + "(assert (not (= " + candidate.leftPattern() + " "
                + candidate.rightPattern() + ")))\n"
                + "(check-sat)\n";
            RuleCandidate updated = new RuleCandidate(
                candidate.leftPattern(),
                candidate.rightPattern(),
                candidate.examplesCount(),
                candidate.averageScoreImprovement(),
                candidate.maximumScoreImprovement(),
                /* equivalenceVerified */ true,
                candidate.generalizationPlausible(),
                candidate.containsFreeParameters(),
                candidate.parameterRelations(),
                candidate.status(),
                CandidateProofStatus.FORMALLY_PROVED,
                candidate.canonicalHash(),
                candidate.supportingTransformationIds()
            );
            return new Result(updated, CandidateProofStatus.FORMALLY_PROVED,
                artifact, "stub-smt", 1L);
        }

        @Override
        public String workerId() {
            return "stub-smt";
        }
    }
}
