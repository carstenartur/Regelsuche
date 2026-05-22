package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.inventory.RuleInventoryRepository;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
     * existed in the first PR 15 cut, where every {@code processPending()}
     * call instantiated (and never closed) a fresh executor.
     */
    private final ExecutorService workerExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** Running futures keyed by job id — for cancellation. */
    private final Map<String, Future<?>> runningFutures = new ConcurrentHashMap<>();

    /** Job ids that have been explicitly cancelled by the caller. */
    private final Set<String> cancelRequests = ConcurrentHashMap.newKeySet();

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
            this::processPending,
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
    public String submit(RuleCandidate candidate, List<Assumption> assumptions, int priority) {
        String id = UUID.randomUUID().toString();
        ProofJob job = new ProofJob(
            id,
            candidate.leftPattern(),
            candidate.rightPattern(),
            assumptions,
            priority,
            worker.workerId()
        );
        jobRepository.save(job);
        return id;
    }

    /**
     * Request cancellation of a job.  If the job is QUEUED it is cancelled
     * immediately; if it is RUNNING the running future is interrupted.
     *
     * @param jobId job to cancel
     * @return the updated job snapshot, or empty if not found
     */
    public Optional<ProofJob> cancel(String jobId) {
        Optional<ProofJob> found = jobRepository.findById(jobId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ProofJob job = found.get();
        if (job.status().isTerminal()) {
            return Optional.of(job);
        }
        cancelRequests.add(jobId);
        // Interrupt a running future if present
        Future<?> future = runningFutures.get(jobId);
        if (future != null) {
            future.cancel(true);
        }
        ProofJob cancelled = job.withStatus(ProofJobStatus.CANCELLED);
        jobRepository.save(cancelled);
        return Optional.of(cancelled);
    }

    /**
     * @return a snapshot of the job with {@code id}, if present.
     */
    public Optional<ProofJob> get(String jobId) {
        return jobRepository.findById(jobId);
    }

    // ── internal poll loop ─────────────────────────────────────────────────

    private void processPending() {
        Optional<ProofJob> next = jobRepository.findNextQueued();
        if (next.isEmpty()) {
            return;
        }
        ProofJob job = next.get();

        // Honour pending cancellation requests
        if (cancelRequests.contains(job.id())) {
            cancelRequests.remove(job.id());
            jobRepository.save(job.withStatus(ProofJobStatus.CANCELLED));
            return;
        }

        // Check proof cache before dispatching
        ProofCacheKey cacheKey = ProofCacheKey.of(
            job.leftPattern(), job.rightPattern(), job.assumptions(), worker.workerId());
        Optional<CandidateProofStatus> cached = proofCache.get(cacheKey);
        if (cached.isPresent()) {
            jobRepository.save(job.withDone(cached.get()));
            return;
        }

        // Mark as RUNNING
        jobRepository.save(job.withStatus(ProofJobStatus.RUNNING));

        // Build a minimal RuleCandidate for the worker
        RuleCandidate candidate = new RuleCandidate(
            job.leftPattern(),
            job.rightPattern(),
            0,
            0.0,
            0,
            false,
            false,
            false,
            List.of(),
            RuleStatus.NEW,
            CandidateProofStatus.OBSERVED,
            ""
        );

        // Submit to the shared worker executor with timeout
        Future<ProofWorker.Result> future = workerExecutor
            .submit(() -> worker.prove(candidate, job.assumptions()));
        runningFutures.put(job.id(), future);
        try {
            ProofWorker.Result result = future.get(jobTimeoutMillis, TimeUnit.MILLISECONDS);
            runningFutures.remove(job.id());

            if (cancelRequests.contains(job.id())) {
                cancelRequests.remove(job.id());
                jobRepository.save(job.withStatus(ProofJobStatus.CANCELLED));
                return;
            }

            CandidateProofStatus status = result.status();
            proofCache.put(cacheKey, status);
            writeArtifactBundle(job, result, status, null);
            jobRepository.save(job.withDone(status));

            // Update RuleInventory if we have a positive result
            if (inventoryRepository != null && status.isPositive()) {
                updateInventory(job, status);
            }

        } catch (TimeoutException ex) {
            future.cancel(true);
            runningFutures.remove(job.id());
            handleFailure(job, "timeout after " + jobTimeoutMillis + "ms");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            runningFutures.remove(job.id());
            jobRepository.save(job.withStatus(ProofJobStatus.CANCELLED));
        } catch (CancellationException ex) {
            runningFutures.remove(job.id());
            cancelRequests.remove(job.id());
            jobRepository.save(job.withStatus(ProofJobStatus.CANCELLED));
        } catch (Exception ex) {
            runningFutures.remove(job.id());
            if (cancelRequests.contains(job.id())) {
                cancelRequests.remove(job.id());
                jobRepository.save(job.withStatus(ProofJobStatus.CANCELLED));
                return;
            }
            handleFailure(job, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
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
