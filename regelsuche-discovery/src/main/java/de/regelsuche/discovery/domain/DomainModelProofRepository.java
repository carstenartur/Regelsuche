package de.regelsuche.discovery.domain;

import java.nio.file.Path;
import java.util.Optional;

/** Retains checker-issued model receipts and revalidates historical receipts before download. */
public final class DomainModelProofRepository {
    public static final int MAX_RECEIPT_BYTES = DomainReceiptStore.MAX_RECEIPT_BYTES;
    public static final int MAX_RECEIPTS_PER_EXPORT = DomainReceiptStore.MAX_RECEIPTS_PER_EXPORT;
    private final DomainReceiptStore store;

    public DomainModelProofRepository(Path sourceDirectory) {
        store = new DomainReceiptStore(sourceDirectory, "-model-proofs", DomainModelProof::requireRetained);
    }

    public void retain(DomainExportWorkspace workspace, DomainModelProof.Receipt receipt) {
        store.retain(workspace, receipt.contentHash(), receipt.toCanonicalJson());
    }

    public Optional<byte[]> find(String runId, String receiptHash) {
        return store.find(runId, receiptHash);
    }
}
