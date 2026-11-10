package de.regelsuche.egraph;

import de.regelsuche.ast.Expr;
import de.regelsuche.egraph.proof.ExtractionCertificate;
import de.regelsuche.egraph.proof.ExtractionProofBuilder;
import de.regelsuche.egraph.proof.ProofLimits;
import de.regelsuche.egraph.proof.ProofOutcome;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fresh-graph adapter. Only actual rule instances introduce equality axioms. */
final class EGraphProofCapture {
    private final ExtractionProofBuilder builder;
    private final ProofLimits limits;
    private final Map<EClassId, Integer> anchors = new HashMap<>();
    private ProofOutcome.Status failure;
    private String detail;

    EGraphProofCapture(ExtractionProofBuilder builder, ProofLimits limits) {
        this.builder = builder;
        this.limits = limits;
    }

    boolean active() { return failure == null; }

    /** Insert the preflighted source DAG once per term, without recursive tree expansion. */
    EClassId insertInput(EGraph graph, int source) {
        Map<Integer, EClassId> inserted = new HashMap<>();
        var pending = new ArrayDeque<InputTerm>();
        pending.push(new InputTerm(source, false));
        while (!pending.isEmpty()) {
            charge(1);
            InputTerm item = pending.pop();
            if (inserted.containsKey(item.term())) continue;
            var term = builder.term(item.term());
            charge(1L + term.children().size());
            if (!item.expanded()) {
                pending.push(new InputTerm(item.term(), true));
                for (int i = term.children().size() - 1; i >= 0; i--) {
                    pending.push(new InputTerm(term.children().get(i), false));
                }
            } else {
                var children = new ArrayList<EClassId>(term.children().size());
                for (int child : term.children()) children.add(inserted.get(child));
                String prefix = switch (term.kind()) {
                    case NUMBER -> "num:";
                    case VARIABLE -> "var:";
                    case BINARY -> "op:";
                    case FUNCTION -> "fn:";
                };
                inserted.put(item.term(), graph.add(new ENode(prefix + term.payload(), children)));
            }
        }
        return inserted.get(source);
    }

    private record InputTerm(int term, boolean expanded) {}

    /** Never throw owned proof exhaustion into graph insertion or pending repair. */
    void allocated(EGraph graph, EClassId id, ENode node) {
        if (!active()) return;
        try {
            if (anchors.size() >= limits.maxTerms()) throw new ExtractionProofBuilder.LimitExceeded("anchor limit");
            var children = childTerms(graph, node.children());
            anchors.put(id, builder.addTerm(term(node, children)));
        } catch (ExtractionProofBuilder.LimitExceeded exhausted) {
            fail(ProofOutcome.Status.LIMIT_EXCEEDED, "capture: " + exhausted.getMessage());
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            fail(ProofOutcome.Status.REPLAY_REJECTED, "invalid finite class anchor");
        }
    }

    ExtractionProofBuilder.GroundInstance prepare(EGraph graph, String rule,
            Map<String, EClassId> bindings) {
        if (!active()) return null;
        try {
            if (bindings.size() > limits.maxReferences()) {
                throw new ExtractionProofBuilder.LimitExceeded("binding reference limit");
            }
            Map<String, Integer> terms = new LinkedHashMap<>();
            for (var binding : bindings.entrySet()) {
                charge(1);
                terms.put(binding.getKey(), anchor(graph, binding.getValue()));
            }
            return builder.prepareInstance(rule, terms);
        } catch (ExtractionProofBuilder.LimitExceeded exhausted) {
            fail(ProofOutcome.Status.LIMIT_EXCEEDED, "capture: " + exhausted.getMessage());
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            fail(ProofOutcome.Status.REPLAY_REJECTED, "invalid captured native instance");
        }
        return null;
    }

    void recorded(ExtractionProofBuilder.GroundInstance instance) {
        if (!active() || instance == null) return;
        try {
            builder.recordInstance(instance);
        } catch (ExtractionProofBuilder.LimitExceeded exhausted) {
            fail(ProofOutcome.Status.LIMIT_EXCEEDED, "capture: " + exhausted.getMessage());
        } catch (IllegalArgumentException invalid) {
            fail(ProofOutcome.Status.REPLAY_REJECTED, "invalid captured native equality");
        }
    }

    private List<Integer> childTerms(EGraph graph, List<EClassId> children) {
        if (children.size() > limits.maxReferences()) {
            throw new ExtractionProofBuilder.LimitExceeded("child reference limit");
        }
        charge(1L + children.size());
        List<Integer> result = new ArrayList<>(children.size());
        for (EClassId child : children) result.add(anchor(graph, child));
        return result;
    }

    private int anchor(EGraph graph, EClassId id) {
        Integer term = anchors.get(graph.find(id));
        if (term == null) throw new IllegalStateException("missing finite class anchor");
        return term;
    }

    /** Reconstruct exactly the existing extractor's selected nodes, without recursive expansion. */
    int selectedTerm(EGraph graph, EClassId root, Map<EClassId, ENode> choices) {
        Map<EClassId, Integer> terms = new HashMap<>();
        var visiting = new HashSet<EClassId>();
        var pending = new ArrayDeque<Selection>();
        pending.push(new Selection(graph.find(root), false, 1));
        while (!pending.isEmpty()) {
            charge(1);
            Selection item = pending.pop();
            if (terms.containsKey(item.id())) continue;
            if (item.depth() > limits.maxDepth()) {
                throw new ExtractionProofBuilder.LimitExceeded("selected expression depth");
            }
            ENode node = choices.get(item.id());
            if (node == null) throw new IllegalStateException("selected class has no finite node");
            if (item.expanded()) {
                finishSelection(graph, node, item.id(), terms);
                visiting.remove(item.id());
            } else {
                if (!visiting.add(item.id())) throw new IllegalStateException("cyclic selected expression");
                if (visiting.size() + terms.size() > limits.maxTerms()
                        || node.children().size() > limits.maxReferences()) {
                    throw new ExtractionProofBuilder.LimitExceeded("selected expression workspace");
                }
                charge(1L + node.children().size());
                pending.push(new Selection(item.id(), true, item.depth()));
                for (int i = node.children().size() - 1; i >= 0; i--) {
                    pending.push(new Selection(graph.find(node.children().get(i)), false, item.depth() + 1));
                }
            }
        }
        // addTerm validates expanded occurrence count; materialize enforces it again.
        return terms.get(graph.find(root));
    }

    private void finishSelection(EGraph graph, ENode node, EClassId id, Map<EClassId, Integer> terms) {
        charge(1L + node.children().size());
        List<Integer> children = new ArrayList<>(node.children().size());
        for (EClassId child : node.children()) {
            Integer term = terms.get(graph.find(child));
            if (term == null) throw new IllegalStateException("cyclic selected expression");
            children.add(term);
        }
        terms.put(id, builder.addTerm(term(node, children)));
    }

    private record Selection(EClassId id, boolean expanded, int depth) {}

    private static ExtractionCertificate.Term term(ENode node, List<Integer> children) {
        String symbol = node.symbol();
        if (symbol.startsWith("num:")) return new ExtractionCertificate.Term(
            ExtractionCertificate.Kind.NUMBER, symbol.substring(4), children);
        if (symbol.startsWith("var:")) return new ExtractionCertificate.Term(
            ExtractionCertificate.Kind.VARIABLE, symbol.substring(4), children);
        if (symbol.startsWith("op:")) return new ExtractionCertificate.Term(
            ExtractionCertificate.Kind.BINARY, symbol.substring(3), children);
        if (symbol.startsWith("fn:")) return new ExtractionCertificate.Term(
            ExtractionCertificate.Kind.FUNCTION, symbol.substring(3), children);
        throw new IllegalArgumentException("unsupported e-node symbol");
    }

    String format(Expr expression) {
        long[] remaining = {limits.maxCharacters()};
        return ExpressionFormatter.formatMeasured(expression, units -> {
            if (units > remaining[0]) throw new ExtractionProofBuilder.LimitExceeded("diagnostic character limit");
            charge(units + 1);
            remaining[0] -= units;
        });
    }

    void charge(long units) { builder.chargeWork(units); }

    ProofOutcome unavailable() {
        return ProofOutcome.unavailable(failure, detail, builder.workUsed(), 0, 0);
    }

    private void fail(ProofOutcome.Status status, String reason) {
        if (failure == null) {
            failure = status;
            detail = reason;
        }
    }
}
