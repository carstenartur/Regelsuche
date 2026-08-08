package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import java.util.Objects;

/**
 * Authority-bearing protocol binding for one retained terminal TRAIN run.
 *
 * <p>The underlying retained population is structurally complete but may be
 * constructed from any compatible engine run. This wrapper additionally binds
 * the frozen evaluator protocol and exact implementation class that the
 * protocol-bound runner verified before execution.</p>
 */
public record ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun(
    String schema,
    RetainedEvolutionRewriteProgramPopulationRun retainedPopulation,
    String evaluationProtocolHash,
    String evaluatorImplementationClass,
    String status,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-protocol-bound-retained-run/v1";
    public static final String STATUS = "PROTOCOL_BOUND_TRAIN_RETAINED";

    public ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported protocol-bound retained-run schema");
        }
        Objects.requireNonNull(
            retainedPopulation, "retainedPopulation");
        EvolutionGenome.requireSha256(
            evaluationProtocolHash, "evaluationProtocolHash");
        evaluatorImplementationClass = requireText(
            evaluatorImplementationClass,
            "evaluatorImplementationClass");
        if (!STATUS.equals(status)) {
            throw new IllegalArgumentException(
                "retained run lacks protocol-bound TRAIN authority");
        }
        if (!"NOT_EVALUATED".equals(
                retainedPopulation.validationStatus())
                || !"NOT_EVALUATED".equals(
                    retainedPopulation.finalTestStatus())) {
            throw new IllegalArgumentException(
                "protocol-bound TRAIN run cannot contain later outcomes");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            retainedPopulation,
            evaluationProtocolHash,
            evaluatorImplementationClass,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "protocol-bound retained-run contentHash mismatch");
        }
    }

    static ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun create(
        RetainedEvolutionRewriteProgramPopulationRun retainedPopulation,
        EvolutionRewriteProgramEvaluationProtocol protocol,
        Class<?> evaluatorClass
    ) {
        Objects.requireNonNull(
            retainedPopulation, "retainedPopulation");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(evaluatorClass, "evaluatorClass");
        if (!protocol.implementationClass().equals(
                evaluatorClass.getName())) {
            throw new IllegalArgumentException(
                "evaluator implementation differs from frozen protocol");
        }
        String hash = EvolutionGenome.hash(render(
            retainedPopulation,
            protocol.contentHash(),
            evaluatorClass.getName(),
            null));
        return new ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun(
            SCHEMA,
            retainedPopulation,
            protocol.contentHash(),
            evaluatorClass.getName(),
            STATUS,
            hash);
    }

    public String toCanonicalJson() {
        return render(
            retainedPopulation,
            evaluationProtocolHash,
            evaluatorImplementationClass,
            contentHash);
    }

    private static String render(
        RetainedEvolutionRewriteProgramPopulationRun retainedPopulation,
        String evaluationProtocolHash,
        String evaluatorImplementationClass,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property(
                "retainedPopulationHash",
                retainedPopulation.contentHash())
            .property(
                "retainedPopulationJson",
                retainedPopulation.toCanonicalJson())
            .property(
                "evaluationProtocolHash", evaluationProtocolHash)
            .property(
                "evaluatorImplementationClass",
                evaluatorImplementationClass)
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
