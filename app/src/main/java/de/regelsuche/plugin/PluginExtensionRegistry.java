package de.regelsuche.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PluginExtensionRegistry<T extends PluginExtension> {
    private final String kind;
    private final Map<String, MutableExtensionRegistration<T>> extensions = new LinkedHashMap<>();

    protected PluginExtensionRegistry(String kind) {
        this.kind = kind;
    }

    public void register(T extension) {
        register(extension, "plugin");
    }

    public void register(T extension, String source) {
        Objects.requireNonNull(extension, "extension");
        if (extensions.containsKey(extension.id())) {
            throw new IllegalArgumentException("Duplicate " + kind + " id: " + extension.id());
        }
        extensions.put(extension.id(), new MutableExtensionRegistration<>(extension, source));
    }

    public void disable(String id) {
        MutableExtensionRegistration<T> registration = extensions.get(id);
        if (registration != null) {
            registration.enabled = false;
        }
    }

    public List<T> enabledExtensions() {
        List<T> enabled = new ArrayList<>();
        for (MutableExtensionRegistration<T> registration : extensions.values()) {
            if (registration.enabled) {
                enabled.add(registration.extension);
            }
        }
        return List.copyOf(enabled);
    }

    public List<ExtensionRegistration<T>> registrations() {
        List<ExtensionRegistration<T>> registrations = new ArrayList<>();
        for (MutableExtensionRegistration<T> registration : extensions.values()) {
            registrations.add(registration.snapshot());
        }
        return List.copyOf(registrations);
    }

    public record ExtensionRegistration<T extends PluginExtension>(
        String id,
        T extension,
        String source,
        boolean enabled
    ) {
    }

    private static final class MutableExtensionRegistration<T extends PluginExtension> {
        private final T extension;
        private final String source;
        private boolean enabled = true;

        private MutableExtensionRegistration(T extension, String source) {
            this.extension = extension;
            this.source = source;
        }

        private ExtensionRegistration<T> snapshot() {
            return new ExtensionRegistration<>(extension.id(), extension, source, enabled);
        }
    }
}
