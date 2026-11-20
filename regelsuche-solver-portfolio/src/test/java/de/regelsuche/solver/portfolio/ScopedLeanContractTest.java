package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.solver.ir.*;
import de.regelsuche.solver.ir.SolverIr.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopedLeanContractTest {
    static Expression app(String name, Expression... args) { return new Call(name, List.of(args)); }
    static final Expression zero = new Literal("0");
    static final Expression x = new Symbol("b0");
    static final Expression f = new Symbol("f");
    static final Expression g = new Symbol("g");
    static final Expression n = new Bound(0);

    static Obligation theorem(List<SymbolDeclaration> declarations, List<Predicate> premises, Expression statement) {
        return Obligation.create("scope", declarations, List.of(Theory.SCOPED_ANALYSIS), premises,
            Goal.holds(statement), RequestedEvidence.FORMAL_PROOF,
            new SourceProvenance("control", "scoped", SolverIr.sha256("scoped-controls")));
    }

    static Obligation witness() {
        return theorem(List.of(new SymbolDeclaration("b0", Sort.REAL)), List.of(),
            new Binder(BinderKind.FORALL, Sort.REAL, new Binder(BinderKind.EXISTS, Sort.REAL,
                app("eq", n, new Binary(BinaryOperator.ADD, new Bound(1), x)))));
    }

    static Obligation sets() {
        var set = new Binder(BinderKind.SET_OF, Sort.REAL, app("le", zero, n));
        return theorem(List.of(new SymbolDeclaration("b0", Sort.REAL)),
            List.of(Predicate.holds("nonnegative", app("le", zero, x))), app("mem", x, set));
    }

    static Obligation summableAdd() {
        var declarations = List.of(new SymbolDeclaration("f", Sort.REAL_SEQUENCE), new SymbolDeclaration("g", Sort.REAL_SEQUENCE));
        var sum = new Binder(BinderKind.SEQUENCE, Sort.NATURAL,
            new Binary(BinaryOperator.ADD, app("at", f, n), app("at", g, n)));
        return theorem(declarations, List.of(Predicate.holds("sf", app("summable", f)),
            Predicate.holds("sg", app("summable", g))), app("summable", sum));
    }

    static Obligation limit(Expression target) {
        return theorem(List.of(), List.of(), app("tends_to",
            new Binder(BinderKind.SEQUENCE, Sort.NATURAL, zero), target));
    }

    @Test void captureAvoidanceAndExactWireRoundTrip() {
        Obligation request = witness();
        assertEquals(SolverIr.SCOPED_OBLIGATION_SCHEMA, request.schema());
        assertEquals(request, new SolverIrJsonCodec().readObligation(request.toCanonicalJson()));
        var rendered = new LeanSourceRenderer().render(request);
        assertTrue(rendered.supported(), rendered.issues().toString());
        assertEquals("∀ (rs_b0 : Real), (∀ (b0 : Real), (∃ (b1 : Real), (b1 = (b0 + rs_b0))))", rendered.theoremType());
        assertEquals(request, LeanSolverBackend.readArtifact(LeanSolverBackend.artifact(request)));
        assertThrows(IllegalArgumentException.class, () -> new SolverIrJsonCodec().readObligation(
            request.toCanonicalJson().replace("\"index\":1", "\"index\":0")));
        assertThrows(IllegalArgumentException.class, () -> theorem(List.of(), List.of(), app("eq", n, n)));
    }

    @Test void malformedDomainsAndSortsCannotReachExecution(@TempDir Path temp) {
        var lean = new LeanSolverBackend(temp, temp.resolve("out"));
        var fDecl = List.of(new SymbolDeclaration("f", Sort.REAL_SEQUENCE));
        for (var bad : List.of(theorem(fDecl, List.of(), app("summable", zero)),
                 theorem(fDecl, List.of(), app("mem", zero, f)),
                 theorem(fDecl, List.of(), app("tends_to", f, f)),
                 theorem(fDecl, List.of(), app("mystery", f)),
                 theorem(List.of(), List.of(), new Binder(BinderKind.FORALL, Sort.REAL, app("eq", app("log", n), app("log", n)))))) {
            assertEquals(ResultStatus.UNSUPPORTED, lean.execute(bad).result().status());
        }
    }

    @Test void localDomainFactsApplyOnlyToTheCorrectVariableAndImplicationBranch() {
        var positive = app("lt", zero, n);
        var logIdentity = app("eq", app("log", n), app("log", n));
        var innerLogOuter = app("eq", app("log", new Bound(1)), app("log", new Bound(1)));
        var renderer = new LeanSourceRenderer();
        for (var body : List.of(app("implies", positive, logIdentity),
                app("implies", positive, new Binder(BinderKind.FORALL, Sort.REAL, innerLogOuter)))) {
            var good = theorem(List.of(), List.of(), new Binder(BinderKind.FORALL, Sort.REAL, body));
            assertTrue(renderer.render(good).supported(), renderer.render(good).issues().toString());
        }
        var captured = app("implies", positive, new Binder(BinderKind.FORALL, Sort.REAL, logIdentity));
        // Neither disjunction nor a completed sibling implication makes its guard available.
        for (var body : List.of(captured, app("or", positive, logIdentity),
                app("and", app("implies", positive, logIdentity), logIdentity))) {
            var bad = theorem(List.of(), List.of(), new Binder(BinderKind.FORALL, Sort.REAL, body));
            assertFalse(renderer.render(bad).supported());
        }
        var guarded = theorem(List.of(new SymbolDeclaration("b0", Sort.REAL)),
            List.of(Predicate.holds("positive", app("lt", zero, x))),
            app("eq", app("log", x), app("log", x)));
        assertTrue(renderer.render(guarded).supported());
    }

    @Test void bothLoweringsBindTheVariableAndDistinguishLimitPoints() {
        var sequence = new Binder(BinderKind.SEQUENCE, Sort.NATURAL, app("of_nat", n));
        var function = new Binder(BinderKind.FUNCTION, Sort.REAL, n);
        var request = theorem(List.of(), List.of(), app("and", app("limit_at", function, zero, zero),
            app("eq", app("at", sequence, app("nat", new Literal("2"))), new Literal("2"))));
        var material = new LeanSourceRenderer().render(request);
        assertTrue(material.supported(), material.issues().toString());
        assertTrue(material.theoremType().contains("Filter.Tendsto (fun (b0 : Real) => b0) (nhds (0 : Real)) (nhds (0 : Real))"));
        assertTrue(LeanStatementReconstruction.theorem(sets()).contains("(Set.Mem ((fun "));
        String rebuilt = LeanStatementReconstruction.theorem(request);
        assertTrue(rebuilt.contains("Filter.Tendsto (fun (local_0 : ℝ) => local_0) (nhds (0 : ℝ)) (nhds (0 : ℝ))"));
        assertNotEquals(LeanStatementReconstruction.theorem(limit(zero)), LeanStatementReconstruction.theorem(limit(new Literal("1"))));
    }

    @Test void v2RequiresTheImportedDependencyAuditAndUnambiguousNames() throws Exception {
        var request = witness();
        String nonce = "1".repeat(32);
        String prefix = "REGELSUCHE_LEAN_AUDIT ";
        String entry = "{\"name\":\"Summable.add\",\"module\":\"Mathlib.Example\"}";
        String v1 = "{\"schema\":\"regelsuche.lean-audit/v1\",\"obligationHash\":\"" + request.contentHash()
            + "\",\"nonce\":\"" + nonce + "\",\"theorem\":\"regelsuche_bound\",\"axioms\":[]}";
        String v2 = v1.replace("/v1", "/v2").replace("}", ",\"imports\":[" + entry + "]}");
        assertTrue(LeanSolverBackend.checkedProcessAudit(prefix + v2, request, nonce).imports().stream()
            .anyMatch(d -> d.name().equals("Summable.add")));
        for (String bad : List.of(v1, v1.replace("/v1", "/v2"), v2.replace(entry, entry + "," + entry),
                v2.replace("Mathlib.Example", ""), v2.replace("\"axioms\":[]", "\"axioms\":[\"sorryAx\"]"))) {
            assertThrows(java.io.IOException.class, () -> LeanSolverBackend.checkedProcessAudit(prefix + bad, request, nonce));
        }
    }

    @Test void weakerBackendsRejectBeforeAttempt(@TempDir Path temp) {
        var request = summableAdd();
        assertEquals(ResultStatus.UNSUPPORTED, new PolynomialNormalFormSolverBackend().execute(request).result().status());
        assertEquals(ResultStatus.UNSUPPORTED, new RegelsucheSearchBackend().execute(request).result().status());
        assertFalse(new SmtLibRenderer().render(request).issues().isEmpty());
        assertFalse(StandardPortfolioBackends.polynomialNormalForm().profile().capabilityIssues(request).isEmpty());
        var profile = StandardPortfolioBackends.lean(new LeanSolverBackend(temp,temp), BackendAvailability.AVAILABLE).profile();
        assertTrue(profile.capabilityIssues(request).isEmpty());
    }
}
