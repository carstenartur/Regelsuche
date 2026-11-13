package de.regelsuche.egraph.proof;

import static de.regelsuche.egraph.proof.ExtractionCertificate.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.egraph.EqualitySaturation;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtractionCertificateCodecTest {
    private static final ProofLimits LIMITS = ProofLimits.defaults();

    @Test
    void realRunnerCertificateSurvivesStorageAndDetachedReplay(@TempDir Path directory) throws Exception {
        var symbol = new SymbolId(new UUID(1, 2), 7);
        var scalar = ExactRational.fromCanonicalText("-17/19");
        var placeholder = PatternExpr.var("A");
        var rule = new PatternRewriteRule("add-fraction",
            PatternExpr.op(BinaryOperator.ADD, placeholder, PatternExpr.num(scalar)), placeholder);
        Expr input = new FunctionExpr("f", List.of(new BinaryExpr(VariableExpr.scoped(symbol),
            BinaryOperator.ADD, new NumberExpr(scalar))));
        var result = new EqualitySaturation(List.of(rule)).saturateWithProof(input, node -> 1,
            EqualitySaturation.ProofOptions.defaults());
        var original = result.proof().checked().orElseThrow().certificate();
        var bytes = ExtractionCertificateCodec.encode(original, LIMITS);
        Path file = Files.write(directory.resolve("extraction.eqsc"), bytes);
        var decoded = ExtractionCertificateCodec.decode(Files.readAllBytes(file), LIMITS);
        assertNotSame(original, decoded);
        assertEquals(original, decoded);
        assertArrayEquals(bytes, ExtractionCertificateCodec.encode(decoded, LIMITS));

        var trustedRule = new PatternRewriteRule("add-fraction",
            PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.num(scalar)),
            PatternExpr.var("A"));
        Expr expected = new FunctionExpr("f", List.of(VariableExpr.scoped(symbol)));
        assertEquals(expected, result.expression());
        var checked = ExtractionProofChecker.check(input, expected, decoded, List.of(trustedRule), LIMITS);
        assertSame(expected, checked.checked().orElseThrow().output());
        Expr unrelated = new FunctionExpr("f", List.of(VariableExpr.scoped(
            new SymbolId(new UUID(1, 3), 7))));
        assertEquals(ExtractionProofChecker.Status.REJECTED,
            ExtractionProofChecker.check(input, unrelated, decoded, List.of(trustedRule), LIMITS).status());
        assertEquals(ExtractionProofChecker.Status.REJECTED,
            ExtractionProofChecker.check(input, expected, decoded, List.of(), LIMITS).status());
    }

    @Test
    void canonicalBindingOrderDoesNotDependOnMapOrder() {
        var left = new LinkedHashMap<String, Integer>();
        left.put("A", 0);
        left.put("B", 1);
        var right = new LinkedHashMap<String, Integer>();
        right.put("B", 1);
        right.put("A", 0);
        var original = instance(left, PatternExpr.var("A"));
        var bytes = ExtractionCertificateCodec.encode(original, LIMITS);
        assertArrayEquals(bytes, ExtractionCertificateCodec.encode(instance(right, PatternExpr.var("A")), LIMITS));
        assertEquals(original, ExtractionCertificateCodec.decode(bytes, LIMITS));
    }

    @Test
    void validEncodingDoesNotAuthorizeForgedRuleOrBinding() {
        var trusted = new PatternRewriteRule("r",
            PatternExpr.fn("f", PatternExpr.var("A"), PatternExpr.var("B")), PatternExpr.var("A"));
        Expr a = new VariableExpr("a");
        Expr b = new VariableExpr("b");
        Expr input = new FunctionExpr("f", List.of(a, b));
        for (var forged : List.of(instance(Map.of("A", 0, "B", 1), PatternExpr.var("B")),
                instance(Map.of("A", 1, "B", 0), PatternExpr.var("A")))) {
            var decoded = ExtractionCertificateCodec.decode(ExtractionCertificateCodec.encode(forged, LIMITS), LIMITS);
            assertEquals(ExtractionProofChecker.Status.REJECTED,
                ExtractionProofChecker.check(input, a, decoded, List.of(trusted), LIMITS).status());
        }
    }

    @Test
    void rejectsCorruptTruncatedAndOversizedData() {
        byte[] bytes = ExtractionCertificateCodec.encode(ExtractionProofCheckerTest.leafCertificate("f"), LIMITS);
        for (int end = 0; end < bytes.length; end++) {
            byte[] prefix = Arrays.copyOf(bytes, end);
            assertThrows(IllegalArgumentException.class, () -> ExtractionCertificateCodec.decode(prefix, LIMITS));
        }
        byte[] wrongMagic = bytes.clone();
        wrongMagic[0] = 0;
        assertThrows(IllegalArgumentException.class, () -> ExtractionCertificateCodec.decode(wrongMagic, LIMITS));
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
        assertThrows(IllegalArgumentException.class, () -> ExtractionCertificateCodec.decode(trailing, LIMITS));
        byte[] excessiveCount = bytes.clone();
        ByteBuffer.wrap(excessiveCount).putInt(8, Integer.MAX_VALUE);
        assertThrows(ExtractionProofBuilder.LimitExceeded.class,
            () -> ExtractionCertificateCodec.decode(excessiveCount, LIMITS));
        byte[] invalidTag = bytes.clone();
        invalidTag[12] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> ExtractionCertificateCodec.decode(invalidTag, LIMITS));
        byte[] futureVersion = bytes.clone();
        ByteBuffer.wrap(futureVersion).putInt(4, 2);
        assertThrows(IllegalArgumentException.class, () -> ExtractionCertificateCodec.decode(futureVersion, LIMITS));
        assertThrows(ExtractionProofBuilder.LimitExceeded.class,
            () -> ExtractionCertificateCodec.decode(bytes, new ProofLimits(1, 1, 1, 1, 8, 1, 1, bytes.length - 1)));
    }

    @Test
    void versionOneWireBytesArePinned() {
        var leaf = ExtractionProofCheckerTest.leafCertificate("f");
        byte[] expected = HexFormat.of().parseHex("45515343 00000001 00000001 03 00000001 0066 "
            .concat("00000000 00000000 00000000 00000001 00 00000000 00000000 00000000 00000000")
            .replace(" ", ""));
        assertArrayEquals(expected, ExtractionCertificateCodec.encode(leaf, LIMITS));
        assertEquals(leaf, ExtractionCertificateCodec.decode(expected, LIMITS));
    }

    @Test
    void sharedPatternExpansionAndRationalLimitsApplyDuringDecode() {
        PatternExpr pattern = PatternExpr.var("A");
        for (int level = 0; level < 10; level++) {
            pattern = PatternExpr.op(BinaryOperator.ADD, pattern, pattern);
        }
        var shared = withRulePattern(pattern);
        byte[] bytes = ExtractionCertificateCodec.encode(shared, LIMITS);
        assertTrue(bytes.length < 500);
        assertThrows(ExtractionProofBuilder.LimitExceeded.class, () ->
            ExtractionCertificateCodec.decode(bytes, new ProofLimits(100, 100, 100, 1000, 128, 20, 100, 10000)));
        var largeScalar = withRulePattern(PatternExpr.num(ExactRational.fromCanonicalText("131071/65537")));
        byte[] rational = ExtractionCertificateCodec.encode(largeScalar, LIMITS);
        assertThrows(ExtractionProofBuilder.LimitExceeded.class, () ->
            ExtractionCertificateCodec.decode(rational, new ProofLimits(100, 100, 100, 1000, 8, 20, 100, 10000)));
    }

    @Test
    void successfulEncodingFitsTheSameStorageWorkLimitWhenDecoded() {
        for (var certificate : List.of(withRulePattern(PatternExpr.var("A")),
                withRulePattern(PatternExpr.num(ExactRational.fromCanonicalText("-17/19"))),
                instance(Map.of("A", 0, "B", 1), PatternExpr.var("A")))) {
            long firstEncoding = java.util.stream.LongStream.rangeClosed(0, 1000)
                .filter(work -> canEncode(certificate, work)).findFirst().orElseThrow();
            var limit = withWork(firstEncoding);
            byte[] bytes = ExtractionCertificateCodec.encode(certificate, limit);
            assertEquals(certificate, ExtractionCertificateCodec.decode(bytes, limit));
        }
    }

    private static boolean canEncode(ExtractionCertificate certificate, long work) {
        try {
            ExtractionCertificateCodec.encode(certificate, withWork(work));
            return true;
        } catch (ExtractionProofBuilder.LimitExceeded e) {
            return false;
        }
    }

    private static ProofLimits withWork(long work) {
        return new ProofLimits(100, 100, 100, 1000, 128, 20, 100, work);
    }

    @Test
    void rejectsForwardTermAndProofReferences() {
        byte[] bytes = ExtractionCertificateCodec.encode(instance(Map.of("A", 0, "B", 1),
            PatternExpr.var("A")), LIMITS);
        ByteBuffer.wrap(bytes).putInt(45, 2); // First child of term 2.
        assertThrows(IllegalArgumentException.class, () -> ExtractionCertificateCodec.decode(bytes, LIMITS));

        byte[] leaf = ExtractionCertificateCodec.encode(ExtractionProofCheckerTest.leafCertificate("f"), LIMITS);
        leaf[35] = 1; // Replace reflexivity with symmetry referring to itself.
        assertThrows(IllegalArgumentException.class, () -> ExtractionCertificateCodec.decode(leaf, LIMITS));
    }

    @Test
    void retainsExactJavaStringUnits() {
        String label = "proof\u0000\u03c0\ud83d\ude00\ud800";
        var certificate = ExtractionProofCheckerTest.leafCertificate(label);
        byte[] bytes = ExtractionCertificateCodec.encode(certificate, LIMITS);
        assertEquals(certificate, ExtractionCertificateCodec.decode(bytes, LIMITS));
        assertArrayEquals(bytes, ExtractionCertificateCodec.encode(
            ExtractionCertificateCodec.decode(bytes, LIMITS), LIMITS));
    }

    private static ExtractionCertificate instance(Map<String, Integer> bindings, PatternExpr target) {
        return new ExtractionCertificate(1,
            List.of(new Term(Kind.VARIABLE, "a", List.of()), new Term(Kind.VARIABLE, "b", List.of()),
                new Term(Kind.FUNCTION, "f", List.of(0, 1))),
            List.of(new RuleIdentity("r", PatternExpr.fn("f", PatternExpr.var("A"), PatternExpr.var("B")), target)),
            List.of(new PatternInstance(0, bindings, 2, 0)), 2, 0, 0);
    }

    private static ExtractionCertificate withRulePattern(PatternExpr pattern) {
        var leaf = ExtractionProofCheckerTest.leafCertificate("f");
        return new ExtractionCertificate(1, leaf.terms(), List.of(new RuleIdentity("r", pattern, pattern)),
            leaf.steps(), 0, 0, 0);
    }
}
