package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * Guards the migration from selected Git-free test slices to a full Maven
 * lifecycle without allowing a partial reactor or skipped evidence to stand
 * in for the complete product build.
 */
class MavenGitFreeFullLifecycleContractTest {
    private static final String STEP =
        "      - name: Reproduce full Maven lifecycle from a Git-free source archive";
    private static final String NEXT =
        "      - name: Retain Maven product verification evidence";
    private static final String FULL_COMMAND =
        "mvn --batch-mode --no-transfer-progress -Pfull verify";

    @Test
    void fullGitFreeProductLifecycleIsRequired() throws IOException {
        String workflow = Files.readString(MavenPomTestSupport.repositoryRoot()
            .resolve(".github/workflows/gradle.yml"));
        verifyFullGitFreeLifecycle(workflow);
    }

    @Test
    void selectedModuleSubsetCannotPretendToBeFullLifecycle() {
        String valid = fixture();
        verifyFullGitFreeLifecycle(valid);
        assertThrows(AssertionError.class,
            () -> verifyFullGitFreeLifecycle(valid.replace(FULL_COMMAND,
                "mvn --batch-mode --no-transfer-progress -Pfull "
                    + "-pl regelsuche-core -am verify")));
        assertThrows(AssertionError.class,
            () -> verifyFullGitFreeLifecycle(valid.replace(FULL_COMMAND,
                "mvn --batch-mode --no-transfer-progress -Pfull "
                    + "-DskipTests verify")));
    }

    @Test
    void missingProvenanceOrReportRechecksAreRejected() {
        String valid = fixture();
        assertThrows(AssertionError.class,
            () -> verifyFullGitFreeLifecycle(valid.replace(
                "export REGELSUCHE_PREPARATION_REVISION=",
                "export MISSING_PREPARATION_REVISION=")));
        assertThrows(AssertionError.class,
            () -> verifyFullGitFreeLifecycle(valid.replace(
                "-Dregelsuche.sourceArchiveMode=true", "-DskipTests")));
        assertThrows(AssertionError.class,
            () -> verifyFullGitFreeLifecycle(valid.replace(
                "-Dregelsuche.sourceArchiveContainerMode=true", "-DskipITs")));
        assertThrows(AssertionError.class,
            () -> verifyFullGitFreeLifecycle(valid.replace(
                "git archive --format=tar HEAD | tar -xf - -C \"$source_root\"",
                "cp -a . \"$source_root\"")));
    }

    private static String fixture() {
        return STEP + "\n"
            + "        run: |\n"
            + "          git archive --format=tar HEAD | tar -xf - -C \"$source_root\"\n"
            + "          export REGELSUCHE_PREPARATION_REVISION=\"source\"\n"
            + "          " + FULL_COMMAND + "\n"
            + "          -pl maven-build-contract \\\n"
            + "          -Dregelsuche.sourceArchiveMode=true \\\n"
            + "          -Dregelsuche.sourceArchiveContainerMode=true \\\n"
            + "          -DfailIfNoTests=true test\n"
            + NEXT + "\n";
    }

    private static void verifyFullGitFreeLifecycle(String workflow) {
        int start = workflow.indexOf(STEP);
        int end = start < 0 ? -1 : workflow.indexOf(NEXT, start + STEP.length());
        assertTrue(start >= 0 && end > start, "missing full source-archive step");
        String block = workflow.substring(start, end);
        assertTrue(block.contains("git archive --format=tar HEAD | tar -xf - -C \"$source_root\""),
            "source archive must come from Git rather than copying .git");
        assertTrue(block.contains("export REGELSUCHE_PREPARATION_REVISION="),
            "git-free tests need the authentic checkout revision");
        assertTrue(block.lines().map(String::trim).anyMatch(FULL_COMMAND::equals),
            "unfiltered mvn -Pfull verify must run before report replay");
        assertTrue(block.contains("-pl maven-build-contract"),
            "final report checks need their own Maven/JUnit pass");
        assertTrue(block.contains("-Dregelsuche.sourceArchiveMode=true"),
            "unit reports must be checked for executed tests");
        assertTrue(block.contains("-Dregelsuche.sourceArchiveContainerMode=true"),
            "Postgres/Neo4j Failsafe reports must be checked");
        assertTrue(block.contains("-DfailIfNoTests=true test"),
            "the report checker cannot be silently skipped");
    }
}
