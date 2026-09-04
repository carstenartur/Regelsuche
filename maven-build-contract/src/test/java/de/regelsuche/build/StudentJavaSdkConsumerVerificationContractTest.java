package de.regelsuche.build;

import static de.regelsuche.build.MavenPomTestSupport.repositoryRoot;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StudentJavaSdkConsumerVerificationContractTest {

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

        assertFalse(
            verifier.contains("--output"),
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
}
