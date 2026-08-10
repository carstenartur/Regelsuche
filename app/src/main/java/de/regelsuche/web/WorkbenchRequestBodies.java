package de.regelsuche.web;

import com.sun.net.httpserver.HttpExchange;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.input.InputType;
import de.regelsuche.search.SearchProfile;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Route-specific streaming decoders for the Workbench JSON request bodies.
 *
 * <p>The class deliberately lives beside the HTTP server instead of in the
 * mathematical core. It consumes fields directly from
 * {@link StreamingJsonRequestBody.ObjectCursor}; unknown fields are skipped
 * token by token and are never assembled into a generic request map.</p>
 */
final class WorkbenchRequestBodies {
    private final StreamingJsonRequestBody json;

    WorkbenchRequestBodies(StreamingJsonRequestBody json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    SearchRequest readSearch(HttpExchange exchange) throws IOException {
        return json.readObject(exchange, object -> {
            String expression = "";
            String type = InputType.TERM.name();
            String profile = SearchProfile.FAST_SIMPLIFY.name();
            String goal = "";
            while (object.nextField()) {
                switch (object.fieldName()) {
                    case "expression" -> expression = stringOr(
                        object.readNullableString(), "");
                    case "type" -> type = stringOr(
                        object.readNullableString(), InputType.TERM.name());
                    case "profile" -> profile = stringOr(
                        object.readNullableString(),
                        SearchProfile.FAST_SIMPLIFY.name());
                    case "goal" -> goal = stringOr(
                        object.readNullableString(), "");
                    default -> object.skipValue();
                }
            }
            return new SearchRequest(expression, type, profile, goal);
        });
    }

    DiscoverRequest readDiscover(HttpExchange exchange) throws IOException {
        return json.readObject(exchange, object -> {
            int min = 1;
            int max = 3;
            while (object.nextField()) {
                switch (object.fieldName()) {
                    case "min" -> min = intOr(object.readNullableInt(), 1);
                    case "max" -> max = intOr(object.readNullableInt(), 3);
                    default -> object.skipValue();
                }
            }
            return new DiscoverRequest(min, max);
        });
    }

    InventoryImportRequest readInventoryImport(HttpExchange exchange)
            throws IOException {
        return json.readObject(exchange, object -> {
            String source = "";
            while (object.nextField()) {
                if (object.fieldName().equals("json")) {
                    source = stringOr(object.readNullableString(), "");
                } else {
                    object.skipValue();
                }
            }
            return new InventoryImportRequest(source);
        });
    }

    InspectApplyRequest readInspectApply(HttpExchange exchange)
            throws IOException {
        return json.readObject(exchange, object -> {
            String expression = "";
            String pathKey = "";
            String matchId = "";
            int matchIndex = -1;
            while (object.nextField()) {
                switch (object.fieldName()) {
                    case "expression" -> expression = stringOr(
                        object.readNullableString(), "");
                    case "pathKey" -> pathKey = stringOr(
                        object.readNullableString(), "");
                    case "matchId" -> matchId = stringOr(
                        object.readNullableString(), "");
                    case "matchIndex" -> matchIndex = intOr(
                        object.readNullableInt(), -1);
                    default -> object.skipValue();
                }
            }
            return new InspectApplyRequest(
                expression, pathKey, matchId, matchIndex);
        });
    }

    DidacticStepRequest readDidacticStep(HttpExchange exchange)
            throws IOException {
        return json.readObject(exchange, object -> {
            String currentExpression = "";
            String studentStep = "";
            String difficulty = "MITTELSTUFE";
            while (object.nextField()) {
                switch (object.fieldName()) {
                    case "currentExpression" -> currentExpression = stringOr(
                        object.readNullableString(), "");
                    case "studentStep" -> studentStep = stringOr(
                        object.readNullableString(), "");
                    case "difficulty" -> difficulty = stringOr(
                        object.readNullableString(), "MITTELSTUFE");
                    default -> object.skipValue();
                }
            }
            return new DidacticStepRequest(
                currentExpression, studentStep, difficulty);
        });
    }

    DidacticHintRequest readDidacticHint(HttpExchange exchange)
            throws IOException {
        return json.readObject(exchange, object -> {
            String currentExpression = "";
            String pedagogyProfile = "SCHOOL";
            while (object.nextField()) {
                switch (object.fieldName()) {
                    case "currentExpression" -> currentExpression = stringOr(
                        object.readNullableString(), "");
                    case "pedagogyProfile" -> pedagogyProfile = stringOr(
                        object.readNullableString(), "SCHOOL");
                    default -> object.skipValue();
                }
            }
            return new DidacticHintRequest(
                currentExpression, pedagogyProfile);
        });
    }

    ProofBridgeRequest readProofBridge(HttpExchange exchange)
            throws IOException {
        return json.readObject(exchange, object -> {
            String leftPattern = "";
            String rightPattern = "";
            String tool = "";
            String backend = "";
            List<Assumption> assumptions = List.of();
            while (object.nextField()) {
                switch (object.fieldName()) {
                    case "leftPattern" -> leftPattern = stringOr(
                        object.readNullableString(), "");
                    case "rightPattern" -> rightPattern = stringOr(
                        object.readNullableString(), "");
                    case "tool" -> tool = stringOr(
                        object.readNullableString(), "");
                    case "backend" -> backend = stringOr(
                        object.readNullableString(), "");
                    case "assumptions" -> assumptions = readAssumptions(object);
                    default -> object.skipValue();
                }
            }
            return new ProofBridgeRequest(
                leftPattern,
                rightPattern,
                firstNonBlank(tool, backend, "lean4"),
                assumptions
            );
        });
    }

    ProofJobCreateRequest readProofJobCreate(HttpExchange exchange)
            throws IOException {
        return json.readObject(exchange, object -> {
            String leftPattern = "";
            String rightPattern = "";
            int priority = 0;
            String worker = "";
            List<Assumption> assumptions = List.of();
            while (object.nextField()) {
                switch (object.fieldName()) {
                    case "leftPattern" -> leftPattern = stringOr(
                        object.readNullableString(), "");
                    case "rightPattern" -> rightPattern = stringOr(
                        object.readNullableString(), "");
                    case "priority" -> priority = intOr(
                        object.readNullableInt(), 0);
                    case "worker" -> worker = stringOr(
                        object.readNullableString(), "");
                    case "assumptions" -> assumptions = readAssumptions(object);
                    default -> object.skipValue();
                }
            }
            return new ProofJobCreateRequest(
                leftPattern, rightPattern, priority, worker, assumptions);
        });
    }

    private static String stringOr(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static int intOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static List<Assumption> readAssumptions(
        StreamingJsonRequestBody.ObjectCursor object
    ) throws IOException {
        return object.readStringOrObjectArray(
            value -> createAssumption("CUSTOM_PREDICATE", value, List.of()),
            WorkbenchRequestBodies::decodeAssumption
        );
    }

    private static Assumption decodeAssumption(
        StreamingJsonRequestBody.ObjectCursor object
    ) throws IOException {
        String kind = "CUSTOM_PREDICATE";
        String expression = "";
        List<String> symbols = List.of();
        while (object.nextField()) {
            switch (object.fieldName()) {
                case "kind" -> kind = stringOr(
                    object.readNullableString(), "CUSTOM_PREDICATE");
                case "expression" -> expression = stringOr(
                    object.readNullableString(), "");
                case "symbols" -> symbols = object.readStringArray();
                default -> object.skipValue();
            }
        }
        return createAssumption(kind, expression, symbols);
    }

    private static Assumption createAssumption(
        String rawKind,
        String rawExpression,
        List<String> rawSymbols
    ) throws StreamingJsonRequestBody.MalformedJsonRequestException {
        String expression = rawExpression == null ? "" : rawExpression.trim();
        if (expression.isEmpty()) {
            throw new StreamingJsonRequestBody.MalformedJsonRequestException(
                "assumption expression must not be blank");
        }
        String kindName = rawKind == null || rawKind.isBlank()
            ? "CUSTOM_PREDICATE"
            : rawKind.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        kindName = switch (kindName) {
            case "CUSTOM" -> "CUSTOM_PREDICATE";
            case "DOMAIN" -> "DOMAIN_MEMBERSHIP";
            default -> kindName;
        };
        Assumption.Kind kind;
        try {
            kind = Assumption.Kind.valueOf(kindName);
        } catch (IllegalArgumentException exception) {
            // Retain the historical API behaviour: unknown custom labels are
            // accepted as free-form predicates instead of becoming 500s.
            kind = Assumption.Kind.CUSTOM_PREDICATE;
        }
        List<String> symbols = rawSymbols == null
            ? List.of()
            : List.copyOf(new LinkedHashSet<>(rawSymbols.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList()));
        return new Assumption(kind, expression, symbols);
    }

    record SearchRequest(
        String expression,
        String type,
        String profile,
        String goal
    ) {
    }

    record DiscoverRequest(int min, int max) {
    }

    record InventoryImportRequest(String json) {
    }

    record InspectApplyRequest(
        String expression,
        String pathKey,
        String matchId,
        int matchIndex
    ) {
    }

    record DidacticStepRequest(
        String currentExpression,
        String studentStep,
        String difficulty
    ) {
    }

    record DidacticHintRequest(
        String currentExpression,
        String pedagogyProfile
    ) {
    }

    record ProofBridgeRequest(
        String leftPattern,
        String rightPattern,
        String tool,
        List<Assumption> assumptions
    ) {
        ProofBridgeRequest {
            assumptions = List.copyOf(assumptions);
        }
    }

    record ProofJobCreateRequest(
        String leftPattern,
        String rightPattern,
        int priority,
        String worker,
        List<Assumption> assumptions
    ) {
        ProofJobCreateRequest {
            assumptions = List.copyOf(assumptions);
        }
    }
}
