package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenStudentSdkStageCacheTest {
    @Test void preservesExternalDependencyBytesButNeverProductArtifacts(@TempDir Path temp) throws Exception {
        Path cache = Files.createDirectory(temp.resolve("cache"));
        String dependency = "org/junit/junit-bom/6.1.3/";
        String own = "io/github/carstenartur/regelsuche-core/0.5.4-SNAPSHOT/";
        write(cache, dependency + "junit-bom-6.1.3.pom", "external POM");
        write(cache, dependency + "_remote.repositories", "remote origin");
        write(cache, dependency + "bom.pom.lastUpdated", "failed download");
        write(cache, dependency + "bom.pom.part", "partial download");
        write(cache, dependency + "bom.pom.part.lock", "locked");
        write(cache, dependency + "resolver-status.properties", "failed metadata");
        write(cache, dependency + "maven-metadata-local.xml", "local metadata");
        write(cache, own + "regelsuche-core.jar", "stale product");
        write(cache, "io/github/carstenartur/regelsuche-parent/0.5.4-SNAPSHOT/parent.pom", "stale parent");
        write(cache, "io/github/another/library/1/library.jar", "other dependency");

        Path stage = temp.resolve("stage");
        assertEquals(3L, StudentSdkStageCache.seed(cache, stage));
        for (String path : new String[] {dependency + "junit-bom-6.1.3.pom",
                dependency + "_remote.repositories", "io/github/another/library/1/library.jar"}) {
            assertEquals(-1L, Files.mismatch(cache.resolve(path), stage.resolve(path)));
        }
        assertFalse(Files.exists(stage.resolve("io/github/carstenartur")));
        assertFalse(Files.exists(stage.resolve(dependency + "bom.pom.lastUpdated")));
        assertFalse(Files.exists(stage.resolve(dependency + "bom.pom.part")));
        assertFalse(Files.exists(stage.resolve(dependency + "bom.pom.part.lock")));
        assertFalse(Files.exists(stage.resolve(dependency + "resolver-status.properties")));
        assertFalse(Files.exists(stage.resolve(dependency + "maven-metadata-local.xml")));
        assertEquals("stale product", Files.readString(cache.resolve(own + "regelsuche-core.jar")));
    }

    @Test void coldCacheStillCreatesAnEmptyStageAndReuseIsRejected(@TempDir Path temp) throws Exception {
        Path missing = temp.resolve("missing");
        Path stage = temp.resolve("stage");
        assertEquals(0L, StudentSdkStageCache.seed(missing, stage));
        try (var entries = Files.list(stage)) {
            assertEquals(0L, entries.count());
        }
        assertThrows(IOException.class, () -> StudentSdkStageCache.seed(missing, stage));
    }

    @Test void overlappingRepositoriesCannotBeUsed(@TempDir Path temp) throws Exception {
        Path cache = Files.createDirectory(temp.resolve("cache"));
        assertThrows(IOException.class, () -> StudentSdkStageCache.seed(cache, cache));
        assertThrows(IOException.class, () -> StudentSdkStageCache.seed(cache, cache.resolve("stage")));
        assertThrows(IOException.class, () -> StudentSdkStageCache.seed(cache, temp));
        assertFalse(Files.exists(cache.resolve("stage")));
    }

    private static void write(Path root, String name, String value) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
    }
}
