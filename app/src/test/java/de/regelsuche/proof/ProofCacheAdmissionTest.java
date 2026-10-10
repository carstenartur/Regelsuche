package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.validation.CandidateProofStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Status-cache controls; the deliberately synthetic worker is not a mathematical prover. */
class ProofCacheAdmissionTest {
    @TempDir Path temporary;

    @Test void legacyPersistentLeanAndSmtStatusesCannotBypassExecution() throws Exception {
        int index = 0;
        for (ProofWorker worker : List.of(new LeanProofWorker(), new SmtProofWorker(),
                new CompositeProofWorker(List.of(new LeanProofWorker(), new SmtProofWorker())))) {
            Path file = temporary.resolve("legacy-" + index++ + ".json");
            var cache = new JsonFileProofCache(file);
            cache.put(key(worker.workerId()), CandidateProofStatus.FORMALLY_PROVED);
            var reopened = new JsonFileProofCache(file);
            var result = run(worker, reopened);
            assertEquals(ProofJobStatus.DONE, result.status());
            assertNotEquals(CandidateProofStatus.FORMALLY_PROVED, result.resultStatus(),
                "a legacy " + worker.workerId() + " status bypassed the current proof boundary");
        }
    }

    @Test void currentIdentityStillCannotMakeAStatusIntoAProof() throws Exception {
        for (ProofWorker worker : List.of(new LeanProofWorker(), new SmtProofWorker(),
                new CompositeProofWorker(List.of(new LeanProofWorker(), new SmtProofWorker())))) {
            var cache = new InMemoryProofCache();
            cache.put(key(worker.cacheIdentity()), CandidateProofStatus.FORMALLY_PROVED);
            assertNotEquals(CandidateProofStatus.FORMALLY_PROVED, run(worker, cache).resultStatus());
        }
    }

    @Test void nonProofCacheHitStillAvoidsRedundantGeneration() throws Exception {
        var calls = new AtomicInteger();
        ProofWorker worker = counter(calls, new AtomicInteger(), false);
        var cache = new InMemoryProofCache();
        cache.put(key(worker.cacheIdentity()), CandidateProofStatus.FORMALLY_PROVABLE);
        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE, run(worker, cache).resultStatus());
        assertEquals(0, calls.get());
    }

    @Test void changedConfigurationDoesNotReuseEarlierGeneration() throws Exception {
        var version = new AtomicInteger();
        var calls = new AtomicInteger();
        ProofWorker worker = counter(calls, version, false);
        var cache = new InMemoryProofCache();
        cache.put(key(worker.cacheIdentity()), CandidateProofStatus.FORMALLY_PROVABLE);
        version.incrementAndGet();
        assertEquals(CandidateProofStatus.OBSERVED, run(worker, cache).resultStatus());
        assertEquals(1, calls.get());
    }

    @Test void configurationChangeDuringAnAttemptCannotPublishSuccess() throws Exception {
        var calls = new AtomicInteger();
        ProofWorker worker = counter(calls, new AtomicInteger(), true);
        var cache = new InMemoryProofCache();
        var result = run(worker, cache);
        assertEquals(ProofJobStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("configuration changed"));
        assertEquals(0, cache.size());
    }

    @Test void leanAndCompositeScopesTrackManifestAndToolchainChanges() throws Exception {
        Path project = Files.createDirectory(temporary.resolve("lean-project"));
        Files.writeString(project.resolve("lean-toolchain"), "leanprover/lean4:v4.19.0\n");
        Files.writeString(project.resolve("lake-manifest.json"), "{}\n");
        var worker = new LeanProofWorker(null, ProverExecutor.lean(project, temporary.resolve("proofs")));
        var composite = new CompositeProofWorker(List.of(worker, new SmtProofWorker()));
        String original = worker.cacheIdentity();
        String originalComposite = composite.cacheIdentity();
        assertNotEquals(original, new LeanProofWorker().cacheIdentity());
        Files.writeString(project.resolve("lake-manifest.json"), "{\"changed\":true}\n");
        assertNotEquals(original, worker.cacheIdentity());
        assertNotEquals(originalComposite, composite.cacheIdentity());
        String manifestChanged = worker.cacheIdentity();
        Files.writeString(project.resolve("lean-toolchain"), "different-pinned-toolchain\n");
        assertNotEquals(manifestChanged, worker.cacheIdentity());
        assertFalse(Files.exists(temporary.resolve("proofs")), "identity checks must not generate proofs");
    }

    @Test void z3AndTransportScopesAreNotTheLegacyWorkerId() {
        var z3 = new SmtProofWorker(null, ProverExecutor.z3(temporary));
        var raw = new SmtProofWorker(null, new ProverExecutor(List.of("true"), "smtlib2", ".smt2"));
        assertTrue(z3.cacheIdentity().startsWith("proof-cache/v2/smtlib2/sha256:"));
        assertNotEquals(z3.workerId(), z3.cacheIdentity());
        assertNotEquals(z3.cacheIdentity(), raw.cacheIdentity());
        assertNotEquals(z3.cacheIdentity(), new SmtProofWorker().cacheIdentity());
    }

    private static ProofWorker counter(AtomicInteger calls, AtomicInteger version, boolean changeDuringProof) {
        return new ProofWorker() {
            @Override public String workerId() { return "synthetic-cache-control"; }
            @Override public String cacheIdentity() {
                return "proof-cache/v2/test/" + version.get();
            }
            @Override public Result prove(RuleCandidate candidate, List<Assumption> assumptions) {
                calls.incrementAndGet();
                if (changeDuringProof) version.incrementAndGet();
                return new Result(candidate, changeDuringProof ? CandidateProofStatus.FORMALLY_PROVED
                    : CandidateProofStatus.OBSERVED, "explicit test fixture", "synthetic", 0);
            }
        };
    }

    private static ProofCacheKey key(String identity) {
        return ProofCacheKey.of("x+1", "x", List.of(), identity);
    }

    private static ProofJob run(ProofWorker worker, ProofCache cache) throws Exception {
        try (var scheduler = new ProofJobScheduler(worker, new InMemoryProofJobRepository(),
                cache, null, Duration.ofSeconds(3))) {
            String id = scheduler.submit(new RuleCandidate("x+1", "x", 0, 0, 0,
                false, false, false, List.of(), RuleStatus.NEW,
                CandidateProofStatus.OBSERVED, ""), List.of(), 0);
            scheduler.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (!scheduler.get(id).orElseThrow().status().isTerminal()
                    && System.nanoTime() < deadline) Thread.sleep(10);
            var result = scheduler.get(id).orElseThrow();
            assertTrue(result.status().isTerminal(), result.toString());
            return result;
        }
    }
}
