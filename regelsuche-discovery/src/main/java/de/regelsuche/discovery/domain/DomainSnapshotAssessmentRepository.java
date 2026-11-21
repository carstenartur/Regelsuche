package de.regelsuche.discovery.domain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/** Reconstructs the decision and compares every original byte before returning a retained assessment. */
public final class DomainSnapshotAssessmentRepository {
    private final DomainReceiptStore store;

    public DomainSnapshotAssessmentRepository(Path sourceDirectory) {
        store = new DomainReceiptStore(sourceDirectory, "-assessments", (source, hash, bytes) -> {
            var verified = DomainSnapshotAssessment.assess(source);
            if (!verified.contentHash().equals(hash)
                    || !Arrays.equals(bytes, verified.toCanonicalJson().getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("retained assessment differs from the reproduced source decision");
            }
        });
    }

    public void retain(DomainExportWorkspace workspace, DomainSnapshotAssessment assessment) {
        store.retain(workspace, assessment.contentHash(), assessment.toCanonicalJson());
    }

    public Optional<byte[]> find(String runId, String receiptHash) {
        return store.find(runId, receiptHash);
    }
}
