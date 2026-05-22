package de.regelsuche.proof;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory {@link ProofJobRepository}.
 */
public final class InMemoryProofJobRepository implements ProofJobRepository {

    private final Map<String, ProofJob> jobs = new ConcurrentHashMap<>();

    @Override
    public void save(ProofJob job) {
        jobs.put(job.id(), job);
    }

    @Override
    public Optional<ProofJob> findById(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public List<ProofJob> findAll() {
        return List.copyOf(jobs.values());
    }

    @Override
    public List<ProofJob> findByStatus(ProofJobStatus status) {
        return jobs.values().stream()
            .filter(j -> j.status() == status)
            .toList();
    }

    @Override
    public Optional<ProofJob> findNextQueued() {
        return jobs.values().stream()
            .filter(j -> j.status() == ProofJobStatus.QUEUED
                || j.status() == ProofJobStatus.RETRYING)
            .min(Comparator
                .comparingInt(ProofJob::priority)
                .thenComparing(ProofJob::createdAt));
    }

    @Override
    public void delete(String id) {
        jobs.remove(id);
    }

    /** @return a snapshot of all jobs sorted by creation time. */
    public List<ProofJob> listSorted() {
        List<ProofJob> sorted = new ArrayList<>(jobs.values());
        sorted.sort(Comparator.comparing(ProofJob::createdAt));
        return sorted;
    }
}
