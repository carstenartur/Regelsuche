package de.regelsuche.evolution;

import java.util.Map;
import java.util.Objects;

/** Durable record of the attempt; deserializing this record does not grant reveal authority. */
public record EvolutionRewriteProgramFinalTestReservation(String schema, String runIdentity,
    EvolutionRewriteProgramFinalTestPlan plan, String finalTestStatus, String contentHash) {
    public static final String SCHEMA = "regelsuche.evolution-rewrite-program-final-test-reservation/v1";
    public static final String RESERVED = "RESERVED";

    public EvolutionRewriteProgramFinalTestReservation {
        if (!SCHEMA.equals(schema) || !RESERVED.equals(finalTestStatus)
                || !Objects.requireNonNull(plan, "plan").runIdentity().equals(runIdentity)) {
            throw new IllegalArgumentException("invalid combined program FINAL TEST reservation");
        }
        EvolutionProgramValidationJson.requireHash(contentHash, material(plan));
    }

    static EvolutionRewriteProgramFinalTestReservation create(EvolutionRewriteProgramFinalTestPlan plan) {
        return new EvolutionRewriteProgramFinalTestReservation(SCHEMA, plan.runIdentity(), plan, RESERVED,
            EvolutionProgramValidationJson.hash(material(plan)));
    }

    public String toCanonicalJson() { return EvolutionProgramValidationJson.write(this); }
    public static EvolutionRewriteProgramFinalTestReservation fromCanonicalJson(String json) {
        return EvolutionProgramValidationJson.read(json, EvolutionRewriteProgramFinalTestReservation.class);
    }

    private static Map<String, Object> material(EvolutionRewriteProgramFinalTestPlan plan) {
        return EvolutionProgramValidationJson.material(SCHEMA, "runIdentity", plan.runIdentity(),
            "plan", plan, "finalTestStatus", RESERVED);
    }
}
