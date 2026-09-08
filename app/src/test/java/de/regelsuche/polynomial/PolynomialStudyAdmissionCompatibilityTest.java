package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCanonicalWorkProjection;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCaseCorpus;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityExecutionInputs;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityExecutionPlan;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.search.program.BudgetedTransformationSource;
import de.regelsuche.search.program.ExactPolynomialTransformationSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Admission diagnostic only: not the five-profile study, and never opens qualification. */
class PolynomialStudyAdmissionCompatibilityTest {
    @Test
    void conservativeRawCeilingCannotAdmitTheFrozenGeneralProfileInputs() {
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases().stream().collect(
            Collectors.toMap(PolynomialTheoryUtilityCaseCorpus.FormationCase::caseId, value -> value));
        AtomicInteger engineRequests = new AtomicInteger();
        Map<String, ExactPolynomialTransformationSource> runs = new java.util.LinkedHashMap<>();
        int checked = 0;
        for (var input : PolynomialTheoryUtilityExecutionInputs.freeze().inputs()) {
            var profile = PolynomialTheoryUtilityExecutionPlan.PROFILES.stream()
                .filter(value -> value.profileId().equals(input.profileId())).findFirst().orElseThrow();
            if (!profile.transformationId().equals(ExactFactorizationTransformationPipeline.TRANSFORMATION_ID)) continue;
            var studyCase = formation.get(input.caseId());
            var session = runs.computeIfAbsent(input.runId(), ignored -> new ExactPolynomialTransformationSource(
                forbiddenEngine(profile.engineId(), engineRequests),
                profile.cacheMode().equals("READ_WRITE") ? ExactPolynomialTransformationSource.Mode.VERIFIED_CACHE
                    : ExactPolynomialTransformationSource.Mode.ON_DEMAND, List.of(), 128));
            List<Integer> path = switch (studyCase.occurrenceLayout()) {
                case "ROOT" -> List.of();
                case "NESTED_RIGHT" -> List.of(1);
                case "TWO_IDENTICAL_SIBLINGS" -> List.of(0);
                case "FOUR_IDENTICAL_LEAVES" -> List.of(0, 0);
                default -> throw new IllegalStateException("unknown frozen occurrence layout");
            };
            var source = session.atPath(path);
            // This deliberately gives the source the entire mechanical ceiling,
            // before primitive/factor caps can narrow it further. If even the
            // first occurrence fails preflight, resetting on later occurrences
            // would not be a valid way to qualify a row.
            var result = source.transform(studyCase.sourceExpression(), input.totalMechanicalWork());
            assertEquals(BudgetedTransformationSource.Status.BUDGET_INCONCLUSIVE, result.status(), input.inputId());
            assertTrue(result.mechanicalWorkUnits() <= input.totalMechanicalWork());
            var partition = PolynomialTheoryUtilityCanonicalWorkProjection.partition(0, source.lastWork());
            var projection = PolynomialTheoryUtilityCanonicalWorkProjection.project(input, partition);
            assertEquals(0, projection.work().factorizationWork());
            assertEquals(0, source.cacheStats().insertions());
            assertEquals(0, source.cacheStats().replays());
            checked++;
        }
        assertEquals(360, checked);
        assertEquals(18, runs.size());
        assertEquals(0, engineRequests.get());
    }

    private FactorizationEngine<ExactRational> forbiddenEngine(String id, AtomicInteger requests) {
        return new FactorizationEngine<>() {
            public String engineId() { return id; }
            public String coefficientDomainId() { return ExactRationalField.DOMAIN_ID; }
            public EngineResult<ExactRational> propose(FactorizationRequest<ExactRational> request) {
                requests.incrementAndGet();
                throw new AssertionError("admission diagnostic unexpectedly reached an engine");
            }
        };
    }
}
