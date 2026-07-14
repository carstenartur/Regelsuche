package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.CampaignBudget;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.ResourceKind;
import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Factual configured/executed/skipped/remaining accounting for campaign work. */
public record CampaignBudgetLedger(
    String schema,
    String briefHash,
    List<BudgetLine> lines,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.campaign-budget-ledger/v1";

    public CampaignBudgetLedger {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported campaign budget ledger schema");
        }
        requireSha256(briefHash, "briefHash");
        lines = lines == null
            ? List.of()
            : lines.stream().sorted(BudgetLine.ORDER).toList();
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("budget ledger must contain configured work");
        }
        long distinctKeys = lines.stream()
            .map(line -> line.stage().name() + '|' + line.resource().name())
            .distinct()
            .count();
        if (distinctKeys != lines.size()) {
            throw new IllegalArgumentException("budget ledger contains duplicate stage/resource lines");
        }
        requireSha256(contentHash, "contentHash");
    }

    public static CampaignBudgetLedger configured(
        AutonomousResearchBrief brief
    ) {
        Objects.requireNonNull(brief, "brief");
        List<BudgetLine> lines = new ArrayList<>();
        brief.budget().stageBudgets().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(stage -> stage.getValue().resources().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(resource -> lines.add(new BudgetLine(
                    stage.getKey(),
                    resource.getKey(),
                    resource.getValue(),
                    0L,
                    0L,
                    resource.getValue()))));
        return create(brief.contentHash(), lines);
    }

    public static CampaignBudgetLedger create(
        String briefHash,
        List<BudgetLine> lines
    ) {
        List<BudgetLine> ordered = lines == null
            ? List.of()
            : lines.stream().sorted(BudgetLine.ORDER).toList();
        String hash = AutonomousResearchBrief.hash(canonicalMaterial(
            briefHash, ordered));
        return new CampaignBudgetLedger(SCHEMA, briefHash, ordered, hash);
    }

    public long remaining(EvidenceStage stage, ResourceKind resource) {
        return lines.stream()
            .filter(line -> line.stage() == stage && line.resource() == resource)
            .mapToLong(BudgetLine::remaining)
            .findFirst()
            .orElse(0L);
    }

    public CampaignBudgetLedger record(
        EvidenceStage stage,
        ResourceKind resource,
        long executedDelta,
        long skippedDelta
    ) {
        if (executedDelta < 0L || skippedDelta < 0L) {
            throw new IllegalArgumentException("budget deltas must be non-negative");
        }
        List<BudgetLine> updated = new ArrayList<>();
        boolean found = false;
        for (BudgetLine line : lines) {
            if (line.stage() == stage && line.resource() == resource) {
                found = true;
                long consumed = Math.addExact(executedDelta, skippedDelta);
                if (consumed > line.remaining()) {
                    throw new IllegalArgumentException(
                        "budget update exceeds remaining work for " + stage + '/' + resource);
                }
                updated.add(new BudgetLine(
                    stage,
                    resource,
                    line.configured(),
                    Math.addExact(line.executed(), executedDelta),
                    Math.addExact(line.skipped(), skippedDelta),
                    line.remaining() - consumed));
            } else {
                updated.add(line);
            }
        }
        if (!found) {
            throw new IllegalArgumentException(
                "budget line does not exist for " + stage + '/' + resource);
        }
        return create(briefHash, updated);
    }

    public void validateAgainst(
        AutonomousResearchBrief brief
    ) {
        Objects.requireNonNull(brief, "brief");
        if (!brief.contentHash().equals(briefHash)) {
            throw new IllegalArgumentException("ledger belongs to a different research brief");
        }
        CampaignBudget configuredBudget = brief.budget();
        Map<String, Long> expected = new TreeMap<>();
        configuredBudget.stageBudgets().forEach((stage, stageBudget) ->
            stageBudget.resources().forEach((resource, amount) ->
                expected.put(stage.name() + '|' + resource.name(), amount)));
        Map<String, Long> actual = new TreeMap<>();
        lines.forEach(line -> actual.put(
            line.stage().name() + '|' + line.resource().name(), line.configured()));
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                "ledger configured work differs from research brief budget");
        }
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("briefHash", briefHash)
            .array("lines", array -> lines.forEach(line ->
                array.objectValue(object -> object
                    .property("stage", line.stage().name())
                    .property("resource", line.resource().name())
                    .property("configured", line.configured())
                    .property("executed", line.executed())
                    .property("skipped", line.skipped())
                    .property("remaining", line.remaining()))))
            .property("contentHash", contentHash)
            .endObject()
            .toString();
    }

    private static String canonicalMaterial(
        String briefHash,
        List<BudgetLine> lines
    ) {
        StringBuilder material = new StringBuilder(SCHEMA)
            .append("\nbrief=").append(briefHash);
        lines.forEach(line -> material.append("\nline=")
            .append(line.canonicalMaterial()));
        return material.toString();
    }

    public record BudgetLine(
        EvidenceStage stage,
        ResourceKind resource,
        long configured,
        long executed,
        long skipped,
        long remaining
    ) {
        static final Comparator<BudgetLine> ORDER = Comparator
            .comparing(BudgetLine::stage)
            .thenComparing(BudgetLine::resource);

        public BudgetLine {
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(resource, "resource");
            if (configured < 0L || executed < 0L || skipped < 0L || remaining < 0L) {
                throw new IllegalArgumentException("budget values must be non-negative");
            }
            if (configured != executed + skipped + remaining) {
                throw new IllegalArgumentException(
                    "configured must equal executed + skipped + remaining");
            }
        }

        String canonicalMaterial() {
            return stage.name() + '|' + resource.name() + '|'
                + configured + '|' + executed + '|' + skipped + '|' + remaining;
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }
}
