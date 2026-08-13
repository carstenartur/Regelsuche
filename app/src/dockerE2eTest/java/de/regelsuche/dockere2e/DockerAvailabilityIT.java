package de.regelsuche.dockere2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

class DockerAvailabilityIT {
    @Test
    void fullProfileRequiresAReachableDockerDaemon() {
        assertTrue(
            DockerClientFactory.instance().isDockerAvailable(),
            "Maven profile 'full' requires a reachable Docker daemon. "
                + "This is a technical test prerequisite, not a mathematical result."
        );
    }
}
