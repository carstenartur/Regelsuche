package de.regelsuche.web;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.representation.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.value.ExprValueArena;
import de.regelsuche.value.ExprValueFactory;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/** Bounded observations of the source and selected end candidate, independently of the currently inspected path step. */
final class CandidateRepresentationReview {
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    static ObjectNode compare(String source, String candidate, RepresentationDiscoveryInformationBoundary boundary,
            KnownStructureCatalog catalog, int maxGrowth, int limit) {
        var engine = new AstRewriteTransformationEngine(boundary.candidateFormationRules(), maxGrowth, limit + 1);
        var beforeRules = new TreeSet<String>();
        var afterRules = new TreeSet<String>();
        var result = JSON.createObjectNode().put("catalogHash", catalog.contentHash())
            .put("semanticIdentityVersion", ExprValueArena.DIGEST_VERSION)
            .put("formationInventoryHash", boundary.candidateFormationRuleInventoryHash())
            .put("successorLimit", limit).put("maxAstIncrease", maxGrowth);
        result.set("source", describe(source, engine, catalog, limit, beforeRules));
        result.set("candidate", describe(candidate, engine, catalog, limit, afterRules));
        result.set("observedGainedRules", JSON.valueToTree(difference(afterRules, beforeRules)));
        result.set("observedLostRules", JSON.valueToTree(difference(beforeRules, afterRules)));
        return result;
    }

    private static ObjectNode describe(String expression, AstRewriteTransformationEngine engine, KnownStructureCatalog catalog, int limit,
            SortedSet<String> unconditional) {
        var syntax = new ExpressionParser().parseExactTerm(expression).expression();
        var out = JSON.createObjectNode().put("expression", expression).put("syntaxHash", SolverIr.sha256(expression));
        try (var arena = new ExprValueArena(ExprValueFactory.DEFAULT_MAXIMUM_ENTRIES)) {
            out.put("semanticValueDigest", arena.digest(arena.intern(syntax)));
        }
        out.set("description", JSON.valueToTree(new SemanticDescriptionMeasurer().measure(syntax)));
        var scan = new KnownStructureMatcher(catalog).scan(syntax);
        var recognition = out.putObject("recognition").put("complete", scan.complete());
        recognition.set("matches", JSON.valueToTree(scan.matches()));
        recognition.set("diagnostics", JSON.valueToTree(scan.diagnostics()));
        var successors = engine.transform(expression);
        out.put("successorsTruncated", successors.size() > limit);
        var frontier = out.putArray("oneStepSuccessors");
        for (var step : successors.stream().limit(limit).toList()) {
            var observed = frontier.addObject().put("rule", step.rule()).put("applicationKey", step.applicationKey())
                .put("target", step.transformedExpression()).put("pack", step.packId()).put("license", step.license());
            observed.set("requiredAssumptions", JSON.valueToTree(step.assumptions()));
            if (step.assumptions().isEmpty()) unconditional.add(step.rule());
        }
        return out;
    }

    private static List<String> difference(SortedSet<String> selected, SortedSet<String> control) {
        return selected.stream().filter(rule -> !control.contains(rule)).toList();
    }
}
