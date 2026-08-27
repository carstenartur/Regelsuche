package de.regelsuche.math.sympy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProcessSymPyFactorizationEngineConfigurationTest {
    @Test
    void jvmPropertyOverridesTheEnvironmentForBenchmarkForks() {
        String property =
            ProcessSymPyFactorizationEngine.PYTHON_EXECUTABLE_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "  /tmp/pinned-sympy-python  ");

            assertEquals(
                "/tmp/pinned-sympy-python",
                ProcessSymPyFactorizationEngine
                    .configuredPythonExecutable());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }
}
