package de.regelsuche.experiments.autopilot;

/**
 * Internal canonical hashing facade retained while current Autopilot artifacts
 * are migrated to {@link AutonomousResearchBriefV2#hash(String)}.
 *
 * <p>This is not a research-brief contract. The only supported brief contract
 * is {@link AutonomousResearchBriefV2}.</p>
 */
public final class AutonomousResearchBrief {
    private AutonomousResearchBrief() {
    }

    public static String hash(String material) {
        return AutonomousResearchBriefV2.hash(material);
    }
}
