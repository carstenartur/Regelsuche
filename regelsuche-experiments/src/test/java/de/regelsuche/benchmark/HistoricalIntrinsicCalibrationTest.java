package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.benchmark.HistoricalIntrinsicCalibration.Occurrence;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.TargetRelation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoricalIntrinsicCalibrationTest {
    @Test void regeneratesTheCompletePublicCalibration(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("historical");
        HistoricalIntrinsicCalibration.main(new String[]{output.toString()});
        assertEquals("4aa696c27aee2832250b054dfcfbf033d01eece6bed32f18791490233e477a4b",
            fileHash(output.resolve("freeze.json")));
        assertEquals("3e725f3ce7b31cb7d2b31b3507550dd3b44ae62e2f1a9fbb9876559de091cf01",
            fileHash(output.resolve("assessment.json")));
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
            () -> HistoricalIntrinsicCalibration.main(new String[]{output.toString()}));
    }

    private static String fileHash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static Occurrence step(String expression, int depth, boolean preserving) {
        return new Occurrence(expression, depth, depth == 0 ? List.of() : List.of("primitive"),
            List.of(), depth == 0 ? List.of() : List.of(preserving));
    }

    @Test void creditsVerifiedNewRepresentationOnly() {
        var root = step("x*x-y*y", 0, true);
        var intermediate = step("x^2-y^2", 1, true);
        var factored = step("(x+y)*(x-y)", 1, true);
        var target = "(x-y)*(x+y)";
        var rows = HistoricalIntrinsicCalibration.assess(
            root.expression(), List.of(root, intermediate, factored), target, TargetRelation.VALUE_EQUIVALENT);
        assertEquals(List.of(false, false, true), rows.stream().map(row -> row.credited()).toList());
        assertTrue(rows.getLast().acMatch());
        assertFalse(rows.getLast().exactMatch());
        assertTrue(rows.getFirst().validation().intrinsicallyVerified());
        assertTrue(rows.get(1).validation().intrinsicallyVerified());
        assertFalse(HistoricalIntrinsicCalibration.assess(root.expression(), List.of(root, factored),
            target, TargetRelation.SYNTAX_EXACT).getLast().credited());
    }

    @Test void retainsRejectedUnassessedAndDepthZeroOutcomes() {
        var root = step("x+0", 0, true);
        var wrong = step("x+1", 1, true);
        var unclaimed = step("x", 1, false);
        var rejected = HistoricalIntrinsicCalibration.assess(root.expression(), List.of(root, wrong),
            wrong.expression(), TargetRelation.SYNTAX_EXACT).getLast();
        assertTrue(rejected.exactMatch());
        assertTrue(rejected.validation().intrinsicallyRejected());
        assertFalse(rejected.credited());
        assertFalse(HistoricalIntrinsicCalibration.assess(root.expression(), List.of(root, unclaimed),
            "x", TargetRelation.SYNTAX_EXACT).getLast().credited());
        assertFalse(HistoricalIntrinsicCalibration.assess("x+0", List.of(root, step("0+x", 1, true)),
            "0+x", TargetRelation.VALUE_EQUIVALENT).getLast().credited());
        var unsupported = HistoricalIntrinsicCalibration.validate("x", step("sin(x)", 1, true));
        assertEquals("UNAVAILABLE", unsupported.oracleStatus());
        var conditional = new Occurrence("x+1", 1, List.of("conditional"), List.of("x=0"), List.of(true));
        assertTrue(HistoricalIntrinsicCalibration.validate("x", conditional).conditionalValidityUnresolved());
    }
}
