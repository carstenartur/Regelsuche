package de.regelsuche.quality.aggregate;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenSlowTestModuleCoverageTest {
    @Test void configuredReportsCoverBothCurrentReactorProfiles() throws Exception {
        MavenSlowTestModuleContract.verify(Path.of(System.getProperty("regelsuche.repositoryRoot")));
    }

    @Test void aNewJavaModuleCannotSilentlyDisappearFromEitherReport(@TempDir Path root) throws Exception {
        writePom(root, """
            <modules><module>library</module><module>bom</module><module>regelsuche-quality-aggregate</module></modules>
            <profiles>
              <profile><id>product-reactor</id><modules><module>app</module></modules></profile>
              <profile><id>full</id><modules><module>app</module><module>integration</module></modules></profile>
            </profiles>
            """);
        for (String module : Set.of("library", "app", "integration")) writePom(root.resolve(module), "");
        writePom(root.resolve("bom"), "<packaging>pom</packaging>");
        Path aggregate = root.resolve("regelsuche-quality-aggregate");
        String declarations = """
            <properties><regelsuche.slow-test-report.modules>library,app</regelsuche.slow-test-report.modules></properties>
            <profiles><profile><id>full</id><properties>
              <regelsuche.slow-test-report.modules>library,app,integration</regelsuche.slow-test-report.modules>
            </properties></profile></profiles>
            """;
        writePom(aggregate, declarations);
        MavenSlowTestModuleContract.verify(root);
        assertEquals(Set.of("library", "app"), MavenSlowTestModuleContract.expectedModules(root, "product-reactor"));
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("<module>library</module>",
            "<module>library</module><module>new-sdk</module>"));
        writePom(root.resolve("new-sdk"), "");
        assertTrue(assertThrows(IllegalStateException.class, () -> MavenSlowTestModuleContract.verify(root))
            .getMessage().contains("new-sdk"));
        // Updating only the default report still leaves the full profile uncovered.
        writePom(aggregate, declarations.replace("library,app</", "library,new-sdk,app</"));
        assertTrue(assertThrows(IllegalStateException.class, () -> MavenSlowTestModuleContract.verify(root))
            .getMessage().contains("full slow-test module mismatch"));
        writePom(aggregate, declarations.replace("library,app", "library,new-sdk,app"));
        MavenSlowTestModuleContract.verify(root);
    }

    private static void writePom(Path directory, String body) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"),
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">" + body + "</project>");
    }
}
