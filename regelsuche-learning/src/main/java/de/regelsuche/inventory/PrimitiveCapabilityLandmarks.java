package de.regelsuche.inventory;

import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Landmarks are registered executable rules, not token-name detectors or speculative solver promises. */
public final class PrimitiveCapabilityLandmarks implements StateValue {
    public record Registration(String capabilityId, String providerId, RewriteRule rule, double value) {
        public Registration {
            if (capabilityId == null || capabilityId.isBlank() || providerId == null || providerId.isBlank()
                    || rule == null || !Double.isFinite(value) || value < 0) throw new IllegalArgumentException("invalid capability registration");
        }
    }
    private record Occurrence(Expr expression, String path) {}
    private final List<Registration> registrations;
    public PrimitiveCapabilityLandmarks(List<Registration> registrations, List<MoveProvider> inventory) {
        this.registrations = List.copyOf(registrations);
        var ids = inventory.stream().map(provider -> provider.descriptor().id()).collect(java.util.stream.Collectors.toSet());
        var unique = new java.util.HashSet<String>();
        for (var registration : registrations) {
            if (!ids.contains(registration.providerId()) || !unique.add(registration.capabilityId()))
                throw new IllegalArgumentException("capability must name one registered provider and have a unique identity");
            var provider = inventory.stream().filter(value -> value.descriptor().id().equals(registration.providerId())).findFirst().orElseThrow();
            if (!(provider instanceof EngineMoveProvider engine)
                    || !(engine.engine() instanceof de.regelsuche.transform.PreparedAstRewriteTransformationEngine ast)
                    || !ast.rules().contains(registration.rule()) || !engine.completeRelation())
                throw new IllegalArgumentException("primitive landmark requires the exact rule object in a complete AST provider");
        }
    }
    @Override public Assessment evaluate(MoveState state, MoveContext context) {
        var root = new ExpressionParser().parseTerm(state.expression());
        var occurrences = new ArrayList<Occurrence>(); collect(root, "root", occurrences);
        var capabilities = new TreeMap<String, Capability>();
        long work = occurrences.size(), primitive = 0; double value = 0;
        for (var registration : registrations) {
            for (var occurrence : occurrences) {
                work++;
                if (!registration.rule().matches(occurrence.expression())) continue;
                work++;
                if (!context.carries(registration.rule().assumptions(occurrence.expression()).stream()
                        .map(de.regelsuche.assumption.Assumption::expression).toList(), state)) continue;
                var rewritten = registration.rule().apply(occurrence.expression()); primitive++;
                if (rewritten.equals(occurrence.expression())) continue;
                capabilities.put(registration.capabilityId(), new Capability(registration.providerId(), state.expression(), occurrence.path(),
                    ExpressionFormatter.format(occurrence.expression()), ExpressionFormatter.format(rewritten)));
                value += registration.value(); break;
            }
        }
        return new Assessment(occurrences.size(), value, work, primitive, capabilities);
    }
    private static void collect(Expr expression, String path, List<Occurrence> occurrences) {
        occurrences.add(new Occurrence(expression, path));
        if (expression instanceof BinaryExpr binary) {
            collect(binary.left(), path + "/left", occurrences); collect(binary.right(), path + "/right", occurrences);
        } else if (expression instanceof FunctionExpr function) {
            for (int i = 0; i < function.arguments().size(); i++) collect(function.arguments().get(i), path + "/argument-" + i, occurrences);
        }
    }
}
