package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutonomousProductionCampaignContainerDiagnosticsTest {
    @Test
    void reportsTheDeepestCauseWithoutAStackTraceOrLineBreaks() {
        RuntimeException failure = new RuntimeException(
            "container startup failed",
            new IllegalStateException("container did not start\ncorrectly")
        );

        String diagnostic = AutonomousProductionCampaignContainerTest
            .cleanupFailureSummary(
                "start permission-restoration container",
                failure
            );

        assertTrue(diagnostic.startsWith(
            "Autopilot Testcontainers cleanup could not "
                + "start permission-restoration container"
        ));
        assertTrue(diagnostic.contains("[IllegalStateException]"));
        assertTrue(diagnostic.endsWith("container did not start correctly"));
        assertFalse(diagnostic.contains("\n"));
        assertFalse(diagnostic.contains("\r"));
        assertFalse(diagnostic.contains("\tat "));
    }

    @Test
    void boundsLongDiagnosticDetailToTheDeclaredLimit() {
        RuntimeException failure = new RuntimeException("x".repeat(400));

        String diagnostic = AutonomousProductionCampaignContainerTest
            .cleanupFailureSummary("stop cleanup container", failure);
        int detailSeparator = diagnostic.indexOf("]: ");

        assertTrue(detailSeparator >= 0, diagnostic);
        String detail = diagnostic.substring(detailSeparator + 3);
        assertTrue(detail.endsWith("..."));
        assertTrue(detail.length() <= 240, detail);
    }

    @Test
    void terminatesWhenTheCauseGraphContainsACycle() {
        MutableCauseException first = new MutableCauseException("first");
        MutableCauseException second = new MutableCauseException("second");
        first.cause = second;
        second.cause = first;

        String diagnostic = AutonomousProductionCampaignContainerTest
            .cleanupFailureSummary("stop cleanup container", first);

        assertTrue(diagnostic.contains("[MutableCauseException]"));
        assertTrue(diagnostic.endsWith("second"));
    }

    @Test
    void retainsAUsefulMessageWhenTheExceptionHasNoMessage() {
        RuntimeException failure = new RuntimeException();

        String diagnostic = AutonomousProductionCampaignContainerTest
            .cleanupFailureSummary("stop cleanup container", failure);

        assertTrue(diagnostic.endsWith("no diagnostic message"));
    }

    private static final class MutableCauseException extends RuntimeException {
        private Throwable cause;

        private MutableCauseException(String message) {
            super(message, null);
        }

        @Override
        public synchronized Throwable getCause() {
            return cause;
        }
    }
}
