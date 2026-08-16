package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.WORKSPACE_SCHEMA;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.append;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sortedRequiredStrings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Frozen information, search and backend configuration for one run. */
public record RepresentationDiscoveryRunPlan(
    RepresentationDiscoveryInformationBoundary.Track informationTrack,
    String informationBoundaryHash,
    String ruleInventoryHash,
    String knowledgePackSelectionHash,
    String knownStructureCatalogHash,
    String searchStrategyId,
    String searchProfileId,
    String objectiveId,
    String budgetHash,
    long deterministicSeed,
    List<String> backendIdentities,
    String contentHash
) {
    static final Set<String> PARAMETERS = Set.of(
        "informationTrack",
        "informationBoundaryHash",
        "ruleInventoryHash",
        "knowledgePackSelectionHash",
        "knownStructureCatalogHash",
        "searchStrategyId",
        "searchProfileId",
        "objectiveId",
        "budgetHash",
        "deterministicSeed",
        "backendIdentities"
    );

    public RepresentationDiscoveryRunPlan {
        informationTrack = Objects.requireNonNull(
            informationTrack, "informationTrack");
        informationBoundaryHash = requireSha256(
            informationBoundaryHash, "informationBoundaryHash");
        ruleInventoryHash = requireSha256(
            ruleInventoryHash, "ruleInventoryHash");
        knowledgePackSelectionHash = requireSha256(
            knowledgePackSelectionHash,
            "knowledgePackSelectionHash"
        );
        knownStructureCatalogHash = requireSha256(
            knownStructureCatalogHash,
            "knownStructureCatalogHash"
        );
        searchStrategyId = requireText(
            searchStrategyId, "searchStrategyId");
        searchProfileId = requireText(
            searchProfileId, "searchProfileId");
        objectiveId = requireText(objectiveId, "objectiveId");
        budgetHash = requireSha256(budgetHash, "budgetHash");
        backendIdentities = sortedRequiredStrings(
            backendIdentities, "backendIdentities");
        contentHash = requireSha256(contentHash, "contentHash");
        String expected = planHash(
            informationTrack,
            informationBoundaryHash,
            ruleInventoryHash,
            knowledgePackSelectionHash,
            knownStructureCatalogHash,
            searchStrategyId,
            searchProfileId,
            objectiveId,
            budgetHash,
            deterministicSeed,
            backendIdentities
        );
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "run plan content hash mismatch");
        }
    }

    public static RepresentationDiscoveryRunPlan create(
        RepresentationDiscoveryInformationBoundary.Track informationTrack,
        String informationBoundaryHash,
        String ruleInventoryHash,
        String knowledgePackSelectionHash,
        String knownStructureCatalogHash,
        String searchStrategyId,
        String searchProfileId,
        String objectiveId,
        String budgetHash,
        long deterministicSeed,
        List<String> backendIdentities
    ) {
        RepresentationDiscoveryInformationBoundary.Track track =
            Objects.requireNonNull(informationTrack, "informationTrack");
        String boundary = requireSha256(
            informationBoundaryHash, "informationBoundaryHash");
        String inventory = requireSha256(
            ruleInventoryHash, "ruleInventoryHash");
        String selection = requireSha256(
            knowledgePackSelectionHash,
            "knowledgePackSelectionHash"
        );
        String catalog = requireSha256(
            knownStructureCatalogHash, "knownStructureCatalogHash");
        String strategy = requireText(searchStrategyId, "searchStrategyId");
        String profile = requireText(searchProfileId, "searchProfileId");
        String objective = requireText(objectiveId, "objectiveId");
        String budget = requireSha256(budgetHash, "budgetHash");
        List<String> backends = sortedRequiredStrings(
            backendIdentities, "backendIdentities");
        String hash = planHash(
            track,
            boundary,
            inventory,
            selection,
            catalog,
            strategy,
            profile,
            objective,
            budget,
            deterministicSeed,
            backends
        );
        return new RepresentationDiscoveryRunPlan(
            track,
            boundary,
            inventory,
            selection,
            catalog,
            strategy,
            profile,
            objective,
            budget,
            deterministicSeed,
            backends,
            hash
        );
    }

    String singleChangedParameter(RepresentationDiscoveryRunPlan revised) {
        Objects.requireNonNull(revised, "revisedPlan");
        List<String> changes = new ArrayList<>();
        changed(changes, "informationTrack",
            informationTrack != revised.informationTrack);
        changed(changes, "informationBoundaryHash",
            !informationBoundaryHash.equals(
                revised.informationBoundaryHash));
        changed(changes, "ruleInventoryHash",
            !ruleInventoryHash.equals(revised.ruleInventoryHash));
        changed(changes, "knowledgePackSelectionHash",
            !knowledgePackSelectionHash.equals(
                revised.knowledgePackSelectionHash));
        changed(changes, "knownStructureCatalogHash",
            !knownStructureCatalogHash.equals(
                revised.knownStructureCatalogHash));
        changed(changes, "searchStrategyId",
            !searchStrategyId.equals(revised.searchStrategyId));
        changed(changes, "searchProfileId",
            !searchProfileId.equals(revised.searchProfileId));
        changed(changes, "objectiveId",
            !objectiveId.equals(revised.objectiveId));
        changed(changes, "budgetHash",
            !budgetHash.equals(revised.budgetHash));
        changed(changes, "deterministicSeed",
            deterministicSeed != revised.deterministicSeed);
        changed(changes, "backendIdentities",
            !backendIdentities.equals(revised.backendIdentities));
        if (changes.size() != 1) {
            throw new IllegalArgumentException(
                "run duplication requires exactly one changed plan "
                    + "parameter, found " + changes);
        }
        return changes.getFirst();
    }

    private static void changed(
        List<String> changes,
        String parameter,
        boolean changed
    ) {
        if (changed) {
            changes.add(parameter);
        }
    }

    private static String planHash(
        RepresentationDiscoveryInformationBoundary.Track track,
        String boundaryHash,
        String inventoryHash,
        String selectionHash,
        String catalogHash,
        String strategyId,
        String profileId,
        String objectiveId,
        String budgetHash,
        long seed,
        List<String> backends
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, WORKSPACE_SCHEMA + "/plan");
        append(descriptor, track.id());
        append(descriptor, boundaryHash);
        append(descriptor, inventoryHash);
        append(descriptor, selectionHash);
        append(descriptor, catalogHash);
        append(descriptor, strategyId);
        append(descriptor, profileId);
        append(descriptor, objectiveId);
        append(descriptor, budgetHash);
        append(descriptor, Long.toString(seed));
        append(descriptor, Integer.toString(backends.size()));
        backends.forEach(value -> append(descriptor, value));
        return sha256(descriptor.toString());
    }
}
