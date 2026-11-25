package de.regelsuche.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleStatus;
import de.regelsuche.discovery.domain.DomainExportWorkspace;
import de.regelsuche.discovery.domain.DomainExportWorkspaceRepository;
import de.regelsuche.discovery.domain.SourceBoundReceiptFiles;
import de.regelsuche.discovery.domain.ScopedLogicWorkspaceCheck;
import de.regelsuche.discovery.domain.ScopedLogicDiscoveryDomain.Certificate;
import de.regelsuche.solver.portfolio.LeanSourceRenderer;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/** Repository for historical receiver observations. Reopening a receipt never invokes Lean. */
final class ScopedCheckReceipts {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path directory;
    private final SourceBoundReceiptFiles files;

    ScopedCheckReceipts(Path directory) {
        this.directory = directory;
        files = new SourceBoundReceiptFiles(directory.resolveSibling(directory.getFileName() + "-scoped-checks"), 16);
    }

    String retain(DomainExportWorkspace workspace, byte[] bytes, Optional<Certificate> certificate) throws IOException {
        var retained = new DomainExportWorkspaceRepository(directory).find(workspace.runId()).orElseThrow();
        if (!retained.contentHash().equals(workspace.contentHash())) throw new IllegalStateException("scoped source changed");
        String hash = digest(bytes);
        if (certificate.isPresent()) {
            var proof = certificate.orElseThrow();
            new ScopedProofManifestArchive(directory).retain(workspace.runId(), hash, JSON.readTree(bytes),
                proof.kernelDirectory(), proof.rebuildDirectory());
        }
        files.retain(workspace.runId(), hash, bytes);
        return hash.substring(7);
    }

    Optional<byte[]> find(String runId, String hash) throws IOException {
        var workspace = new DomainExportWorkspaceRepository(directory).find(runId);
        var retained = files.find(runId, hash);
        if (workspace.isEmpty() || retained.isEmpty()) return Optional.empty();
        byte[] bytes = retained.orElseThrow();
        if (!hash.equals(digest(bytes))) throw new IllegalStateException("scoped receipt bytes changed");
        var report = JSON.readTree(bytes);
        checkBinding(workspace.orElseThrow(), report);
        if (report.at("/proof/status").asText().equals("CONFIRMED"))
            new ScopedProofManifestArchive(directory).require(runId, hash, report);
        return retained;
    }

    private static void checkBinding(DomainExportWorkspace workspace, JsonNode report) {
        var source = report.path("source");
        var renderer = new LeanSourceRenderer();
        try {
            var bound = ScopedLogicWorkspaceCheck.inspect(workspace, source.path("candidateHash").asText(),
                obligation -> renderer.render(obligation).supported());
            if (!report.path("schema").asText().equals("regelsuche.scoped-workspace-candidate-check/v1")
                    || !source.equals(JSON.valueToTree(bound.source()))
                    || !report.path("proposalHash").asText().equals(bound.proposal().hash())
                    || !report.path("obligationHash").asText().equals(bound.proposal().obligation().contentHash())
                    || !report.path("counterexample").equals(JSON.valueToTree(bound.counterexample()))
                    || !report.path("retainedWitness").asText().equals(bound.retainedWitness().name()))
                throw new IllegalStateException("scoped receipt differs from its bound candidate");
            checkRecordedProof(report.path("proof"), bound.counterexample().status());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("invalid retained scoped candidate", invalid);
        }
    }

    private static void checkRecordedProof(JsonNode proof, CounterexampleStatus counterexample) {
        String status = proof.path("status").asText();
        if (!proof.path("backendConfigured").isBoolean()
                || !Set.of("NOT_RUN", "CONFIRMED", "REFUTED", "UNSUPPORTED", "INCONCLUSIVE").contains(status))
            throw new IllegalStateException("invalid historical proof status");
        boolean attempted = counterexample == CounterexampleStatus.NONE_FOUND;
        if (attempted == status.equals("NOT_RUN") ||
                (attempted && !proof.get("backendConfigured").booleanValue() && !status.equals("UNSUPPORTED")))
            throw new IllegalStateException("historical proof status differs from receiver admission");
        boolean confirmed = status.equals("CONFIRMED");
        if (confirmed && (!proof.path("kernelHash").asText().matches("sha256:[0-9a-f]{64}")
                || !proof.path("rebuildHash").asText().matches("sha256:[0-9a-f]{64}")))
            throw new IllegalStateException("historical kernel or rebuild receipt missing");
        if (!confirmed && (proof.has("kernelHash") || proof.has("rebuildHash")))
            throw new IllegalStateException("unexpected historical kernel receipts");
    }

    private static String digest(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
