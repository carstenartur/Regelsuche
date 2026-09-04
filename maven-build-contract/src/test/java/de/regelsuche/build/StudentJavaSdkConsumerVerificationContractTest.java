package de.regelsuche.build;

import static de.regelsuche.build.MavenPomTestSupport.repositoryRoot;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class StudentJavaSdkConsumerVerificationContractTest {
    private static final Pattern CALLER_OUTPUT_OPTION = Pattern.compile(
        "(?s)\\.add_argument\\s*\\([^)]*[\"']--output[\"']"
    );

    @Test
    void verifierCannotRedirectItsRecursiveCleanupOutsideTheCheckoutBuildTree()
            throws Exception {
        Path root = repositoryRoot();
        String verifier = Files.readString(
            root.resolve("scripts/verify-student-java-sdk-consumer.py")
        );
        String task = Files.readString(
            root.resolve("gradle/student-sdk-consumer-verification.gradle")
        );

        // The child generator legitimately receives --output with a fixed path.
        // Only exposing that option in the verifier's own CLI violates this gate.
        // Python behavioral tests additionally exercise parsing and real cleanup.
        assertFalse(
            CALLER_OUTPUT_OPTION.matcher(verifier).find(),
            "the verifier must not expose a caller-controlled cleanup path"
        );
        assertFalse(
            task.contains("'--output'"),
            "the Gradle task must not redirect the verifier cleanup path"
        );
        assertTrue(
            verifier.contains(
                "OUTPUT_RELATIVE = Path(\"build/reports/student-java-sdk\")"
            ),
            "the verifier must own one fixed checkout-local output directory"
        );
        assertTrue(
            verifier.contains("candidate.is_symlink()"),
            "the fixed output path must reject symlink escapes before cleanup"
        );
    }

    @Test
    void outputOptionGuardDistinguishesCliDeclarationsFromChildCommands() {
        for (String declaration : new String[] {
            "parser.add_argument(\"--output\", type=Path)",
            "parser.add_argument('--output', type=Path)",
            "parser.add_argument(\n    '-o',\n    '--output', type=Path)"
        }) {
            assertTrue(CALLER_OUTPUT_OPTION.matcher(declaration).find(), declaration);
        }
        assertFalse(CALLER_OUTPUT_OPTION.matcher(
            "run([sys.executable, str(generator), '--output', str(starter)], root)"
        ).find());
    }
}
