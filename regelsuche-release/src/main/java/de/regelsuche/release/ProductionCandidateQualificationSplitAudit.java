package de.regelsuche.release;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.experiments.autopilot.AutonomousProductionCampaignRunner.CampaignRun;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.search.learning.ExpressionFingerprint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact and alpha-normalized split audit for the release qualification suite. */
public final class ProductionCandidateQualificationSplitAudit {
    public static final String SCHEMA =
        "regelsuche.autonomous-candidate-qualification-split/v1";

    public SplitAudit audit(CampaignRun campaign) {
        Objects.requireNonNull(campaign, "campaign");
        List<Fingerprint> upstream = new ArrayList<>();
        campaign.lifecycle().mining().generation().observations().forEach(item ->
            upstream.add(fingerprint("formation:" + item.seed().id(),
                item.seed().id(), item.seed().expression())));
        int formationCount = upstream.size();
        ProductionCandidateQualificationCatalog.developmentExpressions().forEach(value ->
            upstream.add(fingerprint("development", "development:" + value, value)));

        List<Fingerprint> qualification = new ArrayList<>();
        ProductionCandidateQualificationCatalog.positives().forEach(item -> {
            qualification.add(fingerprint("positive-input:" + item.id(),
                item.id(), item.inputExpression()));
            qualification.add(fingerprint("positive-target:" + item.id(),
                item.id(), item.targetExpression()));
        });
        ProductionCandidateQualificationCatalog.negatives().forEach(item ->
            qualification.add(fingerprint("negative-input:" + item.id(),
                item.id(), item.inputExpression())));

        List<String> upstreamCollisions = crossCollisions(upstream, qualification);
        List<String> internalCollisions = internalCollisions(qualification);
        int compositeFactors = (int) ProductionCandidateQualificationCatalog
            .positives().stream().filter(item -> item.factorExpression().contains("*")
                || item.factorExpression().contains("^")).count();
        int heldOut = compositeFactors == 12
                && upstreamCollisions.isEmpty() && internalCollisions.isEmpty()
            ? 1 : 0;
        String hash = AutonomousResearchBriefV2.hash(
            SCHEMA + "\nformation=" + formationCount
                + "\ndevelopment="
                    + ProductionCandidateQualificationCatalog.developmentExpressions().size()
                + "\nqualification=" + qualification.size()
                + "\ncomposite=" + compositeFactors
                + "\nupstreamCollisions=" + upstreamCollisions
                + "\ninternalCollisions=" + internalCollisions
                + "\nheldOut=" + heldOut);
        return new SplitAudit(
            SCHEMA,
            ProductionCandidateQualificationCatalog.REVISION,
            ProductionCandidateQualificationCatalog.HELD_OUT_CLUSTER_ID,
            formationCount,
            ProductionCandidateQualificationCatalog.developmentExpressions().size(),
            qualification.size(),
            compositeFactors,
            upstreamCollisions,
            internalCollisions,
            heldOut,
            hash);
    }

    private static Fingerprint fingerprint(
        String source,
        String caseId,
        String expression
    ) {
        ExpressionFingerprint value = ExpressionFingerprint.of(
            expression, new ExpressionCanonicalizer());
        if (!value.parseable()) {
            throw new IllegalArgumentException("unparseable split expression: " + source);
        }
        return new Fingerprint(
            source, caseId, value.valueHash(), value.alphaShapeHash());
    }

    private static List<String> crossCollisions(
        List<Fingerprint> upstream,
        List<Fingerprint> qualification
    ) {
        List<String> collisions = new ArrayList<>();
        qualification.forEach(candidate -> upstream.forEach(existing -> {
            if (candidate.value().equals(existing.value())) {
                collisions.add("EXACT|" + candidate.source() + '|' + existing.source()
                    + '|' + candidate.value());
            } else if (candidate.alpha().equals(existing.alpha())) {
                collisions.add("ALPHA|" + candidate.source() + '|' + existing.source()
                    + '|' + candidate.alpha());
            }
        }));
        return collisions.stream().distinct().sorted().toList();
    }

    private static List<String> internalCollisions(List<Fingerprint> values) {
        List<String> collisions = new ArrayList<>();
        Map<String, Fingerprint> exact = new HashMap<>();
        Map<String, Fingerprint> alpha = new HashMap<>();
        for (Fingerprint value : values) {
            Fingerprint oldExact = exact.putIfAbsent(value.value(), value);
            if (oldExact != null && !oldExact.caseId().equals(value.caseId())) {
                collisions.add("EXACT|" + value.source() + '|' + oldExact.source()
                    + '|' + value.value());
            }
            Fingerprint oldAlpha = alpha.putIfAbsent(value.alpha(), value);
            if (oldAlpha != null
                    && !oldAlpha.caseId().equals(value.caseId())
                    && !oldAlpha.value().equals(value.value())) {
                collisions.add("ALPHA|" + value.source() + '|' + oldAlpha.source()
                    + '|' + value.alpha());
            }
        }
        return collisions.stream().distinct().sorted().toList();
    }

    public record SplitAudit(
        String schema,
        String suiteRevision,
        String heldOutClusterId,
        int formationExpressionCount,
        int developmentExpressionCount,
        int qualificationExpressionCount,
        int compositeFactorCount,
        List<String> upstreamCollisions,
        List<String> internalCollisions,
        int heldOutFamilyOrClusterCount,
        String contentHash
    ) {
        public SplitAudit {
            upstreamCollisions = upstreamCollisions == null
                ? List.of() : upstreamCollisions.stream().sorted().toList();
            internalCollisions = internalCollisions == null
                ? List.of() : internalCollisions.stream().sorted().toList();
            if (!SCHEMA.equals(schema) || formationExpressionCount < 1
                    || developmentExpressionCount < 1
                    || qualificationExpressionCount != 36
                    || compositeFactorCount != 12
                    || heldOutFamilyOrClusterCount < 0
                    || heldOutFamilyOrClusterCount > 1) {
                throw new IllegalArgumentException("invalid qualification split audit");
            }
            requireSha(contentHash);
        }

        public boolean passed() {
            return heldOutFamilyOrClusterCount == 1
                && upstreamCollisions.isEmpty() && internalCollisions.isEmpty();
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("suiteRevision", suiteRevision)
                .property("heldOutClusterId", heldOutClusterId)
                .property("formationExpressionCount", formationExpressionCount)
                .property("developmentExpressionCount", developmentExpressionCount)
                .property("qualificationExpressionCount", qualificationExpressionCount)
                .property("compositeFactorCount", compositeFactorCount)
                .stringArray("upstreamCollisions", upstreamCollisions)
                .stringArray("internalCollisions", internalCollisions)
                .property("heldOutFamilyOrClusterCount",
                    heldOutFamilyOrClusterCount)
                .property("passed", passed())
                .property("contentHash", contentHash)
                .endObject().toString();
        }
    }

    private record Fingerprint(
        String source,
        String caseId,
        String value,
        String alpha
    ) {
    }

    private static void requireSha(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash must be SHA-256");
        }
    }
}
