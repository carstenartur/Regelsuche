package de.regelsuche.overhaul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.cli.CliRouter;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.example.AlgebraicExampleGenerator;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.export.DefaultTransformationImportService;
import de.regelsuche.export.ExportBundle;
import de.regelsuche.export.TransformationImportService;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.InventoryBackedRewriteRuleProvider;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.inventory.RuleActivationDecision;
import de.regelsuche.inventory.RuleInventoryConfiguration;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.DiscoverySettings;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleCandidateListener;
import de.regelsuche.mining.RuleCandidateMiner;
import de.regelsuche.mining.RuleDiscoveryService;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.BeamSearchStrategy;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pr2OverhaulTest {

    @Test
    void knownRuleDiscoveryUsesOnlyAtomicRewriteRules() {
        DiscoveryRun run = runDiscovery();
        assertTrue(run.store.snapshot().edges().stream()
            .map(GraphEdge::transformationRule)
            .noneMatch(this::isForbiddenSpecialRule),
            "Discovery must not use forbidden special rules");
    }

    @Test
    void knownRuleDiscoveryDoesNotNeedExpansionRewardingScorer() {
        DiscoveryRun run = runDiscovery();
        assertTrue(
            run.candidates.stream().anyMatch(this::isFirstBinomialRule),
            () -> "First binomial rule should emerge from atomic paths without a special scorer, but got: "
                + run.candidates);
    }

    @Test
    void discoveredCandidateReferencesSupportingPaths() {
        DiscoveryRun run = runDiscovery();
        RuleCandidate firstBinomial = run.candidates.stream()
            .filter(this::isFirstBinomialRule)
            .findFirst()
            .orElseThrow();
        assertFalse(firstBinomial.supportingTransformationIds().isEmpty(),
            "Candidate should reference at least one supporting transformation id");
        Set<String> knownIds = run.store.discoveredTransformations().stream()
            .map(DiscoveredTransformation::id)
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(knownIds.containsAll(firstBinomial.supportingTransformationIds()),
            "Supporting ids must point to persisted DiscoveredTransformation entries");
    }

    @Test
    void jsonExportContainsSchemaVersionAndGeneratedAt() {
        DefaultTransformationExportService exporter = new DefaultTransformationExportService(
            Clock.fixed(Instant.parse("2024-05-05T00:00:00Z"), ZoneId.of("UTC"))
        );
        String json = exporter.exportJson(List.of(sampleTransformation()), List.of(), List.of(sampleRule()));
        assertTrue(json.contains("\"schemaVersion\":\"1.0\""), json);
        assertTrue(json.contains("\"generatedAt\":\"2024-05-05T00:00:00Z\""), json);
        assertTrue(json.contains("\"ruleCandidates\":["), json);
    }

    @Test
    void jsonRoundTripPreservesTransformationSteps() {
        DefaultTransformationExportService exporter = new DefaultTransformationExportService();
        TransformationImportService importer = new DefaultTransformationImportService();

        DiscoveredTransformation original = sampleTransformation();
        String json = exporter.exportJson(List.of(original), List.of(), List.of());
        ExportBundle bundle = importer.importJson(json);

        assertEquals(1, bundle.transformations().size());
        DiscoveredTransformation roundTripped = bundle.transformations().getFirst();
        assertEquals(original.id(), roundTripped.id());
        assertEquals(original.originalExpression(), roundTripped.originalExpression());
        assertEquals(original.improvedExpression(), roundTripped.improvedExpression());
        assertEquals(original.steps().size(), roundTripped.steps().size());
        TransformationStep firstStep = roundTripped.steps().getFirst();
        assertEquals("power_to_product", firstStep.ruleId());
        assertEquals(RewriteKind.EXPAND, firstStep.ruleKind());
        assertTrue(firstStep.equivalencePreserving());
    }

    @Test
    void jsonRoundTripPreservesReusableRules() {
        DefaultTransformationExportService exporter = new DefaultTransformationExportService();
        TransformationImportService importer = new DefaultTransformationImportService();
        ReusableRule original = sampleRule();
        String json = exporter.exportJson(List.of(), List.of(), List.of(original));
        ExportBundle bundle = importer.importJson(json);
        assertEquals(1, bundle.reusableRules().size());
        ReusableRule roundTripped = bundle.reusableRules().getFirst();
        assertEquals(original.id(), roundTripped.id());
        assertEquals(original.leftPattern(), roundTripped.leftPattern());
        assertEquals(original.rightPattern(), roundTripped.rightPattern());
        assertEquals(original.proofStatus(), roundTripped.proofStatus());
        assertEquals(original.parameterRelations(), roundTripped.parameterRelations());
    }

    @Test
    void jsonRoundTripPreservesRuleCandidates() {
        DefaultTransformationExportService exporter = new DefaultTransformationExportService();
        TransformationImportService importer = new DefaultTransformationImportService();
        RuleCandidate original = new RuleCandidate(
            "(x + A)^2",
            "x^2 + 2*A*x + A^2",
            5,
            12.5,
            20,
            true,
            true,
            true,
            List.of("N1 = 2*A", "N2 = A^2"),
            RuleStatus.MATCHES_KNOWN_RULE,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            "hash-candidate",
            List.of("path-1", "path-2")
        );
        String json = exporter.exportJson(List.of(), List.of(original), List.of());
        ExportBundle bundle = importer.importJson(json);
        assertEquals(1, bundle.ruleCandidates().size());
        RuleCandidate roundTripped = bundle.ruleCandidates().getFirst();
        assertEquals(original.leftPattern(), roundTripped.leftPattern());
        assertEquals(original.rightPattern(), roundTripped.rightPattern());
        assertEquals(original.canonicalHash(), roundTripped.canonicalHash());
        assertEquals(original.supportingTransformationIds(), roundTripped.supportingTransformationIds());
        assertEquals(original.proofStatus(), roundTripped.proofStatus());
    }

    @Test
    void markdownExportIsHumanReadable() {
        String markdown = new DefaultTransformationExportService().exportMarkdown(List.of(sampleTransformation()));
        assertTrue(markdown.startsWith("# Gefundene Umformungen"), markdown);
        assertTrue(markdown.contains("## 1. (x+3)^2 → x^2+6*x+9"), markdown);
        assertTrue(markdown.contains("#### Rechenweg"), markdown);
        assertTrue(markdown.contains("| Eigenschaft | Vorher | Nachher |"), markdown);
        assertTrue(markdown.contains("#### Status\nVALIDATED_BY_EXAMPLES"), markdown);
    }

    @Test
    void latexExportContainsAlignEnvironment() {
        String latex = new DefaultTransformationExportService().exportLatex(List.of(sampleTransformation()));
        assertTrue(latex.contains("\\begin{align*}"), latex);
        assertTrue(latex.contains("\\end{align*}"), latex);
        assertTrue(latex.contains("&\\rightarrow"), latex);
    }

    @Test
    void mermaidExportConnectsSequentialSteps() {
        String mermaid = new DefaultTransformationExportService().exportMermaid(List.of(sampleTransformation()));
        assertTrue(mermaid.startsWith("graph TD"), mermaid);
        java.util.regex.Pattern edge = java.util.regex.Pattern.compile(
            "(\\S+)\\[.*?]\\s*-->\\|([^|]+)\\|\\s*(\\S+)\\["
        );
        java.util.regex.Matcher matcher = edge.matcher(mermaid);
        String toFromStep0 = null;
        String fromFromStep1 = null;
        while (matcher.find()) {
            String ruleLabel = matcher.group(2);
            if ("power_to_product".equals(ruleLabel)) {
                toFromStep0 = matcher.group(3);
            } else if ("distribute".equals(ruleLabel)) {
                fromFromStep1 = matcher.group(1);
            }
        }
        assertNotNull(toFromStep0, mermaid);
        assertNotNull(fromFromStep1, mermaid);
        assertEquals(toFromStep0, fromFromStep1, "Sequential steps must share a node id: " + mermaid);
    }

    @Test
    void inventoryRejectsDuplicateRules() {
        InMemoryRuleInventoryRepository repository = new InMemoryRuleInventoryRepository();
        ReusableRule first = new ReusableRule(
            "first-id",
            "(x + A)^2",
            "x^2 + 2*A*x + A^2",
            List.of(),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.MATCHES_KNOWN_RULE,
            3,
            5.0,
            Instant.EPOCH,
            "binomial-hash",
            null,
            0
        );
        ReusableRule duplicate = new ReusableRule(
            "second-id",
            "(x + A)^2",
            "x^2 + 2*A*x + A^2",
            List.of(),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.MATCHES_KNOWN_RULE,
            5,
            6.0,
            Instant.EPOCH,
            "binomial-hash",
            null,
            0
        );
        repository.save(first);
        repository.save(duplicate);
        List<ReusableRule> all = repository.findAll();
        assertEquals(1, all.size(), "Duplicate canonical hash must not create a second entry");
        assertEquals("first-id", all.getFirst().id());
        assertTrue(all.getFirst().usageCount() >= 1, "Duplicate save should bump usage count");
    }

    @Test
    void inventoryProviderExplainsDisabledRules() {
        InMemoryRuleInventoryRepository repository = new InMemoryRuleInventoryRepository();
        repository.save(new ReusableRule(
            "low-proof",
            "x + A",
            "A + x",
            List.of(),
            CandidateProofStatus.OBSERVED,
            RuleStatus.NEW,
            1,
            1.0,
            Instant.EPOCH,
            "h1",
            null,
            0
        ));
        repository.save(new ReusableRule(
            "denied",
            "x * A",
            "A * x",
            List.of(),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.NEW,
            5,
            2.0,
            Instant.EPOCH,
            "h2",
            null,
            0
        ));
        RuleInventoryConfiguration configuration = RuleInventoryConfiguration.enabledDefaults()
            .withDenyList(Set.of("denied"));
        InventoryBackedRewriteRuleProvider provider = new InventoryBackedRewriteRuleProvider(
            repository, configuration, List.of()
        );
        provider.activatedRules();
        List<RuleActivationDecision> decisions = provider.lastDecisions();
        assertTrue(decisions.stream().anyMatch(d -> d.rule().id().equals("low-proof") && !d.activated()
            && d.reason().contains("below minimum")));
        assertTrue(decisions.stream().anyMatch(d -> d.rule().id().equals("denied") && !d.activated()
            && d.reason().contains("allow/deny")));
    }

    @Test
    void cliDiscoverWritesRequestedExports(@TempDir Path tempDir) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Path exportDir = tempDir.resolve("exports");
        CliRouter router = new CliRouter(
            new PrintStream(output),
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            true
        );
        int exit = router.run(new String[]{
            "discover",
            "--min", "1",
            "--max", "1",
            "--export", "json,markdown,mermaid",
            "--dir", exportDir.toString()
        });
        assertEquals(0, exit);
        assertTrue(Files.exists(exportDir.resolve("discovered-transformations.json")));
        assertTrue(Files.exists(exportDir.resolve("discovered-transformations.md")));
        assertTrue(Files.exists(exportDir.resolve("transformation-graph.mmd")));
        String log = output.toString();
        assertTrue(log.contains("Exported"), log);
    }

    private boolean isFirstBinomialRule(RuleCandidate candidate) {
        return ("(x + A)^2".equals(candidate.leftPattern())
                && "x^2 + 2*A*x + A^2".equals(candidate.rightPattern()))
            || ("x^2 + 2*A*x + A^2".equals(candidate.leftPattern())
                && "(x + A)^2".equals(candidate.rightPattern()));
    }

    private boolean isForbiddenSpecialRule(String ruleId) {
        String normalized = ruleId.toLowerCase();
        return normalized.contains("quadratic")
            || normalized.contains("binomial")
            || normalized.contains("perfect_square")
            || normalized.contains("difference_of_squares");
    }

    private DiscoveryRun runDiscovery() {
        InMemoryExpressionGraphStore store = new InMemoryExpressionGraphStore();
        RuleDiscoveryService service = new RuleDiscoveryService(
            new AlgebraicExampleGenerator() {
                @Override
                public List<String> generateSmallIntegerExamples(int min, int max) {
                    return List.of("(x + 1)^2", "(x + 2)^2", "(x + 3)^2");
                }
            },
            new AstRewriteTransformationEngine(),
            new SymPyEquivalenceService(),
            new ExpressionScorer(),
            store,
            new RuleCandidateMiner(new KnownRuleRepository()),
            RuleCandidateListener.NOOP,
            new BeamSearchStrategy(),
            DiscoverySettings.collectingEquivalentPaths()
        );
        try {
            List<RuleCandidate> candidates = service.discover(1, 5);
            return new DiscoveryRun(candidates, store);
        } finally {
            service.shutdown();
        }
    }

    private DiscoveredTransformation sampleTransformation() {
        ExpressionScore originalScore = new ExpressionScore(42, 10, 8, 4, 0);
        ExpressionScore improvedScore = new ExpressionScore(17, 5, 4, 2, 0);
        return new DiscoveredTransformation(
            "path-1",
            "(x+3)^2",
            "x^2+6*x+9",
            List.of(
                new TransformationStep(0, "(x+3)^2", "(x+3)*(x+3)", "power_to_product", RewriteKind.EXPAND, 42, 35, true, "power as product"),
                new TransformationStep(1, "(x+3)*(x+3)", "x^2+6*x+9", "distribute", RewriteKind.EXPAND, 35, 17, true, "distribute")
            ),
            originalScore,
            improvedScore,
            originalScore.improvementTo(improvedScore),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Instant.EPOCH,
            "hash-1"
        );
    }

    private ReusableRule sampleRule() {
        return new ReusableRule(
            "rule-1",
            "x^2 + 2*A*x + A^2",
            "(x + A)^2",
            List.of("N1 = 2*A", "N2 = A^2"),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.MATCHES_KNOWN_RULE,
            3,
            12.5,
            Instant.EPOCH,
            "rule-hash",
            null,
            0
        );
    }

    private record DiscoveryRun(List<RuleCandidate> candidates, InMemoryExpressionGraphStore store) {
    }
}
