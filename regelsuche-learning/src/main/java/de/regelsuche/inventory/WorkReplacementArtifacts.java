package de.regelsuche.inventory;

import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

/** Versioned envelope retains raw artifacts verbatim; accounting-incomplete rows require v4. */
public final class WorkReplacementArtifacts {
    private WorkReplacementArtifacts() {}
    public static String json(WorkReplacementExperiment.Report report) {
        var writer = new JsonWriter().beginObject().property("schema", report.revision())
            .property("claim", "BOUNDED_DEVELOPMENT_LOGICAL_WORK;NO_GENERAL_OR_CPU_SPEEDUP_CLAIM")
            .property("manifest", report.manifest().toCanonicalJson())
            .array("arms", arms -> {
                for (var arm : WorkReplacementManifest.Arm.values()) {
                    var result = report.arms().get(arm);
                    arms.objectValue(out -> out.property("arm", arm.name()).property("account", result.account().toCanonicalJson())
                        .property("accountingComplete", result.accountingComplete()).property("elapsedNanos", result.elapsedNanos())
                        .property("withinBudget", result.withinBudget(report.manifest().resources().totalWork()))
                        .array("rows", rows -> result.rows().forEach(row -> rows.objectValue(item -> writeRow(item, row)))));
                }
            });
        return writer.endObject().toString();
    }
    private static void writeRow(JsonWriter out, WorkReplacementExperiment.Row row) {
        out.property("query", row.queryId()).property("status", row.status().name()).property("allocatedWork", row.allocatedWork())
            .property("elapsedNanos", row.elapsedNanos()).property("processId", row.processId()).property("detail", row.detail());
        if (row.evaluation() == null) out.nullProperty("evaluation");
        else out.object("evaluation", value -> value.property("qualityReached", row.evaluation().qualityReached())
            .property("validProof", row.evaluation().validProof()).property("inputScore", row.evaluation().inputScore())
            .property("outputScore", row.evaluation().outputScore()).property("outputIdentity", row.evaluation().outputIdentity())
            .property("rawReceipt", row.evaluation().rawReceipt())
            .stringArray("learnedWitnessIds", row.evaluation().learnedWitnessIds()));
    }
    /** Counts attempted UTF-16 writes, including the write that fails; materialization is a separate receipt. */
    public static void stream(String text, Writer sink, WorkReplacementExperiment.Journal journal, String prefix) {
        long attempted = 0;
        try {
            for (int i = 0; i < text.length(); i++) { attempted = Math.addExact(attempted, 1); sink.write(text.charAt(i)); }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
        finally {
            WorkReplacementTypedExecution.charge(journal, prefix + "/io", LifecycleWorkAccount.Phase.OUTPUT,
                attempted, "attempted-utf16-writes/v1", "");
        }
    }
}
