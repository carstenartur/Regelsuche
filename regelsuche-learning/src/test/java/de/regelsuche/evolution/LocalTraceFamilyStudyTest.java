package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class LocalTraceFamilyStudyTest {
    @Test
    void finalCasesAreDisjointAndRetainEveryFamilyIncludingNegativeControls() {
        var sets = new HashSet<>();
        assertEquals(4, LocalTraceFamilyCases.train().size());
        assertEquals(4, LocalTraceFamilyCases.gate().size());
        assertEquals(24, LocalTraceFamilyCases.finalCases().size());
        assertEquals(6L, LocalTraceFamilyCases.finalCases().stream().map(LocalTraceFamilyCases.Case::family).distinct().count());
        Stream.of(LocalTraceFamilyCases.train(), LocalTraceFamilyCases.gate(), LocalTraceFamilyCases.finalCases())
            .flatMap(java.util.Collection::stream).forEach(input -> assertTrue(sets.add(input.task().source())));
    }

    @Test
    void alteredModelDigestCannotStartFinal(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("freeze.json"), "{}");
        var output = dir.resolve("result.json");
        assertThrows(IllegalArgumentException.class, () -> LocalTraceFamilyStudy.main(
            new String[]{"final", dir.toString(), "wrong", "0", output.toString()}));
        assertFalse(java.nio.file.Files.exists(output));
    }

    @Test
    @EnabledIfSystemProperty(named = "localTrace.phase", matches = "train|final")
    void runFrozenPublicStudy() throws Exception {
        String directory = System.getProperty("localTrace.directory");
        if ("train".equals(System.getProperty("localTrace.phase"))) {
            LocalTraceFamilyStudy.main(new String[]{"train", directory});
        } else {
            LocalTraceFamilyStudy.main(new String[]{"final", directory, System.getProperty("localTrace.digest"),
                System.getProperty("localTrace.rotation"), System.getProperty("localTrace.output")});
        }
    }
}
