package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.learning.MacroLearningPipeline;
import de.regelsuche.learning.MacroLearningResult;
import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import de.regelsuche.mining.MacroMoveTransformationEngine;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.convergence.ArtifactMetadata;
import de.regelsuche.search.convergence.ConvergentDiscoveryAnalysis;
import de.regelsuche.search.convergence.ConvergentDiscoveryGallerySnippetWriter;
import de.regelsuche.search.convergence.ConvergentDiscoveryMermaidWriter;
import de.regelsuche.search.convergence.ConvergentDiscoveryReport;
import de.regelsuche.search.convergence.ConvergentDiscoverySvgWriter;
import de.regelsuche.search.convergence.RuleFamily;
import de.regelsuche.search.convergence.SearchSpaceSubgraph;
import de.regelsuche.search.convergence.SearchSpaceSubgraphExtractor;
import de.regelsuche.search.convergence.SearchSpaceSvgWriter;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConvergentSophieGermainGalleryTest {
    @TempDir
    Path tempDir;

    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final SymPyEquivalenceService equivalence = new SymPyEquivalenceService();

    @Test
    void learnsMacroRerunsSearchAndGeneratesConvergentGraph() throws Exception {
        String input = "x^4 + 4*y^4";
        SuccessfulTransformationPath discoveryPath = hiddenStructureReplayPath(input);
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        MacroLearningResult learning = new MacroLearningPipeline(inventory).learn(List.of(discoveryPath));
        assertFalse(learning.newlyActivated().isEmpty(), learning.stageEvidence().toString());
        ReusableRule learned = learning.newlyActivated().getFirst();

        MacroMoveTransformationEngine engine = new MacroMoveTransformationEngine(
            new HypothesisTransformationEngine(
                new AstRewriteTransformationEngine(),
                List.of(new DifferenceOfSquaresPreparationOperator())
            ),
            new GoalAwareMacroMoveSelector(inventory),
            discoveryPath.targetExpression(),
            Map.of(learned.id(), atomicSteps(discoveryPath)),
            learned.assumptions()
        );
        SearchProblem problem = new SearchProblem(
            input,
            engine,
            scorer,
            canonicalizer,
            new SearchHeuristic(4, 240, 1, 20, 240, 240)
        );
        List<SearchState> states = new BestFirstSearchStrategy().search(problem);
        ConvergentDiscoveryReport report = new ConvergentDiscoveryAnalysis().analyze(problem, states);

        assertTrue(report.isGalleryEligible(), "Expected hidden-structure and learned-macro paths: " + report);
        assertTrue(report.ruleFamiliesUsed().contains(RuleFamily.HIDDEN_STRUCTURE), report.ruleFamiliesUsed().toString());
        assertTrue(report.ruleFamiliesUsed().contains(RuleFamily.LEARNED_MACRO), report.ruleFamiliesUsed().toString());
        assertTrue(report.pathsToTarget().stream().anyMatch(path ->
            path.ruleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID)), report.pathsToTarget().toString());
        assertTrue(report.pathsToTarget().stream().anyMatch(path ->
            path.ruleIds().stream().anyMatch(rule -> rule.startsWith("macro_"))), report.pathsToTarget().toString());
        assertTrue(equivalence.areEquivalent(input, report.canonicalTargetExpression()), report.canonicalTargetExpression());
        SearchSpaceSubgraph searchSpace = new SearchSpaceSubgraphExtractor().extract(problem, states, report);
        assertTrue(searchSpace.nodes().size() > 8, "Expected a visible search space: " + searchSpace.nodes());
        assertTrue(searchSpace.edges().size() > 10, "Expected branched rule edges: " + searchSpace.edges());
        assertTrue(searchSpace.edges().stream().map(SearchSpaceSubgraph.Edge::ruleFamily).distinct().count() >= 2,
            searchSpace.edges().toString());
        assertTrue(searchSpace.nodes().stream().anyMatch(SearchSpaceSubgraph.Node::isTarget), searchSpace.nodes().toString());
        assertTrue(searchSpace.nodes().stream().anyMatch(node -> node.isTarget()
            && (node.isOnDidacticPath() || node.isOnMacroPath())), searchSpace.nodes().toString());
        assertTrue(searchSpace.nodes().stream().anyMatch(SearchSpaceSubgraph.Node::notSelected), searchSpace.nodes().toString());
        assertTrue(searchSpace.nodes().stream().anyMatch(SearchSpaceSubgraph.Node::isDeadEnd), searchSpace.nodes().toString());
        assertTrue(searchSpace.nodes().stream().map(SearchSpaceSubgraph.Node::id)
            .allMatch(id -> id.matches("space_[0-9a-f]{64}")), searchSpace.nodes().toString());
        Map<String, Integer> depthByNodeId = searchSpace.nodes().stream()
            .collect(Collectors.toMap(SearchSpaceSubgraph.Node::id, SearchSpaceSubgraph.Node::depth));
        assertTrue(searchSpace.edges().stream()
            .filter(SearchSpaceSubgraph.Edge::notSelected)
            .allMatch(edge -> depthByNodeId.get(edge.toId()) == depthByNodeId.get(edge.fromId()) + 1),
            searchSpace.edges().toString());
        Map<String, Set<RuleFamily>> incomingFamiliesByNodeId = searchSpace.edges().stream().collect(Collectors.groupingBy(
            SearchSpaceSubgraph.Edge::toId,
            Collectors.mapping(SearchSpaceSubgraph.Edge::ruleFamily, Collectors.toSet())
        ));
        for (SearchSpaceSubgraph.Node node : searchSpace.nodes()) {
            assertEquals(incomingFamiliesByNodeId.getOrDefault(node.id(), Set.of()), node.ruleFamilies(), node.toString());
        }

        String mermaid = new ConvergentDiscoveryMermaidWriter().render(report);
        assertTrue(mermaid.contains(DifferenceOfSquaresPreparationOperator.RULE_ID), mermaid);
        assertTrue(mermaid.contains("macro_"), mermaid);
        String svg = new ConvergentDiscoverySvgWriter().render(report,
            new ArtifactMetadata("convergent-sophie-germain.mmd", "Convergent Sophie-Germain discovery graph"));
        assertTrue(svg.contains("data-source=\"convergent-sophie-germain.mmd\""), svg);
        assertTrue(svg.contains("data-generated-by=\"ConvergentDiscoverySvgWriter\""), svg);
        assertMermaidLabelsAppearInSvg(mermaid, svg);
        assertNoManualOnlyNodes(svg);
        String searchSpaceSvg = new SearchSpaceSvgWriter().render(searchSpace,
            new ArtifactMetadata("search-space-sophie-germain.svg", "Generated Sophie-Germain search-space subgraph"));
        assertTrue(searchSpaceSvg.contains("data-generated-by=\"SearchSpaceSvgWriter\""), searchSpaceSvg);
        assertTrue(searchSpaceSvg.contains("data-generated-from=\"SearchSpaceSubgraph\""), searchSpaceSvg);
        assertTrue(searchSpaceSvg.contains("alternative branch")
            || searchSpaceSvg.contains("dead-end alternative"), searchSpaceSvg);
        String snippet = new ConvergentDiscoveryGallerySnippetWriter().render(report);
        assertTrue(snippet.contains("number of distinct paths"), snippet);
        assertTrue(snippet.contains("macro shortcut path"), snippet);

        Path graph = tempDir.resolve("convergent-sophie-germain.mmd");
        Path svgFile = tempDir.resolve("convergent-sophie-germain.svg");
        Path searchSpaceSvgFile = tempDir.resolve("search-space-sophie-germain.svg");
        Path snippetFile = tempDir.resolve("convergent-sophie-germain-gallery-snippet.md");
        Files.writeString(graph, mermaid);
        Files.writeString(svgFile, svg);
        Files.writeString(searchSpaceSvgFile, searchSpaceSvg);
        Files.writeString(snippetFile, snippet);
        assertTrue(Files.exists(graph));
        assertTrue(Files.exists(svgFile));
        assertTrue(Files.exists(searchSpaceSvgFile));
        assertTrue(Files.size(graph) > 0);
        assertTrue(Files.size(svgFile) > 0);
        assertTrue(Files.size(searchSpaceSvgFile) > 0);
        assertTrue(Files.size(snippetFile) > 0);
        assertGeneratedOrProvenancePresent(graph, svgFile, svg);

        if (Boolean.getBoolean("regelsuche.recordDocs")) {
            Path screenshots = locateRepoRoot().resolve("docs/assets/screenshots");
            Files.createDirectories(screenshots);
            Files.writeString(screenshots.resolve("convergent-sophie-germain.mmd"), mermaid);
            Files.writeString(screenshots.resolve("convergent-sophie-germain.svg"), svg);
            Files.writeString(screenshots.resolve("search-space-sophie-germain.svg"), searchSpaceSvg);
            Files.writeString(screenshots.resolve("convergent-sophie-germain-gallery-snippet.md"), snippet);
        }
        Path screenshots = locateRepoRoot().resolve("docs/assets/screenshots");
        Path docsMmd = screenshots.resolve("convergent-sophie-germain.mmd");
        Path docsSvg = screenshots.resolve("convergent-sophie-germain.svg");
        Path docsSearchSpaceSvg = screenshots.resolve("search-space-sophie-germain.svg");
        assertTrue(Files.exists(docsMmd), "Missing generated Mermaid asset");
        assertTrue(Files.exists(docsSvg), "Missing generated SVG asset");
        assertTrue(Files.exists(docsSearchSpaceSvg), "Missing generated search-space SVG asset");
        String docsSvgContent = Files.readString(docsSvg);
        String docsSearchSpaceSvgContent = Files.readString(docsSearchSpaceSvg);
        assertTrue(Boolean.getBoolean("regelsuche.recordDocs")
            || docsSvgContent.contains("data-generated-by=\"ConvergentDiscoverySvgWriter\""),
            "SVG must be freshly generated in recordDocs mode or carry generated provenance");
        assertTrue(Boolean.getBoolean("regelsuche.recordDocs")
            || docsSearchSpaceSvgContent.contains("data-generated-from=\"SearchSpaceSubgraph\""),
            "Search-space SVG must be generated from SearchSpaceSubgraph");
        String readme = Files.readString(locateRepoRoot().resolve("README.md"));
        String gallery = Files.readString(locateRepoRoot().resolve("docs/demo-gallery.md"));
        assertTrue(readme.contains("search-space-sophie-germain.svg"), "README must show the search-space SVG");
        assertTrue(gallery.contains("search-space-sophie-germain.svg"), "Gallery must show the search-space SVG");
        assertTrue(gallery.contains("Regelsuche does not only output the factorization.\n"
            + "It records the explored transformation space and shows which different ideas converge."));
    }

    private void assertMermaidLabelsAppearInSvg(String mermaid, String svg) {
        for (String label : mermaidLabels(mermaid)) {
            assertTrue(svg.contains(escapeXml(label)), "Missing SVG label from Mermaid: " + label + "\n" + svg);
        }
    }

    private Set<String> mermaidLabels(String mermaid) {
        Set<String> labels = new LinkedHashSet<>();
        Matcher nodeMatcher = Pattern.compile("\\[\"([^\"]+)\"\\]").matcher(mermaid);
        while (nodeMatcher.find()) {
            labels.add(nodeMatcher.group(1));
        }
        Matcher edgeMatcher = Pattern.compile("\\|([^|]+)\\|").matcher(mermaid);
        while (edgeMatcher.find()) {
            labels.add(edgeMatcher.group(1));
        }
        return labels;
    }

    private void assertNoManualOnlyNodes(String svg) {
        assertFalse(svg.contains("path 1: hidden structure"), svg);
        assertFalse(svg.contains("path 2: learned macro shortcut"), svg);
        assertFalse(svg.contains("same target node"), svg);
    }

    private void assertGeneratedOrProvenancePresent(Path mmd, Path svg, String svgContent) throws Exception {
        boolean generatedInRecordDocs = Boolean.getBoolean("regelsuche.recordDocs")
            && Files.getLastModifiedTime(svg).toMillis() >= Files.getLastModifiedTime(mmd).toMillis();
        boolean hasProvenance = svgContent.contains("data-source=\"convergent-sophie-germain.mmd\"")
            && svgContent.contains("data-generated-by=\"ConvergentDiscoverySvgWriter\"");
        assertTrue(generatedInRecordDocs || hasProvenance,
            "SVG must be newer/generated in recordDocs mode or carry generated provenance");
    }

    private String escapeXml(String value) {
        return (value == null ? "" : value)
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private SuccessfulTransformationPath hiddenStructureReplayPath(String source) {
        HypothesisTransformationEngine engine = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(),
            List.of(new DifferenceOfSquaresPreparationOperator())
        );
        SearchProblem problem = new SearchProblem(
            source,
            engine,
            scorer,
            canonicalizer,
            new SearchHeuristic(4, 160, 1, 10, 200, 200)
        );
        SearchState factoredState = new BestFirstSearchStrategy().search(problem).stream()
            .filter(state -> state.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .filter(state -> state.appliedRuleIds().contains("ast_square_difference_factor"))
            .findFirst()
            .orElseThrow();
        return new SuccessfulTransformationPath(
            "convergent-sophie-germain-hidden-structure-replay",
            factoredState.path().getFirst(),
            factoredState.path().getLast(),
            factoredState.path(),
            factoredState.appliedRuleIds(),
            scorer.score(factoredState.path().getFirst()),
            factoredState.score(),
            true,
            "search problem replay with polynomial equivalence",
            Map.of("source", "SearchProblem")
        );
    }

    private List<TransformationStep> atomicSteps(SuccessfulTransformationPath path) {
        java.util.ArrayList<TransformationStep> steps = new java.util.ArrayList<>();
        for (int index = 0; index < path.rules().size(); index++) {
            String before = path.expressionPath().get(index);
            String after = path.expressionPath().get(index + 1);
            steps.add(new TransformationStep(
                index,
                before,
                after,
                path.rules().get(index),
                RewriteKind.NORMALIZE,
                scorer.score(before).weightedTotal(),
                scorer.score(after).weightedTotal(),
                true,
                path.rules().get(index),
                path.assumptions()
            ));
        }
        return steps;
    }

    private static Path locateRepoRoot() {
        Path candidate = Path.of(".").toAbsolutePath().normalize();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(candidate.resolve("README.md"))
                && Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
            Path parent = candidate.getParent();
            if (parent == null) {
                break;
            }
            candidate = parent;
        }
        throw new IllegalStateException("Could not locate repository root");
    }
}
