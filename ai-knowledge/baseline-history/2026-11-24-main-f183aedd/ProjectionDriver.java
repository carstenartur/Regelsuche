package org.aiknowledge.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;

/** Reads the complete retained reports and delegates projection/trend semantics to v0.1.10. */
public final class ProjectionDriver {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException(
            "complexity.json check.json seed-directory snapshot-output trend-output");
        var json = new ObjectMapper();
        Map complexity = json.readValue(Path.of(args[0]).toFile(), Map.class);
        Map check = json.readValue(Path.of(args[1]).toFile(), Map.class);
        Map thresholds = (Map) check.get("trendThresholds");
        Path seed = Path.of(args[2]).toAbsolutePath().normalize();
        Path snapshot = Path.of(args[3]).toAbsolutePath().normalize();
        Path trend = Path.of(args[4]).toAbsolutePath().normalize();
        var options = new ExtractionOptions(seed.getParent(), trend.getParent(), seed, seed,
            (Boolean) thresholds.get("failOnWarnings"),
            ((Number) check.get("maxCognitiveDebt")).doubleValue(),
            ((Number) thresholds.get("maxCognitiveDebtIncrease")).doubleValue(),
            ((Number) thresholds.get("maxConceptRadiusIncrease")).doubleValue(),
            ((Number) thresholds.get("maxContextTokenIncrease")).doubleValue());
        StableIo.writeJson(snapshot, TrendAnalyzer.snapshot(complexity));
        StableIo.writeJson(trend, TrendAnalyzer.trend(options, complexity));
    }
}
