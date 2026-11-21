package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class PolynomialSourceProofTest {
    @Test void verifiesEveryFrozenAncestorAndTheOriginalMerkleBoundProtocol() throws Exception {
        Path root = Path.of("..").toRealPath();
        var builder = new CommitBuilder();
        var tree = ObjectId.fromString("3fd5ec1d1e4248692aa2d862113b1bd00b1b56ce");
        builder.setTreeId(tree);
        builder.setParentId(ObjectId.fromString("e115dca89bba32bad516d66f408e26f99fbb6149"));
        var signer = new PersonIdent("Source fixture", "archive@example.invalid", Instant.EPOCH, ZoneOffset.UTC);
        builder.setAuthor(signer);
        builder.setCommitter(signer);
        builder.setMessage("Synthetic descendant of the published registration checkpoint");
        byte[] raw = builder.build();
        try (var hasher = new ObjectInserter.Formatter()) {
            String id = hasher.idFor(Constants.OBJ_COMMIT, raw).name();
            var source = QualityAcceptanceCollection.JSON.createObjectNode().put("revision", id).put("tree", tree.name())
                .put("trackedChangesPresent", false);
            var receipt = QualityAcceptanceCollection.JSON.createObjectNode().put("schema", "regelsuche.polynomial-source-witness/v1")
                .put("revision", id);
            receipt.putObject("objects").putObject(id).put("type", "commit").put("data", Base64.getEncoder().encodeToString(raw));
            var verified = PolynomialSourceProof.verify(root, receipt, source);
            assertEquals(source.required("revision"), verified.required("revision"));
            assertEquals(PolynomialSourceProof.SCHEMA_HASH, verified.required("registeredProtocolSha256").textValue());
            for (String freeze : PolynomialSourceProof.FREEZES) assertTrue(verified.required("ancestors").has(freeze), freeze);
            var dirtySource = source.deepCopy().put("trackedChangesPresent", true);
            var dirty = assertThrows(IOException.class, () -> PolynomialSourceProof.verify(root, receipt, dirtySource));
            assertEquals("polynomial proof requires an unchanged tracked source", dirty.getMessage());
            var substituted = receipt.deepCopy();
            substituted.put("revision", "0".repeat(40));
            assertThrows(Exception.class, () -> PolynomialSourceProof.verify(root, substituted, source));
            receipt.withObject("objects").remove(id);
            assertThrows(Exception.class, () -> PolynomialSourceProof.verify(root, receipt, source));
        }
    }

    @Test @EnabledIfSystemProperty(named="regelsuche.polynomial.registerSource", matches="true")
    void captureSourceForTheGitFreeExperiments() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.polynomial.root", "..")).toRealPath();
        var source = QualityCollectionCheckout.identity(root);
        var receipt = PolynomialSourceProof.prepare(root);
        PolynomialSourceProof.verify(root, receipt, source);
        Path output = Path.of(System.getProperty("regelsuche.polynomial.registration"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, receipt.toString() + "\n", StandardOpenOption.CREATE_NEW);
    }
}
