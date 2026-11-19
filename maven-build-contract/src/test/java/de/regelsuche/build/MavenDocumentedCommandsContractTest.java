package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The repository intentionally uses an installed Maven 3.9 toolchain and does
 * not ship mvnw. Commands in current developer guidance must actually run
 * from an ordinary checkout, without asking users to invoke a missing file.
 */
class MavenDocumentedCommandsContractTest {
    private static final Map<String, String> COMMANDS = Map.of(
        "docs/equality-saturation.md",
        "mvn -pl regelsuche-egraph,regelsuche-search -am test",
        "docs/research/work-replacing-search.md",
        "mvn -pl regelsuche-search -am "
            + "-Dtest=MoveSearchDominanceTest,MoveSearchVisitsTest "
            + "-Dsurefire.failIfNoSpecifiedTests=false test",
        "docs/research/strategies.md",
        "mvn -pl regelsuche-math-algorithms -am "
            + "-Dtest=RationalDenominatorReductionTest "
            + "-Dsurefire.failIfNoSpecifiedTests=false test"
    );

    @Test
    void activeExamplesUseTheAvailableMavenExecutable() throws IOException {
        var root = MavenPomTestSupport.repositoryRoot();
        assertFalse(Files.exists(root.resolve("mvnw")),
            "This contract assumes Maven 3.9 is installed, not a repository wrapper");
        for (var entry : COMMANDS.entrySet()) {
            var path = root.resolve(entry.getKey());
            assertTrue(Files.isRegularFile(path), "missing developer guidance: " + entry.getKey());
            verifyExample(Files.readString(path), entry.getValue());
        }
    }

    @Test
    void staleWrapperOrMissingReactorCommandIsRejected() {
        String command = COMMANDS.get("docs/equality-saturation.md");
        verifyExample("Run `" + command + "`.", command);
        assertThrows(AssertionError.class, () -> verifyExample(
            "Run `./mvnw -pl regelsuche-egraph,regelsuche-search -am test`.", command));
        assertThrows(AssertionError.class, () -> verifyExample(
            "Run `mvn test`.", command));
    }

    private static void verifyExample(String document, String expectedCommand) {
        assertFalse(document.contains("./mvnw"),
            "A missing Maven wrapper cannot be the required documented command");
        assertTrue(document.contains(expectedCommand),
            "The tested Maven module/reactor command must remain documented");
    }
}
