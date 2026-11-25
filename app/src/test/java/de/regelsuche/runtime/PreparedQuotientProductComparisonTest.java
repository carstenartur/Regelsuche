package de.regelsuche.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.regelsuche.json.JsonReader;
import de.regelsuche.scoring.cost.TransformationGoal;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Fixed source-only comparison against two controls with the identical rewrite inventory. */
class PreparedQuotientProductComparisonTest {
    private static final int WARMUPS = 6;
    private static final int SAMPLES = 15;
    private static final Map<String, Object> BUDGET = Map.of("maxDepth", 4, "maxVisitedExpressions", 300,
        "maxExpandingSteps", 4, "maxCandidatesPerState", 64);
    private static final List<Sample> SAMPLES_BY_CASE = List.of(
        new Sample("independent-neutral-sites", "pair(a+0,b+0,c+0,d+0)", Map.of("budget", BUDGET), "pair(a, b, c, d)"),
        new Sample("syntax-order", "pair(0+x,x+0)", Map.of("budget", BUDGET), "pair(x, x)"),
        new Sample("selected-sibling", "pair(x+0,x+0)", Map.of("budget", BUDGET,
            "position", Map.of("path", List.of(1), "text", "x + 0")), "pair(x + 0, x)"),
        new Sample("square", "(x+1)^2", Map.of("budget", BUDGET), null),
        new Sample("distributive", "(x+1)*(x+2)", Map.of("budget", BUDGET), null),
        new Sample("no-rule", "pair(x,y,z)", Map.of("budget", BUDGET), null));

    @Test
    void comparesProductContinuationAndBothControlsWithIndependentReplay() throws Exception {
        boolean measure = Boolean.getBoolean("regelsuche.preparedQuotient.measure");
        var rows = new StringBuilder("case\tarm\tbest\tstatus\treplay\tvisited\tproviderCalls\truleAttempts\tnodeVisits"
            + "\tproposals\tcacheHits\treplayCalls\tcpuNs\twallNs\tallocatedBytes\n");
        for (var sample : SAMPLES_BY_CASE) {
            var fixed = new EnumMap<PreparedQuotientSearch.Arm, Observation>(PreparedQuotientSearch.Arm.class);
            for (var arm : PreparedQuotientSearch.Arm.values()) {
                var result = observe(run(sample, arm));
                if (sample.expected != null) assertEquals(sample.expected, result.best, sample.id + " " + arm);
                if (sample.id.equals("no-rule")) assertEquals(null, result.best);
                assertEquals(result.best == null ? "NO_CANDIDATE" : "VERIFIED_PRIMITIVE_REPLAY", result.replay);
                fixed.put(arm, result);
            }
            if (measure) measure(sample, fixed, rows);
        }
        if (measure) {
            var output = Path.of("target", "prepared-quotient-product-v1", "measurements.tsv");
            Files.createDirectories(output.getParent());
            Files.writeString(output, rows, StandardCharsets.UTF_8);
        }
    }

    private static void measure(Sample sample, Map<PreparedQuotientSearch.Arm, Observation> fixed,
            StringBuilder rows) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isCurrentThreadCpuTimeSupported() || !bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("CPU and allocation counters required for this measurement");
        }
        bean.setThreadCpuTimeEnabled(true);
        bean.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        var arms = PreparedQuotientSearch.Arm.values();
        for (int run = 0; run < WARMUPS; run++) {
            for (var arm : arms) run(sample, arm);
        }
        var timings = new long[arms.length][3][SAMPLES];
        for (int index = 0; index < SAMPLES; index++) {
            // Rotate the order to prevent a fixed adjacent arm from claiming every warm-cache effect.
            for (int offset = 0; offset < arms.length; offset++) {
                var arm = arms[(index + offset) % arms.length];
                long beforeAllocation = bean.getThreadAllocatedBytes(threadId);
                long beforeCpu = bean.getCurrentThreadCpuTime();
                long beforeWall = System.nanoTime();
                String response = run(sample, arm);
                timings[arm.ordinal()][1][index] = System.nanoTime() - beforeWall;
                timings[arm.ordinal()][0][index] = bean.getCurrentThreadCpuTime() - beforeCpu;
                timings[arm.ordinal()][2][index] = bean.getThreadAllocatedBytes(threadId) - beforeAllocation;
                assertEquals(fixed.get(arm), observe(response), sample.id + " " + arm);
            }
        }
        for (var arm : arms) {
            var item = fixed.get(arm);
            var values = timings[arm.ordinal()];
            rows.append(sample.id).append('\t').append(arm).append('\t').append(item.best).append('\t')
                .append(item.status).append('\t').append(item.replay).append('\t').append(item.visited).append('\t')
                .append(item.calls).append('\t').append(item.attempts).append('\t').append(item.nodes).append('\t')
                .append(item.proposals).append('\t').append(item.hits).append('\t').append(item.replayCalls).append('\t')
                .append(median(values[0])).append('\t').append(median(values[1])).append('\t').append(median(values[2])).append('\n');
        }
    }

    private static long median(long[] values) {
        Arrays.sort(values);
        return values[values.length / 2];
    }

    private static String run(Sample sample, PreparedQuotientSearch.Arm arm) {
        return PreparedQuotientSearch.run(sample.source, TransformationGoal.SIMPLIFY, sample.options, arm);
    }

    private static Observation observe(String response) {
        var result = new JsonReader(response).readObject();
        var details = RuntimeJson.object(result.get("continuation"));
        var work = RuntimeJson.object(details.get("work"));
        var quotient = RuntimeJson.object(details.get("quotient"));
        assertNotNull(details.get("sourceBinding"));
        return new Observation((String) result.get("bestExpression"), (String) details.get("status"),
            (String) details.get("replay"), units(work, "visitedStates"), units(work, "providerCalls"),
            units(work, "ruleAttempts"), units(work, "rewriteNodeVisits"), units(work, "providerProposals"),
            units(quotient, "successorCacheHits"), units(work, "replayCalls"));
    }

    private static long units(Map<String, ?> values, String field) {
        return ((Number) values.get(field)).longValue();
    }

    private record Sample(String id, String source, Map<String, ?> options, String expected) { }
    private record Observation(String best, String status, String replay, long visited, long calls,
            long attempts, long nodes, long proposals, long hits, long replayCalls) { }
}
