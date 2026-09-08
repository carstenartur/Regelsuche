package de.regelsuche.search.strategy;

import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import de.regelsuche.search.program.ProgrammedTransformationEngine;
import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.search.program.RewriteExecution;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.search.program.RewriteProgramInterpreter;
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
}
