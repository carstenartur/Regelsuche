package de.regelsuche.plugin;

public record RuleDebugMetadata(
    String ruleId,
    RuleRejectionReason reason,
    String context,
    String detail
) {
    String diagnostic() {
        StringBuilder sb = new StringBuilder(ruleId).append(" ").append(reason);
        if (context != null && !context.isBlank()) {
            sb.append(" [").append(context).append("]");
        }
        if (detail != null && !detail.isBlank()) {
            sb.append(": ").append(detail);
        }
        return sb.toString();
    }
}
