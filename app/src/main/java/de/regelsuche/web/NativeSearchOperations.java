package de.regelsuche.web;

import de.regelsuche.discovery.representation.TargetFreeRepresentationDiscoveryRun;
import de.regelsuche.discovery.representation.TargetFreeSearchExecution;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.telemetry.SearchEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Coordinates one bounded, observable native search with an immutable terminal record. */
final class NativeSearchOperations implements AutoCloseable {
    interface Runner {
        TargetFreeSearchExecution.RunResult run(String source,SearchHeuristic budget,String revision,
            Consumer<SearchEvent> observer) throws Exception;
    }
    private final Path runs;
    private final Supplier<String> revision;
    private final Runner runner;
    private final NativeSearchOperationStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("retained-native-search").factory());
    private NativeSearchOperation active;
    private FileLock lease;
    private boolean closing;

    NativeSearchOperations(Path runs,Supplier<String> revision) { this(runs,revision,TargetFreeRepresentationDiscoveryRun::runTargetFree); }
    NativeSearchOperations(Path runs,Supplier<String> revision,Runner runner) {
        this.runs=runs; this.revision=revision; this.runner=runner; store=new NativeSearchOperationStore(runs);
    }

    synchronized NativeSearchOperation start(String source,String profile) throws IOException {
        return start(source, profile, false);
    }

    synchronized NativeSearchOperation start(String source, String profile, boolean startPaused) throws IOException {
        if (source==null || source.isBlank() || source.length()>256) throw new IllegalArgumentException("source must contain 1 to 256 characters");
        new de.regelsuche.parse.ExpressionParser().parseTerm(source);
        SearchHeuristic budget=switch(profile) {
            case "small" -> new SearchHeuristic(2,12,1,1,8,8);
            case "extended" -> new SearchHeuristic(4,32,1,2,8,8);
            default -> throw new IllegalArgumentException("unsupported native search budget");
        };
        String sha=revision.get();
        if (sha==null || !sha.matches("[0-9a-f]{40}")) throw new IllegalStateException("source revision unavailable");
        if (closing || lease!=null) throw new IllegalStateException("native search worker unavailable");
        lease=store.tryLease();
        if (lease==null) throw new IllegalStateException("another native search owns the workspace");
        try {
            var ids=store.ids();
            for (String id:ids) recover(id);
            if (ids.size()>=256) throw new IllegalStateException("native operation repository is full");
            var value=new NativeSearchOperation(UUID.randomUUID().toString(),source,profile,budget,sha,startPaused ? "PAUSED" : "RUNNING","",null,
                NativeSearchOperation.Progress.initial());
            store.save(value); active=value;
            executor.execute(()->execute(value));
            return value;
        } catch (IOException | RuntimeException failure) { release(); throw failure; }
    }

    private void execute(NativeSearchOperation started) {
        try {
            synchronized (this) { awaitReady(); }
            var result=runner.run(started.source(),started.budget(),started.repositoryRevision(),this::observe);
            synchronized(this) {
                awaitReady();
                result.retain(runs);
                finish("COMPLETED", termination(result), result.workspace().runId());
            }
        } catch (Stopped | InterruptedException stopped) {
            synchronized(this) { finish(closing?"INTERRUPTED":"CANCELLED",closing?"HOST_STOP":"OPERATOR_STOP",null); }
        } catch (Exception failure) {
            String reason = TargetFreeSearchExecution.DOSSIER_SIZE_LIMIT.equals(failure.getMessage())
                ? "DOSSIER_BYTE_BUDGET" : failure.getClass().getSimpleName();
            synchronized(this) { finish("FAILED", reason, null); }
        } finally {
            synchronized(this) {
                try { release(); } catch (IOException failure) { throw new UncheckedIOException(failure); }
                finally { notifyAll(); }
            }
        }
    }

    private static String termination(TargetFreeSearchExecution.RunResult result) {
        var content = result.artifact().content();
        var metrics = content.metrics();
        if (content.events().getLast().frontierSize() > 0 && metrics.exploredStates() >= content.heuristic().maxVisitedExpressions()) {
            return "VISITED_STATE_BUDGET";
        }
        if (content.work().generatedTransformations() == 0) return "NO_TRANSFORMATIONS_RETURNED";
        if (metrics.depthPrunes() > 0) return "FRONTIER_DRAINED_WITH_DEPTH_PRUNES";
        return "BOUNDED_FRONTIER_DRAINED";
    }

    private synchronized void observe(SearchEvent event) {
        awaitReady();
        active=active.observe(event);
        if (active.progress().events()%32==0) persist(active);
    }
    private void awaitReady() {
        if ("PAUSING".equals(active.status())) {
            active = active.transition("PAUSED", "", null);
            persist(active);
            notifyAll();
        }
        while ("PAUSED".equals(active.status()) && !closing) {
            try { wait(); }
            catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new Stopped();
            }
        }
        if (closing || active.status().equals("CANCELLING") || Thread.currentThread().isInterrupted()) throw new Stopped();
    }
    private void finish(String state,String reason,String runId) {
        var terminal=active.transition(state,reason,runId);
        persist(terminal); active=terminal;
    }
    private void persist(NativeSearchOperation value) {
        try { store.save(value); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private void release() throws IOException {
        try { NativeSearchOperationStore.release(lease); } finally { lease=null; }
    }

    synchronized Optional<NativeSearchOperation> find(String id) throws IOException {
        if (lease != null && active != null && !active.terminal() && active.operationId().equals(id)) return Optional.of(active);
        var saved=store.find(id);
        if (saved.isEmpty() || saved.get().terminal() || lease!=null) return saved;
        var recovery=store.tryLease();
        if (recovery==null) return saved;
        try { return recover(id); } finally { NativeSearchOperationStore.release(recovery); }
    }
    private Optional<NativeSearchOperation> recover(String id) throws IOException {
        var saved=store.find(id);
        if (saved.isEmpty() || saved.get().terminal()) return saved;
        var terminal=saved.get().transition("INTERRUPTED","WORKER_LEASE_ENDED",null);
        store.save(terminal); return Optional.of(terminal);
    }
    synchronized List<String> ids() throws IOException { return store.ids(); }

    synchronized NativeSearchOperation cancel(String id) throws IOException {
        return control(id, "cancel");
    }

    synchronized NativeSearchOperation control(String id, String action) throws IOException {
        var value = find(id).orElseThrow(() -> new NoSuchElementException("operation not found"));
        if (value.terminal()) return value;
        if (lease == null || active == null || !active.operationId().equals(id)) throw new IllegalStateException("operation belongs to another live worker");
        var next = switch (action) {
            case "cancel" -> value.transition("CANCELLING", "OPERATOR_STOP", null);
            case "pause" -> "RUNNING".equals(value.status()) ? value.transition("PAUSING", "", null) : value;
            case "resume" -> java.util.Set.of("PAUSED", "PAUSING").contains(value.status()) ? value.transition("RUNNING", "", null) : value;
            default -> throw new IllegalArgumentException("unknown operation control");
        };
        store.save(next);
        active = next;
        notifyAll();
        return next;
    }

    synchronized NativeSearchOperation await(String id,long timeout,TimeUnit unit) throws Exception {
        long deadline=System.nanoTime()+unit.toNanos(timeout);
        var current=find(id).orElseThrow();
        // A terminal record is durable before execute() releases its workspace lease.
        // Do not return to the owning caller until that lease is released: an immediate
        // next operation must not fail solely because terminal persistence won the race.
        while (!current.terminal() || (lease != null && active != null && active.operationId().equals(id))) {
            long remaining=deadline-System.nanoTime();
            if (remaining<=0) throw new TimeoutException();
            TimeUnit.NANOSECONDS.timedWait(this,remaining);
            current=find(id).orElseThrow();
        }
        return current;
    }

    @Override public void close() {
        synchronized(this) { closing=true; notifyAll(); }
        // A queued operation must start even when shutdown immediately follows its creation so it can persist interruption.
        executor.shutdown();
    }
    private static final class Stopped extends RuntimeException { }
}
