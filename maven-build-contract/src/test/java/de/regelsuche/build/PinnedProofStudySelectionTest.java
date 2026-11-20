package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PinnedProofStudySelectionTest {
    @Test void workerSelectsTheNativeDiscoveryProofAndBothRecordedStudies() throws Exception {
        Path root = Path.of("..").toRealPath();
        Path module = root.resolve("regelsuche-proof-tests");
        var suites = Files.readAllLines(module.resolve("src/test/resources/checked-proof-suites.txt"));
        String pom = Files.readString(module.resolve("pom.xml"));
        for (var registration : List.of("regelsuche-discovery de.regelsuche.discovery.domain.ScopedLogicDiscoveryDomainTest",
                "regelsuche-learning de.regelsuche.evolution.ResidualPlanComparisonTest",
                "regelsuche-benchmarks de.regelsuche.benchmarks.ScopedProofTransferStudyTest")) {
            assertTrue(suites.contains(registration), registration);
            String[] names = registration.split(" ");
            String source = names[1].replace('.', '/') + ".java";
            assertTrue(Files.isRegularFile(root.resolve(names[0]).resolve("src/test/java").resolve(source)));
            assertTrue(pom.contains("<testInclude>" + source + "</testInclude>"), source);
        }
    }
}
