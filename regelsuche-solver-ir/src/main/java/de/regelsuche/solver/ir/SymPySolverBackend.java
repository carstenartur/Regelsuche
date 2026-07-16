package de.regelsuche.solver.ir;

import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** External symbolic-equivalence adapter using the existing SymPy bridge contract. */
public final class SymPySolverBackend implements SolverBackend {
    private final CoreExpressionIrAdapter expressions = new CoreExpressionIrAdapter();
    private final EquivalenceService equivalence;
    private final BackendDescriptor descriptor;

    public SymPySolverBackend() {
        this(new SymPyEquivalenceService(), "sympy-equivalence", "1");
    }

    public SymPySolverBackend(
        EquivalenceService equivalence,
        String backendId,
        String backendVersion
    ) {
        this.equivalence = Objects.requireNonNull(equivalence, "equivalence");
        descriptor = new BackendDescriptor(
            backendId,
            backendVersion,
            List.of(Theory.REAL_ARITHMETIC),
            List.of(Relation.EQUALS),
            List.of(RequestedEvidence.DECISION,
                RequestedEvidence.SYMBOLIC_CERTIFICATE),
            true);
    }

    @Override
    public BackendDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public SolverResult solve(Obligation obligation) {
        Objects.requireNonNull(obligation, "obligation");
        List<String> issues = SolverBackendSupport.issues(
            obligation, descriptor, false);
        if (!issues.isEmpty()) {
            return SolverBackendSupport.unsupported(obligation, descriptor, issues);
        }
        try {
            String left = expressions.render(obligation.goal().left());
            String right = expressions.render(obligation.goal().right());
            boolean confirmed = equivalence.areEquivalent(left, right);
            String evidence = equivalence.evidence(left, right);
            ResultStatus status = classify(confirmed, evidence);
            String certificate = status == ResultStatus.CONFIRMED
                ? SolverIr.sha256(evidence) : "";
            return SolverResult.create(
                obligation,
                descriptor,
                status,
                TranslationStatus.LOSSLESS,
                List.of("SYMBOLIC_EQUIVALENCE"),
                List.of(),
                evidence,
                Map.of(),
                certificate);
        } catch (RuntimeException exception) {
            return SolverResult.create(
                obligation,
                descriptor,
                ResultStatus.ERROR,
                TranslationStatus.LOSSLESS,
                List.of("SYMBOLIC_EQUIVALENCE"),
                List.of(),
                exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                Map.of(),
                "");
        }
    }

    private static ResultStatus classify(boolean confirmed, String evidence) {
        if (confirmed) {
            return ResultStatus.CONFIRMED;
        }
        String normalized = evidence == null
            ? "" : evidence.toLowerCase(Locale.ROOT);
        if (normalized.contains("not equivalent")
                || normalized.contains("counterexample")
                || normalized.contains("refut")) {
            return ResultStatus.REFUTED;
        }
        return ResultStatus.UNKNOWN;
    }
}
