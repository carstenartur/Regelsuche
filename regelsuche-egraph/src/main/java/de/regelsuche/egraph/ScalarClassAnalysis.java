package de.regelsuche.egraph;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.*;
import de.regelsuche.egraph.proof.ScalarGuardFacts;
import de.regelsuche.scalar.ExactRational;
import java.util.*;

/** Finite descending sign/sort lattice. make reads child classes, merge intersects evidence and retains contradictions. */
final class ScalarClassAnalysis {
    private final EGraph graph;
    private final List<Assumption> context;
    private final Map<EClassId,ScalarGuardFacts> facts = new HashMap<>();
    private final Map<EClassId,ScalarShapeFacts> shapes = new HashMap<>();
    private long evaluations;
    ScalarClassAnalysis(EGraph graph, List<Assumption> context) { this.graph = graph; this.context = context; }
    ScalarGuardFacts get(EClassId id) { return facts.getOrDefault(graph.find(id), ScalarGuardFacts.UNKNOWN); }
    ScalarShapeFacts shape(EClassId id) { return shapes.getOrDefault(graph.find(id),ScalarShapeFacts.EMPTY); }
    List<NativeScalarSaturation.Analysis> snapshot() {
        return graph.classes().stream().map(EClass::id).sorted().map(id ->
            new NativeScalarSaturation.Analysis(id.value(),get(id),shape(id))).toList();
    }
    long evaluations() { return evaluations; }

    void update(Collection<EClassId> dirty) {
        // Dirty closure includes affected parents. At least one of the five possible bits disappears per refinement.
        boolean changed;
        do {
            changed = false;
            for (var id : dirty) {
                var root = graph.find(id);
                var merged = get(root);
                var shape = shape(root);
                for (var node : graph.classOrThrow(root).nodes()) {
                    merged = merged.merge(make(node));
                    shape = shape.merge(ScalarShapeFacts.make(node,node.children().stream().map(this::shape).toList()));
                    evaluations++;
                }
                if (shape.contradictory()) merged = merged.merge(new ScalarGuardFacts(0,0,false));
                if (!shape.equals(shape(root))) { shapes.put(root,shape); changed=true; }
                if (!merged.equals(get(root))) { facts.put(root, merged); changed = true; }
            }
        } while (changed);
    }
    private ScalarGuardFacts make(ENode node) {
        String symbol = node.symbol();
        if (symbol.startsWith("num:")) return ScalarGuardFacts.of(new NumberExpr(ExactRational.fromCanonicalText(symbol.substring(4))),context);
        if (symbol.startsWith("var:")) return ScalarGuardFacts.variable(symbol.substring(4),context);
        if (!symbol.startsWith("op:") && !symbol.startsWith("ac:")) return ScalarGuardFacts.UNKNOWN;
        var operator = BinaryOperator.valueOf(symbol.substring(3));
        var value = get(node.children().getFirst());
        for (int i=1;i<node.children().size();i++) value = ScalarGuardFacts.combine(operator,value,get(node.children().get(i)));
        return value;
    }
}
