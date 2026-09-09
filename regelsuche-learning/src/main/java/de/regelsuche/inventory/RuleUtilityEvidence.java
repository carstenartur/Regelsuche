package de.regelsuche.inventory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.json.JsonWriter;
import java.util.List;
import java.util.Objects;

/** Persisted search-utility observations. Deserialization never issues mathematical/replay authority. */
public record RuleUtilityEvidence(String schema, int observedPathSteps, int bestKnownPrimitiveSteps,
        boolean boundedMinimumProved, int macroSearchDepth, long directApplicationWork, long proofReplayWork,
        long successfulApplications, long failedApplications, long duplicateSuccessors, long deadEnds,
        long applicabilityCount, List<String> capabilitiesUnlocked, long firstReachabilityGains,
        double confidence, long evidenceCount, ReferenceScope reference) {
    public static final String REVISION = "regelsuche.rule-utility/v1";
    public static final RuleUtilityEvidence UNKNOWN = new RuleUtilityEvidence(REVISION, 0, -1, false, 1,
        -1, -1, 0, 0, 0, 0, 0, List.of(), 0, 0, 0, null);
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** The minimum applies only to these concrete endpoints, inventory and declared reference budget. */
    public record ReferenceScope(String primitiveInventoryHash, String source, String target, String relationScope,
            String limitsJson, String assessmentHash, boolean observedReplayVerified, long referenceWork) {
        public ReferenceScope {
            for (String value : List.of(primitiveInventoryHash, source, target, relationScope, limitsJson, assessmentHash)) {
                if (value.isBlank()) throw new IllegalArgumentException("reference scope must be explicit");
            }
            if (referenceWork < 0) throw new IllegalArgumentException("negative reference work");
        }
    }

    public RuleUtilityEvidence {
        if (!REVISION.equals(schema) || observedPathSteps < 0 || bestKnownPrimitiveSteps < -1 || macroSearchDepth < 1
                || directApplicationWork < -1 || proofReplayWork < -1 || successfulApplications < 0 || failedApplications < 0
                || duplicateSuccessors < 0 || deadEnds < 0 || applicabilityCount < 0 || firstReachabilityGains < 0
                || evidenceCount < 0 || !Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("invalid utility observations");
        }
        if (bestKnownPrimitiveSteps >= 0 && (reference == null || !reference.observedReplayVerified()
                || bestKnownPrimitiveSteps > observedPathSteps)) {
            throw new IllegalArgumentException("a known primitive distance requires a retained reference witness");
        }
        if (boundedMinimumProved && (bestKnownPrimitiveSteps < 0 || evidenceCount == 0)) {
            throw new IllegalArgumentException("minimum cannot be proved without scoped evidence");
        }
        capabilitiesUnlocked = Objects.requireNonNull(capabilitiesUnlocked).stream().distinct().sorted().toList();
    }

    public int knownDepthCompression() { return Math.max(0, bestKnownPrimitiveSteps - macroSearchDepth); }
    public boolean hasReferenceEvidence() { return reference != null && reference.observedReplayVerified(); }

    public String toCanonicalJson() {
        var writer = new JsonWriter().beginObject();
        writeJson(writer);
        return writer.endObject().toString();
    }

    public void writeJson(JsonWriter writer) {
        writer.property("schema", schema).property("observedPathSteps", observedPathSteps)
            .property("bestKnownPrimitiveSteps", bestKnownPrimitiveSteps).property("boundedMinimumProved", boundedMinimumProved)
            .property("macroSearchDepth", macroSearchDepth).property("directApplicationWork", directApplicationWork)
            .property("proofReplayWork", proofReplayWork).property("successfulApplications", successfulApplications)
            .property("failedApplications", failedApplications).property("duplicateSuccessors", duplicateSuccessors)
            .property("deadEnds", deadEnds).property("applicabilityCount", applicabilityCount)
            .stringArray("capabilitiesUnlocked", capabilitiesUnlocked).property("firstReachabilityGains", firstReachabilityGains)
            .property("confidence", confidence).property("evidenceCount", evidenceCount);
        if (reference == null) writer.nullProperty("reference");
        else writer.object("reference", value -> value.property("primitiveInventoryHash", reference.primitiveInventoryHash())
            .property("source", reference.source()).property("target", reference.target()).property("relationScope", reference.relationScope())
            .property("limitsJson", reference.limitsJson()).property("assessmentHash", reference.assessmentHash())
            .property("observedReplayVerified", reference.observedReplayVerified()).property("referenceWork", reference.referenceWork()));
    }

    public static RuleUtilityEvidence fromJson(String json) {
        if (json == null || json.isBlank() || json.equals("null")) return UNKNOWN;
        try { return JSON.readValue(json, RuleUtilityEvidence.class); }
        catch (java.io.IOException exception) { throw new IllegalArgumentException("invalid utility evidence JSON", exception); }
    }

    public static RuleUtilityEvidence fromValue(Object value) {
        if (value == null) return UNKNOWN;
        if (value instanceof String text) return fromJson(text);
        return JSON.convertValue(value, RuleUtilityEvidence.class);
    }
}
