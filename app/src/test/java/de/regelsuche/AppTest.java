package de.regelsuche;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class AppTest {
    @Test
    void printsUsageForInvalidInputTypeInsteadOfThrowing() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output));
        try {
            assertDoesNotThrow(() -> App.main(new String[] {"unknown", "x + 1"}));
        } finally {
            System.setOut(originalOut);
        }

        assertTrue(output.toString().contains("Valid input types"));
    }
}
