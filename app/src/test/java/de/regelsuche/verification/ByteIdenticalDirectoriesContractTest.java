package de.regelsuche.verification;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ByteIdenticalDirectoriesContractTest {

    private static final String LEFT_PROPERTY =
        "regelsuche.byteIdentity.left";
    private static final String RIGHT_PROPERTY =
        "regelsuche.byteIdentity.right";
    private static final String INCLUDE_PROPERTY =
        "regelsuche.byteIdentity.include";

    @Test
    void configuredEvidenceDirectoriesAreByteIdentical() throws Exception {
        String left = System.getProperty(LEFT_PROPERTY);
        String right = System.getProperty(RIGHT_PROPERTY);
        String include = System.getProperty(INCLUDE_PROPERTY);

        boolean configured = Stream.of(left, right, include)
            .anyMatch(value -> value != null && !value.isBlank());
        assumeTrue(configured,
            "The standalone evidence contract is configured by its Gradle task");

        assertNotNull(left, LEFT_PROPERTY + " must be configured");
        assertNotNull(right, RIGHT_PROPERTY + " must be configured");
        assertNotNull(include, INCLUDE_PROPERTY + " must be configured");

        Path leftRoot = Path.of(left).toAbsolutePath().normalize();
        Path rightRoot = Path.of(right).toAbsolutePath().normalize();
        ByteIdenticalDirectoriesVerifier.Comparison comparison =
            ByteIdenticalDirectoriesVerifier.compare(
                leftRoot, rightRoot, include);

        assertTrue(comparison.identical(),
            () -> comparison.describe(leftRoot, rightRoot, include));
    }
}
