package de.regelsuche.cli.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CliCommandRegistryTest {
    @Test
    void defaultRegistryMatchesTopLevelCommandsCaseInsensitively() {
        CliCommandRegistry registry = CliCommandRegistry.defaults();

        assertTrue(registry.contains("discover"));
        assertTrue(registry.contains("SERVE"));
        assertFalse(registry.contains("term"));
    }
}
