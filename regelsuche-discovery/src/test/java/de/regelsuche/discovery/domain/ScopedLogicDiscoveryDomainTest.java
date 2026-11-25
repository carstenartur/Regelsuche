package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.*;
import de.regelsuche.solver.ir.ScopedProofSearch;
import de.regelsuche.solver.portfolio.LeanSolverBackend;
import de.regelsuche.solver.portfolio.LeanEvidenceRebuilder;
import de.regelsuche.solver.portfolio.CheckedLeanScopedProofExecutor;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopedLogicDiscoveryDomainTest {
    static final Symbol P = new Symbol("p"), Q = new Symbol("q"), R = new Symbol("r");
    static Obligation context(Expression ignored) {
        return Obligation.create("context",List.of(new SymbolDeclaration("p",Sort.PROPOSITION),
            new SymbolDeclaration("q",Sort.PROPOSITION),new SymbolDeclaration("r",Sort.PROPOSITION)),
            List.of(Theory.SCOPED_ANALYSIS),List.of(Predicate.holds("start",P),
                Predicate.holds("imp-pq",new Call("implies",List.of(P,Q))),
                Predicate.holds("imp-qr",new Call("implies",List.of(Q,R)))),
            Goal.holds(ignored),RequestedEvidence.FORMAL_PROOF,new SourceProvenance("test","logic",SolverIr.sha256("scope")));
    }

    @Test void sourceOnlyFormationRetainsTwoHopProofAndBudgetExhaustion() {
        var first = ScopedProofSearch.discover(context(P),1024);
        assertEquals(first,ScopedProofSearch.discover(context(R),1024));
        assertEquals(List.of(Q,R),first.proposals().stream().map(p -> p.dag().obligation().goal().left()).toList());
        assertEquals(5,first.proposals().getLast().dag().nodes().size());
        assertFalse(first.exhausted());
        assertTrue(ScopedProofSearch.discover(context(Q),1).exhausted());
    }

    @Test void incrementalFormationResumesWithoutReplayingPremisesAndRefusesForeignScope(@TempDir Path dir) {
        var receiver = new CheckedLeanScopedProofExecutor(new LeanSolverBackend(dir.resolve("missing"),dir),
            new LeanEvidenceRebuilder(dir.resolve("other"),dir));
        var domain = new ScopedLogicDiscoveryDomain(receiver,1024);
        var source = context(R);
        var initial = new ScopedLogicDiscoveryDomain.State(source,null,0,false);
        var expected = ScopedProofSearch.discover(source,1024);
        try (var cursor = domain.openPlans(initial)) {
            assertTrue(cursor.next(0).isEmpty());
            assertEquals(0,cursor.work());
            var found = new java.util.ArrayList<ScopedProofSearch.Proposal>();
            while (!cursor.done()) cursor.next(1).ifPresent(found::add);
            assertEquals(expected.proposals(),found);
            assertEquals(expected.work(),cursor.work());
            var dag = found.getFirst().dag();
            assertNotEquals(DiscoveryDomain.EvaluationStatus.CONFIRMED,domain.receivePlan(dag.obligation(),dag).status());
            assertThrows(IllegalArgumentException.class,()->domain.receivePlan(source,dag));
            var changed = Obligation.create(source.obligationId(),source.declarations(), source.theories(),
                List.of(Predicate.holds("bound",new Binder(BinderKind.FORALL,Sort.NATURAL,P))),
                dag.obligation().goal(),source.requestedEvidence(),source.provenance());
            assertThrows(IllegalArgumentException.class,()->domain.receivePlan(changed,dag));
        }
        // Change only the kind of a nested source binder, keeping assumption names and the receiving goal.
        var premises = new java.util.ArrayList<>(source.assumptions());
        var inner = new Binder(BinderKind.FORALL,Sort.NATURAL,P);
        premises.add(Predicate.holds("binding",new Binder(BinderKind.FORALL,Sort.NATURAL,inner)));
        var bound = Obligation.create(source.obligationId(),source.declarations(),source.theories(),premises,
            source.goal(),source.requestedEvidence(),source.provenance());
        try (var cursor = domain.openPlans(new ScopedLogicDiscoveryDomain.State(bound,null,0,false))) {
            java.util.Optional<ScopedProofSearch.Proposal> offer = java.util.Optional.empty();
            while (!cursor.done() && offer.isEmpty()) offer = cursor.next(1);
            var dag = offer.orElseThrow().dag();
            premises.set(premises.size()-1,Predicate.holds("binding",new Binder(BinderKind.FORALL,Sort.NATURAL,
                new Binder(BinderKind.EXISTS,Sort.NATURAL,P))));
            var foreign = Obligation.create(source.obligationId(),source.declarations(),source.theories(),premises,
                dag.obligation().goal(),source.requestedEvidence(),source.provenance());
            assertThrows(IllegalArgumentException.class,()->domain.receivePlan(foreign,dag));
        }
        var stopped = domain.openPlans(initial);
        stopped.next(1);
        stopped.close();
        assertTrue(stopped.done());
        assertTrue(stopped.next(1024).isEmpty());
        assertEquals(1,stopped.work());
    }

    @Test void normalDiscoveryEntryKeepsAProposedGraphSeparateFromCheckedEvidence(@TempDir Path dir) {
        var domain = new ScopedLogicDiscoveryDomain(new CheckedLeanScopedProofExecutor(
            new LeanSolverBackend(dir.resolve("missing"),dir),
            new LeanEvidenceRebuilder(dir.resolve("separate"),dir)),1024);
        var query = context(R);
        var seed = DiscoveryDomain.DiscoverySeed.create("scope",domain.domainId(),query.toCanonicalJson(),"source-only");
        var state = domain.generator().generate(seed).getFirst();
        var successors = domain.operators().getFirst().apply(state);
        assertEquals(2,successors.size());
        assertTrue(domain.objective().assess(successors.getLast().state()).candidateReady());
        var candidate = domain.candidateExtractor().extract(new DiscoveryDomain.CandidateContext<>(state,successors.getLast().state(),List.of()));
        var verdict = domain.evaluator().evaluate(candidate);
        assertNotEquals(DiscoveryDomain.EvaluationStatus.CONFIRMED,verdict.status());
        assertNull(verdict.certificate());
        assertNotEquals(domain.stateCodec().contentHash(state),domain.stateCodec().contentHash(successors.getFirst().state()));
        assertEquals(DiscoveryDomain.CounterexampleStatus.NONE_FOUND,domain.counterexampleGenerator().search(candidate,12).status());
        var run = new DomainDiscoveryRunner().run("logic",domain,seed,new DiscoveryDomain.DiscoveryBudget(2,16,16,16,4,8));
        assertTrue(run.selectedCertificate().isEmpty());
        assertEquals(DomainDiscoveryEvidence.Outcome.INCONCLUSIVE,run.evidence().outcome());
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="REGELSUCHE_REAL_LEAN",matches="true")
    void sourceOnlyDiscoveryRunnerReturnsARealIndependentlyRebuiltProof(@TempDir Path export) throws Exception {
        var root=Path.of("target","checked-proof").toAbsolutePath();
        var receiver=new CheckedLeanScopedProofExecutor(
            new LeanSolverBackend(Path.of(System.getenv("REGELSUCHE_LEAN_PROJECT")),root),
            new LeanEvidenceRebuilder(Path.of(System.getenv("REGELSUCHE_LEAN_REBUILD_PROJECT")),root));
        var domain=new ScopedLogicDiscoveryDomain(receiver,1024);
        var seed=DiscoveryDomain.DiscoverySeed.create("scope",domain.domainId(),context(P).toCanonicalJson(),"public-propositions");
        var result=new DomainDiscoveryRunner().run("real-kernel-domain",domain,seed,new DiscoveryDomain.DiscoveryBudget(2,16,16,16,4,8));
        assertEquals(DomainDiscoveryEvidence.Outcome.CONFIRMED,result.evidence().outcome(),result.evidence().toString());
        var receipt=result.selectedCertificate().orElseThrow();
        assertTrue(java.nio.file.Files.isRegularFile(receipt.kernelDirectory().resolve("proof.olean")));
        assertTrue(java.nio.file.Files.isRegularFile(receipt.rebuildDirectory().resolve("rebuild.txt")));
        assertEquals(result.selectedCandidate().orElseThrow().hash(),receipt.dag().hash());

        try (var cursor = domain.openPlans(domain.generator().generate(seed).getFirst())) {
            var offered = java.util.Optional.<ScopedProofSearch.Proposal>empty();
            while (!cursor.done() && offered.isEmpty()) offered = cursor.next(1);
            var dag = offered.orElseThrow().dag();
            var replay = domain.receivePlan(dag.obligation(),dag);
            assertEquals(DiscoveryDomain.EvaluationStatus.CONFIRMED,replay.status(),replay.summary());
            assertEquals(dag.hash(),replay.certificate().dag().hash());
        }

        new DomainDiscoveryExport().write(export, result.evidence());
        var workspace = DomainExportWorkspace.fromVerified(new DomainDiscoveryExportVerifier().requireVerified(export));
        var checked = ScopedLogicWorkspaceCheck.check(workspace, result.evidence().selectedCandidateHash(), receiver);
        assertEquals(result.selectedCandidate().orElseThrow(), checked.proposal());
        assertEquals(result.evidence().contentHash(), checked.source().evidenceHash());
        var evaluation = checked.freshProof().orElseThrow();
        assertEquals(DiscoveryDomain.EvaluationStatus.CONFIRMED, evaluation.status(), evaluation.summary());
        assertNotEquals(receipt.kernelDirectory(), evaluation.certificate().kernelDirectory());
        assertNotEquals(receipt.rebuildDirectory(), evaluation.certificate().rebuildDirectory());
        assertTrue(java.nio.file.Files.isRegularFile(evaluation.certificate().kernelDirectory().resolve("proof.olean")));
        assertTrue(java.nio.file.Files.isRegularFile(evaluation.certificate().rebuildDirectory().resolve("rebuild.txt")));
    }

    @Test void finiteCountermodelIsAnActualSatisfyingAssignmentForThePremises() {
        var request = context(P);
        var falseTarget = Obligation.create("not-entailed",request.declarations(),request.theories(),List.of(Predicate.holds("p",P)),
            Goal.holds(Q),request.requestedEvidence(),request.provenance());
        var syntactic = de.regelsuche.solver.ir.ScopedProofDag.create(falseTarget,
            List.of(de.regelsuche.solver.ir.ScopedProofDag.Node.premise(0)));
        var result = new ScopedTruthAssignments(o -> new de.regelsuche.solver.portfolio.LeanSourceRenderer()
            .render(o).supported()).search(syntactic,8);
        assertEquals(DiscoveryDomain.CounterexampleStatus.FOUND,result.status());
        assertTrue(result.witness().contains("p=TRUE"));
        assertTrue(result.witness().contains("q=FALSE"));
    }
    @Test
    void modelSearchFailsClosedWhenTheFormalReceiverRejectsItsTheory() {
        var candidate = ScopedProofSearch.discover(context(R), 1024).proposals().getFirst().dag();
        var rejected = new ScopedTruthAssignments(ignored -> false).search(candidate, 8);
        assertEquals(DiscoveryDomain.CounterexampleStatus.UNSUPPORTED, rejected.status());
        assertTrue(rejected.witness().isBlank());
        assertEquals(0, rejected.attempts());
        assertEquals("Constant interpretations require a supported scoped proposition", rejected.metrics().get("reason"));
    }

    @Test void generalCasesDoubleNegationAndWitnessesAreActuallyFormedFromSourceFacts() {
        var template=context(R);
        var n=new Symbol("n"); var f=new Symbol("f");
        var pn=new Call("at",List.of(f,n));
        var declarations=new java.util.ArrayList<>(template.declarations());
        declarations.add(new SymbolDeclaration("n",Sort.NATURAL));
        declarations.add(new SymbolDeclaration("f",Sort.NAT_PREDICATE));
        var notNotP=new Call("not",List.of(new Call("not",List.of(P))));
        var premises=List.of(Predicate.holds("branch",new Call("or",List.of(P,Q))),
            Predicate.holds("left",new Call("implies",List.of(P,R))),Predicate.holds("right",new Call("implies",List.of(Q,R))),
            Predicate.holds("negative",notNotP),Predicate.holds("annotation",pn));
        var source=Obligation.create("other-source",declarations,template.theories(),premises,Goal.holds(Q),
            template.requestedEvidence(),template.provenance());
        var proposals=ScopedProofSearch.discover(source,2048).proposals();
        assertTrue(proposals.stream().anyMatch(x->x.dag().obligation().goal().left().equals(R)
            && x.dag().nodes().getLast().rule()==de.regelsuche.solver.ir.ScopedProofDag.Rule.CASES));
        assertTrue(proposals.stream().anyMatch(x->x.dag().obligation().goal().left().equals(P)
            && x.dag().nodes().getLast().rule()==de.regelsuche.solver.ir.ScopedProofDag.Rule.BY_CONTRADICTION));
        var exists=new Binder(BinderKind.EXISTS,Sort.NATURAL,new Call("at",List.of(f,new Bound(0))));
        assertTrue(proposals.stream().anyMatch(x->x.dag().obligation().goal().left().equals(exists)
            && x.dag().nodes().getLast().rule()==de.regelsuche.solver.ir.ScopedProofDag.Rule.WITNESS));
    }
}
