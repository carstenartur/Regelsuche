package de.regelsuche.cli.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CliOptionsTest {
    @Test
    void parsesFlagsPairsAndEqualsSyntax() {
        CliOptions options = CliOptions.parse(new String[] {
            "--min", "2", "ignored", "--format=json", "--quiet"
        });

        assertEquals("2", options.getOrDefault("min", "1"));
        assertEquals("json", options.get("format"));
        assertEquals("true", options.get("quiet"));
        assertTrue(options.containsKey("quiet"));
    }

    @Test
    void splitsCsvWithoutBlankItems() {
        assertEquals(List.of("json", "markdown", "latex"),
            CliOptions.splitCsv("json, markdown,, latex "));
    }
}
