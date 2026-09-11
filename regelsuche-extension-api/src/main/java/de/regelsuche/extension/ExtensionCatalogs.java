package de.regelsuche.extension;

import de.regelsuche.api.StableApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Factory for deterministic immutable extension catalogs. */
@StableApi(since = "2")
public final class ExtensionCatalogs {
    private ExtensionCatalogs() {
    }

    /** Returns the canonical empty catalog. */
    public static ExtensionCatalog empty() {
        return of(List.of());
    }

    /** Creates and validates one complete immutable catalog snapshot. */
    public static ExtensionCatalog of(
        Collection<? extends RegisteredExtension<?>> registrations
    ) {
        return new Catalog(registrations);
    }

    private static final class Catalog implements ExtensionCatalog {
        private static final Comparator<RegisteredExtension<?>> ORDER =
            Comparator.comparing((RegisteredExtension<?> registration) ->
                    registration.point().id())
                .thenComparing(registration ->
                    registration.point().contractType().getName())
                .thenComparing(registration -> registration.descriptor().id())
                .thenComparing(registration -> registration.origin().kind().name())
                .thenComparing(registration -> registration.origin().sourceId())
                .thenComparing(registration -> registration.origin().sourceVersion())
                .thenComparing(registration -> registration.origin().sourceReference())
                .thenComparing(registration -> registration.origin().artifactSha256())
                .thenComparing(registration -> registration.origin().trustEvidenceSha256());

        private final List<RegisteredExtension<?>> registrations;
        private final String canonicalManifest;
        private final String contentHash;

        Catalog(Collection<? extends RegisteredExtension<?>> source) {
            Objects.requireNonNull(source, "registrations");
            var normalized = new ArrayList<RegisteredExtension<?>>();
            for (RegisteredExtension<?> registration : source) {
                normalized.add(Objects.requireNonNull(registration, "registration"));
            }
            normalized.sort(ORDER);
            validate(normalized);
            registrations = List.copyOf(normalized);
            canonicalManifest = render(registrations);
            contentHash = sha256(canonicalManifest);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<RegisteredExtension<T>> registrations(ExtensionPoint<T> point) {
            Objects.requireNonNull(point, "point");
            return registrations.stream()
                .filter(registration -> registration.point().id().equals(point.id()))
                .filter(registration ->
                    registration.point().contractType().equals(point.contractType()))
                .map(registration -> (RegisteredExtension<T>) registration)
                .toList();
        }

        @Override
        public <T> Optional<RegisteredExtension<T>> find(
            ExtensionPoint<T> point,
            String id
        ) {
            String checkedId = ExtensionIdentifiers.identifier(id, "extension id");
            return registrations(point).stream()
                .filter(registration -> registration.descriptor().id().equals(checkedId))
                .findFirst();
        }

        @Override
        public String canonicalManifest() {
            return canonicalManifest;
        }

        @Override
        public String contentHash() {
            return contentHash;
        }
    }

    private static void validate(List<RegisteredExtension<?>> registrations) {
        Map<String, Class<?>> pointTypes = new HashMap<>();
        Set<String> contributionIds = new HashSet<>();
        for (RegisteredExtension<?> registration : registrations) {
            if (!registration.point().contractType().isInstance(registration.implementation())) {
                throw new IllegalArgumentException(
                    "implementation type mismatch for " + registration.point().id()
                );
            }
            Class<?> previous = pointTypes.putIfAbsent(
                registration.point().id(),
                registration.point().contractType()
            );
            if (previous != null && !previous.equals(registration.point().contractType())) {
                throw new IllegalArgumentException(
                    "extension point type conflict: " + registration.point().id()
                );
            }
            String key = registration.point().id() + "\u0000" + registration.descriptor().id();
            if (!contributionIds.add(key)) {
                throw new IllegalArgumentException(
                    "duplicate extension contribution: "
                        + registration.point().id() + "/" + registration.descriptor().id()
                );
            }
        }
    }

    private static String render(List<RegisteredExtension<?>> registrations) {
        StringBuilder json = new StringBuilder("{\"extensions\":[");
        boolean first = true;
        for (RegisteredExtension<?> registration : registrations) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('{');
            property(json, "point", registration.point().id()).append(',');
            property(json, "contract", registration.point().contractType().getName()).append(',');
            property(json, "id", registration.descriptor().id()).append(',');
            property(json, "name", registration.descriptor().name()).append(',');
            json.append("\"tags\":[");
            for (int index = 0; index < registration.descriptor().tags().size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                string(json, registration.descriptor().tags().get(index));
            }
            json.append("],\"origin\":{");
            property(json, "kind", registration.origin().kind().name()).append(',');
            property(json, "sourceId", registration.origin().sourceId()).append(',');
            property(json, "sourceVersion", registration.origin().sourceVersion()).append(',');
            property(json, "sourceReference", registration.origin().sourceReference()).append(',');
            property(json, "artifactSha256", registration.origin().artifactSha256()).append(',');
            property(json, "trustEvidenceSha256", registration.origin().trustEvidenceSha256());
            json.append("}}");
        }
        return json.append("]}").toString();
    }

    private static StringBuilder property(StringBuilder target, String key, String value) {
        string(target, key).append(':');
        return string(target, value);
    }

    private static StringBuilder string(StringBuilder target, String value) {
        target.append('"');
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        target.append(String.format("\\u%04x", (int) ch));
                    } else {
                        target.append(ch);
                    }
                }
            }
        }
        return target.append('"');
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
