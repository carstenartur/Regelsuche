package de.regelsuche.release;

import de.regelsuche.json.JsonWriter;
import java.util.List;

/** Separate evidence profile for the stronger domain-generic discovery claim. */
public enum DomainGenericEvidenceProfile {
    DOMAIN_GENERIC_DISCOVERY(
        "reproducible generation, bounded search, counterexample search, "
            + "validation, certificate rendering and evidence across distinct "
            + "mathematical object types",
        true,
        false,
        List.of(
            "VERIFIED_EXPORT_SNAPSHOTS",
            "AT_LEAST_TWO_DISTINCT_DOMAINS",
            "DISTINCT_MATHEMATICAL_STATE_TYPES",
            "EXPRESSION_REWRITE_DOMAIN_RETAINED",
            "NON_EXPRESSION_DOMAIN_RETAINED",
            "CONFIRMED_CANDIDATES_WITH_CERTIFICATES",
            "SHARED_RESOURCE_ACCOUNTING",
            "BALANCED_RESOURCE_ACCOUNTING",
            "REPRESENTATION_FREE_LIFECYCLE_HANDOFF",
            "THREE_CLEAN_MULTI_DOMAIN_RUNS",
            "PROOF_STATUS_NOT_EVALUATED",
            "EXTERNAL_NOVELTY_STATUS_NOT_EVALUATED",
            "PROMOTION_STATUS_NOT_EVALUATED",
            "PUBLIC_EVIDENCE_STATUS_NOT_EVALUATED"));

    public static final String CATALOG_SCHEMA =
        "regelsuche.domain-generic-evidence-profile-catalog/v1";

    private final String claim;
    private final boolean authorizesDomainGenericClaim;
    private final boolean authorizesAutonomousCampaignClaim;
    private final List<String> requirements;

    DomainGenericEvidenceProfile(
        String claim,
        boolean authorizesDomainGenericClaim,
        boolean authorizesAutonomousCampaignClaim,
        List<String> requirements
    ) {
        this.claim = claim;
        this.authorizesDomainGenericClaim = authorizesDomainGenericClaim;
        this.authorizesAutonomousCampaignClaim = authorizesAutonomousCampaignClaim;
        this.requirements = List.copyOf(requirements);
    }

    public String claim() {
        return claim;
    }

    public boolean authorizesDomainGenericClaim() {
        return authorizesDomainGenericClaim;
    }

    public boolean authorizesAutonomousCampaignClaim() {
        return authorizesAutonomousCampaignClaim;
    }

    public List<String> requirements() {
        return requirements;
    }

    public static String catalogJson() {
        return new JsonWriter().beginObject()
            .property("schema", CATALOG_SCHEMA)
            .array("profiles", array -> {
                for (DomainGenericEvidenceProfile profile : values()) {
                    array.objectValue(object -> object
                        .property("profile", profile.name())
                        .property("claim", profile.claim())
                        .property("authorizesDomainGenericClaim",
                            profile.authorizesDomainGenericClaim())
                        .property("authorizesAutonomousCampaignClaim",
                            profile.authorizesAutonomousCampaignClaim())
                        .stringArray("requirements", profile.requirements()));
                }
            })
            .property("existingAutonomousCampaignProfileUnaffected", true)
            .endObject()
            .toString();
    }
}
