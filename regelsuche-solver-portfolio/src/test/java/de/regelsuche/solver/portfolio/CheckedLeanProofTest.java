package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.solver.ir.*;
import de.regelsuche.solver.ir.SolverIr.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckedLeanProofTest {
    static Obligation obligation(String left, String right, String... assumptions) {
        return new SolverObligationFactory().equality("contract", left, right, List.of(assumptions),
            RequestedEvidence.FORMAL_PROOF, new SourceProvenance("contract-test", "generic",
                SolverIr.sha256("generic-proof-contract/v1")));
    }
    @Test void generatedLeanContainsActualTacticsAndClosedTypeAudit() {
        Obligation o=obligation("a / b * b", "a", "b != 0");
        String source=LeanSolverBackend.artifact(o);
        assertFalse(source.contains("sorry"));
        assertTrue(source.contains("field_simp"));
        assertTrue(source.contains("theorem regelsuche_bound"));
        assertTrue(source.contains("Lean.collectAxioms"));
        assertEquals(o,LeanSolverBackend.readArtifact(source));
    }
    @Test void editsAndArbitrarySourceCannotEnterDefaultExecutor() {
        String source=LeanSolverBackend.artifact(obligation("x+0","x"));
        assertThrows(IllegalArgumentException.class,()->LeanSolverBackend.readArtifact(source+"\n#eval IO.println \"success\""));
        assertThrows(IllegalArgumentException.class,()->LeanSolverBackend.readArtifact("theorem t : True := by trivial"));
        assertThrows(IllegalArgumentException.class,()->LeanSolverBackend.readArtifact(source.replace("theorem regelsuche_lemma", "theorem other")));
    }
    @Test void actualAnalyticSemanticsAndLargePowersAreRendered() {
        var r=new LeanSourceRenderer();
        assertTrue(r.render(obligation("log(exp(x))","x")).supported());
        assertTrue(r.render(obligation("exp(log(x))","x","x > 0")).supported());
        assertTrue(r.render(obligation("x^65","x^65")).supported());
        String source=LeanSolverBackend.artifact(obligation("x^y","pow(x,y)","x > 0"));
        assertTrue(source.contains("Real.rpow"));
        assertFalse(source.contains("axiom "));
    }
    @Test void missingDomainPremisesAndUnknownFunctionsAreRejected(@TempDir Path temp) throws Exception {
        LeanSolverBackend backend=new LeanSolverBackend(temp,temp.resolve("evidence"));
        for (Obligation o: List.of(obligation("a/b","a/b"),obligation("log(x)","log(x)"),
                obligation("sqrt(x)","sqrt(x)"),obligation("x^y","x^y"),obligation("sin(x)","sin(x)"))) {
            var result=backend.executeWithEvidence(o);
            assertEquals(ResultStatus.UNSUPPORTED,result.execution().result().status());
            assertEquals(TranslationStatus.REJECTED,result.execution().translation().status());
            assertFalse(Files.exists(result.directory().resolve("proof.lean")));
            assertTrue(result.execution().result().certificateHash().isEmpty());
        }
    }
    @Test void arbitraryDomainsAreNotTurnedIntoTrue(@TempDir Path temp) {
        var o=obligation("x+0","x","x is integer");
        assertEquals(ResultStatus.UNSUPPORTED,new LeanSolverBackend(temp,temp.resolve("out")).execute(o).result().status());
    }
    @Test void auditRejectsStaleForgedAndUnapprovedDependencies() throws Exception {
        String hash=SolverIr.sha256("goal"), nonce="1".repeat(32);
        String json="{\"schema\":\"regelsuche.lean-audit/v1\",\"obligationHash\":\""+hash
            +"\",\"nonce\":\""+nonce+"\",\"theorem\":\"regelsuche_bound\",\"axioms\":[\"propext\"]}";
        String line="REGELSUCHE_LEAN_AUDIT "+json;
        assertEquals(json,LeanSolverBackend.checkedAudit(line,hash,nonce));
        for (String forged:List.of("success",line+"\n"+line,line.replace("propext","sorryAx"),
                line.replace("propext","injectedAxiom"),line.replace("propext","Lean.trustCompiler"),
                line.replace(nonce,"2".repeat(32)))) {
            assertThrows(java.io.IOException.class,()->LeanSolverBackend.checkedAudit(forged,hash,nonce));
        }
    }
    @Test void absentPinnedEnvironmentNeverConfirms(@TempDir Path temp) {
        var a=new LeanSolverBackend(temp,temp.resolve("out")).executeWithEvidence(obligation("x","x"));
        assertEquals(ResultStatus.ERROR,a.execution().result().status());
        assertTrue(a.execution().result().certificateHash().isEmpty());
    }
}
