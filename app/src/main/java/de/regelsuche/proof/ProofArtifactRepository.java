package de.regelsuche.proof;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Persistent store for proof artifacts (Lean lemma files, SMT-LIB scripts, ...).
 *
 * <p>An artifact is identified by a stable {@link String} id (typically the
 * job id followed by the worker's file suffix, e.g. {@code "abc-123.lean"}).
 * Implementations write the artifact body verbatim and can return the
 * absolute on-disk {@link Path} so downstream tooling (Lean toolchain, Z3,
 * …) can refer to the file directly.</p>
 */
public interface ProofArtifactRepository {

    /**
     * Persist {@code body} under {@code artifactId} and return the on-disk
     * {@link Path}. The id should already include a file suffix matching the
     * worker tool (e.g. {@code .lean}, {@code .smt2}, {@code .txt}).
     */
    Path store(String artifactId, String body) throws IOException;

    /** @return the artifact body if present. */
    Optional<String> read(String artifactId) throws IOException;

    /** @return the absolute on-disk path for the artifact, if present. */
    Optional<Path> pathOf(String artifactId);

    /** @return all known artifact ids. */
    List<String> listArtifactIds();

    /** Delete the artifact (no-op if not present). */
    void delete(String artifactId) throws IOException;

    // ── Per-job artifact bundle ────────────────────────────────────────────
    //
    // A proof attempt typically produces several related files: the prover
    // input ({@code proof.lean}, {@code proof.smt2}), the captured streams
    // ({@code stdout.txt}, {@code stderr.txt}) and a small {@code
    // metadata.json}. Grouping them per job keeps the {@code proofs/} root
    // tidy and matches what the Workbench UI expects.

    /**
     * Store {@code body} as {@code <jobId>/<name>}. Returns the absolute
     * on-disk path. Default delegates to the flat {@link #store} API using
     * {@code <jobId>-<name>} as the synthesized id.
     */
    default Path storeJobArtifact(String jobId, String name, String body) throws IOException {
        return store(jobId + "-" + name, body);
    }

    /** @return the body of the artifact {@code name} stored for {@code jobId}. */
    default Optional<String> readJobArtifact(String jobId, String name) throws IOException {
        return read(jobId + "-" + name);
    }

    /** @return the absolute on-disk path for a per-job artifact. */
    default Optional<Path> jobArtifactPath(String jobId, String name) {
        return pathOf(jobId + "-" + name);
    }

    /** @return all artifact names belonging to {@code jobId}, sorted. */
    default List<String> listJobArtifacts(String jobId) {
        String prefix = jobId + "-";
        return listArtifactIds().stream()
            .filter(id -> id.startsWith(prefix))
            .map(id -> id.substring(prefix.length()))
            .toList();
    }
}
