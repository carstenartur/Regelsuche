package de.regelsuche.discovery.domain;

import de.regelsuche.solver.ir.ScopedProofDag;
import de.regelsuche.solver.ir.ScopedProofSearch;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIrJsonCodec;
import de.regelsuche.solver.portfolio.LeanEvidenceRebuilder;
import de.regelsuche.solver.portfolio.LeanSolverBackend;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Source-only proposals, independently evaluated finite countermodels and separate, rebuilt kernel proofs. */
public final class ScopedLogicDiscoveryDomain implements DiscoveryDomain<ScopedLogicDiscoveryDomain.State,ScopedProofDag,ScopedLogicDiscoveryDomain.Certificate> {
    public record State(Obligation context, ScopedProofDag proposal, int cumulativeWork, boolean exhausted) {
        public State {
            Objects.requireNonNull(context);
            if (!SolverIr.SCOPED_OBLIGATION_SCHEMA.equals(context.schema()) || cumulativeWork < 0)
                throw new IllegalArgumentException("invalid scoped state");
            if (proposal != null) {
                var receiving = Obligation.create(context.obligationId(), context.declarations(), context.theories(), context.assumptions(),
                    proposal.obligation().goal(),context.requestedEvidence(),context.provenance());
                if (!receiving.equals(proposal.obligation())) throw new IllegalArgumentException("proposal changed source context");
            }
        }
    }

    /** Issued only after both the original kernel execution and the independent reconstruction succeed. */
    public static final class Certificate {
        private final ScopedProofDag dag;
        private final String kernel, rebuild;
        private final Path kernelDirectory, rebuildDirectory;
        private Certificate(ScopedProofDag dag, String kernel, Path source, LeanEvidenceRebuilder.Receipt receipt) {
            this.dag = dag; this.kernel = kernel; rebuild = receipt.receiptHash();
            kernelDirectory = source; rebuildDirectory = receipt.directory();
        }
        public ScopedProofDag dag() { return dag; }
        public String kernelHash() { return kernel; }
        public String rebuildHash() { return rebuild; }
        public Path kernelDirectory() { return kernelDirectory; }
        public Path rebuildDirectory() { return rebuildDirectory; }
        String canonical() { return dag.canonical() + kernel + "\n" + rebuild; }
    }

    private final LeanSolverBackend kernel;
    private final LeanEvidenceRebuilder independent;
    private final int formationBudget;

    public ScopedLogicDiscoveryDomain(LeanSolverBackend kernel, LeanEvidenceRebuilder independent, int formationBudget) {
        this.kernel = Objects.requireNonNull(kernel);
        this.independent = Objects.requireNonNull(independent);
        if (formationBudget < 0 || formationBudget > 65536) throw new IllegalArgumentException("invalid formation budget");
        this.formationBudget = formationBudget;
    }
    @Override public String domainId() { return "scoped-logic"; }
    @Override public String revision() { return "v1"; }
    @Override public String stateType() { return "scoped-proof-state/v1"; }
    @Override public String candidateType() { return ScopedProofDag.SCHEMA; }
    @Override public String certificateType() { return "scoped-kernel-rebuild/v1"; }

    @Override public StateGenerator<State> generator() {
        return new StateGenerator<>() {
            public String id() { return "scoped-context/v1"; }
            public List<State> generate(DiscoverySeed seed) {
                if (!seed.domainId().equals(domainId()) || seed.payload().length()>1_000_000) throw new IllegalArgumentException("invalid scoped seed");
                var context = new SolverIrJsonCodec().readObligation(seed.payload());
                if (context.declarations().size()>32 || context.assumptions().size()>32)
                    throw new IllegalArgumentException("bounded logical domain permits at most 32 declarations and assumptions");
                return List.of(new State(context,null,0,false));
            }
        };
    }
    private static <T> CanonicalCodec<T> codec(String id, Function<T,String> encode) {
        return new CanonicalCodec<>() {
            public String id() { return id; }
            public String canonicalForm(T input) { return encode.apply(input); }
        };
    }
    @Override public CanonicalCodec<State> stateCodec() {
        return codec(stateType(), s -> s.context().toCanonicalJson()+"\n"+s.cumulativeWork()+"\n"+s.exhausted()+"\n"
            +(s.proposal()==null?"":s.proposal().canonical()));
    }
    @Override public CanonicalCodec<ScopedProofDag> candidateCodec() {
        return codec(candidateType(),p -> p.obligation().toCanonicalJson()+"\n"+p.canonical());
    }
    @Override public CanonicalCodec<Certificate> certificateCodec() { return codec(certificateType(),Certificate::canonical); }
    @Override public List<Invariant<State>> invariants() {
        return List.of(new Invariant<>() {
            public String id() { return "scoped-work-bound/v1"; }
            public InvariantResult check(State state) {
                return state.cumulativeWork() <= formationBudget ? InvariantResult.pass() : InvariantResult.fail("formation budget exceeded");
            }
        });
    }
    @Override public List<TransitionOperator<State>> operators() {
        return List.of(new TransitionOperator<>() {
            public String id() { return "form-scoped-consequences/v1"; }
            public List<Successor<State>> apply(State s) {
                if (s.proposal()!=null || s.exhausted()) return List.of();
                var report = ScopedProofSearch.discover(s.context(),formationBudget);
                var outputs = new java.util.ArrayList<Successor<State>>();
                for (var p : report.proposals()) {
                    var next = new State(s.context(),p.dag(),p.formationWork(),false);
                    outputs.add(new Successor<>("propose-scoped-dag",next,p.formationWork(),false,List.of(),
                        Map.of("attemptedWork",Integer.toString(report.work()),"formationExhausted",Boolean.toString(report.exhausted()))));
                }
                if (report.exhausted()) outputs.add(new Successor<>("formation-budget-exhausted",
                    new State(s.context(),null,report.work(),true),report.work(),false,List.of(),Map.of()));
                return List.copyOf(outputs);
            }
        });
    }
    @Override public Objective<State> objective() {
        return new Objective<>() {
            public String id() { return "replayable-proof/v1"; }
            public ObjectiveAssessment assess(State s) {
                return new ObjectiveAssessment(s.proposal()==null?0:1,s.proposal()!=null,
                    Map.of("formationWork",Integer.toString(s.cumulativeWork()),"formationExhausted",Boolean.toString(s.exhausted())));
            }
        };
    }
    @Override public CandidateExtractor<State,ScopedProofDag> candidateExtractor() {
        return new CandidateExtractor<>() {
            public String id() { return "scoped-plan/v1"; }
            public ScopedProofDag extract(CandidateContext<State> c) {
                if (!c.initialState().context().equals(c.currentState().context())) throw new IllegalArgumentException("different proof context");
                return Objects.requireNonNull(c.currentState().proposal(),"no proof proposal");
            }
        };
    }
    @Override public CounterexampleGenerator<ScopedProofDag> counterexampleGenerator() { return new ScopedTruthAssignments(); }
    @Override public CandidateEvaluator<ScopedProofDag,Certificate> evaluator() {
        return new CandidateEvaluator<>() {
            public String id() { return "independent-lean-rebuild/v1"; }
            public Evaluation<Certificate> evaluate(ScopedProofDag dag) { return receive(dag); }
        };
    }

    private Evaluation<Certificate> receive(ScopedProofDag dag) {
        var original = kernel.using(dag).executeWithEvidence(dag.obligation());
        var result = original.execution().result();
        var evidence = Map.of("proposal",dag.hash(),"kernelStatus",result.status().name());
        if (result.status()==ResultStatus.UNSUPPORTED) return Evaluation.unsupported(result.message(),evidence);
        if (result.status()!=ResultStatus.CONFIRMED) return Evaluation.inconclusive(result.message(),evidence);
        try {
            var receipt = independent.rebuild(dag.obligation(),result.certificateHash(),original.directory());
            var certificate = new Certificate(dag,result.certificateHash(),original.directory(),receipt);
            return Evaluation.confirmed(certificate,"Scoped theorem checked in both pinned projects",evidence);
        } catch (Exception refused) {
            return Evaluation.inconclusive("Independent proof reconstruction failed: "+refused,
                Map.of("proposal",dag.hash(),"originalKernel",result.certificateHash()));
        }
    }
    @Override public CertificateRenderer<Certificate> certificateRenderer() {
        return new CertificateRenderer<>() {
            public String id() { return "scoped-proof-receipt/v1"; }
            public CertificateRendering render(Certificate c) { return new CertificateRendering(certificateType(),"text",c.canonical()); }
        };
    }
    @Override public EvidenceAdapter<State,ScopedProofDag,Certificate> evidenceAdapter() {
        return new EvidenceAdapter<>() {
            public String id() { return "scoped-proof-evidence/v1"; }
            public DomainPayload adapt(Optional<State> initial,Optional<ScopedProofDag> candidate,Optional<Certificate> certificate) {
                return new DomainPayload(domainId(),Map.of("source",initial.map(s->s.context().contentHash()).orElse(""),
                    "proposal",candidate.map(ScopedProofDag::hash).orElse(""),"kernel",certificate.map(Certificate::kernelHash).orElse(""),
                    "independentRebuild",certificate.map(Certificate::rebuildHash).orElse("")));
            }
        };
    }
}
