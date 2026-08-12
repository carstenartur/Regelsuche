package de.regelsuche.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ByteIdenticalDirectoriesContractTest {

    private static final String LEFT_PROPERTY =
        "regelsuche.byteIdentity.left";
    private static final String RIGHT_PROPERTY =
        "regelsuche.byteIdentity.right";
    private static final String INCLUDE_PROPERTY =
        "regelsuche.byteIdentity.include";
    private static final String CONFIGURATION_MESSAGE =
        "The standalone evidence contract is configured by its Gradle task";

    @Test
    void configuredEvidenceDirectoriesAreByteIdentical() throws Exception {
        String left = System.getProperty(LEFT_PROPERTY, "");
        String right = System.getProperty(RIGHT_PROPERTY, "");
        String include = System.getProperty(INCLUDE_PROPERTY, "");

        assumeFalse(left.isBlank(), CONFIGURATION_MESSAGE);
        assumeFalse(right.isBlank(), CONFIGURATION_MESSAGE);
        assumeFalse(include.isBlank(), CONFIGURATION_MESSAGE);

        Path leftRoot = Path.of(left).toAbsolutePath().normalize();
        Path rightRoot = Path.of(right).toAbsolutePath().normalize();
        ByteIdenticalDirectoriesVerifier.Comparison comparison =
            ByteIdenticalDirectoriesVerifier.compare(
                leftRoot, rightRoot, include);

        assertTrue(comparison.identical(),
            () -> comparison.describe(leftRoot, rightRoot, include));
    }
}
