package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompiledLinearRewriteEngineTest {
    private static RewriteProgram program() {
        var rules = AstRewriteTransformationEngine.defaultRules();
        var zero = rules.stream().filter(rule -> rule.id().equals("ast_add_zero_right")).toList();
        var one = rules.stream().filter(rule -> rule.id().equals("ast_multiply_one_right")).toList();
        return RewritePrograms.sequence("linear", RewritePrograms.source("zero", new PreparedAstRewriteTransformationEngine(zero)),
            RewritePrograms.source("one", new PreparedAstRewriteTransformationEngine(one)));
    }

    @Test
    void sourceOrderingMatchesInterpreterEvenWhenPrimitiveEmissionIsUnsorted() {
        var rules = AstRewriteTransformationEngine.defaultRules().stream()
            .filter(rule -> List.of("ast_add_zero_right", "ast_multiply_one_right").contains(rule.id())).toList();
        List<TransformationEngine> engines = List.of(new AstRewriteTransformationEngine(rules),
            new PreparedAstRewriteTransformationEngine(rules));
        for (var engine : engines) {
            var source = RewritePrograms.source("unsorted", engine);
            String expression = "(x+0)*1";
            var reference = new ProgrammedTransformationEngine(source).transform(expression);
            assertNotEquals(engine.transform(expression), reference, "fixture must exercise source sorting");
            assertEquals("ast_add_zero_right", reference.getFirst().rule());
            assertParity(source, expression);
            assertParity(RewritePrograms.sequence("ordered-sequence", source, source), expression);
        }
    }

    @Test
    void kindTiesAndDuplicatePrefixIdentitiesRetainInterpreterPathsAndWork() {
        var zero = (PatternRewriteRule) AstRewriteTransformationEngine.defaultRules().stream()
            .filter(rule -> rule.id().equals("ast_add_zero_right")).findFirst().orElseThrow();
        List<RewriteRule> rules = List.of(
            new PatternRewriteRule("same", zero.source(), zero.target(), RewriteKind.SIMPLIFY, false, -1, true),
            new PatternRewriteRule("same", zero.source(), zero.target(), RewriteKind.NORMALIZE, false, -1, true));
        List<TransformationEngine> engines = List.of(new AstRewriteTransformationEngine(rules),
            new PreparedAstRewriteTransformationEngine(rules));
        var tail = ((RewriteProgram.Sequence) program()).steps().getLast();
        for (var engine : engines) {
            var source = RewritePrograms.source("kind-tie", engine);
            String expression = "(x+0)*1";
            var reference = new ProgrammedTransformationEngine(source).transform(expression);
            assertEquals(1, reference.size());
            assertEquals(RewriteKind.NORMALIZE, reference.getFirst().kind());
            assertParity(source, expression);
            var sequence = RewritePrograms.sequence("duplicate-prefixes", source, tail);
            assertEquals(2, new ProgrammedTransformationEngine(sequence).transform(expression).size());
            assertParity(sequence, expression);
        }
    }

    private static void assertParity(RewriteProgram program, String expression) {
        var reference = new ProgrammedTransformationEngine(program).transformMeasured(expression);
        var actual = new CompiledLinearRewriteEngine(program, 32).transformMeasured(expression);
        assertEquals(reference.transformations(), actual.transformations());
        assertEquals(reference.workMetrics().sourceInvocations(), actual.workMetrics().sourceInvocations());
        assertEquals(reference.workMetrics().sourceCandidates(), actual.workMetrics().sourceCandidates());
        assertEquals(reference.workMetrics().composedCandidates(), actual.workMetrics().composedCandidates());
        assertEquals(reference.workMetrics().duplicateCandidatesDropped(), actual.workMetrics().duplicateCandidatesDropped());
        assertEquals(reference.workMetrics().totalWorkUnits() - reference.workMetrics().programNodeVisits(),
            actual.workMetrics().totalWorkUnits());
    }

    @Test
    void compiledPipelinePreservesEveryInterpretedPrimitivePath() {
        for (var program : List.of(program(), ((RewriteProgram.Sequence) program()).steps().getFirst())) {
            var interpreted = new ProgrammedTransformationEngine(program);
            var compiled = new CompiledLinearRewriteEngine(program, 32);
            for (var expression : List.of("(x+0)*1+(y+0)*1", " (x+0)*1 ", "x+0", "x")) {
                var reference = interpreted.transformMeasured(expression);
                var actual = compiled.transformMeasured(expression);
                assertEquals(reference.transformations(), actual.transformations());
                assertEquals(reference.workMetrics().sourceInvocations(), actual.workMetrics().sourceInvocations());
                assertEquals(reference.workMetrics().sourceCandidates(), actual.workMetrics().sourceCandidates());
                assertEquals(reference.workMetrics().composedCandidates(), actual.workMetrics().composedCandidates());
                assertEquals(reference.workMetrics().totalWorkUnits() - reference.workMetrics().programNodeVisits(),
                    actual.workMetrics().totalWorkUnits());
                assertEquals(0, actual.workMetrics().programNodeVisits());
            }
        }
    }

    @Test
    void unsupportedTopologyAndOversizedIntermediateBatchesFailExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> new CompiledLinearRewriteEngine(
            RewritePrograms.choice("choice", program(), program()), 32));
        assertThrows(IllegalArgumentException.class, () -> new CompiledLinearRewriteEngine(program(), 1)
            .transformMeasured("(x+0)*1+(y+0)*1"));
        assertThrows(IllegalArgumentException.class, () -> new CompiledLinearRewriteEngine(program(), 32).transformMeasured(" "));
        assertThrows(IllegalArgumentException.class, () -> new CompiledLinearRewriteEngine(RewritePrograms.source("opaque",
            de.regelsuche.transform.MeasuredTransformationEngines.counting(new AstRewriteTransformationEngine())), 32));
    }
}
