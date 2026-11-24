package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlphaCOutputDriverTest {
    private static final Path BINARY = Path.of("candidate");

    @Test
    void retainsExactRankTwoCoordinatesInSourceOrder() {
        assertEquals(List.of("candidate", "3", "2", "7", "0", "3", "1", "0", "1", "2"),
            AlphaCOutputDriver.command(BINARY, 3, 2, 7,
                List.of(List.of(0, 3), List.of(1, 0), List.of(1, 2))));
        assertTrue(AlphaCOutputDriver.source("variant", 2).contains("#define RANK 2"));
        assertTrue(AlphaCOutputDriver.source("variant", 1).contains("#define ALPHA_FUNCTION variant"));
    }

    @Test
    void rejectsInvalidCoordinatesShapeAndArguments() {
        for (List<List<Integer>> points : List.of(List.of(List.of(0), List.of(0)),
                List.of(List.of(1, 0), List.of(0, 3)), List.of(List.of(0), List.of(1, 2)),
                List.of(List.of(-1)), List.of(List.of(513)), List.of(List.of(0, 1, 2)))) {
            assertThrows(IllegalArgumentException.class, () -> AlphaCOutputDriver.command(BINARY, 3, 2, 7, points));
        }
        assertThrows(IllegalArgumentException.class,
            () -> AlphaCOutputDriver.command(BINARY, 3, 2, 7, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> AlphaCOutputDriver.command(BINARY, 3, 3, 7, List.of(List.of(0))));
        assertThrows(IllegalArgumentException.class, () -> AlphaCOutputDriver.source("inj;ection", 1));
        assertThrows(IllegalArgumentException.class, () -> AlphaCOutputDriver.source("variant", 3));
    }
}
