package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Keeps SDK dependency downloads out of artifacts without dropping evidence. */
class MavenSdkEvidenceRetentionContractTest {
    private static final List<String> RETAINED = List.of(
        "build/logs/**",
        "build/reports/**",
        "build/ai-knowledge/**",
        "build/independent-reproduction/**",
        "build/proof-carrying-showcase/**",
        "public/**",
        "**/build/reports/**",
        "**/build/test-results/**",
        "**/build/discovery-artifacts/**",
        "docs/generated/**"
    );
    private static final List<String> EXCLUDED = List.of(
        "!build/reports/student-java-sdk/isolated-gradle-user-home/**",
        "!build/reports/student-java-sdk/generated-gradle-user-home/**"
    );

    @Test
    void excludesOnlyTheTwoDisposableSdkCachesAfterAllEvidencePatterns()
            throws Exception {
        String configured = System.getProperty("regelsuche.repositoryRoot");
        assertTrue(configured != null && !configured.isBlank(),
            "Maven must expose the repository root");
        String workflow = Files.readString(Path.of(configured).resolve(
            ".github/workflows/gradle.yml"));
        String retention = between(workflow,
            "      - name: Retain Gradle verification evidence\n",
            "\n  jmh-verification:\n");
        assertTrue(retention.contains("actions/upload-artifact@"));
        assertTrue(retention.contains("include-hidden-files: true"),
            "hidden evidence must not be dropped to hide dependency caches");
        assertTrue(retention.contains("always() && !cancelled()"),
            "failed verification must still retain diagnostic evidence");
        String paths = between(retention,
            "          path: |\n", "          include-hidden-files:");
        validatePatterns(paths.lines().map(String::strip)
            .filter(line -> !line.isEmpty()).toList());
    }

    @Test
    void rejectsMissingEvidenceBroaderExclusionsAndReinclusion() {
        List<String> expected = expectedPatterns();
        validatePatterns(expected);

        List<String> missingCacheExclusion = new ArrayList<>(expected);
        missingCacheExclusion.remove(EXCLUDED.getFirst());
        assertThrows(AssertionError.class,
            () -> validatePatterns(missingCacheExclusion));

        List<String> broadExclusion = new ArrayList<>(expected);
        broadExclusion.set(broadExclusion.size() - 1,
            "!build/reports/student-java-sdk/**");
        assertThrows(AssertionError.class,
            () -> validatePatterns(broadExclusion));

        List<String> missingReports = new ArrayList<>(expected);
        missingReports.remove("**/build/test-results/**");
        assertThrows(AssertionError.class, () -> validatePatterns(missingReports));

        List<String> laterReinclusion = new ArrayList<>(expected);
        laterReinclusion.add("build/reports/**");
        assertThrows(AssertionError.class,
            () -> validatePatterns(laterReinclusion));
    }

    private static void validatePatterns(List<String> patterns) {
        assertEquals(expectedPatterns(), patterns,
            "retain every evidence category; exclude only the two SDK cache trees last");
    }

    private static List<String> expectedPatterns() {
        List<String> expected = new ArrayList<>(RETAINED);
        expected.addAll(EXCLUDED);
        return List.copyOf(expected);
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        assertTrue(from >= 0, "missing retention boundary: " + start.strip());
        from += start.length();
        int to = text.indexOf(end, from);
        assertTrue(to > from, "missing retention boundary: " + end.strip());
        return text.substring(from, to);
    }
}
