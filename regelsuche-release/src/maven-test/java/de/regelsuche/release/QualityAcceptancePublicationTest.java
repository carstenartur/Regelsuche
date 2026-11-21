package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Binds the independently produced reports in the final CI/publication lane. */
class QualityAcceptancePublicationTest {
    @Test @EnabledIfSystemProperty(named="regelsuche.qualityAcceptance.collect", matches="true")
    void completeEvidenceIsRequiredForPublication() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.qualityAcceptance.root", "..")).toAbsolutePath().normalize();
        Path output = Path.of(System.getProperty("regelsuche.qualityAcceptance.output", "build/reports/quality/acceptance-evidence"));
        var index = QualityAcceptanceCollection.collect(root, output, QualityCollectionCheckout.identity(root));
        assertEquals("COMPLETE", index.required("completeness").textValue(), index.required("problems")::toString);
    }

    @Test @EnabledIfSystemProperty(named="regelsuche.qualityAcceptance.registerArchive", matches="true")
    void registerExactSourceArchiveTree() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.qualityAcceptance.root", "..")).toAbsolutePath().normalize();
        QualityArchiveRegistration.write(root, Path.of(System.getProperty("regelsuche.qualityAcceptance.registration")));
    }

    @Test @EnabledIfSystemProperty(named="regelsuche.qualityAcceptance.verifyArchive", matches="true")
    void actualGitFreeSourceMatchesTheRegisteredCommit() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.qualityAcceptance.root", "..")).toAbsolutePath().normalize();
        assertFalse(java.nio.file.Files.exists(root.resolve(".git")));
        var identity = QualityArchiveRegistration.identity(root);
        assertEquals(System.getProperty("regelsuche.qualityAcceptance.revision"), identity.required("revision").textValue());
        assertFalse(identity.required("trackedChangesPresent").booleanValue(), identity::toString);
    }
}
