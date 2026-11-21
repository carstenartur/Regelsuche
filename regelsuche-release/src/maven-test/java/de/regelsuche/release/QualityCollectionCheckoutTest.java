package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QualityCollectionCheckoutTest {
    @Test void exportedRegistrationRebuildsTheWholeTreeAndDetectsTrackedChanges(@TempDir Path temp) throws Exception {
        Path checkout = Files.createDirectory(temp.resolve("repo"));
        Path source = Files.createDirectory(temp.resolve("source"));
        try (var git = Git.init().setDirectory(checkout.toFile()).call()) {
            Files.writeString(checkout.resolve(".gitattributes"), "stamp export-subst\n");
            Files.writeString(checkout.resolve("stamp"), "$Format:%H$\n");
            Files.writeString(checkout.resolve("source.txt"), "registered source\n");
            git.add().addFilepattern(".").call();
            var commit = git.commit().setMessage("registered source").setAuthor("Fixture", "archive@example.invalid")
                .setCommitter("Fixture", "archive@example.invalid").call();
            var expected = QualityCollectionCheckout.identity(checkout);
            assertEquals(commit.name(), expected.required("revision").textValue());
            assertFalse(expected.required("trackedChangesPresent").booleanValue());
            Files.copy(checkout.resolve(".gitattributes"), source.resolve(".gitattributes"));
            Files.copy(checkout.resolve("source.txt"), source.resolve("source.txt"));
            Files.writeString(source.resolve("stamp"), commit.name() + "\n");
            Path proof = source.resolve(QualityArchiveRegistration.RECEIPT);
            QualityArchiveRegistration.write(checkout, proof);
            Files.writeString(source.resolve("untracked.txt"), "outside tracked scope");
            assertEquals(expected, QualityCollectionCheckout.identity(source));
            Files.writeString(source.resolve("source.txt"), "edited\n");
            assertTrue(QualityCollectionCheckout.identity(source).required("trackedChangesPresent").booleanValue());
            var registration = (com.fasterxml.jackson.databind.node.ObjectNode) QualityAcceptanceCollection.JSON.readTree(proof.toFile());
            registration.withArray("members").remove(0);
            Files.writeString(proof, registration.toString());
            assertThrows(IOException.class, () -> QualityCollectionCheckout.identity(source));
        }
    }

    @Test void rejectsDirtySourcesBeforeAdmittingAPolynomialReceipt(@TempDir Path temp) throws Exception {
        Path checkout = Files.createDirectory(temp.resolve("repo"));
        Path source = Files.createDirectory(temp.resolve("source"));
        try (var git = Git.init().setDirectory(checkout.toFile()).call()) {
            Files.writeString(checkout.resolve("source.txt"), "registered source\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("registered source").setAuthor("Fixture", "archive@example.invalid")
                .setCommitter("Fixture", "archive@example.invalid").call();
            Files.copy(checkout.resolve("source.txt"), source.resolve("source.txt"));
            QualityArchiveRegistration.write(checkout, source.resolve(QualityArchiveRegistration.RECEIPT));
            for (Path dirtyRoot : java.util.List.of(checkout, source)) {
                Files.writeString(dirtyRoot.resolve("source.txt"), "altered source\n");
                var identity = QualityCollectionCheckout.identity(dirtyRoot);
                assertTrue(identity.required("trackedChangesPresent").booleanValue());
                var problem = assertThrows(IOException.class, () -> PolynomialSourceProof.verify(dirtyRoot,
                    QualityAcceptanceCollection.JSON.createObjectNode(), identity));
                assertEquals("polynomial proof requires an unchanged tracked source", problem.getMessage());
            }
        }
    }
}
