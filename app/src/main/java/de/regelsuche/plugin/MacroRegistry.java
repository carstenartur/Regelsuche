package de.regelsuche.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MacroRegistry {
    private final Map<String, MutableMacroRegistration> macros = new LinkedHashMap<>();

    public void register(RuleMacro macro) {
        register(macro, "plugin");
    }

    public void register(RuleMacro macro, String source) {
        Objects.requireNonNull(macro, "macro");
        if (macros.containsKey(macro.id())) {
            throw new IllegalArgumentException("Duplicate macro id: " + macro.id());
        }
        macros.put(macro.id(), new MutableMacroRegistration(macro, source));
    }

    public void disable(String id) {
        MutableMacroRegistration registration = macros.get(id);
        if (registration != null) {
            registration.enabled = false;
        }
    }

    public List<RuleMacro> enabledMacros() {
        List<RuleMacro> enabled = new ArrayList<>();
        for (MutableMacroRegistration registration : macros.values()) {
            if (registration.enabled) {
                enabled.add(registration.macro);
            }
        }
        return List.copyOf(enabled);
    }

    public List<MacroRegistration> registrations() {
        List<MacroRegistration> registrations = new ArrayList<>();
        for (MutableMacroRegistration registration : macros.values()) {
            registrations.add(registration.snapshot());
        }
        return List.copyOf(registrations);
    }

    public record MacroRegistration(String id, RuleMacro macro, String source, boolean enabled) {
    }

    private static final class MutableMacroRegistration {
        private final RuleMacro macro;
        private final String source;
        private boolean enabled = true;

        private MutableMacroRegistration(RuleMacro macro, String source) {
            this.macro = macro;
            this.source = source;
        }

        private MacroRegistration snapshot() {
            return new MacroRegistration(macro.id(), macro, source, enabled);
        }
    }
}
