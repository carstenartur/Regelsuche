package de.regelsuche.search.program;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Explicit AST execution of an existing linear program. Native source emission order,
 * structural histories and work accounting are separate from historical text execution.
 * Replay establishes reproducibility under the supplied rules, not their proof authority.
 */
public final class CompiledAstRewriteProgram {
    public static final String REVISION = "regelsuche.compiled-linear-rewrite/ast-v1";

    /** All intermediate states and their source-node/rule metadata, not an occurrence certificate. */
    public record Candidate(String programId, List<String> sourceIds, List<AstRewriteTransport.Step> steps) {
        public Candidate {
            requireId(programId);
            sourceIds = List.copyOf(sourceIds);
            steps = List.copyOf(steps);
            if (steps.isEmpty() || steps.size() > 8 || sourceIds.size() != steps.size()) {
                throw new IllegalArgumentException("require one through eight source-bound primitive steps");
            }
            sourceIds.forEach(CompiledAstRewriteProgram::requireId);
            for (int i = 1; i < steps.size(); i++) {
                if (!steps.get(i - 1).target().equals(steps.get(i).source())) {
                    throw new IllegalArgumentException("disconnected typed primitive path");
                }
            }
        }
        public Expr source() { return steps.getFirst().source(); }
        public Expr target() { return steps.getLast().target(); }
        public List<Expr> states() {
            var states = new ArrayList<Expr>();
            states.add(source());
            steps.forEach(step -> states.add(step.target()));
            return List.copyOf(states);
        }
        public List<String> assumptions() {
            return AssumptionSignature.ofExpressions(steps.stream()
                .flatMap(step -> step.assumptions().stream()).toList()).normalizedAssumptions();
        }
    }

    /** Mechanical source/candidate/composition events; not AST-node, CPU or allocation costs. */
    public record Batch(List<Candidate> candidates, TransformationWorkMetrics workMetrics) {
        public Batch {
            candidates = List.copyOf(candidates);
            Objects.requireNonNull(workMetrics, "workMetrics");
        }
    }

    /** Regeneration work is returned separately instead of being hidden in a boolean check. */
    public record Replay(Expr target, TransformationWorkMetrics workMetrics) {
        public Replay {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(workMetrics, "workMetrics");
        }
    }

    private record Stage(String sourceId, AstRewriteTransport transport) {}
    private final String programId;
    private final List<Stage> stages;
    private final int maximumCandidates;

    CompiledAstRewriteProgram(String programId, List<RewriteProgram.Source> sources, int maximumCandidates) {
        requireId(programId);
        var retained = List.copyOf(sources);
        if (retained.isEmpty() || retained.size() > 8 || maximumCandidates < 1 || maximumCandidates > 128) {
            throw new IllegalArgumentException("invalid typed pipeline bounds");
        }
        // Reject an unsupported stage before any source can run. In particular, do not
        // replace a custom reference-engine override with a base prepared implementation.
        for (var source : retained) {
            if (!(source.engine() instanceof PreparedAstRewriteTransformationEngine)) {
                throw new IllegalArgumentException("typed compilation requires prepared AST sources");
            }
        }
        this.programId = programId;
        this.maximumCandidates = maximumCandidates;
        stages = retained.stream().map(source -> new Stage(source.id(),
            ((PreparedAstRewriteTransformationEngine) source.engine()).astTransport())).toList();
    }

    /** Complete paths only. Exceeding a candidate/structural limit throws, never silently truncates. */
    public Batch transformMeasured(Expr expression) {
        Objects.requireNonNull(expression, "expression");
        List<Candidate> current = List.of();
        long calls = 0, emitted = 0, composed = 0, duplicates = 0;
        for (int index = 0; index < stages.size(); index++) {
            var stage = stages.get(index);
            var next = new ArrayList<Candidate>();
            int prefixes = index == 0 ? 1 : current.size();
            for (int p = 0; p < prefixes; p++) {
                Expr input = index == 0 ? expression : current.get(p).target();
                var generated = stage.transport().generate(input);
                calls++;
                emitted = Math.addExact(emitted, generated.size());
                for (var step : generated) {
                    if (next.size() >= maximumCandidates) {
                        throw new IllegalArgumentException("typed linear pipeline candidate bound exceeded");
                    }
                    var steps = new ArrayList<AstRewriteTransport.Step>();
                    var ids = new ArrayList<String>();
                    if (index != 0) {
                        steps.addAll(current.get(p).steps());
                        ids.addAll(current.get(p).sourceIds());
                        composed++;
                    }
                    steps.add(step);
                    ids.add(stage.sourceId());
                    next.add(new Candidate(programId, ids, steps));
                }
            }
            // Equal endpoints alone cannot collapse histories with different intermediate states,
            // rule metadata or side conditions. First structural history wins deterministically.
            current = List.copyOf(new LinkedHashSet<>(next));
            duplicates = Math.addExact(duplicates, next.size() - current.size());
            if (current.isEmpty()) break;
        }
        var metrics = new TransformationWorkMetrics(1, 0, calls, emitted, composed, 0, 0, 0, 0, 0, 0, 0, 0, duplicates)
            .withCandidateWork(new ExecutionWork(emitted, 0, 0));
        return new Batch(current, metrics);
    }

    /**
     * Regenerate the entire bounded program and require exact candidate equality. This includes
     * stage IDs, all intermediate ASTs, rule metadata and assumptions. Public records do not
     * authorize a candidate; side conditions remain obligations, not discharged facts.
     */
    public Replay replay(Expr source, Candidate candidate) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(candidate, "candidate");
        if (!programId.equals(candidate.programId()) || !source.equals(candidate.source())
                || !stages.stream().map(Stage::sourceId).toList().equals(candidate.sourceIds())) {
            throw new IllegalArgumentException("candidate is not bound to this program and source");
        }
        var regenerated = transformMeasured(source);
        if (!regenerated.candidates().contains(candidate)) {
            throw new IllegalArgumentException("candidate differs from complete typed program regeneration");
        }
        return new Replay(candidate.target(), regenerated.workMetrics());
    }

    /** Decode untrusted persisted data, then regenerate the full program under the supplied source. */
    public Replay replayEncoded(Expr source, byte[] document) {
        return replay(source, new CompiledAstReplayCodec().decode(document));
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("program/source ID must be present");
    }
}
