package de.regelsuche.radar;

import com.sun.net.httpserver.HttpExchange;
import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.radar.AstRuleRadar.CandidateOutcome;
import de.regelsuche.radar.AstRuleRadar.Context;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.web.StreamingJsonRequestBody;
import de.regelsuche.web.StreamingJsonRequestBody.ObjectCursor;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Typed request envelope shared by the three rule-radar POST operations. */
record RuleRadarRequestBody(
    String expression,
    String candidateId,
    String targetExpression,
    Context context,
    int maxDepth,
    int maxStates,
    int maxMovesPerState
) {
    RuleRadarRequestBody {
        expression = normalize(expression);
        candidateId = normalize(candidateId);
        targetExpression = normalize(targetExpression);
        context = context == null ? Context.defaults() : context;
    }

    static RuleRadarRequestBody read(
        StreamingJsonRequestBody json,
        HttpExchange exchange
    ) throws IOException {
        return json.readObject(exchange, RuleRadarRequestBody::decode);
    }

    static RuleRadarRequestBody read(
        StreamingJsonRequestBody json,
        InputStream input
    ) throws IOException {
        return json.readObject(input, RuleRadarRequestBody::decode);
    }

    private static RuleRadarRequestBody decode(ObjectCursor object)
            throws IOException {
        String expression = "";
        String candidateId = "";
        String targetExpression = "";
        int maxDepth = 4;
        int maxStates = 120;
        int maxMovesPerState = 60;
        ContextBuilder topLevelContext = new ContextBuilder();
        ContextBuilder nestedContext = null;

        while (object.nextField()) {
            String field = object.fieldName();
            switch (field) {
                case "expression" -> expression = valueOr(
                    object.readNullableString(), "");
                case "candidateId" -> candidateId = valueOr(
                    object.readNullableString(), "");
                case "targetExpression" -> targetExpression = valueOr(
                    object.readNullableString(), "");
                case "maxDepth" -> maxDepth = valueOr(
                    object.readNullableInt(), 4);
                case "maxStates" -> maxStates = valueOr(
                    object.readNullableInt(), 120);
                case "maxMovesPerState" -> maxMovesPerState = valueOr(
                    object.readNullableInt(), 60);
                case "context" -> nestedContext = object.readNullableObject(
                    ContextBuilder::decode);
                default -> {
                    if (!topLevelContext.readField(field, object)) {
                        object.skipValue();
                    }
                }
            }
        }

        ContextBuilder source = nestedContext != null && nestedContext.hasFields()
            ? nestedContext
            : topLevelContext;
        return new RuleRadarRequestBody(
            expression,
            candidateId,
            targetExpression,
            source.toContext(),
            maxDepth,
            maxStates,
            maxMovesPerState
        );
    }

    private static final class ContextBuilder {
        private boolean hasFields;
        private String knowledgeProfile;
        private List<String> enabledPacks;
        private List<String> disabledPacks;
        private Boolean includePlugins;
        private Boolean includeLearnedMacros;
        private String minMacroProofStatus;
        private String searchProfile;
        private String goalExpression;
        private Integer maxCandidatesPerPosition;
        private Integer maxCandidatesTotal;
        private List<String> assumptions;
        private Boolean includeRejectedCandidates;
        private String selectedCandidateId;
        private Map<String, String> outcomeByCandidateId;

        private static ContextBuilder decode(ObjectCursor object)
                throws IOException {
            ContextBuilder builder = new ContextBuilder();
            while (object.nextField()) {
                builder.hasFields = true;
                if (!builder.readField(object.fieldName(), object)) {
                    object.skipValue();
                }
            }
            return builder;
        }

        private boolean readField(String field, ObjectCursor object)
                throws IOException {
            switch (field) {
                case "knowledgeProfile" -> knowledgeProfile =
                    object.readNullableString();
                case "enabledPacks" -> enabledPacks = object.readStringArray();
                case "disabledPacks" -> disabledPacks = object.readStringArray();
                case "includePlugins" -> includePlugins =
                    object.readNullableBoolean();
                case "includeLearnedMacros" -> includeLearnedMacros =
                    object.readNullableBoolean();
                case "minMacroProofStatus" -> minMacroProofStatus =
                    object.readNullableString();
                case "searchProfile" -> searchProfile =
                    object.readNullableString();
                case "goalExpression" -> goalExpression =
                    object.readNullableString();
                case "maxCandidatesPerPosition" -> maxCandidatesPerPosition =
                    object.readNullableInt();
                case "maxCandidatesTotal" -> maxCandidatesTotal =
                    object.readNullableInt();
                case "assumptions" -> assumptions = object.readStringArray();
                case "includeRejectedCandidates" -> includeRejectedCandidates =
                    object.readNullableBoolean();
                case "selectedCandidateId" -> selectedCandidateId =
                    object.readNullableString();
                case "outcomeByCandidateId" -> outcomeByCandidateId =
                    object.readStringMap();
                default -> {
                    return false;
                }
            }
            return true;
        }

        private boolean hasFields() {
            return hasFields;
        }

        private Context toContext() {
            Map<String, CandidateOutcome> outcomes = new LinkedHashMap<>();
            if (outcomeByCandidateId != null) {
                for (Map.Entry<String, String> entry
                        : outcomeByCandidateId.entrySet()) {
                    String id = normalize(entry.getKey());
                    if (!id.isEmpty()) {
                        outcomes.put(id, enumValue(
                            CandidateOutcome.class,
                            entry.getValue(),
                            null,
                            "candidate outcome"
                        ));
                    }
                }
            }
            return new Context(
                ruleProfile(knowledgeProfile),
                normalizedSet(enabledPacks),
                normalizedSet(disabledPacks),
                valueOr(includePlugins, true),
                valueOr(includeLearnedMacros, true),
                enumValue(
                    CandidateProofStatus.class,
                    minMacroProofStatus,
                    CandidateProofStatus.VALIDATED_BY_EXAMPLES,
                    "candidate proof status"
                ),
                valueOr(searchProfile, "DISCOVERY"),
                valueOr(goalExpression, ""),
                valueOr(maxCandidatesPerPosition, 24),
                valueOr(maxCandidatesTotal, 240),
                normalizedList(assumptions),
                valueOr(includeRejectedCandidates, true),
                valueOr(selectedCandidateId, ""),
                outcomes
            );
        }
    }

    private static RuleProfile ruleProfile(String value) {
        if (value == null || value.isBlank()) {
            return RuleProfile.CORE;
        }
        String normalized = enumName(value);
        try {
            return RuleProfile.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            try {
                return RuleProfile.fromId(value);
            } catch (IllegalArgumentException ignored) {
                throw new IllegalArgumentException(
                    "unknown rule profile: " + value,
                    exception
                );
            }
        }
    }

    private static <E extends Enum<E>> E enumValue(
        Class<E> type,
        String value,
        E fallback,
        String owner
    ) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, enumName(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "unknown " + owner + ": " + value,
                exception
            );
        }
    }

    private static String enumName(String value) {
        return value.trim()
            .toUpperCase(Locale.ROOT)
            .replace('-', '_');
    }

    private static List<String> normalizedList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .sorted()
            .toList();
    }

    private static Set<String> normalizedSet(List<String> values) {
        return Set.copyOf(new LinkedHashSet<>(normalizedList(values)));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String valueOr(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static boolean valueOr(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }
}
