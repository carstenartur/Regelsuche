package de.regelsuche.search.strategy;

import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import de.regelsuche.search.program.ProgrammedTransformationEngine;
import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.search.program.RewriteExecution;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.search.program.RewriteProgramInterpreter;
import de.regelsuche.search.program.ExactPolynomialTransformationSource;
import de.regelsuche.search.program.BudgetedTransformationSource;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationWorkMetrics;
import de.regelsuche.transform.MeasuredTransformationEngine;
import java.util.List;
import java.util.Objects;

/** Expansion contract for the common work-budget frontier. */
public sealed interface SearchExpansionSource {
    RewriteExecution expand(String expression, PathBudget remainingPathBudget);

    WorkRevision workRevision();

    enum WorkRevision {
        MECHANICAL_V1("regelsuche.search-work/v1"),
        MIXED_V2("regelsuche.search-work/v2");

        private final String schema;

        WorkRevision(String schema) { this.schema = schema; }

        public String schema() { return schema; }
    }

    /** Frozen mechanical accounting for historical primitive-only evaluations. */
    record Measured(MeasuredTransformationEngine engine) implements SearchExpansionSource {
        public Measured { Objects.requireNonNull(engine, "engine"); }

        @Override public RewriteExecution expand(String expression, PathBudget remainingPathBudget) {
            if (engine instanceof ProgrammedTransformationEngine programmed) {
                return programmed.execute(expression);
            }
            var batch = engine.transformMeasured(expression);
            var candidates = batch.transformations().stream()
                .map(step -> new RewriteCandidate("search-source", expression,
                    step.transformedExpression(), List.of(step))).toList();
            return new RewriteExecution(candidates, true, batch.workMetrics());
        }

        @Override public WorkRevision workRevision() { return WorkRevision.MECHANICAL_V1; }
    }

    /** Uses ordinary Source nodes, including verified theory, under explicit authority. */
    record Program(RewriteProgram program) implements SearchExpansionSource {
        public Program { Objects.requireNonNull(program, "program"); }

        @Override public RewriteExecution expand(String expression, PathBudget remainingPathBudget) {
            return new RewriteProgramInterpreter().executeWithWorkBudget(
                program, expression, remainingPathBudget);
        }

        @Override public WorkRevision workRevision() { return WorkRevision.MIXED_V2; }
    }

    /** Actual best-first frontier handoff; retains theory evidence and delegated mechanics separately. */
    record ExactPolynomial(ExactPolynomialTransformationSource source) implements SearchExpansionSource {
        public ExactPolynomial { Objects.requireNonNull(source, "source"); }

        @Override public RewriteExecution expand(String expression, PathBudget remainingPathBudget) {
            Objects.requireNonNull(remainingPathBudget, "remainingPathBudget");
            synchronized (source) {
                var result = source.transform(expression, remainingPathBudget.exactTheoryWorkUnits());
                var capability = source.lastVerifiedExecution();
                List<RewriteCandidate> candidates = capability.map(verified -> {
                    if (!verified.result().equals(result)) throw new IllegalStateException("polynomial execution changed during handoff");
                    var transformation = Transformation.exactTheory(ExactTheoryEvidence.fromVerified(verified));
                    return List.of(new RewriteCandidate(source.identity().sourceId(), expression,
                        transformation.transformedExpression(), List.of(transformation)));
                }).orElse(List.of());
                var observations = candidates.stream().map(candidate ->
                    new RewriteExecution.SourceObservation(candidate, remainingPathBudget,
                        remainingPathBudget.admits(candidate.executionWork()))).toList();
                var mathematical = candidates.stream().map(RewriteCandidate::executionWork)
                    .reduce(ExecutionWork.ZERO, ExecutionWork::plus);
                var mechanics = TransformationWorkMetrics.flatEngine(candidates.size())
                    .withDelegatedMechanicalWork(result.mechanicalWorkUnits()).withCandidateWork(mathematical);
                boolean complete = result.status() != BudgetedTransformationSource.Status.BUDGET_INCONCLUSIVE
                    && observations.stream().allMatch(RewriteExecution.SourceObservation::admitted);
                return new RewriteExecution(observations.stream().filter(RewriteExecution.SourceObservation::admitted)
                    .map(RewriteExecution.SourceObservation::candidate).toList(), complete, mechanics, observations,
                    remainingPathBudget);
            }
        }

        @Override public WorkRevision workRevision() { return WorkRevision.MIXED_V2; }
    }
}
