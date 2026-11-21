package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.solver.ir.*;
import de.regelsuche.solver.ir.SolverIr.*;
import java.io.IOException;
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
            assertEquals(ResultStatus.CONFIRMED,attempt.execution().result().status(),diagnostics(attempt.directory()));
            for (String name:List.of("proof.lean","proof.olean","audit.json","certificate.txt",
                    "obligation.json","translation.json","result.json","execution.json",
                    "version.stdout","proof.stdout","proof.stderr","lean-toolchain","lake-manifest.json"))
                assertTrue(Files.exists(attempt.directory().resolve(name)),name);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "REGELSUCHE_LEAN_REBUILD_PROJECT", matches = ".+")
    void rebuildsAnAnalyticTheoremInASeparatePinnedProject() throws Exception {
        var expected = CheckedLeanProofTest.obligation("exp(log(x))", "x", "x > 0");
        var original = backend().executeWithEvidence(expected);
        assertEquals(ResultStatus.CONFIRMED, original.execution().result().status(), diagnostics(original.directory()));
        String originalCertificate = original.execution().result().certificateHash();
        var rebuild = new LeanEvidenceRebuilder(Path.of(System.getenv("REGELSUCHE_LEAN_REBUILD_PROJECT")), root());
        var receipt = rebuild.rebuild(expected, originalCertificate, original.directory());
        assertEquals(SolverIr.sha256(Files.readString(receipt.directory().resolve("rebuild.txt"))), receipt.receiptHash());
        assertEquals(-1, Files.mismatch(original.directory().resolve("proof.lean"), receipt.directory().resolve("proof.lean")));
        assertTrue(Files.size(receipt.directory().resolve("proof.olean")) > 0);
        var changedPremise = CheckedLeanProofTest.obligation("exp(log(x))", "x", "x >= 0");
        assertThrows(IOException.class, () -> rebuild.rebuild(changedPremise, originalCertificate, original.directory()));

        Path source = original.directory().resolve("proof.lean");
        String genuine = Files.readString(source);
        try {
            Files.writeString(source, genuine + "\n-- edited after certification\n");
            assertThrows(IOException.class, () -> rebuild.rebuild(expected, originalCertificate, original.directory()));
        } finally {
            Files.writeString(source, genuine);
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
            "axiom rs_unapproved_hidden : False\ntheorem intermediary : False := rs_unapproved_hidden\ntheorem regelsuche_lemma : "+type+" := False.elim intermediary\n",
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
    @Test void scopedStatementsUseTheKernelAndAnIndependentTypeReconstruction() throws Exception {
        // In the required real-tool lane a missing second project is a failure.
        String second = System.getenv("REGELSUCHE_LEAN_REBUILD_PROJECT");
        assertNotNull(second, "The real scoped proof lane requires the independent pinned project");
        var checker = new LeanEvidenceRebuilder(Path.of(second), root());
        var zero = ScopedLeanContractTest.zero;
        var declarations = List.of(new SymbolDeclaration("f", Sort.REAL_SEQUENCE));
        var f = ScopedLeanContractTest.f;
        var hasSum = ScopedLeanContractTest.app("has_sum", f, zero);
        var fromSum = ScopedLeanContractTest.theorem(declarations, List.of(Predicate.holds("sum", hasSum)),
            ScopedLeanContractTest.app("summable", f));
        for (var request : List.of(ScopedLeanContractTest.witness(), ScopedLeanContractTest.sets(),
                ScopedLeanContractTest.limit(zero), ScopedLeanContractTest.summableAdd(), fromSum)) {
            var original = backend().executeWithEvidence(request);
            assertEquals(ResultStatus.CONFIRMED, original.execution().result().status(), diagnostics(original.directory()));
            var audit = LeanSolverBackend.checkedProcessAudit(Files.readString(original.directory().resolve("proof.stdout")),
                request, null);
            assertTrue(audit.scoped());
            assertFalse(audit.imports().isEmpty());
            if (request.equals(ScopedLeanContractTest.summableAdd()))
                assertTrue(audit.imports().stream().anyMatch(d -> d.name().equals("Summable.add")
                    && d.module().startsWith("Mathlib.")), audit.imports().toString());

            String cert = original.execution().result().certificateHash();
            var receipt = checker.rebuild(request, cert, original.directory());
            String rebuilt = Files.readString(receipt.directory().resolve("proof.lean"));
            assertTrue(rebuilt.endsWith(LeanStatementReconstruction.theorem(request)));
            String report = Files.readString(receipt.directory().resolve("rebuild.txt"));
            assertTrue(report.contains("reconstructed=" + SolverIr.sha256(LeanStatementReconstruction.theorem(request))));
            assertEquals(receipt.receiptHash(), SolverIr.sha256(report));
            if (request.equals(ScopedLeanContractTest.summableAdd())) {
                var learned = LeanLemmaPlan.learn(request, cert, original.directory());
                assertTrue(learned.declarations().contains("Summable.add"), learned.declarations().toString());
                // Acquisition has already finished before the second, one-premise target exists.
                var value = ScopedLeanContractTest.app("at", f, new Bound(0));
                var doubled = new Binder(BinderKind.SEQUENCE, Sort.NATURAL, new Binary(BinaryOperator.ADD, value, value));
                var goal = ScopedLeanContractTest.app("summable", doubled);
                var target = ScopedLeanContractTest.theorem(declarations,
                    List.of(Predicate.holds("sf", ScopedLeanContractTest.app("summable", f))), goal);
                var trained = backend().using(learned);
                var transfer = trained.executeWithEvidence(target);
                assertEquals(ResultStatus.CONFIRMED, transfer.execution().result().status(), diagnostics(transfer.directory()));
                String targetCertificate = transfer.execution().result().certificateHash();
                checker.rebuild(target, targetCertificate, transfer.directory());
                assertNotEquals(targetCertificate, cert);
                var missingPremise = ScopedLeanContractTest.theorem(declarations, List.of(), goal);
                var failed = trained.executeWithEvidence(missingPremise);
                assertEquals(ResultStatus.UNKNOWN, failed.execution().result().status(), diagnostics(failed.directory()));
                assertTrue(failed.execution().result().certificateHash().isEmpty());
            }
            if (request.equals(fromSum)) {
                var stronger = ScopedLeanContractTest.theorem(declarations, List.of(), ScopedLeanContractTest.app("summable", f));
                assertThrows(IOException.class, () -> checker.rebuild(stronger, cert, original.directory()));
                // The second rendering is accepted by Lean only if its type really matches.
                String attack = rebuilt.replace(LeanStatementReconstruction.theorem(request), LeanStatementReconstruction.theorem(stronger));
                assertNotEquals(0, compile(attack));
            }
        }
    }

    @Test void falseScopedLimitAndCapturedWitnessNeverConfirm() throws Exception {
        var badCapture = ScopedLeanContractTest.theorem(List.of(), List.of(), new Binder(BinderKind.EXISTS, Sort.REAL,
            new Binder(BinderKind.FORALL, Sort.REAL, ScopedLeanContractTest.app("eq", new Bound(1), new Bound(0)))));
        for (var request : List.of(ScopedLeanContractTest.limit(new Literal("1")), badCapture)) {
            var attempt = backend().executeWithEvidence(request);
            assertEquals(ResultStatus.UNKNOWN, attempt.execution().result().status(), diagnostics(attempt.directory()));
            assertTrue(attempt.execution().result().certificateHash().isEmpty());
        }
    }

    @Test void existingWitnessAndNaturalInductionPlansReachAFreshKernelAndSecondChecker() throws Exception {
        var body = new SolverObligationFactory().equality("witness", "w", "x+1", List.of(),
            RequestedEvidence.SYMBOLIC_CERTIFICATE, new SourceProvenance("test", "witness", SolverIr.sha256("witness")));
        var statement = new ExistentialIntroduction.Statement(body, "w");
        var proof = ExistentialWitnessSearch.search(statement, 256).proof().orElseThrow();
        assertTrue(ExistentialIntroduction.replay(statement, proof));
        var witness = statement.toScopedObligation();

        var p = new Symbol("property");
        var n = new Bound(0);
        var pn = ScopedLeanContractTest.app("at", p, n);
        var base = Predicate.holds("initial", ScopedLeanContractTest.app("at", p, ScopedLeanContractTest.app("nat", new Literal("0"))));
        var next = ScopedLeanContractTest.app("at", p, ScopedLeanContractTest.app("succ", n));
        var step = Predicate.holds("transition", new Binder(BinderKind.FORALL, Sort.NATURAL, ScopedLeanContractTest.app("implies", pn, next)));
        var declarations = List.of(new SymbolDeclaration("property", Sort.NAT_PREDICATE));
        var endpoint = new Binder(BinderKind.FORALL, Sort.NATURAL, pn);
        var target = ScopedLeanContractTest.theorem(declarations, List.of(base, step), endpoint);
        var plan = NaturalInduction.discover(target).getFirst();
        assertTrue(NaturalInduction.matches(target, plan));
        var rebuild = new LeanEvidenceRebuilder(Path.of(System.getenv("REGELSUCHE_LEAN_REBUILD_PROJECT")), root());
        for (var request : List.of(witness, plan.conclusion())) {
            var checked = backend().executeWithEvidence(request);
            assertEquals(ResultStatus.CONFIRMED, checked.execution().result().status(), diagnostics(checked.directory()));
            rebuild.rebuild(request, checked.execution().result().certificateHash(), checked.directory());
        }
        var withoutBase = backend().executeWithEvidence(ScopedLeanContractTest.theorem(declarations, List.of(step), endpoint));
        assertEquals(ResultStatus.UNKNOWN, withoutBase.execution().result().status(), diagnostics(withoutBase.directory()));
        assertTrue(withoutBase.execution().result().certificateHash().isEmpty());
    }

    private static String diagnostics(Path directory) throws Exception {
        StringBuilder out = new StringBuilder(directory.toString());
        for (String file : List.of("proof.stdout", "proof.stderr", "FAILED.txt")) {
            Path path = directory.resolve(file);
            if (Files.exists(path)) out.append("\n").append(Files.readString(path));
        }
        return out.toString();
    }
    private int compile(String source) throws Exception {
        Files.createDirectories(root());
        Path dir=Files.createTempDirectory(root(),"audit-negative-");
        Path file=dir.resolve("fixture.lean");Files.writeString(file,source);
        Process p=new ProcessBuilder("lake","env","lean",file.toString()).directory(project().toFile())
            .redirectOutput(dir.resolve("stdout.txt").toFile()).redirectError(dir.resolve("stderr.txt").toFile()).start();
        try {
            assertTrue(p.waitFor(90,TimeUnit.SECONDS),dir.toString());
            System.out.println("Lean fixture at " + dir + "\n" + Files.readString(dir.resolve("stdout.txt"))
                + "\n" + Files.readString(dir.resolve("stderr.txt")));
            return p.exitValue();
        }
        finally { if(p.isAlive())p.destroyForcibly(); }
    }
}
