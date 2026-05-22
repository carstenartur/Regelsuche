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
}
