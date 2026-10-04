package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.solver.ir.*;
import de.regelsuche.solver.ir.SolverIr.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Dedicated proof lane requires a genuine pinned Lean/mathlib installation. */
@EnabledIfEnvironmentVariable(named="REGELSUCHE_REAL_LEAN", matches="true")
class CheckedLeanRealTest {
    static Path project() { return Path.of(System.getenv("REGELSUCHE_LEAN_PROJECT")); }
    static Path root() { return Path.of("target/checked-proof").toAbsolutePath(); }
    private static LeanSolverBackend backend() { return new LeanSolverBackend(project(),root()); }

    @Test void realAlgebraAndAnalyticProofsHaveCompleteBoundEvidence() throws Exception {
        for (Obligation o: List.of(CheckedLeanProofTest.obligation("x+0","x"),
                CheckedLeanProofTest.obligation("a / b * b","a","b != 0"),
                CheckedLeanProofTest.obligation("log(exp(x))","x"),
                CheckedLeanProofTest.obligation("exp(log(x))","x","x > 0"),
                CheckedLeanProofTest.obligation("exp(x+y)","exp(x)*exp(y)"),
                CheckedLeanProofTest.obligation("x^65","x^65"))) {
            var attempt=backend().executeWithEvidence(o);
            assertEquals(ResultStatus.CONFIRMED,attempt.execution().result().status(),attempt.directory().toString());
            for (String name:List.of("proof.lean","proof.olean","audit.json","certificate.txt",
                    "obligation.json","translation.json","result.json","execution.json",
                    "version.stdout","proof.stdout","proof.stderr","lean-toolchain","lake-manifest.json"))
                assertTrue(Files.exists(attempt.directory().resolve(name)),name);
        }
    }
    @Test void boundedTacticFailureIsNotARefutation() {
        var a=backend().executeWithEvidence(CheckedLeanProofTest.obligation("x+1","x"));
        assertEquals(ResultStatus.UNKNOWN,a.execution().result().status(),a.directory().toString());
        assertTrue(a.execution().result().certificateHash().isEmpty());
    }
    @Test void actualKernelAuditRejectsDirectAndIndirectAxiomsAndHiddenHoles() throws Exception {
        String type="∀ (rs_x : Real), rs_x = rs_x";
        for (String declarations: List.of(
            "axiom unproved : False\ntheorem regelsuche_lemma : "+type+" := False.elim unproved\n",
            "axiom hidden : False\ntheorem intermediary : False := hidden\ntheorem regelsuche_lemma : "+type+" := False.elim intermediary\n",
            "theorem hiddenHole : False := @sorryAx False true\ntheorem regelsuche_lemma : "+type+" := False.elim hiddenHole\n",
            "theorem regelsuche_lemma : True := by trivial\n",
            "theorem regelsuche_lemma (extra : False) : "+type+" := by intro x; rfl\n")) {
            String audit=new LeanSourceRenderer().audit(type,SolverIr.sha256("audit-fixture"),"3".repeat(32));
            String text="import Mathlib\nimport Lean.Util.CollectAxioms\nset_option Elab.async false\n"
                +declarations+audit;
            assertEquals(0, compile("import Mathlib\nset_option Elab.async false\n" + declarations));
            assertNotEquals(0,compile(text));
        }
    }
    @Test void genuineExpectedTheoremPassesTheSameAudit() throws Exception {
        String type="∀ (rs_x : Real), rs_x = rs_x";
        String text="import Mathlib\nimport Lean.Util.CollectAxioms\nset_option Elab.async false\n"
            +"theorem regelsuche_lemma : "+type+" := by intro x; rfl\n"
            +new LeanSourceRenderer().audit(type,SolverIr.sha256("audit-fixture"),"3".repeat(32));
        assertEquals(0,compile(text));
    }
    private int compile(String source) throws Exception {
        Files.createDirectories(root());
        Path dir=Files.createTempDirectory(root(),"audit-negative-");
        Path file=dir.resolve("fixture.lean");Files.writeString(file,source);
        Process p=new ProcessBuilder("lake","env","lean",file.toString()).directory(project().toFile())
            .redirectOutput(dir.resolve("stdout.txt").toFile()).redirectError(dir.resolve("stderr.txt").toFile()).start();
        try { assertTrue(p.waitFor(90,TimeUnit.SECONDS),dir.toString());return p.exitValue(); }
        finally { if(p.isAlive())p.destroyForcibly(); }
    }
}
