package de.regelsuche.discovery;

import de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleProfile;

import java.util.LinkedHashSet;
import java.util.Set;

/** Aggregate configuration for discovery engine composition, orchestration learning and generated gallery output. */
public record DiscoveryOptions(
    DiscoveryProfile profile,
    DiscoveryEngineOptions engine,
    DiscoveryLearningOptions learning,
    boolean enableGeneratedGallery,
    RuleProfile ruleProfile,
    Set<String> enabledPacks,
    Set<String> disabledPacks
) {
    public DiscoveryOptions(
        DiscoveryProfile profile,
        DiscoveryEngineOptions engine,
        DiscoveryLearningOptions learning,
        boolean enableGeneratedGallery
    ) {
        this(profile, engine, learning, enableGeneratedGallery, RuleProfile.CORE, Set.of(), Set.of());
    }

    public DiscoveryOptions {
        profile = profile == null ? DiscoveryProfile.PURE_REWRITE : profile;
        engine = engine == null ? DiscoveryEngineOptions.forProfile(profile) : engine;
        learning = learning == null ? DiscoveryLearningOptions.disabled() : learning;
        ruleProfile = ruleProfile == null ? RuleProfile.CORE : ruleProfile;
        enabledPacks = enabledPacks == null ? Set.of() : Set.copyOf(enabledPacks);
        disabledPacks = disabledPacks == null ? Set.of() : Set.copyOf(disabledPacks);
    }

    public static DiscoveryOptions forProfile(DiscoveryProfile profile) {
        DiscoveryProfile resolved = profile == null ? DiscoveryProfile.PURE_REWRITE : profile;
        DiscoveryLearningOptions learning = resolved == DiscoveryProfile.RESEARCH_DISCOVERY_PIPELINE
            ? DiscoveryLearningOptions.researchDefaults()
            : DiscoveryLearningOptions.disabled();
        return new DiscoveryOptions(resolved, DiscoveryEngineOptions.forProfile(resolved), learning,
            resolved == DiscoveryProfile.RESEARCH_DISCOVERY_PIPELINE);
    }

    public static DiscoveryOptions researchDiscoveryPipeline() {
        return forProfile(DiscoveryProfile.RESEARCH_DISCOVERY_PIPELINE);
    }

    public DiscoveryOptions enablePack(String packId) {
        Set<String> enabled = new LinkedHashSet<>(enabledPacks);
        Set<String> disabled = new LinkedHashSet<>(disabledPacks);
        enabled.add(packId);
        disabled.remove(packId);
        return new DiscoveryOptions(profile, engine, learning, enableGeneratedGallery, ruleProfile, enabled, disabled);
    }

    public DiscoveryOptions disablePack(String packId) {
        Set<String> enabled = new LinkedHashSet<>(enabledPacks);
        Set<String> disabled = new LinkedHashSet<>(disabledPacks);
        disabled.add(packId);
        enabled.remove(packId);
        return new DiscoveryOptions(profile, engine, learning, enableGeneratedGallery, ruleProfile, enabled, disabled);
    }

    public DiscoveryOptions withRuleProfile(RuleProfile ruleProfile) {
        return new DiscoveryOptions(profile, engine, learning, enableGeneratedGallery, ruleProfile, enabledPacks, disabledPacks);
    }

    public KnowledgePackSelection knowledgePackSelection() {
        return new KnowledgePackSelection(ruleProfile, enabledPacks, disabledPacks);
    }

    public RepresentationDiscoveryInformationBoundary
            representationDiscoveryBoundary(
                RepresentationDiscoveryInformationBoundary.Track track
            ) {
        return representationDiscoveryBoundary(track, Set.of());
    }

    public RepresentationDiscoveryInformationBoundary
            representationDiscoveryBoundary(
                RepresentationDiscoveryInformationBoundary.Track track,
                Set<String> hiddenStructureIds
            ) {
        return RepresentationDiscoveryInformationBoundary.fromKnowledgePacks(
            track,
            knowledgePackSelection(),
            hiddenStructureIds
        );
    }
}
