package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import java.util.Objects;

/**
 * Pre-execution binding between an immutable rewrite-program study and the
 * mechanics used to execute its TRAIN population.
 *
 * <p>This additive artifact deliberately leaves the historical study-plan v1
 * schema untouched. A new execution protocol therefore receives a new identity
 * without reinterpreting old study hashes.</p>
 */
public record EvolutionRewriteProgramPopulationExecutionPlan(
    String schema,
    String studyPlanHash,
    String executionProtocolHash,
    String status,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-population-execution-plan/v1";
    public static final String STATUS = "FROZEN_NOT_RUN";

    public EvolutionRewriteProgramPopulationExecutionPlan {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported rewrite-program population execution plan");
        }
        EvolutionGenome.requireSha256(studyPlanHash, "studyPlanHash");
        EvolutionGenome.requireSha256(
            executionProtocolHash, "executionProtocolHash");
        if (!STATUS.equals(status)) {
            throw new IllegalArgumentException(
                "population execution plan must remain frozen and unexecuted");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            studyPlanHash,
            executionProtocolHash,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "population execution plan contentHash mismatch");
        }
    }

    public static EvolutionRewriteProgramPopulationExecutionPlan create(
        EvolutionRewriteProgramStudyPlan studyPlan,
        EvolutionRewriteProgramPopulationExecutionProtocol executionProtocol
    ) {
        Objects.requireNonNull(studyPlan, "studyPlan");
        Objects.requireNonNull(executionProtocol, "executionProtocol");
        String hash = EvolutionGenome.hash(render(
            studyPlan.contentHash(),
            executionProtocol.contentHash(),
            null));
        return new EvolutionRewriteProgramPopulationExecutionPlan(
            SCHEMA,
            studyPlan.contentHash(),
            executionProtocol.contentHash(),
            STATUS,
            hash);
    }

    public void requireInputs(
        EvolutionRewriteProgramStudyPlan studyPlan,
        EvolutionRewriteProgramPopulationExecutionProtocol executionProtocol
    ) {
        Objects.requireNonNull(studyPlan, "studyPlan");
        Objects.requireNonNull(executionProtocol, "executionProtocol");
        if (!studyPlanHash.equals(studyPlan.contentHash())
                || !executionProtocolHash.equals(
                    executionProtocol.contentHash())) {
            throw new IllegalArgumentException(
                "population execution plan input identity mismatch");
        }
    }

    public String toCanonicalJson() {
        return render(studyPlanHash, executionProtocolHash, contentHash);
    }

    private static String render(
        String studyPlanHash,
        String executionProtocolHash,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("studyPlanHash", studyPlanHash)
            .property("executionProtocolHash", executionProtocolHash)
            .property("status", STATUS);
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }
}
