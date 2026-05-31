package de.regelsuche.plugin;

import de.regelsuche.ast.Expr;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AstVisitorContext {
    private final Map<Expr, Map<String, Object>> metadataByNode = new IdentityHashMap<>();
    private final List<VisitorDiagnostic> diagnostics = new ArrayList<>();
    private final List<String> markers = new ArrayList<>();
    private String lastRuleId;

    public void putMetadata(Expr node, String key, Object value) {
        metadataByNode.computeIfAbsent(node, ignored -> new LinkedHashMap<>()).put(key, value);
    }

    public Map<String, Object> metadata(Expr node) {
        return Map.copyOf(metadataByNode.getOrDefault(node, Map.of()));
    }

    public void report(String visitorId, String message) {
        diagnostics.add(new VisitorDiagnostic(visitorId, message, lastRuleId));
    }

    public void mark(String marker) {
        markers.add(marker);
    }

    public List<VisitorDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public List<String> markers() {
        return List.copyOf(markers);
    }

    void setLastRuleId(String lastRuleId) {
        this.lastRuleId = lastRuleId;
    }

    public record VisitorDiagnostic(String visitorId, String message, String ruleId) {
    }
}
