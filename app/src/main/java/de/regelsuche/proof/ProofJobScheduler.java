package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.inventory.RuleInventoryRepository;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;

/**
 * Schedules and executes {@link ProofJob}s asynchronously.
 *
 * <p>The scheduler maintains a persistent {@link ProofJobRepository}, dispatches
 * jobs to a {@link ProofWorker}, handles retries with configurable back-off,
 * enforces a per-job timeout, supports cancellation, and updates the
 * {@link RuleInventoryRepository} when a rule is formally proved.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ProofJobScheduler scheduler = new ProofJobScheduler(
 *     worker, jobRepository, proofCache, inventoryRepository);
 * scheduler.start();
 *
 * // Submit a job (returns immediately)
 * String jobId = scheduler.submit(candidate, assumptions, priority);
 *
 * // Cancel a queued or running job
 * scheduler.cancel(jobId);
 *
 * // Shutdown gracefully
 * scheduler.close();
 * }</pre>
 *
 * <p>The scheduler polls the repository for pending work every
 * {@link #POLL_INTERVAL_MS} milliseconds.  This keeps the design
 * self-contained without requiring an event-driven broker.</p>
 */
public final class ProofJobScheduler implements AutoCloseable {

    private static final long POLL_INTERVAL_MS = 500L;
    private static final long DEFAULT_JOB_TIMEOUT_MS = 30_000L;

    private final ProofWorker worker;
    private final ProofJobRepository jobRepository;
    private final ProofCache proofCache;
    private final RuleInventoryRepository inventoryRepository;
    private final ProofArtifactRepository artifactRepository;
    private final long jobTimeoutMillis;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "proof-job-scheduler");
            t.setDaemon(true);
            return t;
        });

    /**
     * Single executor that runs every individual proof attempt. Using a
     * shared virtual-thread-per-task executor avoids the per-job leak that
     * existed in the original implementation, where every polling
     * call instantiated (and never closed) a fresh executor.
     */
    private final ExecutorService workerExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // All transitions and worker admission use the scheduler monitor.
    private Attempt activeAttempt;
    private boolean closing;

    public ProofJobScheduler(
        ProofWorker worker,
        ProofJobRepository jobRepository,
        ProofCache proofCache,
        RuleInventoryRepository inventoryRepository
    ) {
        this(worker, jobRepository, proofCache, inventoryRepository,
            Duration.ofMillis(DEFAULT_JOB_TIMEOUT_MS));
    }

    public ProofJobScheduler(
        ProofWorker worker,
        ProofJobRepository jobRepository,
        ProofCache proofCache,
        RuleInventoryRepository inventoryRepository,
        Duration jobTimeout
    ) {
        this(worker, jobRepository, proofCache, inventoryRepository, null, jobTimeout);
    }

    /**
     * Full constructor. Pass a non-null {@code artifactRepository} to have the
     * scheduler write a structured {@code proofs/<jobId>/} bundle for every
     * completed job (proof body, captured streams, metadata.json).
     */
    public ProofJobScheduler(
        ProofWorker worker,
        ProofJobRepository jobRepository,
        ProofCache proofCache,
        RuleInventoryRepository inventoryRepository,
        ProofArtifactRepository artifactRepository,
        Duration jobTimeout
    ) {
        this.worker = worker;
        this.jobRepository = jobRepository;
        this.proofCache = proofCache;
        this.inventoryRepository = inventoryRepository;
        this.artifactRepository = artifactRepository;
        this.jobTimeoutMillis = Math.max(100L, jobTimeout.toMillis());
    }

    /**
     * Start the scheduler's polling loop.  Must be called before
     * {@link #submit}.
     */
    public void start() {
        scheduler.scheduleWithFixedDelay(
            this::dispatch,
            0L,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Submit a new proof job for the given candidate.
     *
     * @param candidate   the rule to prove
     * @param assumptions side conditions
     * @param priority    lower value = higher priority (0 = most urgent)
     * @return the new job id
     */
    public synchronized String submit(RuleCandidate candidate, List<Assumption> assumptions, int priority) {
        if (closing) throw new IllegalStateException("Proof scheduler is closed");
        String id = UUID.randomUUID().toString();
        ProofJob job = new ProofJob(
            id,
            candidate.leftPattern(),
            candidate.rightPattern(),
            assumptions,
            priority,
            worker.workerId()
        );
        jobRepository.save(job.withBudget(jobTimeoutMillis));
        return id;
    }

    /**
     * Request cancellation of a job.  If the job is QUEUED it is cancelled
     * immediately; if it is RUNNING the running future is interrupted.
     *
     * @param jobId job to cancel
     * @return the updated job snapshot, or empty if not found
     */
    public synchronized Optional<ProofJob> cancel(String jobId) {
        return jobRepository.findById(jobId).map(job -> requestStop(job, "USER_CANCELLED"));
    }

    private ProofJob requestStop(ProofJob job, String reason) {
        if (job.status().isTerminal() || job.status() == ProofJobStatus.CANCELLING) {
            return job;
        }
        if (activeAttempt == null || activeAttempt.thread == null || !activeAttempt.job.id().equals(job.id())) {
            ProofJob cancelled = job.stopped(reason);
            jobRepository.save(cancelled);
            return cancelled;
        }
        ProofJob pending = job.stopping(reason);
        jobRepository.save(pending);
        activeAttempt.thread.interrupt();
        return pending;
    }

    /**
     * @return a snapshot of the job with {@code id}, if present.
     */
    public Optional<ProofJob> get(String jobId) {
        return jobRepository.findById(jobId);
    }

    // ── internal poll loop ─────────────────────────────────────────────────

    private synchronized void dispatch() {
        if (closing || activeAttempt != null) return;
        var next = jobRepository.findNextQueued();
        if (next.isEmpty()) return;
        ProofJob job = next.get();
        String identity = worker.cacheIdentity();
        ProofCacheKey key = ProofCacheKey.of(job.leftPattern(), job.rightPattern(), job.assumptions(), identity);
        var cached = proofCache.get(key).filter(status -> status != CandidateProofStatus.FORMALLY_PROVED);
        if (cached.isPresent() && identity.equals(worker.cacheIdentity())) {
            jobRepository.save(job.withDone(cached.get()));
            return;
        }
        Attempt attempt = new Attempt(job, identity, key);
        activeAttempt = attempt;
        workerExecutor.execute(attempt::run);
    }

    private final class Attempt {
        final ProofJob job;
        final String identity;
        final ProofCacheKey key;
        Thread thread;
        boolean timedOut;

        Attempt(ProofJob job, String identity, ProofCacheKey key) {
            this.job = job;
            this.identity = identity;
            this.key = key;
        }

        void run() {
            try {
                execute();
            } finally {
                synchronized (ProofJobScheduler.this) {
                    if (activeAttempt == this) activeAttempt = null;
                }
            }
        }

        void execute() {
            ProofJob running;
            ScheduledFuture<?> deadline;
            synchronized (ProofJobScheduler.this) {
                if (jobRepository.findById(job.id()).orElseThrow().status().isTerminal()) return;
                thread = Thread.currentThread();
                running = job.startAttempt(jobTimeoutMillis);
                jobRepository.save(running);
                deadline = scheduler.schedule(this::timeout, jobTimeoutMillis, TimeUnit.MILLISECONDS);
            }
            RuleCandidate candidate = new RuleCandidate(job.leftPattern(), job.rightPattern(), 0, 0.0,
                0, false, false, false, List.of(), RuleStatus.NEW, CandidateProofStatus.OBSERVED, "");
            ProofWorker.Result result = null;
            Throwable failure = null;
            try {
                result = worker.prove(candidate, job.assumptions());
            } catch (Throwable thrown) {
                failure = thrown;
            } finally {
                deadline.cancel(false);
                // The prover may restore its interrupt flag. Persist its outcome on a thread
                // that can perform file I/O even after a cooperative stop.
                synchronized (ProofJobScheduler.this) {
                    Thread.interrupted();
                    finish(running, result, failure);
                    activeAttempt = null;
                }
            }
        }

        void timeout() {
            synchronized (ProofJobScheduler.this) {
                if (activeAttempt == this) {
                    timedOut = true;
                    thread.interrupt();
                }
            }
        }

        void finish(ProofJob running, ProofWorker.Result result, Throwable failure) {
            ProofJob current = jobRepository.findById(job.id()).orElseThrow();
            if (current.status() == ProofJobStatus.CANCELLING) {
                jobRepository.save(current.stopped(current.stopReason()));
            } else if (timedOut) {
                handleFailure(running, "timeout after " + jobTimeoutMillis + "ms");
            } else if (failure != null || result == null) {
                String reason = failure == null ? "worker returned no result" : failure.toString();
                handleFailure(running, reason);
            } else if (!identity.equals(worker.cacheIdentity())) {
                handleFailure(running, "proof configuration changed during the attempt");
            } else {
                CandidateProofStatus status = result.status();
                proofCache.put(key, status);
                writeArtifactBundle(running, result, status, null);
                jobRepository.save(running.withDone(status));
                if (inventoryRepository != null && status.isPositive()) updateInventory(running, status);
            }
        }
    }

    private void handleFailure(ProofJob job, String reason) {
        writeArtifactBundle(job, null, CandidateProofStatus.OBSERVED, reason);
        if (job.canRetry()) {
            jobRepository.save(job.withRetry());
        } else {
            jobRepository.save(job.withFailed(reason));
        }
    }

    /**
     * Persist a structured per-job artifact bundle. Always writes
     * {@code metadata.json}; writes {@code proof.<ext>} when the result has a
     * non-empty body. {@code stdout.txt} / {@code stderr.txt} are placeholders
     * today — once {@link ProofWorker.Result} carries captured streams they'll
     * become populated automatically.
     */
    private void writeArtifactBundle(
        ProofJob job,
        ProofWorker.Result result,
        CandidateProofStatus status,
        String errorReason
    ) {
        if (artifactRepository == null) {
            return;
        }
        try {
            String tool = result == null ? worker.workerId() : result.tool();
            String body = result == null ? "" : result.artifact();
            long durationMs = result == null ? 0L : result.durationMillis();
            if (body != null && !body.isBlank()) {
                String suffix = bodyExtension(tool);
                artifactRepository.storeJobArtifact(job.id(), "proof" + suffix, body);
            }
            // Placeholder stdout/stderr so the bundle layout is uniform.
            artifactRepository.storeJobArtifact(job.id(), "stdout.txt", "");
            artifactRepository.storeJobArtifact(job.id(), "stderr.txt",
                errorReason == null ? "" : errorReason);
            String metadata = renderMetadataJson(job, tool, status, durationMs, errorReason);
            artifactRepository.storeJobArtifact(job.id(), "metadata.json", metadata);
        } catch (java.io.IOException ex) {
            // Never let artifact-write errors derail the scheduler loop.
            System.err.println("Failed to persist artifact bundle for job "
                + job.id() + ": " + ex.getMessage());
        }
    }

    private static String bodyExtension(String tool) {
        if (tool == null) {
            return ".txt";
        }
        String lower = tool.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("lean")) {
            return ".lean";
        }
        if (lower.contains("smt")) {
            return ".smt2";
        }
        return ".txt";
    }

    private static String renderMetadataJson(
        ProofJob job,
        String tool,
        CandidateProofStatus status,
        long durationMillis,
        String errorReason
    ) {
        StringBuilder builder = new StringBuilder("{");
        builder.append("\"jobId\":").append(jsonString(job.id()));
        builder.append(",\"workerId\":").append(jsonString(job.workerType()));
        builder.append(",\"tool\":").append(jsonString(tool));
        builder.append(",\"status\":").append(jsonString(status == null ? "" : status.name()));
        builder.append(",\"durationMillis\":").append(durationMillis);
        builder.append(",\"leftPattern\":").append(jsonString(job.leftPattern()));
        builder.append(",\"rightPattern\":").append(jsonString(job.rightPattern()));
        builder.append(",\"priority\":").append(job.priority());
        builder.append(",\"retryCount\":").append(job.retryCount());
        builder.append(",\"maxRetries\":").append(job.maxRetries());
        builder.append(",\"createdAt\":").append(jsonString(job.createdAt().toString()));
        builder.append(",\"completedAt\":").append(jsonString(java.time.Instant.now().toString()));
        builder.append(",\"error\":").append(jsonString(errorReason == null ? "" : errorReason));
        builder.append('}');
        return builder.toString();
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private void updateInventory(ProofJob job, CandidateProofStatus status) {
        inventoryRepository.findAll().stream()
            .filter(r -> r.leftPattern().equals(job.leftPattern())
                && r.rightPattern().equals(job.rightPattern()))
            .findFirst()
            .ifPresent(rule -> {
                if (rule.proofStatus().ordinal() < status.ordinal()) {
                    inventoryRepository.save(new ReusableRule(
                        rule.id(),
                        rule.leftPattern(),
                        rule.rightPattern(),
                        rule.parameterRelations(),
                        status,
                        rule.knownRuleStatus(),
                        rule.supportingExamples(),
                        rule.averageImprovement(),
                        rule.createdAt(),
                        rule.canonicalHash(),
                        Instant.now(),
                        rule.usageCount()
                    ));
                }
            });
    }

    @Override
    public void close() {
        synchronized (this) {
            closing = true;
            if (activeAttempt != null) {
                jobRepository.findById(activeAttempt.job.id()).ifPresent(job -> requestStop(job, "SCHEDULER_SHUTDOWN"));
            }
        }
        scheduler.shutdownNow();
        workerExecutor.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
            workerExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return whether the shared worker executor has fully shut down. Exposed
     *         primarily for tests that verify {@link #close()} cleans up
     *         executor resources.
     */
    public boolean isWorkerExecutorTerminated() {
        return workerExecutor.isTerminated();
    }
}
