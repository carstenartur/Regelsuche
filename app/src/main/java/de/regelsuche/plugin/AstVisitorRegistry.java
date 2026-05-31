package de.regelsuche.plugin;

import de.regelsuche.ast.Expr;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AstVisitorRegistry {
    private final Map<String, MutableVisitorRegistration> visitors = new LinkedHashMap<>();

    public void register(AstVisitorPlugin visitor) {
        register(visitor, "plugin");
    }

    public void register(AstVisitorPlugin visitor, String source) {
        Objects.requireNonNull(visitor, "visitor");
        if (visitors.containsKey(visitor.id())) {
            throw new IllegalArgumentException("Duplicate visitor id: " + visitor.id());
        }
        visitors.put(visitor.id(), new MutableVisitorRegistration(visitor, source));
    }

    public void disable(String id) {
        MutableVisitorRegistration registration = visitors.get(id);
        if (registration != null) {
            registration.enabled = false;
        }
    }

    public AstVisitorContext execute(AstVisitorPhase phase, Expr root, AstVisitorContext context) {
        for (MutableVisitorRegistration registration : visitors.values()) {
            if (registration.enabled && registration.visitor.phase() == phase) {
                registration.visitor.visit(root, context);
            }
        }
        return context;
    }

    public List<VisitorRegistration> registrations() {
        List<VisitorRegistration> registrations = new ArrayList<>();
        for (MutableVisitorRegistration registration : visitors.values()) {
            registrations.add(registration.snapshot());
        }
        return List.copyOf(registrations);
    }

    public record VisitorRegistration(String id, AstVisitorPlugin visitor, String source, boolean enabled) {
    }

    private static final class MutableVisitorRegistration {
        private final AstVisitorPlugin visitor;
        private final String source;
        private boolean enabled = true;

        private MutableVisitorRegistration(AstVisitorPlugin visitor, String source) {
            this.visitor = visitor;
            this.source = source;
        }

        private VisitorRegistration snapshot() {
            return new VisitorRegistration(visitor.id(), visitor, source, enabled);
        }
    }
}
