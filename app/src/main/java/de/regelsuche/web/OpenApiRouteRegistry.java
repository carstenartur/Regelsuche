package de.regelsuche.web;

import de.regelsuche.json.JsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Executable route contract derived from the canonical packaged OpenAPI document.
 *
 * <p>The embedded JDK {@code HttpServer} registers broad prefix contexts. This
 * registry adds the concrete path-template and HTTP-method layer that
 * {@code HttpContext} does not provide. As a result, the same OpenAPI document
 * that users and clients consume also defines which requests reach the
 * workbench handlers.</p>
 */
final class OpenApiRouteRegistry {
    private static final String SPECIFICATION_RESOURCE = "/web/openapi/openapi.json";
    private static final Set<String> HTTP_METHODS =
        Set.of("get", "post", "put", "patch", "delete", "options", "head");

    private final List<Route> routes;
    private final Set<String> contexts;

    private OpenApiRouteRegistry(List<Route> routes) {
        this.routes = List.copyOf(routes);
        LinkedHashSet<String> discoveredContexts = new LinkedHashSet<>();
        routes.forEach(route -> discoveredContexts.add(route.context()));
        this.contexts = Set.copyOf(discoveredContexts);
    }

    static OpenApiRouteRegistry load() {
        return Holder.INSTANCE;
    }

    Set<String> contexts() {
        return contexts;
    }

    List<Route> routes() {
        return routes;
    }

    Match match(String context, String requestPath, String requestMethod) {
        TreeSet<String> allowedMethods = new TreeSet<>();
        for (Route route : routes) {
            if (route.context().equals(context) && route.matches(requestPath)) {
                allowedMethods.add(route.method());
                if (route.method().equalsIgnoreCase(requestMethod)) {
                    return new Match(MatchStatus.ALLOWED, allowedMethods, route.operationId());
                }
            }
        }
        if (allowedMethods.isEmpty()) {
            return new Match(MatchStatus.NOT_FOUND, Set.of(), "");
        }
        return new Match(MatchStatus.METHOD_NOT_ALLOWED, allowedMethods, "");
    }

    private static OpenApiRouteRegistry readPackagedSpecification() {
        try (InputStream stream = OpenApiRouteRegistry.class.getResourceAsStream(SPECIFICATION_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing packaged OpenAPI specification: " + SPECIFICATION_RESOURCE);
            }
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return fromDocument(new JsonReader(source).readObject());
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read packaged OpenAPI specification", ex);
        }
    }

    private static OpenApiRouteRegistry fromDocument(Map<String, Object> document) {
        Map<String, Object> paths = object(document.get("paths"));
        if (paths.isEmpty()) {
            throw new IllegalStateException("OpenAPI specification does not contain paths");
        }

        List<Route> routes = new ArrayList<>();
        Set<String> operationIds = new LinkedHashSet<>();
        Set<String> methodPaths = new LinkedHashSet<>();
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            String pathTemplate = pathEntry.getKey();
            for (Map.Entry<String, Object> methodEntry : object(pathEntry.getValue()).entrySet()) {
                String method = methodEntry.getKey().toLowerCase(Locale.ROOT);
                if (!HTTP_METHODS.contains(method)) {
                    continue;
                }
                Map<String, Object> operation = object(methodEntry.getValue());
                String operationId = requiredString(operation, "operationId", method + " " + pathTemplate);
                String context = requiredString(operation, "x-regelsuche-context", operationId);
                if (!pathTemplate.equals(context) && !pathTemplate.startsWith(context + "/")) {
                    throw new IllegalStateException("OpenAPI operation " + operationId
                        + " is outside declared context " + context + ": " + pathTemplate);
                }
                if (!operationIds.add(operationId)) {
                    throw new IllegalStateException("Duplicate OpenAPI operationId: " + operationId);
                }
                String methodPath = method.toUpperCase(Locale.ROOT) + " " + pathTemplate;
                if (!methodPaths.add(methodPath)) {
                    throw new IllegalStateException("Duplicate OpenAPI operation: " + methodPath);
                }
                routes.add(new Route(
                    method.toUpperCase(Locale.ROOT),
                    pathTemplate,
                    operationId,
                    context,
                    compileTemplate(pathTemplate)
                ));
            }
        }
        if (routes.isEmpty()) {
            throw new IllegalStateException("OpenAPI specification does not define HTTP operations");
        }
        return new OpenApiRouteRegistry(routes);
    }

    private static Pattern compileTemplate(String template) {
        StringBuilder regex = new StringBuilder("^");
        int literalStart = 0;
        int index = 0;
        while (index < template.length()) {
            if (template.charAt(index) != '{') {
                index++;
                continue;
            }
            if (literalStart < index) {
                regex.append(Pattern.quote(template.substring(literalStart, index)));
            }
            int end = template.indexOf('}', index + 1);
            if (end < 0 || end == index + 1) {
                throw new IllegalStateException("Invalid OpenAPI path template: " + template);
            }
            regex.append("[^/]+");
            index = end + 1;
            literalStart = index;
        }
        if (literalStart < template.length()) {
            regex.append(Pattern.quote(template.substring(literalStart)));
        }
        regex.append("/?$");
        return Pattern.compile(regex.toString());
    }

    private static String requiredString(Map<String, Object> source, String key, String owner) {
        String value = String.valueOf(source.getOrDefault(key, "")).trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("Missing " + key + " for " + owner);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    enum MatchStatus {
        ALLOWED,
        METHOD_NOT_ALLOWED,
        NOT_FOUND
    }

    record Match(MatchStatus status, Set<String> allowedMethods, String operationId) {
        Match {
            allowedMethods = Collections.unmodifiableSet(
                new LinkedHashSet<>(allowedMethods)
            );
            operationId = operationId == null ? "" : operationId;
        }
    }

    record Route(
        String method,
        String pathTemplate,
        String operationId,
        String context,
        Pattern pathPattern
    ) {
        Route {
            method = method.toUpperCase(Locale.ROOT);
        }

        boolean matches(String requestPath) {
            return requestPath != null && pathPattern.matcher(requestPath).matches();
        }
    }

    private static final class Holder {
        private static final OpenApiRouteRegistry INSTANCE = readPackagedSpecification();
    }
}
