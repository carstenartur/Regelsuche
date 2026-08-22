package de.regelsuche;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class AppTest {
    @Test
    void printsUsageForInvalidInputTypeInsteadOfThrowing() {
        String output = capture(() -> assertDoesNotThrow(
            () -> App.main(new String[] {"unknown", "x + 1"})));

        assertTrue(output.contains("Valid input types"));
    }

    @Test
    void systemInputUsesExactMatrixRepresentationInsteadOfSeparateRootSearch() {
        String output = capture(() -> App.main(new String[] {
            "system",
            "x = 1; y = 2"
        }));

        assertTrue(output.contains("Recognized exact matrix representation"));
        assertTrue(output.contains("A = [[1, 0], [0, 1]]"));
        assertTrue(output.contains("x = [x, y]^T"));
        assertTrue(output.contains("Independent components: 2"));
        assertFalse(output.contains("Graph nodes:"));
        assertFalse(output.contains("No simplification found yet"));
    }

    @Test
    void nonlinearSystemReportsFailClosedRepresentationStatus() {
        String output = capture(() -> App.main(new String[] {
            "system",
            "x*y = 1; x + y = 2"
        }));

        assertTrue(output.contains("Exact matrix representation: NONLINEAR"));
        assertTrue(output.contains("PRODUCT_OF_NON_CONSTANT_FORMS"));
        assertFalse(output.contains("Graph nodes:"));
    }

    private static String capture(Runnable action) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output));
        try {
            action.run();
        } finally {
            System.setOut(originalOut);
        }
        return output.toString();
    }
}
