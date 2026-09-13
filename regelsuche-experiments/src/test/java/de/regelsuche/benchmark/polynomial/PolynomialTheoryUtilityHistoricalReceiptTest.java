package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityHistoricalReceiptTest {
    @Test
    void preservesHistoricalTerminalOrdinals() {
        assertEquals(List.of("VALIDATED_TRANSITION", "NO_TRANSITION", "UNSUPPORTED", "BUDGET_INCONCLUSIVE", "TECHNICAL_FAILURE"),
            java.util.Arrays.stream(PolynomialTheoryUtilityCandidateResult.TerminalStatus.values())
                .limit(5).map(Enum::name).toList());
    }

    @Test
    void preservesTheReceiptProducedByTheUnmodifiedMainContracts() {
        // Established by compiling the original polynomial contract sources from main 3f437b044dc9.
        // This is component serialization plus one native row, not a utility study or qualification.
        assertEquals("sha256:81ed2c729c5fb500fcebd5124b78ab3d779d23640fc335f774228d86617a54a2", receipt());
    }

    public static void main(String[] args) {
        System.out.println(receipt());
    }

    private static String receipt() {
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze();
        var cases = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var results = new ArrayList<PolynomialTheoryUtilityCandidateResult>();
        var measurements = new ArrayList<PolynomialTheoryUtilityCandidateMeasurements>();
        for (int index = 0; index < inputs.inputs().size(); index++) {
            var result = PolynomialTheoryUtilityCandidateResult.noTransition(inputs.inputs().get(index),
                cases.get(index % cases.size()), "HISTORICAL_COMPONENT_" + index);
            results.add(result);
            measurements.add(PolynomialTheoryUtilityCandidateMeasurements.create(result, List.of(), List.of(), List.of()));
        }
        var batch = PolynomialTheoryUtilityCandidateMeasurementBatch.create(
            PolynomialTheoryUtilityProfileAdapter.CandidateBatch.create(inputs, results), measurements);
        var freeze = PolynomialTheoryUtilityCandidateFreeze.create(batch);
        freeze.requireVerified(freeze.bytes());
        String material = results.getFirst().resultId() + measurements.getFirst().measurementId() + batch.batchId()
            + freeze.schema() + freeze.contentHash() + freeze.byteLength();
        var input = inputs.inputs().stream().filter(value -> value.profileId().equals("ON_DEMAND_VERIFIED_FACTORIZATION")
            && value.caseId().equals("z02-difference-of-squares") && value.checkpointId().equals("CP06_FULL"))
            .findFirst().orElseThrow();
        var nativeRow = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.executeCase(input, cases.getFirst());
        material += nativeRow.measured().result().resultId() + nativeRow.measured().measurements().measurementId()
            + nativeRow.evidenceHash() + nativeRow.rawWork().canonicalMaterial();
        var event = PolynomialTheoryUtilityCacheEvent.create(0, input.inputId(), "NONE",
            PolynomialTheoryUtilityCacheEvent.Kind.LOOKUP_HIT, PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            results.getFirst().resultId(), measurements.getFirst().measurementId());
        material += event.eventId();
        return PolynomialTheoryUtilityExecutionIdentity.sha256(material.getBytes(StandardCharsets.UTF_8));
    }
}
