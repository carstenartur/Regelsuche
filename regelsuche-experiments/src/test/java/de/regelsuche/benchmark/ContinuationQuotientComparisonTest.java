package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContinuationQuotientComparisonTest {
    @Test void retainsEveryFrozenCaseAndBothBackendsWithIndependentPrimitiveReplay() throws Exception {
        var report = ContinuationQuotientComparison.run();
        assertEquals(96, report.rows().size());
        assertEquals("53bff974a9ecb101b85e01f202d095fb2a99f5f33127ed7ec0e4ade2685ac422", report.corpusSha256());
        assertTrue(report.rows().stream().allMatch(row -> row.replayVerified()));
        assertTrue(report.rows().stream().filter(row -> row.caseId().equals("independent-three-factor"))
            .allMatch(row -> row.targetReached()));
        assertTrue(report.rows().stream().filter(row -> row.caseId().equals("nested-power-control"))
            .allMatch(row -> row.targetReached()));
        assertEquals("95283b036c53031e950712931f10cfd58fa32298694b489eabaed075258bbc8a",
            java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(report.json().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        var output = Path.of("target/continuation-quotient/comparison.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.json());
    }
}
