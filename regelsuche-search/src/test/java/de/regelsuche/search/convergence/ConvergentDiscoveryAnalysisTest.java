package de.regelsuche.search.convergence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConvergentDiscoveryAnalysisTest {
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ConvergentDiscoveryAnalysis analysis = new ConvergentDiscoveryAnalysis();

    @Test
    void detectsTwoDifferentRulePathsToSameCanonicalResult() {
        ConvergentDiscoveryReport report = analysis.analyze("x^4 + 4*y^4", List.of(
            state(List.of("x^4 + 4*y^4", "(x^2 + 2*y^2)^2 - (2*x*y)^2", "(x^2 - 2*x*y + 2*y^2)*(x^2 + 2*x*y + 2*y^2)"),
                List.of("hypothesis_difference_of_squares_preparation", "ast_square_difference_factor")),
            state(List.of("x^4 + 4*y^4", "(x^2 - 2*x*y + 2*y^2)*(x^2 + 2*x*y + 2*y^2)"),
                List.of("macro_sophie_germain"))
        ), canonicalizer, scorer);

        assertTrue(report.isGalleryEligible());
        assertEquals(2, report.pathsToTarget().size());
        assertTrue(report.ruleFamiliesUsed().contains(RuleFamily.HIDDEN_STRUCTURE));
        assertTrue(report.ruleFamiliesUsed().contains(RuleFamily.LEARNED_MACRO));
        assertTrue(report.pathsToTarget().stream().allMatch(path -> path.sourceReplayIds().isEmpty()));
    }

    @Test
    void reportsCanonicalizedTargetExpression() {
        ConvergentDiscoveryReport report = analysis.analyze("x + 1", List.of(
            state(List.of("x + 1", "1 + x"), List.of("hypothesis_complete_square_preparation")),
            state(List.of("x + 1", "x + 1 + 0"), List.of("macro_complete_square"))
        ), canonicalizer, scorer);

        assertEquals(canonicalizer.canonicalize("1 + x"), report.canonicalTargetExpression());
    }

    @Test
    void rejectsDuplicatePathsWithSameRuleSequence() {
        ConvergentDiscoveryReport report = analysis.analyze("x", List.of(
            state(List.of("x", "x + 0", "x"), List.of("ast_add_zero_right", "ast_canonical_normalize")),
            state(List.of("x", "0 + x", "x"), List.of("ast_add_zero_right", "ast_canonical_normalize"))
        ), canonicalizer, scorer);

        assertFalse(report.isGalleryEligible());
        assertTrue(report.convergentStates().isEmpty());
    }

    @Test
    void identifiesSharedIntermediateStates() {
        ConvergentDiscoveryReport report = analysis.analyze("x", List.of(
            state(List.of("x", "x + 1", "x + 2"), List.of("hypothesis_difference_of_squares_preparation", "ast_square_difference_factor")),
            state(List.of("x", "x + 1", "x + 2"), List.of("macro_bridge", "macro_finish"))
        ), canonicalizer, scorer);

        assertEquals(1, report.sharedIntermediateStates().size());
        assertEquals("x + 1", report.sharedIntermediateStates().getFirst().expression());
    }

    @Test
    void classifiesPathFamilies() {
        RuleFamilyClassifier classifier = new RuleFamilyClassifier();

        assertEquals(RuleFamily.EXPANSION, classifier.classify("ast_distribute_left_add"));
        assertEquals(RuleFamily.COMPLETE_SQUARE, classifier.classify("hypothesis_complete_square_preparation"));
        assertEquals(RuleFamily.HIDDEN_STRUCTURE, classifier.classify("hypothesis_difference_of_squares_preparation"));
        assertEquals(RuleFamily.FACTORIZATION, classifier.classify("ast_square_difference_factor"));
        assertEquals(RuleFamily.LEARNED_MACRO, classifier.classify("macro_abcd"));
        assertEquals(RuleFamily.TELESCOPING, classifier.classify("hypothesis_telescoping_fraction"));
        assertEquals(RuleFamily.RATIONALIZATION, classifier.classify("hypothesis_rationalization"));
        assertEquals(RuleFamily.NORMALIZATION, classifier.classify("ast_canonical_normalize"));
    }

    @Test
    void rendersSyntheticSvgWithArtifactMetadata() {
        String input = "a^2 + 2*a*b + b^2";
        String target = "(a + b)^2";
        ConvergentPath algebraPath = syntheticPath(
            "binomial-factorization-path",
            List.of(input, target),
            List.of("ast_binomial_square_factor"),
            List.of(RuleFamily.FACTORIZATION),
            false
        );
        ConvergentPath macroPath = syntheticPath(
            "binomial-macro-path",
            List.of(input, "square(a + b)", target),
            List.of("macro_binomial_square", "ast_expand_square_notation"),
            List.of(RuleFamily.LEARNED_MACRO, RuleFamily.NORMALIZATION),
            true
        );
        ConvergentDiscoveryReport report = new ConvergentDiscoveryReport(
            input,
            canonicalizer.canonicalize(target),
            List.of(new ConvergentState(
                target,
                canonicalizer.stableHash(target),
                List.of(algebraPath, macroPath),
                algebraPath.pathId(),
                macroPath.pathId(),
                Optional.of(macroPath.pathId())
            )),
            List.of(algebraPath, macroPath),
            List.of(),
            List.of(),
            Set.of(RuleFamily.FACTORIZATION, RuleFamily.LEARNED_MACRO),
            Set.of("synthetic")
        );

        String svg = new ConvergentDiscoverySvgWriter().render(report,
            new ArtifactMetadata("synthetic-binomial.mmd", "Synthetic binomial convergence graph"));
        assertTrue(svg.contains("data-source=\"synthetic-binomial.mmd\""), svg);
        assertTrue(svg.contains("<title id=\"title\">Synthetic binomial convergence graph</title>"), svg);
        assertFalse(svg.contains("Sophie-Germain"), svg);

        String inputTitledSvg = new ConvergentDiscoverySvgWriter().render(report,
            new ArtifactMetadata("synthetic-binomial.mmd", ""));
        assertTrue(inputTitledSvg.contains("<title id=\"title\">Convergent discovery: "
            + input + "</title>"), inputTitledSvg);
    }

    @Test
    void rendersGallerySnippetWithFriendlyPathLabels() {
        String input = "x^4 + 4*y^4";
        String target = "(x^2 - 2*x*y + 2*y^2)*(x^2 + 2*x*y + 2*y^2)";
        ConvergentPath macroPath = syntheticPath(
            "path-cryptic-macro",
            List.of(input, target),
            List.of("macro_sophie_germain"),
            List.of(RuleFamily.LEARNED_MACRO),
            true
        );
        ConvergentPath hiddenStructurePath = syntheticPath(
            "path-cryptic-hidden",
            List.of(input, "(x^2 + 2*y^2)^2 - (2*x*y)^2", target),
            List.of("hypothesis_difference_of_squares_preparation", "ast_square_difference_factor"),
            List.of(RuleFamily.HIDDEN_STRUCTURE, RuleFamily.FACTORIZATION),
            false,
            List.of("convergent-sophie-germain-hidden-structure-replay")
        );
        ConvergentPath expandedVariantPath = syntheticPath(
            "path-cryptic-expanded",
            List.of(input, "(x^2 + 2*y^2)^2 - (2*x*y)^2", "expanded form", target),
            List.of(
                "hypothesis_difference_of_squares_preparation",
                "ast_power_two_to_product",
                "ast_square_difference_factor"
            ),
            List.of(RuleFamily.HIDDEN_STRUCTURE, RuleFamily.OTHER, RuleFamily.FACTORIZATION),
            false
        );
        ConvergentDiscoveryReport report = new ConvergentDiscoveryReport(
            input,
            canonicalizer.canonicalize(target),
            List.of(new ConvergentState(
                target,
                canonicalizer.stableHash(target),
                List.of(macroPath, hiddenStructurePath, expandedVariantPath),
                macroPath.pathId(),
                hiddenStructurePath.pathId(),
                Optional.of(macroPath.pathId())
            )),
            List.of(macroPath, hiddenStructurePath, expandedVariantPath),
            List.of(),
            List.of(expandedVariantPath),
            Set.of(RuleFamily.LEARNED_MACRO, RuleFamily.HIDDEN_STRUCTURE, RuleFamily.FACTORIZATION),
            Set.of("synthetic")
        );

        String snippet = new ConvergentDiscoveryGallerySnippetWriter().render(report);

        assertTrue(snippet.contains("shortest path: learned macro shortcut"), snippet);
        assertTrue(snippet.contains("most didactic path: hidden-structure discovery"), snippet);
        assertTrue(snippet.contains("Path 1: learned macro shortcut"), snippet);
        assertTrue(snippet.contains("Path 2: hidden-structure discovery"), snippet);
        assertTrue(snippet.contains("Path 3: expanded discovery variant"), snippet);
        assertTrue(snippet.contains("source replay ids: convergent-sophie-germain-hidden-structure-replay"), snippet);
        assertFalse(snippet.contains("path-cryptic-"), snippet);
        assertFalse(snippet.contains("(none)"), snippet);
    }

    @Test
    void omitsEmptySourceReplayIdsFromGallerySnippet() {
        String input = "x^4 + 4*y^4";
        String target = "(x^2 - 2*x*y + 2*y^2)*(x^2 + 2*x*y + 2*y^2)";
        ConvergentPath macroPath = syntheticPath(
            "path-cryptic-macro",
            List.of(input, target),
            List.of("macro_sophie_germain"),
            List.of(RuleFamily.LEARNED_MACRO),
            true
        );
        ConvergentPath hiddenStructurePath = syntheticPath(
            "path-cryptic-hidden",
            List.of(input, "(x^2 + 2*y^2)^2 - (2*x*y)^2", target),
            List.of("hypothesis_difference_of_squares_preparation", "ast_square_difference_factor"),
            List.of(RuleFamily.HIDDEN_STRUCTURE, RuleFamily.FACTORIZATION),
            false
        );
        ConvergentDiscoveryReport report = new ConvergentDiscoveryReport(
            input,
            canonicalizer.canonicalize(target),
            List.of(new ConvergentState(
                target,
                canonicalizer.stableHash(target),
                List.of(macroPath, hiddenStructurePath),
                macroPath.pathId(),
                hiddenStructurePath.pathId(),
                Optional.of(macroPath.pathId())
            )),
            List.of(macroPath, hiddenStructurePath),
            List.of(),
            List.of(),
            Set.of(RuleFamily.LEARNED_MACRO, RuleFamily.HIDDEN_STRUCTURE, RuleFamily.FACTORIZATION),
            Set.of("synthetic")
        );

        String snippet = new ConvergentDiscoveryGallerySnippetWriter().render(report);

        assertFalse(snippet.contains("source replay ids:"), snippet);
        assertFalse(snippet.contains("(none)"), snippet);
    }

    private SearchState state(List<String> path, List<String> rules) {
        String expression = path.getLast();
        return new SearchState(
            expression,
            rules.size(),
            scorer.score(expression),
            path,
            rules,
            Set.copyOf(rules),
            0,
            canonicalizer.stableHash(expression),
            path.size() > 1 ? path.get(path.size() - 2) : null,
            rules.isEmpty() ? null : rules.getLast(),
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            1,
            rules.stream().map(ignored -> RewriteKind.NORMALIZE).toList(),
            rules.stream().map(ignored -> true).toList()
        );
    }

    private ConvergentPath syntheticPath(
        String pathId,
        List<String> expressions,
        List<String> ruleIds,
        List<RuleFamily> ruleFamilies,
        boolean containsMacroStep
    ) {
        return syntheticPath(pathId, expressions, ruleIds, ruleFamilies, containsMacroStep, List.of());
    }

    private ConvergentPath syntheticPath(
        String pathId,
        List<String> expressions,
        List<String> ruleIds,
        List<RuleFamily> ruleFamilies,
        boolean containsMacroStep,
        List<String> sourceReplayIds
    ) {
        return new ConvergentPath(
            pathId,
            expressions,
            ruleIds,
            ruleFamilies,
            expressions.getLast(),
            scorer.score(expressions.getLast()),
            ruleIds.size(),
            false,
            containsMacroStep,
            containsMacroStep,
            "proved",
            "validated",
            sourceReplayIds
        );
    }
}
