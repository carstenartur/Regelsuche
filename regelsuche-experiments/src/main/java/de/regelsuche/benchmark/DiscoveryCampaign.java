package de.regelsuche.benchmark;

import de.regelsuche.json.JsonWriter;
import java.util.List;

/** Product-level campaign descriptor for replayable multi-seed discovery runs. */
public record DiscoveryCampaign(
    String id,
    List<String> seedIds,
    int globalBudget,
    int parallelism,
    List<String> enabledBackends,
    String persistenceMode,
    List<String> reportArtifacts
) {
    public DiscoveryCampaign {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        seedIds = seedIds == null ? List.of() : List.copyOf(seedIds);
        globalBudget = Math.max(0, globalBudget);
        parallelism = Math.max(1, parallelism);
        enabledBackends = enabledBackends == null ? List.of() : List.copyOf(enabledBackends);
        persistenceMode = persistenceMode == null || persistenceMode.isBlank() ? "IN_MEMORY" : persistenceMode;
        reportArtifacts = reportArtifacts == null ? List.of() : List.copyOf(reportArtifacts);
    }

    public static DiscoveryCampaign fromReport(
        String id,
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        int globalBudget,
        int parallelism,
        List<String> enabledBackends,
        String persistenceMode
    ) {
        return new DiscoveryCampaign(
            id,
            report.rows().stream().map(row -> row.seed().id()).sorted().toList(),
            globalBudget,
            parallelism,
            enabledBackends,
            persistenceMode,
            List.of(
                "discovery-report.json",
                "discovery-report.html",
                "discovery-report.md",
                "discovery-replay.json",
                "hypotheses.json",
                "macro-rules.json",
                "counterexamples.json",
                "provenance.graph.json",
                "reproducibility-pack.json"
            )
        );
    }

    public String renderJson() {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("schema", "regelsuche.discovery-campaign/v1");
        writer.property("id", id);
        writer.array("seedIds", seeds -> seedIds.forEach(seeds::value));
        writer.object("budgets", budgets -> {
            budgets.property("globalBudget", globalBudget);
            budgets.property("parallelism", parallelism);
        });
        writer.array("enabledBackends", backends -> enabledBackends.forEach(backends::value));
        writer.property("persistenceMode", persistenceMode);
        writer.array("reportArtifacts", artifacts -> reportArtifacts.forEach(artifacts::value));
        writer.endObject();
        return writer.toString();
    }
}
