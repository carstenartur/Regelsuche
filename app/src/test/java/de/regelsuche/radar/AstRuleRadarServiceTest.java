package de.regelsuche.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.plugin.PluginRuntimeConfig;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AstRuleRadarServiceTest {
    private InMemoryRuleInventoryRepository inventory;
    private AstRuleRadarService service;

    @BeforeEach
    void setUp() {
        inventory = new InMemoryRuleInventoryRepository();
        inventory.save(binomialMacro());
        service = new AstRuleRadarService(
            inventory,
            new InMemoryExpressionGraphStore(),
            PluginRuntimeConfig.defaults()
        );
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void exposesCompleteAstAndConcretePositionBoundCandidates() {
        AstRuleRadar.Snapshot snapshot = service.inspect("(x + 1)^2 + 0", AstRuleRadar.Context.defaults());

        assertTrue(snapshot.valid(), snapshot.diagnostics().toString());
        assertTrue(snapshot.nodes().stream().anyMatch(node -> "root".equals(node.pathKey()) && "+".equals(node.label())));
        assertTrue(snapshot.nodes().stream().anyMatch(node -> "000".equals(node.pathKey()) && "^".equals(node.label())));
        assertTrue(snapshot.nodes().stream().anyMatch(node -> "000.000.000".equals(node.pathKey()) && "x".equals(node.label())));

        AstRuleRadar.ApplicableMove addZero = snapshot.candidates().stream()
            .filter(candidate -> "root".equals(candidate.pathKey()))
            .filter(candidate -> "ast_add_zero_right".equals(candidate.ruleId()))
            .findFirst().orElseThrow();
        assertEquals("CORE", addZero.origin().name());
        assertTrue(addZero.applicable());
        assertFalse(addZero.candidateId().isBlank());
        assertFalse(addZero.expressionAfter().contains("+ 0"));

        assertTrue(snapshot.candidates().stream()
            .anyMatch(candidate -> "000".equals(candidate.pathKey())
                && "ast_power_two_to_product".equals(candidate.ruleId())),
            "atomic power expansion must be visible at the power subtree");
        assertTrue(snapshot.candidates().stream()
            .anyMatch(candidate -> "000".equals(candidate.pathKey())
                && candidate.origin() == AstRuleRadar.RuleOrigin.LEARNED_MACRO
                && "macro_test_binomial".equals(candidate.ruleId())),
            "qualified learned macro must coexist at the same power subtree");
    }

    @Test
    void candidateIdentityAndOrderingAreStableForFrozenContext() {
        AstRuleRadar.Snapshot first = service.inspect("(x + 1)^2 + 0", AstRuleRadar.Context.defaults());
        AstRuleRadar.Snapshot second = service.inspect("(x + 1)^2 + 0", AstRuleRadar.Context.defaults());

        assertEquals(
            first.candidates().stream().map(AstRuleRadar.ApplicableMove::candidateId).toList(),
            second.candidates().stream().map(AstRuleRadar.ApplicableMove::candidateId).toList());
        assertEquals(
            first.candidates().stream().map(AstRuleRadar.ApplicableMove::orderingKey).toList(),
            first.candidates().stream().map(AstRuleRadar.ApplicableMove::orderingKey).sorted().toList(),
            "candidate order must be deterministic after position ordering");
    }

    @Test
    void bindingsOriginValidationAndMacroEvidenceCannotDisappear() {
        AstRuleRadar.ApplicableMove macro = service.inspect("(x + 1)^2 + 0", AstRuleRadar.Context.defaults())
            .candidates().stream()
            .filter(candidate -> candidate.origin() == AstRuleRadar.RuleOrigin.LEARNED_MACRO)
            .findFirst().orElseThrow();

        assertEquals("VALIDATED_BY_EXAMPLES", macro.validationStatus());
        assertTrue(macro.bindings().stream().anyMatch(binding -> "A".equals(binding.name())));
        assertTrue(macro.bindings().stream().anyMatch(binding -> "B".equals(binding.name())));
        assertNotNull(macro.macroEvidence());
        assertEquals("test_binomial", macro.macroEvidence().reusableRuleId());
        assertEquals(List.of("supporting-path-1"), macro.macroEvidence().supportingPathIds());
    }

    @Test
    void rejectedAssumptionIsVisibleButNotExecutable() {
        inventory.save(new ReusableRule(
            "assumption_macro",
            "A / A",
            "1",
            List.of(),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.NEW,
            2,
            2.0,
            Instant.parse("2026-01-01T00:00:00Z"),
            "hash",
            null,
            0,
            3,
            List.of(),
            0.9,
            List.of("A != 0")
        ));

        AstRuleRadar.ApplicableMove rejected = service.inspect("x / x", AstRuleRadar.Context.defaults())
            .candidates().stream()
            .filter(candidate -> "macro_assumption_macro".equals(candidate.ruleId()))
            .findFirst().orElseThrow();
        assertFalse(rejected.applicable());
        assertEquals(AstRuleRadar.CandidateOutcome.REJECTED_ASSUMPTION, rejected.outcome());
        assertEquals(List.of("A != 0"), rejected.assumptions());
    }

    @Test
    void candidateBudgetsReportExactOmittedCounts() {
        AstRuleRadar.Context context = new AstRuleRadar.Context(
            de.regelsuche.knowledge.RuleProfile.CORE,
            Set.of(), Set.of(),
            false, true,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            "DISCOVERY", "",
            1, 2,
            List.of(), true, "", Map.of());

        AstRuleRadar.Snapshot snapshot = service.inspect("(x + 1)^2 + 0", context);
        assertTrue(snapshot.truncation().truncated());
        assertEquals(snapshot.truncation().generatedCandidateCount(),
            snapshot.truncation().returnedCandidateCount() + snapshot.truncation().omittedCandidateCount());
        assertTrue(snapshot.nodes().stream().anyMatch(node -> node.omittedCandidateCount() > 0));
    }

    @Test
    void invalidInputReturnsStructuredDiagnostic() {
        AstRuleRadar.Snapshot snapshot = service.inspect("(((", AstRuleRadar.Context.defaults());
        assertFalse(snapshot.valid());
        assertTrue(snapshot.nodes().isEmpty());
        assertEquals("INVALID_EXPRESSION", snapshot.diagnostics().getFirst().code());
    }

    private ReusableRule binomialMacro() {
        return new ReusableRule(
            "test_binomial",
            "(A + B)^2",
            "A^2 + 2*A*B + B^2",
            List.of(),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.NEW,
            3,
            4.0,
            Instant.parse("2026-01-01T00:00:00Z"),
            "binomial-hash",
            null,
            0,
            4,
            List.of("supporting-path-1"),
            0.95,
            List.of()
        );
    }
}
