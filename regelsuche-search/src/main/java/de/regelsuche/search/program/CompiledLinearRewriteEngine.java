package de.regelsuche.search.program;

import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationBatch;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Compiles a flat Source/Sequence program to a bounded source pipeline once.
 * Execution retains all primitive paths but does not traverse interpreter nodes.
 * Branching, guards, nested programs and theory sources use the ordinary interpreter.
 */
public final class CompiledLinearRewriteEngine implements MeasuredTransformationEngine {
    public static final String REVISION = "regelsuche.compiled-linear-rewrite/v1";
    private final String programId;
    private final List<RewriteProgram.Source> sources;
    private final int maximumCandidates;

    public CompiledLinearRewriteEngine(RewriteProgram program, int maximumCandidates) {
        if (maximumCandidates < 1 || maximumCandidates > 128) throw new IllegalArgumentException("invalid pipeline candidate bound");
        this.maximumCandidates = maximumCandidates;
        this.programId = program.id();
        List<RewriteProgram> nodes = program instanceof RewriteProgram.Sequence sequence ? sequence.steps() : List.of(program);
        if (nodes.isEmpty() || nodes.size() > 8) throw new IllegalArgumentException("linear pipeline requires one to eight sources");
        List<RewriteProgram.Source> compiled = new ArrayList<>();
        for (var node : nodes) {
            if (!(node instanceof RewriteProgram.Source source)
                    || !(source.engine() instanceof PreparedAstRewriteTransformationEngine
                        || source.engine() instanceof AstRewriteTransformationEngine)) {
                throw new IllegalArgumentException("linear compilation supports plain primitive AST sources only");
            }
            compiled.add(source);
        }
        sources = List.copyOf(compiled);
    }

    @Override
    public TransformationBatch transformMeasured(String expression) {
        expression = java.util.Objects.requireNonNull(expression, "expression").trim().replaceAll("\\s+", " ");
        if (expression.isEmpty()) throw new IllegalArgumentException("expression must not be blank");
        List<RewriteCandidate> current = List.of();
        long calls = 0, emitted = 0, composed = 0, duplicates = 0;
        for (int index = 0; index < sources.size(); index++) {
            var source = sources.get(index);
            List<RewriteCandidate> next = new ArrayList<>();
            int prefixes = index == 0 ? 1 : current.size();
            for (int p = 0; p < prefixes; p++) {
                String input = index == 0 ? expression : current.get(p).outputExpression();
                var steps = new ArrayList<>(source.engine().transform(input));
                Transformation.requirePrimitiveOnly(steps);
                steps.sort(RewriteProgramInterpreter.TRANSFORMATION_ORDER);
                calls++;
                emitted = Math.addExact(emitted, steps.size());
                for (var step : steps) {
                    if (next.size() >= maximumCandidates) throw new IllegalArgumentException("linear pipeline candidate bound exceeded");
                    var suffix = new RewriteCandidate(source.id(), input, step.transformedExpression(), List.of(step));
                    next.add(index == 0 ? suffix : current.get(p).append(suffix, programId));
                    if (index != 0) composed++;
                }
            }
            current = retainStageCandidates(next, index);
            duplicates = Math.addExact(duplicates, next.size() - current.size());
            if (current.isEmpty()) break;
        }
        return new TransformationBatch(current.stream().map(candidate -> candidate.withOriginNodeId(programId).toTransformation()).toList(),
            new TransformationWorkMetrics(1, 0, calls, emitted, composed, 0, 0, 0, 0, 0, 0, 0, 0, duplicates));
    }

    private List<RewriteCandidate> retainStageCandidates(List<RewriteCandidate> candidates, int index) {
        // The interpreter composes every first-Source prefix before deduplicating a Sequence stage.
        if (index == 0 && sources.size() > 1) return List.copyOf(candidates);
        var distinct = new LinkedHashMap<RewriteCandidate.Identity, RewriteCandidate>();
        candidates.forEach(candidate -> distinct.putIfAbsent(candidate.identity(), candidate));
        return List.copyOf(distinct.values());
    }
}
