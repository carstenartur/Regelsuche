package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramPopulationExecutionProtocol.MutatorSemanticsVersion;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationExecutionProtocol.PopulationEngineSemanticsVersion;
import de.regelsuche.json.JsonWriter;
import java.util.Objects;

/**
 * Authority-bearing retained TRAIN run whose population mechanics are bound in
 * addition to the already frozen mathematical evaluator protocol.
 */
public record ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRun(
    String schema,
    ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun retainedRun,
    String executionPlanHash,
    String executionProtocolHash,
    String populationEngineImplementationClass,
    PopulationEngineSemanticsVersion populationEngineSemanticsVersion,
    String mutatorImplementationClass,
    MutatorSemanticsVersion mutatorSemanticsVersion,
    String status,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-execution-protocol-bound-retained-run/v1";
    public static final String STATUS =
        "EVALUATOR_AND_POPULATION_EXECUTION_PROTOCOL_BOUND_TRAIN_RETAINED";

    public ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRun {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported execution-protocol-bound retained-run schema");
        }
        Objects.requireNonNull(retainedRun, "retainedRun");
        EvolutionGenome.requireSha256(executionPlanHash, "executionPlanHash");
        EvolutionGenome.requireSha256(
            executionProtocolHash, "executionProtocolHash");
        populationEngineImplementationClass = requireText(
            populationEngineImplementationClass,
            "populationEngineImplementationClass");
        Objects.requireNonNull(
            populationEngineSemanticsVersion,
            "populationEngineSemanticsVersion");
        mutatorImplementationClass = requireText(
            mutatorImplementationClass,
            "mutatorImplementationClass");
        Objects.requireNonNull(
            mutatorSemanticsVersion,
            "mutatorSemanticsVersion");
        if (!STATUS.equals(status)) {
            throw new IllegalArgumentException(
                "retained TRAIN run lacks population execution authority");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            retainedRun,
            executionPlanHash,
            executionProtocolHash,
            populationEngineImplementationClass,
            populationEngineSemanticsVersion,
            mutatorImplementationClass,
            mutatorSemanticsVersion,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "execution-protocol-bound retained-run contentHash mismatch");
        }
    }

    public static ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRun
            create(
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun retainedRun,
        EvolutionRewriteProgramPopulationExecutionPlan executionPlan,
        EvolutionRewriteProgramPopulationExecutionProtocol executionProtocol
    ) {
        Objects.requireNonNull(retainedRun, "retainedRun");
        Objects.requireNonNull(executionPlan, "executionPlan");
        Objects.requireNonNull(executionProtocol, "executionProtocol");
        if (!executionPlan.executionProtocolHash().equals(
                executionProtocol.contentHash())) {
            throw new IllegalArgumentException(
                "execution plan differs from population execution protocol");
        }
        if (!executionPlan.studyPlanHash().equals(
                retainedRun.retainedPopulation().populationRun()
                    .studyPlanHash())) {
            throw new IllegalArgumentException(
                "retained population differs from execution plan study");
        }
        String hash = EvolutionGenome.hash(render(
            retainedRun,
            executionPlan.contentHash(),
            executionProtocol.contentHash(),
            executionProtocol.populationEngineImplementationClass(),
            executionProtocol.populationEngineSemanticsVersion(),
            executionProtocol.mutatorImplementationClass(),
            executionProtocol.mutatorSemanticsVersion(),
            null));
        return new ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRun(
            SCHEMA,
            retainedRun,
            executionPlan.contentHash(),
            executionProtocol.contentHash(),
            executionProtocol.populationEngineImplementationClass(),
            executionProtocol.populationEngineSemanticsVersion(),
            executionProtocol.mutatorImplementationClass(),
            executionProtocol.mutatorSemanticsVersion(),
            STATUS,
            hash);
    }

    /**
     * Rebinds a deserialized retained run to the actual execution artifacts a
     * consumer intends to trust. The redundant implementation/version fields
     * are checked as well as the content-addressed plan/protocol identities so
     * a valid outer hash cannot be mistaken for semantic protocol agreement.
     */
    public void requireCompatible(
        EvolutionRewriteProgramPopulationExecutionPlan executionPlan,
        EvolutionRewriteProgramPopulationExecutionProtocol executionProtocol
    ) {
        Objects.requireNonNull(executionPlan, "executionPlan");
        Objects.requireNonNull(executionProtocol, "executionProtocol");
        if (!executionPlanHash.equals(executionPlan.contentHash())
                || !executionProtocolHash.equals(executionProtocol.contentHash())
                || !executionPlan.executionProtocolHash().equals(
                    executionProtocol.contentHash())
                || !populationEngineImplementationClass.equals(
                    executionProtocol.populationEngineImplementationClass())
                || populationEngineSemanticsVersion
                    != executionProtocol.populationEngineSemanticsVersion()
                || !mutatorImplementationClass.equals(
                    executionProtocol.mutatorImplementationClass())
                || mutatorSemanticsVersion
                    != executionProtocol.mutatorSemanticsVersion()) {
            throw new IllegalArgumentException(
                "retained TRAIN run population execution identity mismatch");
        }
    }

    public String toCanonicalJson() {
        return render(
            retainedRun,
            executionPlanHash,
            executionProtocolHash,
            populationEngineImplementationClass,
            populationEngineSemanticsVersion,
            mutatorImplementationClass,
            mutatorSemanticsVersion,
            contentHash);
    }

    private static String render(
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun retainedRun,
        String executionPlanHash,
        String executionProtocolHash,
        String populationEngineImplementationClass,
        PopulationEngineSemanticsVersion populationEngineSemanticsVersion,
        String mutatorImplementationClass,
        MutatorSemanticsVersion mutatorSemanticsVersion,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("retainedRunHash", retainedRun.contentHash())
            .property("retainedRunJson", retainedRun.toCanonicalJson())
            .property("executionPlanHash", executionPlanHash)
            .property("executionProtocolHash", executionProtocolHash)
            .property(
                "populationEngineImplementationClass",
                populationEngineImplementationClass)
            .property(
                "populationEngineSemanticsVersion",
                populationEngineSemanticsVersion.name())
            .property("mutatorImplementationClass", mutatorImplementationClass)
            .property(
                "mutatorSemanticsVersion",
                mutatorSemanticsVersion.name())
            .property("status", STATUS);
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
