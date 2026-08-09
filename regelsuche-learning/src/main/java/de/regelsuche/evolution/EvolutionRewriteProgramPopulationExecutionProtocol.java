package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import java.util.Objects;

/**
 * Versioned identity for the mechanics that turn a frozen rewrite-program study
 * into a bounded deterministic TRAIN population.
 *
 * <p>The study plan intentionally remains the historical v1 artifact. This
 * protocol binds execution semantics that were previously implicit in Java
 * implementation details, so future experiments can change scheduling without
 * silently reinterpreting an older study identity.</p>
 */
public record EvolutionRewriteProgramPopulationExecutionProtocol(
    String schema,
    String populationEngineImplementationClass,
    String mutatorImplementationClass,
    ProposalOrderingPolicy proposalOrderingPolicy,
    OffspringSchedulingPolicy offspringSchedulingPolicy,
    int generatedPoolMultiplier,
    MutationSeedDerivationPolicy mutationSeedDerivationPolicy,
    SurvivorSelectionPolicy survivorSelectionPolicy,
    String status,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-population-execution-protocol/v1";
    public static final String STATUS = "FROZEN_NOT_RUN";

    public enum ProposalOrderingPolicy {
        KEY_ASCENDING_THEN_GLOBAL_SEED_ROTATION_V1
    }

    public enum OffspringSchedulingPolicy {
        ROTATED_PREFIX_V1,
        STRATIFIED_MUTATION_KIND_V1
    }

    public enum MutationSeedDerivationPolicy {
        STUDY_HASH_GENERATION_PARENT_HASH_SHA256_PREFIX64_V1
    }

    public enum SurvivorSelectionPolicy {
        FITNESS_DESC_NODES_ASC_HASH_ASC_UNIQUE_ALPHA_ELITES_V1
    }

    public EvolutionRewriteProgramPopulationExecutionProtocol {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported rewrite-program population execution protocol");
        }
        populationEngineImplementationClass = requireText(
            populationEngineImplementationClass,
            "populationEngineImplementationClass");
        mutatorImplementationClass = requireText(
            mutatorImplementationClass,
            "mutatorImplementationClass");
        Objects.requireNonNull(proposalOrderingPolicy, "proposalOrderingPolicy");
        Objects.requireNonNull(
            offspringSchedulingPolicy, "offspringSchedulingPolicy");
        if (generatedPoolMultiplier < 1 || generatedPoolMultiplier > 64) {
            throw new IllegalArgumentException(
                "generatedPoolMultiplier must be in [1,64]");
        }
        Objects.requireNonNull(
            mutationSeedDerivationPolicy,
            "mutationSeedDerivationPolicy");
        Objects.requireNonNull(
            survivorSelectionPolicy,
            "survivorSelectionPolicy");
        if (!STATUS.equals(status)) {
            throw new IllegalArgumentException(
                "execution protocol must remain frozen and unexecuted");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            populationEngineImplementationClass,
            mutatorImplementationClass,
            proposalOrderingPolicy,
            offspringSchedulingPolicy,
            generatedPoolMultiplier,
            mutationSeedDerivationPolicy,
            survivorSelectionPolicy,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "population execution protocol contentHash mismatch");
        }
    }

    /** Exact identity of the population/mutator behavior used before #613. */
    public static EvolutionRewriteProgramPopulationExecutionProtocol legacyV1() {
        return create(
            EvolutionRewriteProgramPopulationEngine.class,
            DeterministicRewriteProgramMutator.class,
            ProposalOrderingPolicy.KEY_ASCENDING_THEN_GLOBAL_SEED_ROTATION_V1,
            OffspringSchedulingPolicy.ROTATED_PREFIX_V1,
            2,
            MutationSeedDerivationPolicy
                .STUDY_HASH_GENERATION_PARENT_HASH_SHA256_PREFIX64_V1,
            SurvivorSelectionPolicy
                .FITNESS_DESC_NODES_ASC_HASH_ASC_UNIQUE_ALPHA_ELITES_V1);
    }

    public static EvolutionRewriteProgramPopulationExecutionProtocol create(
        Class<?> populationEngineClass,
        Class<?> mutatorClass,
        ProposalOrderingPolicy proposalOrderingPolicy,
        OffspringSchedulingPolicy offspringSchedulingPolicy,
        int generatedPoolMultiplier,
        MutationSeedDerivationPolicy mutationSeedDerivationPolicy,
        SurvivorSelectionPolicy survivorSelectionPolicy
    ) {
        Objects.requireNonNull(populationEngineClass, "populationEngineClass");
        Objects.requireNonNull(mutatorClass, "mutatorClass");
        String hash = EvolutionGenome.hash(render(
            populationEngineClass.getName(),
            mutatorClass.getName(),
            proposalOrderingPolicy,
            offspringSchedulingPolicy,
            generatedPoolMultiplier,
            mutationSeedDerivationPolicy,
            survivorSelectionPolicy,
            null));
        return new EvolutionRewriteProgramPopulationExecutionProtocol(
            SCHEMA,
            populationEngineClass.getName(),
            mutatorClass.getName(),
            proposalOrderingPolicy,
            offspringSchedulingPolicy,
            generatedPoolMultiplier,
            mutationSeedDerivationPolicy,
            survivorSelectionPolicy,
            STATUS,
            hash);
    }

    public void requireImplementations(
        Class<?> populationEngineClass,
        Class<?> mutatorClass
    ) {
        Objects.requireNonNull(populationEngineClass, "populationEngineClass");
        Objects.requireNonNull(mutatorClass, "mutatorClass");
        if (!populationEngineImplementationClass.equals(
                populationEngineClass.getName())) {
            throw new IllegalArgumentException(
                "population engine implementation differs from execution protocol");
        }
        if (!mutatorImplementationClass.equals(mutatorClass.getName())) {
            throw new IllegalArgumentException(
                "mutator implementation differs from execution protocol");
        }
    }

    public String toCanonicalJson() {
        return render(
            populationEngineImplementationClass,
            mutatorImplementationClass,
            proposalOrderingPolicy,
            offspringSchedulingPolicy,
            generatedPoolMultiplier,
            mutationSeedDerivationPolicy,
            survivorSelectionPolicy,
            contentHash);
    }

    private static String render(
        String populationEngineImplementationClass,
        String mutatorImplementationClass,
        ProposalOrderingPolicy proposalOrderingPolicy,
        OffspringSchedulingPolicy offspringSchedulingPolicy,
        int generatedPoolMultiplier,
        MutationSeedDerivationPolicy mutationSeedDerivationPolicy,
        SurvivorSelectionPolicy survivorSelectionPolicy,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property(
                "populationEngineImplementationClass",
                populationEngineImplementationClass)
            .property("mutatorImplementationClass", mutatorImplementationClass)
            .property("proposalOrderingPolicy", proposalOrderingPolicy.name())
            .property("offspringSchedulingPolicy", offspringSchedulingPolicy.name())
            .property("generatedPoolMultiplier", generatedPoolMultiplier)
            .property(
                "mutationSeedDerivationPolicy",
                mutationSeedDerivationPolicy.name())
            .property("survivorSelectionPolicy", survivorSelectionPolicy.name())
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
