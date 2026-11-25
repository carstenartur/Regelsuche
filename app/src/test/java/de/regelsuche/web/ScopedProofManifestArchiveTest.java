package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.solver.ir.SolverIr;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopedProofManifestArchiveTest {
    @Test void retainsTheTwoReceiverManifestsAcrossReopenAndRejectsRebinding(@TempDir Path directory) throws Exception {
        var json = new ObjectMapper();
        var source = directory.resolve("exports");
        var kernel = Files.createDirectory(directory.resolve("kernel"));
        var rebuild = Files.createDirectory(directory.resolve("independent"));
        String run = SolverIr.sha256("run"), obligation = SolverIr.sha256("obligation");
        String original = "obligation=" + obligation + "\nsource=" + SolverIr.sha256("source") + "\n";
        String kernelHash = SolverIr.sha256(original);
        String rebuilt = "regelsuche.lean-evidence-rebuild/v2\noriginal=" + kernelHash
            + "\nrebuild=" + SolverIr.sha256("rebuilt") + "\nreconstructed=" + SolverIr.sha256("source") + "\n";
        Files.writeString(kernel.resolve("certificate.txt"), original);
        Files.writeString(rebuild.resolve("rebuild.txt"), rebuilt);
        var report = json.createObjectNode().put("obligationHash", obligation);
        report.putObject("proof").put("kernelHash", kernelHash).put("rebuildHash", SolverIr.sha256(rebuilt));
        String hash = SolverIr.sha256(report.toString());
        new ScopedProofManifestArchive(source).retain(run, hash, report, kernel, rebuild);
        Files.delete(kernel.resolve("certificate.txt"));
        Files.delete(rebuild.resolve("rebuild.txt"));
        var reopened = new ScopedProofManifestArchive(source);
        reopened.require(run, hash, report);
        assertThrows(IllegalStateException.class, () -> reopened.require(run, SolverIr.sha256("another receipt"), report));
        report.put("obligationHash", SolverIr.sha256("different obligation"));
        assertThrows(IllegalStateException.class, () -> reopened.require(run, hash, report));
    }
}
