package de.regelsuche.evolution;

import de.regelsuche.evolution.StructuralConstructionPrograms.Work;
import de.regelsuche.search.moves.IncrementalProviderContract.*;
import de.regelsuche.search.moves.IncrementalProviderContract;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.MoveVerifier;
import de.regelsuche.search.moves.RegisteredIncrementalMoveProvider;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationProvenance;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/** Finite, query-owned residual alternatives. Only a fresh source replay can issue a search edge. */
public final class ResidualPlanProvider implements MoveVerifier {
    private static final String ID = "residual-proof-plans/v1";
    private final MoveState state;
    private final MoveContext context;
    private final ResidualPlanController.Mode mode;
    private final int mask;
    private final long formationBudget, replayBudget;
    private final String scope;
    private final RegisteredIncrementalMoveProvider provider;

    public ResidualPlanProvider(MoveState state, MoveContext context, ResidualPlanController.Mode mode, int mask,
                                long formationBudget, long replayBudget) {
        this.state = Objects.requireNonNull(state);
        this.context = Objects.requireNonNull(context);
        this.mode = Objects.requireNonNull(mode);
        if (mask < 0 || mask > 7) throw new IllegalArgumentException("selector mask must be in [0,7]");
        this.mask = mask;
        this.formationBudget = Work.requireLimit(formationBudget);
        this.replayBudget = Work.requireLimit(replayBudget);
        scope = SchematicProofPlan.hash(CheckedSchemaSupport.JSON.valueToTree(List.of(ID, state.expression(),
            state.searchDepth(), state.primitiveDepth(), state.previousRule(), state.assumptions(),
            new TreeSet<>(state.capabilities()), state.complexityDebt(), context, mode, mask, formationBudget, replayBudget)).toString());
        var descriptor = new MoveProvider.Descriptor(ID, "residual-composition", SearchMove.SourceKind.PREPARATION,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, scope);
        var definition = new Definition(IncrementalProviderContract.PREPAID_REVISION, ID, Kind.REGISTERED_SCHEMA, scope,
            ResidualPlanCompiler.REVISION, Transport.PARSER_TEXT, Mathematics.EXACT, null);
        var registration = new Registration(definition, (offered, receiving, meter) -> {
            if (!matches(offered, receiving)) return new Source() {
                public Optional<Transformation> next(long allowance) { return Optional.empty(); }
                public Status status() { return Status.EXHAUSTED; }
            };
            return new Plans(meter);
        });
        provider = new RegisteredIncrementalMoveProvider(descriptor, definition, new Registry(List.of(registration)));
    }

    public RegisteredIncrementalMoveProvider provider() { return provider; }

    private boolean matches(MoveState offered, MoveContext receiving) {
        return state.equals(offered) && context.equals(receiving);
    }

    private final class Plans implements Source {
        private final Meter meter;
        private final Work work = new Work(formationBudget);
        private ResidualPlanController.Session session;
        private ResidualPlanController.Step pending;
        private Work replayWork;
        private PrepaidApplication ticket;
        private StructuralConstructionPrograms.ResidualPreparation replayPrefix;
        private Status status = Status.READY;
        private boolean replayIncomplete;

        Plans(Meter meter) { this.meter = meter; }
        @Override public Status status() { return status; }

        @Override public Optional<Transformation> next(long allowance) {
            if (allowance == 0) { status = Status.LIMIT; return Optional.empty(); }
            if (pending != null) return replay(allowance);
            if (session == null) session = new ResidualPlanController.Session(
                normalizedSource(), mode, mask, formationBudget, work);
            boolean preparing = session.status().equals("PREPARING");
            long before = work.units();
            try {
                if (preparing) session.prepareNext();
                else {
                    var step = session.advance();
                    if (!step.proofRoots().isEmpty()) pending = step;
                }
            } finally {
                meter.charge(preparing ? Operation.LOAD : Operation.MATCH, work.units() - before);
            }
            status = nextStatus(work.units() - before >= allowance);
            return Optional.empty();
        }

        private Status nextStatus(boolean spent) {
            Status continuing = spent ? Status.LIMIT : Status.READY;
            if (pending != null) return continuing;
            return switch (session.status()) {
                case "COMPLETE" -> replayIncomplete ? Status.INCONCLUSIVE : Status.EXHAUSTED;
                case "UNSUPPORTED" -> Status.EXHAUSTED;
                case "BUDGET_EXHAUSTED" -> Status.INCONCLUSIVE;
                default -> continuing;
            };
        }

        private Optional<Transformation> replay(long allowance) {
            if (ticket == null) {
                ticket = meter.beginPrepaidApplication();
                replayWork = new Work(replayBudget);
            }
            long before = replayWork.units();
            Transformation candidate = null;
            boolean exhausted = false;
            try {
                if (replayPrefix == null) {
                    String source = normalizedSource();
                    replayWork.add(source.length() + 1L);
                    replayPrefix = new StructuralConstructionPrograms.ResidualPreparation(source, replayWork, true);
                }
                if (!replayPrefix.done) replayPrefix.advance();
                else {
                    var checked = ResidualPlanCompiler.checkPrepared(replayPrefix.source, pending.effects(), replayPrefix.result, replayWork);
                    if (!List.of(checked.proofRoot()).equals(pending.proofRoots()))
                        throw new IllegalStateException("source replay differs from selected residual plan");
                    candidate = issue(checked);
                }
            } catch (StructuralConstructionPrograms.Limit refused) {
                exhausted = true;
                replayIncomplete = true;
            } finally {
                meter.prepay(ticket, ApplicationPhase.EVIDENCE, replayWork.units() - before);
            }
            boolean spent = replayWork.units() - before + 1 >= allowance;
            if (exhausted) meter.abandon(ticket);
            else if (candidate != null) meter.complete(ticket, candidate.executionWork());
            else { status = spent ? Status.LIMIT : Status.READY; return Optional.empty(); }
            ticket = null;
            pending = null;
            replayPrefix = null;
            replayWork = null;
            status = nextStatus(spent);
            return Optional.ofNullable(candidate);
        }

        @Override public void close() {
            // The managed cursor closes any open payment ticket after this callback.
            pending = null;
            session = null;
            replayPrefix = null;
            ticket = null;
            replayWork = null;
        }
    }

    private String normalizedSource() { return state.expression().strip().replaceAll("\\s+", " "); }

    private Transformation issue(ResidualPlanCompiler.Bound checked) {
        var data = CheckedSchemaSupport.JSON.createObjectNode();
        data.put("scope", scope);
        data.put("root", checked.proofRoot());
        data.put("resolution", checked.resolution().contentHash());
        data.set("effects", CheckedSchemaSupport.JSON.valueToTree(checked.effects()));
        String json = data.toString();
        var binding = new ExactTheoryEvidence.Binding(state.expression(), checked.expression(), ID,
            SchematicProofPlan.hash(json), checked.proofRoot(), scope, checked.checkWork(), json);
        return Transformation.exactTheory(ExactTheoryEvidence.fromVerified(new Replayed(binding)));
    }

    static final class Replayed {
        final ExactTheoryEvidence.Binding binding;
        private Replayed(ExactTheoryEvidence.Binding binding) { this.binding = binding; }
    }

    /** Reconstruct the current receiver's exact source, bindings and obligations, including on repeated offers. */
    @Override public Verification verify(MoveState offered, SearchMove move, MoveContext receiving) {
        var work = new Work(replayBudget);
        if (!matches(offered, receiving) || !(move.provenance() instanceof TransformationProvenance.ExactTheoryStep exact)
                || !exact.evidence().issuedFrom(Replayed.class) || !scope.equals(exact.evidence().binding().runArtifactId()))
            return new Verification(false, 1, List.of(), "FOREIGN_RESIDUAL_SCOPE");
        try {
            var ids = CheckedSchemaSupport.read(exact.evidence().binding().canonicalEvidenceJson()).required("effects");
            var checked = ResidualPlanCompiler.bind(offered.expression(), List.of(ids.required(0).asText(), ids.required(1).asText()), work);
            if (SearchMove.from(issue(checked), provider.descriptor(), move.generationCost()).equals(move))
                return new Verification(true, 1 + work.units(), List.of(checked.proofRoot()), "EXACT_RESIDUAL_REPLAY");
        } catch (IllegalArgumentException refused) {
            // A rejected or exhausted reconstruction still pays its completed work.
        }
        return new Verification(false, 1 + work.units(), List.of(), "RESIDUAL_REPLAY_REFUSED");
    }
}
