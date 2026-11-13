package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverIr.Goal;
import de.regelsuche.solver.ir.SolverIr.Literal;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SolverObligationVerifier;
import java.util.List;
import java.util.Objects;

/** Checks whether the exact declared premises have a model in the bounded Z3 fragment. */
public final class AssumptionConsistencyAnalyzer {
    private static final String CONFIGURATION = "assumption-consistency/v1";
    private static final Goal CONTRADICTION = new Goal(
        Relation.EQUALS, new Literal("0"), new Literal("1"));

    private final PortfolioBudget budget;
    private final PortfolioBackend backend;

    public AssumptionConsistencyAnalyzer(
        Z3SmtSolverBackend.Detection z3,
        PortfolioBudget budget
    ) {
        Objects.requireNonNull(z3, "z3");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.backend = StandardPortfolioBackends.z3(z3.backend(), z3.availability());
    }

    public Analysis analyze(Obligation original) {
        Objects.requireNonNull(original, "original");
        PortfolioRequest request = PortfolioRequest.create(
            premiseObligation(original), SolverObjective.FORMAL_PROOF,
            PortfolioPolicy.CAPABILITY_FIRST, budget, CONFIGURATION);
        // Each explicit analysis retries transient process failures from earlier calls.
        PortfolioRun run = new SolverPortfolioExecutor(List.of(backend)).execute(request);
        return new Analysis(original, request, run);
    }

    private static Obligation premiseObligation(Obligation original) {
        // Z3 asserts the negation of the goal. Here that adds only a tautology.
        return Obligation.create(CONFIGURATION + '/' + original.contentHash(),
            original.declarations(), original.theories(), original.assumptions(),
            CONTRADICTION, RequestedEvidence.FORMAL_PROOF, original.provenance());
    }

    public enum Verdict {
        SAT,
        UNSAT,
        INCONCLUSIVE
    }

    /** The portfolio evidence is bound to the derived query for the original obligation. */
    public static final class Analysis {
        private final Obligation original;
        private final PortfolioRequest request;
        private final PortfolioRun run;

        private Analysis(Obligation original, PortfolioRequest request, PortfolioRun run) {
            this.original = original;
            this.request = request;
            this.run = run;
        }

        public Obligation original() {
            return original;
        }

        public PortfolioRequest request() {
            return request;
        }

        public PortfolioRun run() {
            return run;
        }

        public Verdict verdict() {
            var execution = run.selectedExecution();
            if (execution == null || execution.translation().status() != TranslationStatus.LOSSLESS
                    || !new SolverObligationVerifier().resultBelongsTo(request.obligation(), execution.result())) {
                return Verdict.INCONCLUSIVE;
            }
            return switch (run.report().outcome()) {
                case REFUTED -> execution.result().status() == ResultStatus.REFUTED
                    ? Verdict.SAT : Verdict.INCONCLUSIVE;
                case CONFIRMED -> run.report().proofAuthorized()
                    && execution.result().status() == ResultStatus.CONFIRMED
                    ? Verdict.UNSAT : Verdict.INCONCLUSIVE;
                default -> Verdict.INCONCLUSIVE;
            };
        }
    }
}
