package de.regelsuche.discovery.domain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;
import java.util.Optional;

/** Bounded, immutable storage shared by source-bound assessment and model receipts. */
final class DomainReceiptStore {
    public static final int MAX_RECEIPT_BYTES = SourceBoundReceiptFiles.MAX_RECEIPT_BYTES;
    public static final int MAX_RECEIPTS_PER_EXPORT = 16;
    private final Path sourceDirectory;
    private final SourceBoundReceiptFiles files;

    interface Check { void verify(DomainExportWorkspace source, String hash, byte[] bytes); }
    private final Check check;

    DomainReceiptStore(Path sourceDirectory, String suffix, Check check) {
        this.check = Objects.requireNonNull(check);
        Path source = Objects.requireNonNull(sourceDirectory, "sourceDirectory").toAbsolutePath().normalize();
        if (source.getFileName() == null) throw new IllegalArgumentException("a named source repository is required");
        this.sourceDirectory = source;
        files = new SourceBoundReceiptFiles(source.resolveSibling(source.getFileName() + suffix), MAX_RECEIPTS_PER_EXPORT);
    }

    /** Called only with checker-issued canonical receipts. */
    void retain(DomainExportWorkspace workspace, String hash, String canonicalJson) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(canonicalJson, "canonicalJson");
        byte[] bytes = canonicalJson.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RECEIPT_BYTES) throw new IllegalArgumentException("downstream receipt exceeds 1 MiB");
        var value = DomainExportWorkspace.read(bytes);
        if (!DomainDownstreamValidation.sourceBinding(workspace).equals(value.path("source"))) {
            throw new IllegalArgumentException("downstream receipt belongs to another source");
        }
        try {
            var retained = new DomainExportWorkspaceRepository(sourceDirectory).find(workspace.runId()).orElseThrow(() ->
                new IllegalStateException("downstream source export is not retained"));
            if (!workspace.toCanonicalJson().equals(retained.toCanonicalJson())) throw new IllegalArgumentException("downstream source workspace differs");
            files.retain(workspace.runId(), hash, bytes);
        } catch (IOException | IllegalArgumentException | DomainDiscoveryExportVerifier.ExportVerificationException exception) {
            throw new IllegalStateException("unable to retain downstream receipt", exception);
        }
    }

    /** Successful historical proofs are reproduced before returning their exact original bytes. */
    public Optional<byte[]> find(String runId, String receiptHash) {
        // Reject caller-controlled path material before entering the retained-artifact error boundary.
        DomainCanonical.requireSha256(runId, "runId");
        DomainCanonical.requireSha256(receiptHash, "receiptHash");
        try {
            var source = new DomainExportWorkspaceRepository(sourceDirectory).find(runId);
            var bytes = files.find(runId, receiptHash);
            if (source.isEmpty() || bytes.isEmpty()) return Optional.empty();
            check.verify(source.get(), receiptHash, bytes.get());
            return bytes;
        } catch (IOException | IllegalArgumentException | DomainDiscoveryExportVerifier.ExportVerificationException exception) {
            throw new IllegalStateException("invalid retained downstream receipt", exception);
        }
    }

}
