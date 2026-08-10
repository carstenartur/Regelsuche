package de.regelsuche.web;

import com.sun.net.httpserver.HttpExchange;
import de.regelsuche.input.InputType;
import de.regelsuche.search.SearchProfile;
import java.io.IOException;
import java.util.List;
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
            String tool = "lean4";
            List<String> assumptions = List.of();
            while (object.nextField()) {
                switch (object.fieldName()) {
                    case "leftPattern" -> leftPattern = stringOr(
                        object.readNullableString(), "");
                    case "rightPattern" -> rightPattern = stringOr(
                        object.readNullableString(), "");
                    case "tool", "backend" -> tool = stringOr(
                        object.readNullableString(), "lean4");
                    case "assumptions" -> assumptions =
                        object.readStringArray();
                    default -> object.skipValue();
                }
            }
            return new ProofBridgeRequest(
                leftPattern, rightPattern, tool, assumptions);
        });
    }

    ProofJobCreateRequest readProofJobCreate(HttpExchange exchange)
            throws IOException {
        return json.readObject(exchange, object -> {
            String leftPattern = "";
            String rightPattern = "";
            int priority = 0;
            String worker = "";
            List<String> assumptions = List.of();
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
                    case "assumptions" -> assumptions =
                        object.readStringArray();
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
        List<String> assumptions
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
        List<String> assumptions
    ) {
        ProofJobCreateRequest {
            assumptions = List.copyOf(assumptions);
        }
    }
}
