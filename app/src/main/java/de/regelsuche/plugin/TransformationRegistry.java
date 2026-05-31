package de.regelsuche.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TransformationRegistry {
    private final Map<String, MutableTransformationRegistration> transformations = new LinkedHashMap<>();

    public void register(PatternTransformation transformation) {
        register(transformation, "plugin", "", List.of());
    }

    public void register(PatternTransformation transformation, String source, String explanation, List<String> tags) {
        Objects.requireNonNull(transformation, "transformation");
        if (transformations.containsKey(transformation.id())) {
            throw new IllegalArgumentException("Duplicate transformation id: " + transformation.id());
        }
        transformations.put(transformation.id(),
            new MutableTransformationRegistration(transformation, source, explanation, tags));
    }

    public void disable(String id) {
        MutableTransformationRegistration registration = transformations.get(id);
        if (registration != null) {
            registration.enabled = false;
        }
    }

    public List<PatternTransformation> enabledTransformations() {
        List<PatternTransformation> enabled = new ArrayList<>();
        for (MutableTransformationRegistration registration : transformations.values()) {
            if (registration.enabled) {
                enabled.add(registration.transformation);
            }
        }
        return List.copyOf(enabled);
    }

    public List<TransformationRegistration> registrations() {
        List<TransformationRegistration> registrations = new ArrayList<>();
        for (MutableTransformationRegistration registration : transformations.values()) {
            registrations.add(registration.snapshot());
        }
        return List.copyOf(registrations);
    }

    public record TransformationRegistration(
        String id,
        PatternTransformation transformation,
        String source,
        String explanation,
        List<String> tags,
        boolean enabled
    ) {
        public TransformationRegistration {
            tags = List.copyOf(tags);
        }
    }

    private static final class MutableTransformationRegistration {
        private final PatternTransformation transformation;
        private final String source;
        private final String explanation;
        private final List<String> tags;
        private boolean enabled = true;

        private MutableTransformationRegistration(
            PatternTransformation transformation,
            String source,
            String explanation,
            List<String> tags
        ) {
            this.transformation = transformation;
            this.source = source;
            this.explanation = explanation == null ? "" : explanation;
            this.tags = List.copyOf(tags);
        }

        private TransformationRegistration snapshot() {
            return new TransformationRegistration(transformation.id(), transformation, source, explanation, tags, enabled);
        }
    }
}
