package de.regelsuche.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.discovery.domain.SourceBoundReceiptFiles;
import de.regelsuche.solver.ir.SolverIr;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Archives the two manifests of a positive live receiver, indexed by run and exact HTTP receipt. */
final class ScopedProofManifestArchive {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_MANIFEST_BYTES = 32_768;
    private final SourceBoundReceiptFiles files;

    ScopedProofManifestArchive(Path directory) {
        files = new SourceBoundReceiptFiles(directory.resolveSibling(directory.getFileName() + "-scoped-proof-manifests"), 16);
    }

    void retain(String run, String hash, JsonNode report, Path kernelDirectory, Path rebuildDirectory) throws IOException {
        var manifests = JSON.createObjectNode().put("receiptHash", hash)
            .put("kernel", read(kernelDirectory.resolve("certificate.txt")))
            .put("rebuild", read(rebuildDirectory.resolve("rebuild.txt")));
        verify(hash, report, manifests);
        files.retain(run, hash, JSON.writeValueAsBytes(manifests));
    }

    void require(String run, String hash, JsonNode report) throws IOException {
        var bytes = files.find(run, hash).orElseThrow(() -> new IllegalStateException("historical Lean manifests missing"));
        verify(hash, report, JSON.readTree(bytes));
    }

    private static void verify(String hash, JsonNode report, JsonNode manifests) {
        var proof = report.path("proof");
        String kernel = manifests.path("kernel").asText();
        String rebuild = manifests.path("rebuild").asText();
        if (!hash.equals(manifests.path("receiptHash").asText())
                || !SolverIr.sha256(kernel).equals(proof.path("kernelHash").asText())
                || !SolverIr.sha256(rebuild).equals(proof.path("rebuildHash").asText())
                || !kernel.startsWith("obligation=" + report.path("obligationHash").asText() + "\n")
                || !rebuild.startsWith("regelsuche.lean-evidence-rebuild/v2\noriginal=" + proof.path("kernelHash").asText() + "\n"))
            throw new IllegalStateException("historical Lean manifests differ from the receiver observation");
    }

    private static String read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing Lean manifest");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("Lean manifest exceeds limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
