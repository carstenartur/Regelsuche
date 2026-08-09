package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.evolution.EvolutionRewriteProgramPopulationExecutionProtocol.MutationSeedDerivationPolicy;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationExecutionProtocol.MutatorSemanticsVersion;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationExecutionProtocol.OffspringSchedulingPolicy;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationExecutionProtocol.PopulationEngineSemanticsVersion;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationExecutionProtocol.ProposalOrderingPolicy;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationExecutionProtocol.SurvivorSelectionPolicy;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramPopulationExecutionProtocolTest {
    @Test
    void legacyIdentityNamesTheHistoricalSemanticsExplicitly() {
        var legacy = EvolutionRewriteProgramPopulationExecutionProtocol.legacyV1();

        assertEquals(
            EvolutionRewriteProgramPopulationEngine.class.getName(),
            legacy.populationEngineImplementationClass());
        assertEquals(
            PopulationEngineSemanticsVersion.LEGACY_POPULATION_ENGINE_V1,
            legacy.populationEngineSemanticsVersion());
        assertEquals(
            DeterministicRewriteProgramMutator.class.getName(),
            legacy.mutatorImplementationClass());
        assertEquals(
            MutatorSemanticsVersion.ROTATED_PREFIX_MUTATOR_V1,
            legacy.mutatorSemanticsVersion());
        assertEquals(
            OffspringSchedulingPolicy.ROTATED_PREFIX_V1,
            legacy.offspringSchedulingPolicy());
        assertEquals(2, legacy.generatedPoolMultiplier());
    }

    @Test
    void schedulingFamilyChangesSemanticVersionsAndContentIdentity() {
        var legacy = EvolutionRewriteProgramPopulationExecutionProtocol.legacyV1();
        var future = EvolutionRewriteProgramPopulationExecutionProtocol.create(
            EvolutionRewriteProgramPopulationEngine.class,
            DeterministicRewriteProgramMutator.class,
            ProposalOrderingPolicy.KEY_ASCENDING_THEN_GLOBAL_SEED_ROTATION_V1,
            OffspringSchedulingPolicy.STRATIFIED_MUTATION_KIND_V1,
            2,
            MutationSeedDerivationPolicy
                .STUDY_HASH_GENERATION_PARENT_HASH_SHA256_PREFIX64_V1,
            SurvivorSelectionPolicy
                .FITNESS_DESC_NODES_ASC_HASH_ASC_UNIQUE_ALPHA_ELITES_V1);

        assertEquals(
            PopulationEngineSemanticsVersion.PROTOCOL_DRIVEN_POPULATION_ENGINE_V2,
            future.populationEngineSemanticsVersion());
        assertEquals(
            MutatorSemanticsVersion.STRATIFIED_MUTATION_KIND_MUTATOR_V2,
            future.mutatorSemanticsVersion());
        assertNotEquals(legacy.contentHash(), future.contentHash());
        assertEquals(
            future,
            EvolutionRewriteProgramPopulationExecutionProtocol
                .stratifiedMutationKindV1());
    }

    @Test
    void explicitFactoryRejectsMixedSchedulingAndSemanticVersions() {
        assertThrows(
            IllegalArgumentException.class,
            () -> EvolutionRewriteProgramPopulationExecutionProtocol.create(
                EvolutionRewriteProgramPopulationEngine.class,
                PopulationEngineSemanticsVersion.LEGACY_POPULATION_ENGINE_V1,
                DeterministicRewriteProgramMutator.class,
                MutatorSemanticsVersion.ROTATED_PREFIX_MUTATOR_V1,
                ProposalOrderingPolicy
                    .KEY_ASCENDING_THEN_GLOBAL_SEED_ROTATION_V1,
                OffspringSchedulingPolicy.STRATIFIED_MUTATION_KIND_V1,
                2,
                MutationSeedDerivationPolicy
                    .STUDY_HASH_GENERATION_PARENT_HASH_SHA256_PREFIX64_V1,
                SurvivorSelectionPolicy
                    .FITNESS_DESC_NODES_ASC_HASH_ASC_UNIQUE_ALPHA_ELITES_V1));
        assertThrows(
            IllegalArgumentException.class,
            () -> EvolutionRewriteProgramPopulationExecutionProtocol.create(
                EvolutionRewriteProgramPopulationEngine.class,
                PopulationEngineSemanticsVersion
                    .PROTOCOL_DRIVEN_POPULATION_ENGINE_V2,
                DeterministicRewriteProgramMutator.class,
                MutatorSemanticsVersion.STRATIFIED_MUTATION_KIND_MUTATOR_V2,
                ProposalOrderingPolicy
                    .KEY_ASCENDING_THEN_GLOBAL_SEED_ROTATION_V1,
                OffspringSchedulingPolicy.ROTATED_PREFIX_V1,
                2,
                MutationSeedDerivationPolicy
                    .STUDY_HASH_GENERATION_PARENT_HASH_SHA256_PREFIX64_V1,
                SurvivorSelectionPolicy
                    .FITNESS_DESC_NODES_ASC_HASH_ASC_UNIQUE_ALPHA_ELITES_V1));
    }

    @Test
    void factoriesRejectInvalidPolicyInputsBeforeHashing() {
        assertThrows(
            NullPointerException.class,
            () -> EvolutionRewriteProgramPopulationExecutionProtocol.create(
                EvolutionRewriteProgramPopulationEngine.class,
                DeterministicRewriteProgramMutator.class,
                null,
                OffspringSchedulingPolicy.ROTATED_PREFIX_V1,
                2,
                MutationSeedDerivationPolicy
                    .STUDY_HASH_GENERATION_PARENT_HASH_SHA256_PREFIX64_V1,
                SurvivorSelectionPolicy
                    .FITNESS_DESC_NODES_ASC_HASH_ASC_UNIQUE_ALPHA_ELITES_V1));
        assertThrows(
            IllegalArgumentException.class,
            () -> EvolutionRewriteProgramPopulationExecutionProtocol.create(
                EvolutionRewriteProgramPopulationEngine.class,
                DeterministicRewriteProgramMutator.class,
                ProposalOrderingPolicy
                    .KEY_ASCENDING_THEN_GLOBAL_SEED_ROTATION_V1,
                OffspringSchedulingPolicy.ROTATED_PREFIX_V1,
                0,
                MutationSeedDerivationPolicy
                    .STUDY_HASH_GENERATION_PARENT_HASH_SHA256_PREFIX64_V1,
                SurvivorSelectionPolicy
                    .FITNESS_DESC_NODES_ASC_HASH_ASC_UNIQUE_ALPHA_ELITES_V1));
    }
}
