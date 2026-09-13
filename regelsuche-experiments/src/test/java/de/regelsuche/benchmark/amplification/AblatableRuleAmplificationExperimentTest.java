package de.regelsuche.benchmark.amplification;

import de.regelsuche.math.sympy.SymPyNamedOperationEngine;
import de.regelsuche.search.reachability.AblatableRulePreparationRunner.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static de.regelsuche.search.reachability.AmplificationJson.*;
import static org.junit.jupiter.api.Assertions.*;

class AblatableRuleAmplificationExperimentTest {
    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    @Test void allCandidatesFreezeBeforeQualificationAndUnavailableExternalCannotWin() {
        var sources = List.of(new Source("cos(x)^2 + sin(x)^2", List.of()));
        byte[] qualification = AblatableRuleAmplificationExperiment.qualificationBytes(List.of(
            new AblatableRuleAmplificationExperiment.Qualification("public-control", "trigonometry", "sympy.trig.pythagorean", "1", "OBSCURED_POSITIVE")));
        var plan = AblatableRuleAmplificationExperiment.plan(REVISION, sources, qualification);
        var calls = new ArrayList<SymPyNamedOperationEngine.Operation>();
        var opened = new AtomicBoolean();
        var frozen = AblatableRuleAmplificationExperiment.freeze(plan, sources, (operation, source) -> {
            assertFalse(opened.get()); calls.add(operation);
            return unavailable(operation, source);
        });
        assertEquals(6, calls.size());
        assertEquals(4, frozen.nativeRuns().size());
        assertFalse(frozen.canonicalJson().contains("OBSCURED_POSITIVE"));
        assertFalse(frozen.canonicalJson().contains("public-control"));
        var report = AblatableRuleAmplificationExperiment.qualify(plan, frozen, () -> { opened.set(true); return qualification; });
        assertTrue(opened.get());
        assertTrue(report.contains("BLOCKED_UNAVAILABLE_INTERNAL_WORK"));
        assertTrue(report.contains("SEMANTIC_COVERAGE_ONLY"));
        assertTrue(report.contains("REPRODUCTION_NOT_EVALUATED"));
        var parsedReport = read(report);
        assertEquals(4, ((java.util.Map<?, ?>) parsedReport.get("profileWork")).size());
        assertEquals(4, ((java.util.Map<?, ?>) parsedReport.get("incrementalWork")).size());
        assertTrue(report.contains("verificationExactPreparationWork"));
        assertTrue(report.contains("MIXED_CONTRACT_UNITS_NEVER_SUMMED_AS_MATCHED_TOTAL_WORK"));
        for (String rows : List.of("nativeRows", "externalRows"))
            assertEquals(0, ((Number) ((java.util.Map<?, ?>) ((List<?>) parsedReport.get(rows)).getFirst()).get("sourceIndex")).intValue());
        assertThrows(IllegalArgumentException.class, () -> AblatableRuleAmplificationExperiment.qualify(plan, frozen,
            () -> qualification.clone().length == 0 ? qualification : "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void completeNoGainIsRetainedAsNullResult() {
        var sources = List.of(new Source("sin(x)^2 + cos(y)^2", List.of()));
        byte[] qualification = AblatableRuleAmplificationExperiment.qualificationBytes(List.of(
            new AblatableRuleAmplificationExperiment.Qualification("negative-control", "trigonometry", "sympy.trig.pythagorean", "", "NEAR_MISS")));
        var plan = AblatableRuleAmplificationExperiment.plan(REVISION, sources, qualification);
        var frozen = AblatableRuleAmplificationExperiment.freeze(plan, sources, AblatableRuleAmplificationExperimentTest::unavailable);
        var report = AblatableRuleAmplificationExperiment.qualify(plan, frozen, () -> qualification);
        assertTrue(report.contains("NULL_NO_INCREMENTAL_COVERAGE"));
        assertEquals(4, frozen.nativeRuns().size());
        assertThrows(IllegalArgumentException.class, () -> AblatableRuleAmplificationExperiment.freeze(plan,
            List.of(new Source("sin(x)^2 + cos(x)^2", List.of())), AblatableRuleAmplificationExperimentTest::unavailable));
    }

    @Test void legalSourceSymbolsCannotBecomeBudgetOrTechnicalStatusEvidence() {
        var source = new Source("MATCH_BUDGET + BUDGET_INCONCLUSIVE + TECHNICAL_FAILURE", List.of());
        var labels = AblatableRuleAmplificationExperiment.qualificationBytes(List.of(
            new AblatableRuleAmplificationExperiment.Qualification("status-name-control", "polynomial", "sympy.poly.factor.diff_squares", "", "NEAR_MISS")));
        var plan = AblatableRuleAmplificationExperiment.plan(REVISION, List.of(source), labels);
        var frozen = AblatableRuleAmplificationExperiment.freeze(plan, List.of(source), AblatableRuleAmplificationExperimentTest::unavailable);
        var report = read(AblatableRuleAmplificationExperiment.qualify(plan, frozen, () -> labels));
        var statuses = (java.util.Map<?, ?>) report.get("statuses");
        assertEquals(java.util.Set.of("NEGATIVE_NO_CANDIDATE"), statuses.keySet());
        assertEquals(4, ((Number) statuses.get("NEGATIVE_NO_CANDIDATE")).intValue());
    }

    @Test void publicCorpusHasAllThreeFamiliesAndEveryRequiredControlKind() {
        var labels = AblatableRuleAmplificationCorpus.publicCorpus().qualification();
        for (String family : List.of("trigonometry", "polynomial", "rational")) {
            var kinds = labels.stream().filter(row -> row.family().equals(family)).map(AblatableRuleAmplificationExperiment.Qualification::kind).toList();
            assertTrue(kinds.containsAll(List.of("DIRECT_POSITIVE", "OBSCURED_POSITIVE", "EXACT_PREPARATION_POSITIVE", "NEAR_MISS", "GUARD_NEGATIVE")), kinds.toString());
        }
    }

    @Test void nativePublicControlsExerciseEachFamilyWithRealExecutors() {
        var corpus = AblatableRuleAmplificationCorpus.publicCorpus();
        var all = new de.regelsuche.knowledge.KnowledgePackRegistry().enabledRules(
            de.regelsuche.knowledge.KnowledgePackSelection.profile(de.regelsuche.knowledge.RuleProfile.ALL));
        var principals = all.stream().filter(rule -> List.of("sympy.trig.pythagorean", "sympy.poly.factor.diff_squares",
            "sympy.rational.partial_fraction.telescoping").contains(rule.id())).map(de.regelsuche.transform.PatternRewriteRule.class::cast).toList();
        var preparation = de.regelsuche.transform.AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule -> rule.id().equals("ast_cancel_division_factor")).toList();
        var runner = new de.regelsuche.search.reachability.AblatableRulePreparationRunner(principals, preparation, REVISION,
            de.regelsuche.search.reachability.AblatableRulePreparationRunner.Budget.publicControls());
        for (int index = 0; index < corpus.sources().size(); index++) {
            // Ordinary native controls only: no external study, no released study artifacts.
            var run = runner.analyze(de.regelsuche.search.reachability.AblatableRulePreparationRunner.Profile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE,
                corpus.sources().get(index));
            var label = corpus.qualification().get(index);
            var candidate = run.candidates().stream().filter(value -> value.principalId().equals(label.principalId())).findFirst();
            assertEquals(!label.reference().isEmpty(), candidate.isPresent(), label.caseId() + ": " + run.canonicalJson());
            if (candidate.isPresent()) {
                var canonicalizer = new de.regelsuche.canonical.ExpressionCanonicalizer();
                assertEquals(canonicalizer.stableHash(label.reference()), canonicalizer.stableHash(candidate.get().output()), label.caseId());
                if (label.kind().equals("EXACT_PREPARATION_POSITIVE")) assertEquals("SAFE_EXACT_PREPARATION", candidate.get().stage());
            }
        }
    }

    @Test void externalResultsCannotSubstituteTheirSourceOrClaimDifferentOutcomeFields() {
        var source = new Source("x", List.of());
        var labels = AblatableRuleAmplificationExperiment.qualificationBytes(List.of(
            new AblatableRuleAmplificationExperiment.Qualification("binding-control", "polynomial", "sympy.poly.factor.diff_squares", "", "NEAR_MISS")));
        var plan = AblatableRuleAmplificationExperiment.plan(REVISION, List.of(source), labels);
        assertThrows(IllegalArgumentException.class, () -> AblatableRuleAmplificationExperiment.freeze(plan, List.of(source),
            (operation, ignored) -> unavailable(operation, new Source("y", List.of()))));
        assertThrows(IllegalArgumentException.class, () -> AblatableRuleAmplificationExperiment.freeze(plan, List.of(source),
            (operation, ignored) -> {
                var actual = unavailable(operation, source);
                return new SymPyNamedOperationEngine.Outcome("COMPLETED", "1", actual.configurationHash(), actual.canonicalJson(), actual.contentHash());
            }));
    }

    @Test void fullNativePublicControlFreezeFitsBoundedArtifactContractWithoutStartingForeignRuntime() {
        var corpus = AblatableRuleAmplificationCorpus.publicCorpus();
        var labels = AblatableRuleAmplificationExperiment.qualificationBytes(corpus.qualification());
        var plan = AblatableRuleAmplificationExperiment.plan(REVISION, corpus.sources(), labels);
        var frozen = AblatableRuleAmplificationExperiment.freeze(plan, corpus.sources(), AblatableRuleAmplificationExperimentTest::unavailable);
        int bytes = frozen.canonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(bytes <= 8_000_000, "ordinary control freeze bytes: " + bytes);
        assertEquals(corpus.sources().size() * 4, frozen.nativeRuns().size());
        var report = read(AblatableRuleAmplificationExperiment.qualify(plan, frozen, () -> labels));
        assertEquals(0, ((Number) report.get("falsePositives")).intValue());
        assertEquals(0, ((Number) report.get("invalidReplays")).intValue());
    }

    private static SymPyNamedOperationEngine.Outcome unavailable(SymPyNamedOperationEngine.Operation operation, Source source) {
        var closed = new SymPyNamedOperationEngine();
        closed.close();
        return closed.execute(operation, source.expression(), source.assumptions());
    }
}
