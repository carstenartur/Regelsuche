package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.PopulationCheckpoint;
import de.regelsuche.json.JsonWriter;
import java.util.Objects;

/**
 * TRAIN-only checkpoint wrapper that prevents resume under different population
 * execution semantics.
 */
public record ExecutionProtocolBoundEvolutionRewriteProgramPopulationCheckpoint(
    String schema,
    PopulationCheckpoint checkpoint,
    String executionPlanHash,
    String executionProtocolHash,
    String status,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-execution-protocol-bound-checkpoint/v1";
    public static final String STATUS =
        "POPULATION_EXECUTION_PROTOCOL_BOUND_TRAIN_CHECKPOINT";

    public ExecutionProtocolBoundEvolutionRewriteProgramPopulationCheckpoint {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported execution-protocol-bound checkpoint schema");
        }
        Objects.requireNonNull(checkpoint, "checkpoint");
        EvolutionGenome.requireSha256(executionPlanHash, "executionPlanHash");
        EvolutionGenome.requireSha256(
            executionProtocolHash, "executionProtocolHash");
        if (!STATUS.equals(status)) {
            throw new IllegalArgumentException(
                "checkpoint lacks population execution protocol binding");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            checkpoint,
            executionPlanHash,
            executionProtocolHash,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "execution-protocol-bound checkpoint contentHash mismatch");
        }
    }

    public static ExecutionProtocolBoundEvolutionRewriteProgramPopulationCheckpoint
            create(
        PopulationCheckpoint checkpoint,
        EvolutionRewriteProgramPopulationExecutionPlan executionPlan,
        EvolutionRewriteProgramPopulationExecutionProtocol executionProtocol
    ) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(executionPlan, "executionPlan");
        Objects.requireNonNull(executionProtocol, "executionProtocol");
        if (!executionPlan.studyPlanHash().equals(checkpoint.studyPlanHash())
                || !executionPlan.executionProtocolHash().equals(
                    executionProtocol.contentHash())) {
            throw new IllegalArgumentException(
                "checkpoint differs from population execution plan");
        }
        String hash = EvolutionGenome.hash(render(
            checkpoint,
            executionPlan.contentHash(),
            executionProtocol.contentHash(),
            null));
        return new ExecutionProtocolBoundEvolutionRewriteProgramPopulationCheckpoint(
            SCHEMA,
            checkpoint,
            executionPlan.contentHash(),
            executionProtocol.contentHash(),
            STATUS,
            hash);
    }

    public void requireCompatible(
        EvolutionRewriteProgramPopulationExecutionPlan executionPlan,
        EvolutionRewriteProgramPopulationExecutionProtocol executionProtocol
    ) {
        Objects.requireNonNull(executionPlan, "executionPlan");
        Objects.requireNonNull(executionProtocol, "executionProtocol");
        if (!executionPlanHash.equals(executionPlan.contentHash())
                || !executionProtocolHash.equals(
                    executionProtocol.contentHash())) {
            throw new IllegalArgumentException(
                "checkpoint population execution identity mismatch");
        }
    }

    public String toCanonicalJson() {
        return render(
            checkpoint,
            executionPlanHash,
            executionProtocolHash,
            contentHash);
    }

    private static String render(
        PopulationCheckpoint checkpoint,
        String executionPlanHash,
        String executionProtocolHash,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("checkpointHash", checkpoint.contentHash())
            .property("checkpointJson", checkpoint.toCanonicalJson())
            .property("executionPlanHash", executionPlanHash)
            .property("executionProtocolHash", executionProtocolHash)
            .property("status", STATUS);
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }
}
