package de.regelsuche.web;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.representation.*;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactParsedUnivariatePolynomialView;
import de.regelsuche.radar.AstRuleRadarService;
import de.regelsuche.solver.ir.*;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A post-run, independently recomputed occurrence review. The mathematical run's historical role bindings stay immutable. */
final class RetainedCandidateEvidence {
    static final String SCHEMA = "regelsuche.retained-candidate-occurrence-review/v1";
    private static final JsonMapper JSON = new JsonMapper();

    record Step(String source, String target, String endCandidate, String rule, String application, List<Integer> path,
            List<String> premises, int maxIncrease, int maxCandidates) { }

    static ObjectNode review(Path directory, RepresentationDiscoveryRunWorkspace run, String candidate, int edge,
            AstRuleRadarService radar) throws IOException {
        return review(directory, run, candidate, edge, radar, "", List.of());
    }

    static ObjectNode review(Path directory, RepresentationDiscoveryRunWorkspace run, String candidate, int edge,
            AstRuleRadarService radar, String query, List<String> additionalPremises) throws IOException {
        var reference = run.artifacts().stream().filter(a -> a.role().name().equals("CANDIDATE_DOSSIERS")).findFirst().orElseThrow();
        Step step = switch (reference.artifactSchema()) {
            case TargetFreeSearchExecution.SCHEMA -> nativeStep(directory, run, candidate, edge);
            case TargetFreeSymPyBridgeDiscoveryScenario.SCHEMA -> frozenStep(directory, run, candidate, edge);
            default -> throw new IllegalArgumentException("unsupported retained candidate artifact");
        };
        var review = JSON.createObjectNode().put("schema", SCHEMA).put("runId", run.runId())
            .put("artifactHash", reference.targetContentHash()).put("candidateId", candidate).put("edgeSequence", edge)
            .put("informationBoundaryHash", run.plan().informationBoundaryHash()).put("ruleInventoryHash", run.plan().ruleInventoryHash());
        review.set("selectedStep", JSON.valueToTree(step));
        String binding = SolverIr.sha256(review.toString());
        review.put("sourceBindingHash", binding);
        boolean frozen = TargetFreeSymPyBridgeDiscoveryScenario.SCHEMA.equals(reference.artifactSchema());
        var packs = frozen ? KnowledgePackSelection.CORE.enablePack(TargetFreeSymPyBridgeDiscoveryScenario.PACK_ID) : KnowledgePackSelection.CORE;
        var boundary = RepresentationDiscoveryInformationBoundary.fromKnowledgePacks(run.plan().informationTrack(), packs);
        var catalog = frozen ? KnownStructureCatalog.fromKnowledgePacks(packs) : KnownStructureCatalog.empty();
        if (!boundary.contentHash().equals(run.plan().informationBoundaryHash()) || !catalog.contentHash().equals(run.plan().knownStructureCatalogHash())) {
            throw new IllegalArgumentException("installed review catalog or source boundary differs from retained context");
        }
        review.set("candidateComparison", CandidateRepresentationReview.compare(run.input().displayText(), step.endCandidate(),
            boundary, catalog, step.maxIncrease(), step.maxCandidates()));
        if (!boundary.candidateFormationRuleInventoryHash().equals(run.plan().ruleInventoryHash())) {
            throw new IllegalArgumentException("installed formation inventory differs from the retained search");
        }
        RewriteRule rule = boundary.candidateFormationRules().stream().filter(r -> r.id().equals(step.rule())).findFirst().orElseThrow();
        var matched = occurrence(step, rule);
        review.set("occurrence", JSON.valueToTree(matched.position().path()));
        review.put("subtreeBefore", matched.sourceOccurrenceExpression()).put("subtreeAfter", matched.transformedOccurrenceExpression());
        var move = radar.inspectFormationOccurrence(step.source(), matched.position().path(), rule, step.premises())
            .filter(r -> r.expressionAfter().equals(step.target()) && r.subtreeBefore().equals(matched.sourceOccurrenceExpression())
                && r.subtreeAfter().equals(matched.transformedOccurrenceExpression()));
        move.ifPresent(r -> review.set("radar", JSON.valueToTree(r)));
        review.put("radarStatus", move.isPresent() ? "REPRODUCED_EXACT_OCCURRENCE" : "NO_EXACT_RADAR_MATCH");
        var measures = new SemanticDescriptionMeasurer();
        review.set("sourceMetrics", JSON.valueToTree(measures.measure(step.source())));
        review.set("targetMetrics", JSON.valueToTree(measures.measure(step.target())));
        var root = new ExpressionParser().parseTerm(step.source());
        var subtree = matched.position().subtreeAt(root).orElseThrow();
        var guards = review.putArray("guards");
        for (var guard : rule.assumptions(subtree)) {
            guards.add(CandidateGuardReview.evaluate(guard, step.premises(), List.of()));
        }
        if (!query.isEmpty()) {
            var parser = new StructuredAssumptionParser();
            var predicate = parser.parse(List.of(query)).getFirst();
            parser.parse(additionalPremises);
            var variables = new java.util.HashSet<>(predicate.left().referencedSymbols());
            if (predicate.right() != null) variables.addAll(predicate.right().referencedSymbols());
            if (!measures.measure(step.source()).variableSymbols().containsAll(variables)) {
                throw new IllegalArgumentException("review guard refers to a symbol outside the selected source");
            }
            var requirement = de.regelsuche.assumption.Assumption.customPredicate(query, variables.stream().sorted().toList());
            review.set("supplementalGuardReview", CandidateGuardReview.evaluate(requirement, step.premises(), additionalPremises));
        }
        verify(review, step, binding, matched.transformation().assumptions());
        return review.put("contentHash", SolverIr.sha256(review.toString()));
    }

    private static AstRewriteTransformationEngine.GeneratedTransformation occurrence(Step step, RewriteRule rule) {
        var matched = new ArrayList<AstRewriteTransformationEngine.GeneratedTransformation>();
        var engine = new AstRewriteTransformationEngine(List.of(rule), step.maxIncrease(), step.maxCandidates());
        engine.transformWithOccurrences(step.source(), generated -> {
            var transformation = generated.transformation();
            if (transformation.applicationKey().equals(step.application()) && transformation.transformedExpression().equals(step.target())
                    && (step.path() == null || step.path().equals(generated.position().path()))) matched.add(generated);
        });
        if (matched.size() != 1) throw new IllegalArgumentException("stored application does not identify one reproducible occurrence");
        return matched.getFirst();
    }

    private static Step nativeStep(Path directory, RepresentationDiscoveryRunWorkspace run, String candidate, int edge) throws IOException {
        String raw = TargetFreeSearchArtifactStore.read(directory, run).orElseThrow();
        var content = TargetFreeSearchExecution.fromCanonicalBytes(run, raw.getBytes(StandardCharsets.UTF_8)).content();
        var state = content.states().stream().filter(s -> s.stateId().equals(candidate)).findFirst().orElseThrow();
        var transition = content.transitions().stream().filter(t -> t.sequence() == edge).findFirst().orElseThrow();
        if (!state.generationSequences().contains(transition.generationSequence())) throw new IllegalArgumentException("edge outside candidate path");
        var generation = content.generations().get(transition.generationSequence());
        return new Step(generation.sourceExpression(), generation.transformedExpression(), JSON.readTree(state.canonicalStateJson()).required("expression").asText(), generation.ruleId(), generation.applicationKey(),
            generation.occurrencePath(), run.input().assumptions(), 12, content.heuristic().maxCandidatesPerState());
    }

    private static Step frozenStep(Path directory, RepresentationDiscoveryRunWorkspace run, String candidate, int edge) throws IOException {
        String raw = RetainedCandidateDossierRepository.read(directory, run).orElseThrow();
        var search = RetainedCandidateDossierRepository.decode(run, raw.getBytes(StandardCharsets.UTF_8)).content().search();
        var transition = search.transitions().stream().filter(t -> t.sequence() == edge && t.toStateHash().equals(candidate))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("edge outside selected frozen candidate"));
        var source = search.state(transition.fromStateHash());
        return new Step(source.expression(), transition.transformedExpression(), search.state(candidate).expression(), transition.ruleId(), transition.applicationKey(),
            null, run.input().assumptions(), search.budget().maxAstSizeIncreasePerStep(), search.budget().maxCandidatesPerState());
    }

    private static void verify(ObjectNode review, Step step, String binding, List<String> required) throws IOException {
        var assumptions = java.util.stream.Stream.concat(step.premises().stream(), required.stream()).distinct().sorted().toList();
        review.set("obligationAssumptions", JSON.valueToTree(assumptions));
        try {
            var obligation = new SolverObligationFactory().equality("retained-occurrence-equality", step.source(), step.target(), assumptions,
                SolverIr.RequestedEvidence.SYMBOLIC_CERTIFICATE, new SolverIr.SourceProvenance("retained-candidate-review", SCHEMA, binding));
            review.set("obligation", JSON.readTree(obligation.toCanonicalJson()));
            if (!polynomialAdmitted(step.source()) || !polynomialAdmitted(step.target())) {
                review.put("verificationStatus", "OUTSIDE_BOUNDED_POLYNOMIAL_FRAGMENT");
                return;
            }
            var execution = new PolynomialNormalFormSolverBackend().execute(obligation);
            review.set("verification", JSON.readTree(execution.toCanonicalJson()));
            review.set("solverResult", JSON.readTree(execution.result().toCanonicalJson()));
            review.put("verificationStatus", execution.result().status().name());
        } catch (IllegalArgumentException unsupported) {
            review.put("verificationStatus", "UNSUPPORTED_TYPED_OBLIGATION");
            review.put("verificationDetail", unsupported.getMessage());
        }
    }

    private static boolean polynomialAdmitted(String expression) {
        if (expression.length() > 256) return false;
        return new ExactParsedUnivariatePolynomialView().analyze(new ExpressionParser().parseExactTerm(expression)).supported();
    }
}
