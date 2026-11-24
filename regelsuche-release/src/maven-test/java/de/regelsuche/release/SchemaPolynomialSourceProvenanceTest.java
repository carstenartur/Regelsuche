package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaPolynomialSourceProvenanceTest {
    @Test void verifiesTheActualRegisteredArchiveAndOriginalHistoricalProtocol(@TempDir Path directory) throws Exception {
        Path source = fixture(directory, "e115dca89bba32bad516d66f408e26f99fbb6149");
        var identity = PolynomialArchiveProvenanceMain.verify(source);
        var verified = admit(source);
        assertEquals(identity.required("revision"), verified.required("revision"));
        assertEquals(identity.required("tree"), verified.required("tree"));
        assertEquals(identity.required("trackedChangesPresent"), verified.required("trackedChangesPresent"));
        assertEquals(PolynomialSourceProof.SCHEMA_HASH, verified.required("registeredProtocolSha256").textValue());
        for (String freeze : PolynomialSourceProof.FREEZES)
            assertEquals(com.fasterxml.jackson.databind.node.BooleanNode.TRUE, verified.required("ancestors").required(freeze), freeze);
        assertFalse(verified.required("ancestors").has("5bc9e21e61131be287b89060ef39f93df111dc25"));

        Path witness = source.resolve(PolynomialSourceProof.RECEIPT);
        byte[] proof = Files.readAllBytes(witness);
        Files.delete(witness);
        assertThrows(Exception.class, () -> admit(source));
        Files.write(witness, proof);
        var substituted = QualityAcceptanceCollection.JSON.readTree(proof);
        ((com.fasterxml.jackson.databind.node.ObjectNode) substituted).put("revision", "0".repeat(40));
        Files.writeString(witness, substituted.toString());
        assertThrows(Exception.class, () -> admit(source));
        Files.write(witness, proof);
        var malformed = QualityAcceptanceCollection.JSON.readTree(proof);
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) malformed.required("objects").elements().next();
        object.put("data", Base64.getEncoder().encodeToString("substituted commit bytes".getBytes(StandardCharsets.UTF_8)));
        Files.writeString(witness, malformed.toString());
        assertThrows(Exception.class, () -> admit(source));
        Files.write(witness, proof);

        Path script = source.resolve("script.sh");
        byte[] original = Files.readAllBytes(script);
        Files.writeString(script, "changed\n");
        assertThrows(Exception.class, () -> admit(source));
        Files.write(script, original);
        var permissions = Files.getPosixFilePermissions(script);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(Exception.class, () -> admit(source));
        Files.setPosixFilePermissions(script, permissions);
        Path extra = Files.writeString(source.resolve("injected.py"), "unregistered\n");
        assertThrows(Exception.class, () -> admit(source));
        Files.delete(extra);
        assertEquals(verified, admit(source));
        Files.delete(source.resolve(QualityArchiveRegistration.RECEIPT));
        assertThrows(Exception.class, () -> admit(source));
    }

    @Test void rejectsARealSourceCommitWithoutThePublishedAncestry(@TempDir Path directory) throws Exception {
        Path source = fixture(directory, "0".repeat(40));
        assertDoesNotThrow(() -> PolynomialArchiveProvenanceMain.verify(source));
        assertThrows(Exception.class, () -> admit(source));
    }

    private static JsonNode admit(Path source) throws Exception {
        var output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            PolynomialArchiveProvenanceMain.main(new String[]{"schema-freeze", source.toString()});
        } finally {
            System.setOut(original);
        }
        return QualityAcceptanceCollection.JSON.readTree(output.toByteArray());
    }

    private static Path fixture(Path directory, String parent) throws Exception {
        Path checkout = Files.createDirectory(directory.resolve("checkout"));
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.createDirectories(checkout.resolve(PolynomialSourceProof.ORIGINAL).getParent());
        Files.copy(Path.of("..").toRealPath().resolve(PolynomialSourceProof.ORIGINAL), checkout.resolve(PolynomialSourceProof.ORIGINAL));
        Files.writeString(checkout.resolve(".gitignore"), "build/\n");
        Files.writeString(checkout.resolve("script.sh"), "#!/bin/sh\n");
        for (Path file : new Path[]{Path.of(".gitignore"), Path.of("script.sh"), PolynomialSourceProof.ORIGINAL}) {
            Files.createDirectories(source.resolve(file).getParent());
            Files.copy(checkout.resolve(file), source.resolve(file));
        }
        try (var git = Git.init().setDirectory(checkout.toFile()).call()) {
            git.add().addFilepattern(".").call();
            var first = git.commit().setMessage("fixture tree").setAuthor("Test", "test@example.org").call();
            var commit = new CommitBuilder();
            commit.setTreeId(first.getTree());
            commit.setParentId(ObjectId.fromString(parent));
            commit.setAuthor(first.getAuthorIdent());
            commit.setCommitter(first.getCommitterIdent());
            commit.setMessage("registered fixture descendant");
            byte[] raw = commit.build();
            ObjectId revision;
            try (var objects = git.getRepository().newObjectInserter()) {
                revision = objects.insert(Constants.OBJ_COMMIT, raw);
                objects.flush();
            }
            var head = git.getRepository().updateRef(Constants.HEAD);
            head.setNewObjectId(revision);
            head.setForceUpdate(true);
            head.update();
            QualityArchiveRegistration.write(checkout, source.resolve(QualityArchiveRegistration.RECEIPT));
            var witness = QualityAcceptanceCollection.JSON.createObjectNode()
                .put("schema", "regelsuche.polynomial-source-witness/v1").put("revision", revision.name());
            witness.putObject("objects").putObject(revision.name()).put("type", "commit")
                .put("data", Base64.getEncoder().encodeToString(raw));
            Files.writeString(source.resolve(PolynomialSourceProof.RECEIPT), witness.toString());
        }
        return source;
    }
}
