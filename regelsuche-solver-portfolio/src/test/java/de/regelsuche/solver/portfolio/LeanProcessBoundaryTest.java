package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Process-contract fixtures only. Mathematical proof authority is tested by CheckedLeanRealTest. */
class LeanProcessBoundaryTest {
    @TempDir Path temp;

    @Test void completeProcessReceiptBindsAllRetainedFilesAndDeclaredPremises() throws Exception {
        var backend = backend("complete");
        var request = CheckedLeanProofTest.obligation("a/b*b", "a", "b != 0");
        String configuration = backend.configurationHash();
        var attempt = backend.executeWithEvidence(request);
        var result = attempt.execution().result();
        Path directory = attempt.directory();

        assertEquals(ResultStatus.CONFIRMED, result.status());
        assertTrue(result.usedCapabilities().contains("ASSUMPTION_SATISFIABILITY_NOT_CHECKED"));
        assertEquals(request.toCanonicalJson(), Files.readString(directory.resolve("obligation.json")));
        assertEquals(attempt.execution().toCanonicalJson(), Files.readString(directory.resolve("execution.json")));
        assertEquals(result.toCanonicalJson(), Files.readString(directory.resolve("result.json")));
        String source = Files.readString(directory.resolve("proof.lean"));
        assertTrue(source.contains("rs_b ≠ (0 : Real)"));
        String audit = Files.readString(directory.resolve("audit.json"));
        String compiledHash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(directory.resolve("proof.olean"))));
        String manifest = "obligation=" + request.contentHash() + "\nsource=" + SolverIr.sha256(source)
            + "\ncompiled=sha256:" + compiledHash + "\naudit=" + SolverIr.sha256(audit)
            + "\nconfiguration=" + configuration + "\nversion=Lean (version transport-fixture)\n";
        assertEquals(manifest, Files.readString(directory.resolve("certificate.txt")));
        assertEquals(SolverIr.sha256(manifest), result.certificateHash());
        assertTrue(Files.readString(directory.resolve("proof.stderr")).endsWith("proof diagnostic\n"));
        assertEquals("test-toolchain\n", Files.readString(directory.resolve("lean-toolchain")));
        assertEquals("{}\n", Files.readString(directory.resolve("lake-manifest.json")));
    }

    @Test void staleAuditMissingCompiledProofAndChangedConfigurationNeverConfirm() throws Exception {
        for (String mode : List.of("stale-audit", "missing-compiled", "changed-configuration")) {
            var attempt = backend(mode).executeWithEvidence(CheckedLeanProofTest.obligation("x", "x"));
            assertRejected(attempt, ResultStatus.ERROR);
            assertTrue(Files.isRegularFile(attempt.directory().resolve("FAILED.txt")), mode);
            assertFalse(Files.exists(attempt.directory().resolve("certificate.txt")), mode);
            assertTrue(Files.readString(attempt.directory().resolve("proof.stdout"))
                .contains("REGELSUCHE_LEAN_AUDIT "), mode);
        }
    }

    @Test void versionAndProofTransportFailuresRemainDistinctAndRetainDiagnostics() throws Exception {
        var invalidVersion = backend("invalid-version")
            .executeWithEvidence(CheckedLeanProofTest.obligation("x", "x"));
        assertRejected(invalidVersion, ResultStatus.ERROR);
        assertFalse(Files.exists(invalidVersion.directory().resolve("proof.lean")));
        assertEquals("not Lean\n", Files.readString(invalidVersion.directory().resolve("version.stdout")));

        var proofFailure = backend("proof-failure")
            .executeWithEvidence(CheckedLeanProofTest.obligation("x", "x"));
        assertRejected(proofFailure, ResultStatus.UNKNOWN);
        assertTrue(Files.readString(proofFailure.directory().resolve("proof.stderr")).endsWith("proof diagnostic\n"));
        assertTrue(Files.exists(proofFailure.directory().resolve("result.json")));

        var missingCommand = new LeanSolverBackend(temp, temp.resolve("missing-command"),
            List.of(temp.resolve("does-not-exist").toString()), Duration.ofSeconds(2))
            .executeWithEvidence(CheckedLeanProofTest.obligation("x", "x"));
        assertRejected(missingCommand, ResultStatus.ERROR);
        assertTrue(Files.exists(missingCommand.directory().resolve("FAILED.txt")));
    }

    @Test void versionAndProofTimeoutsAndOversizedOutputAreBounded() throws Exception {
        for (String mode : List.of("version-timeout", "proof-timeout", "oversized-output", "oversized-stderr")) {
            var attempt = backend(mode).executeWithEvidence(CheckedLeanProofTest.obligation("x", "x"));
            assertRejected(attempt, mode.startsWith("oversized-") ? ResultStatus.ERROR : ResultStatus.TIMEOUT);
            String file = mode.equals("version-timeout") ? "version.stdout"
                : mode.equals("oversized-stderr") ? "proof.stderr" : "proof.stdout";
            Path output = attempt.directory().resolve(file);
            assertTrue(Files.isRegularFile(output), mode + ": " + attempt.execution().result().message());
            assertTrue(Files.size(output) <= 4_000_000, mode);
            if (mode.startsWith("oversized-")) assertEquals(4_000_000, Files.size(output), mode);
            assertFalse(Files.exists(attempt.directory().resolve("certificate.txt")), mode);
        }
    }

    private LeanSolverBackend backend(String mode) throws Exception {
        Files.writeString(temp.resolve("lean-toolchain"), "test-toolchain\n");
        Files.writeString(temp.resolve("lake-manifest.json"), "{}\n");
        Path classes = Path.of(ToolchainFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return new LeanSolverBackend(temp, temp.resolve("evidence-" + mode),
            List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                classes.toString(), ToolchainFixture.class.getName(), mode), Duration.ofSeconds(3));
    }

    private static void assertRejected(LeanSolverBackend.Attempt attempt, ResultStatus status) {
        assertEquals(status, attempt.execution().result().status());
        assertTrue(attempt.execution().result().certificateHash().isEmpty());
        assertTrue(attempt.execution().result().usedCapabilities().isEmpty());
    }

    /** A deliberately simulated trusted command. Its output is not mathematical evidence. */
    public static final class ToolchainFixture {
        public static void main(String[] args) throws Exception {
            String mode = args[0];
            boolean version = args[1].equals("--version");
            if (mode.equals(version ? "version-timeout" : "proof-timeout")) Thread.sleep(30_000);
            if (version) {
                System.out.println(mode.equals("invalid-version") ? "not Lean" : "Lean (version transport-fixture)");
                return;
            }
            System.err.println("proof diagnostic");
            if (mode.equals("proof-failure")) System.exit(7);
            if (mode.startsWith("oversized-")) {
                var stream = mode.equals("oversized-stderr") ? System.err : System.out;
                stream.print("x".repeat(4_100_000));
                stream.flush();
                return;
            }
            Path source = Path.of(args[args.length - 1]);
            String text = Files.readString(source);
            String hash = binding(text, "obligationHash");
            String nonce = mode.equals("stale-audit") ? "stale" : binding(text, "nonce");
            if (!mode.equals("missing-compiled")) Files.writeString(source.resolveSibling("proof.olean"), "fixture bytes");
            if (mode.equals("changed-configuration")) Files.writeString(Path.of("lean-toolchain"), "changed\n");
            System.out.println("REGELSUCHE_LEAN_AUDIT {\"schema\":\"regelsuche.lean-audit/v1\",\"obligationHash\":\""
                + hash + "\",\"nonce\":\"" + nonce + "\",\"theorem\":\"regelsuche_bound\",\"axioms\":[\"propext\"]}");
        }
        private static String binding(String source, String field) {
            var matcher = Pattern.compile("\\(\"" + field + "\", toJson \"([^\"]+)\"\\)").matcher(source);
            if (!matcher.find()) throw new IllegalStateException("missing generated binding " + field);
            return matcher.group(1);
        }
    }
}
