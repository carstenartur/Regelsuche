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
    private static final String PREPARE =
        "      - name: Prepare the exact Git-free source archive";
    private static final String STEP =
        "      - name: Reproduce full Maven lifecycle from a Git-free source archive";
    private static final String NEXT =
        "      - name: Retain Maven product verification evidence";
    private static final String ARCHIVE_ROOT = "${{ steps.source_archive.outputs.root }}";
    private static final String FULL_COMMAND =
        "mvn --batch-mode --no-transfer-progress -Pfull verify";

    @Test
    void fullGitFreeProductLifecycleIsRequired() throws IOException {
        String workflow = Files.readString(MavenPomTestSupport.repositoryRoot()
            .resolve(".github/workflows/gradle.yml"));
        verifyFullGitFreeLifecycle(workflow);
    }

    @Test
    void gitFreeRunRetainsItsOwnRawReportsEvenAfterFailure() throws IOException {
        String workflow = Files.readString(MavenPomTestSupport.repositoryRoot().resolve(".github/workflows/gradle.yml"));
        String step = workflow.substring(workflow.indexOf(STEP), workflow.indexOf(NEXT));
        String preparation = workflow.substring(workflow.indexOf(PREPARE), workflow.indexOf(STEP));
        assertTrue(preparation.contains("id: source_archive"));
        assertTrue(preparation.contains("echo \"root=$source_root\" >> \"$GITHUB_OUTPUT\""));
        assertTrue(step.contains("always() && !cancelled() && steps.source_archive.outputs.root != ''"));
        assertTrue(step.contains("name: git-free-maven-verification"));
        for (String reports : java.util.List.of("surefire-reports", "failsafe-reports", "reports")) {
            assertTrue(step.contains(ARCHIVE_ROOT + "/**/target/" + reports + "/**"));
        }
        assertTrue(step.contains("if-no-files-found: error"));
    }

    @Test
    void archiveCommitWitnessAndSuccessfulPreflightAreRetainedAlongsideTheFullReactor() throws IOException {
        String workflow = Files.readString(MavenPomTestSupport.repositoryRoot().resolve(".github/workflows/gradle.yml"));
        String step = workflow.substring(workflow.indexOf(STEP), workflow.indexOf(NEXT));
        String preparation = workflow.substring(workflow.indexOf(PREPARE), workflow.indexOf(STEP));
        assertTrue(preparation.contains("-Dregelsuche.qualityAcceptance.registerArchive=true")
            && preparation.contains("$source_root/build/authority-evidence/quality-source-tree.json"),
            "register the source receipt in the directory prepared by the archive step");
        int sourceRoot = step.indexOf("cd \"${REGELSUCHE_SOURCE_ARCHIVE_ROOT:?}\"");
        int verification = step.indexOf("-Dregelsuche.qualityAcceptance.verifyArchive=true");
        int receipt = step.indexOf("build/authority-evidence/quality-source-verification.xml");
        int reactor = step.indexOf(FULL_COMMAND);
        assertTrue(sourceRoot >= 0 && verification > sourceRoot && receipt > verification && reactor > receipt,
            "verify the archive bytes and retain the receipt before running the full reactor inside it");
        assertTrue(step.contains("-Dregelsuche.qualityAcceptance.revision=$REGELSUCHE_AUTHORITY_GITHUB_SHA"));
        for (String file : java.util.List.of("quality-source-tree.json", "quality-source-verification.xml")) {
            assertTrue(step.contains(ARCHIVE_ROOT + "/build/authority-evidence/" + file));
        }
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
        assertThrows(AssertionError.class, () -> verifyFullGitFreeLifecycle(valid.replace(
            "REGELSUCHE_SOURCE_ARCHIVE_ROOT: " + ARCHIVE_ROOT,
            "REGELSUCHE_SOURCE_ARCHIVE_ROOT: ${{ github.workspace }}")));
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
        return PREPARE + "\n"
            + "        run: |\n"
            + "          git archive --format=tar HEAD | tar -xf - -C \"$source_root\"\n"
            + STEP + "\n"
            + "        env:\n"
            + "          REGELSUCHE_SOURCE_ARCHIVE_ROOT: " + ARCHIVE_ROOT + "\n"
            + "        run: |\n"
            + "          export REGELSUCHE_PREPARATION_REVISION=\"source\"\n"
            + "          " + FULL_COMMAND + "\n"
            + "          -pl maven-build-contract \\\n"
            + "          -Dregelsuche.sourceArchiveMode=true \\\n"
            + "          -Dregelsuche.sourceArchiveContainerMode=true \\\n"
            + "          -DfailIfNoTests=true test\n"
            + NEXT + "\n";
    }

    private static void verifyFullGitFreeLifecycle(String workflow) {
        int setup = workflow.indexOf(PREPARE);
        int start = workflow.indexOf(STEP);
        int end = start < 0 ? -1 : workflow.indexOf(NEXT, start + STEP.length());
        assertTrue(setup >= 0 && start > setup && end > start, "missing full source-archive step");
        String preparation = workflow.substring(setup, start);
        String block = workflow.substring(start, end);
        assertTrue(preparation.contains("git archive --format=tar HEAD | tar -xf - -C \"$source_root\""),
            "source archive must come from Git rather than copying .git");
        assertTrue(block.contains("REGELSUCHE_SOURCE_ARCHIVE_ROOT: " + ARCHIVE_ROOT),
            "the full source lifecycle must use the same directory prepared by the archive step");
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
