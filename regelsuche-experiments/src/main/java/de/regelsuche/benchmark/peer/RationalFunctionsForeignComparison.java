package de.regelsuche.benchmark.peer;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.math.algorithms.equivalence.RationalFunctionPlanner;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.parse.ExpressionParser;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A separate, complete foreign inventory; never changes the frozen Enumo grammar or results. */
public final class RationalFunctionsForeignComparison {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 20_000, 5_000);
    private static final List<String> CORPORA = List.of(
        "research/benchmarks/enumo-public-v1/rosa.fpcore",
        "research/benchmarks/enumo-horner-v2/transfer/fptaylor-extra.fpcore",
        "research/benchmarks/enumo-horner-v2/transfer/hamming-ch3.fpcore");
    private RationalFunctionsForeignComparison() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("expected checkout and JSON output path");
        Path checkout = Path.of(args[0]), output = Path.of(args[1]);
        var rows = new ArrayList<Map<String, Object>>();
        var sources = new ArrayList<Map<String, Object>>();
        for (var relative : CORPORA) {
            var bytes = Files.readAllBytes(checkout.resolve(relative));
            String text = new String(bytes, StandardCharsets.UTF_8);
            long inventoryStart = System.nanoTime();
            var polynomial = FpcorePolynomialCorpus.read(text);
            var rational = FpcorePolynomialCorpus.readRational(text);
            sources.add(Map.of("path", relative, "sha256", hash(bytes), "programs", rational.size(),
                "inventoryWallNanos", System.nanoTime() - inventoryStart));
            for (int index = 0; index < rational.size(); index++) {
                var entry = rational.get(index);
                var row = new LinkedHashMap<String, Object>();
                row.put("corpus", relative);
                row.put("ordinal", entry.ordinal());
                row.put("name", entry.name());
                row.put("polynomialStatus", polynomial.get(index).status().name());
                row.put("status", entry.status().name());
                row.put("detail", entry.detail());
                row.put("source", entry.source());
                if (entry.status() == FpcorePolynomialCorpus.Status.SUPPORTED) measure(entry.source(), row);
                rows.add(row);
            }
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", "regelsuche.rational-foreign/v1");
        report.put("semantics", "exact rational functions on explicitly retained original domains; no IEEE754 claim");
        report.put("java", System.getProperty("java.runtime.version"));
        report.put("limits", LIMITS);
        report.put("sources", sources);
        report.put("rows", rows);
        Files.createDirectories(output.toAbsolutePath().getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("rational-foreign/v1 rows=" + rows.size() + " admitted="
            + rows.stream().filter(row -> "SUPPORTED".equals(row.get("status"))).count());
    }

    private static void measure(String source, Map<String, Object> row) throws Exception {
        var cpu = ManagementFactory.getThreadMXBean();
        boolean cpuAvailable = cpu.isCurrentThreadCpuTimeSupported() && cpu.isThreadCpuTimeEnabled();
        long wallStart = System.nanoTime(), cpuStart = cpuAvailable ? cpu.getCurrentThreadCpuTime() : -1;
        // Acquiring the original domain is included; it is not free training or an oracle answer.
        var discovery = RationalFunctionPlanner.simplify(source, List.of(), LIMITS, () -> false);
        var proposal = RationalFunctionPlanner.simplify(source, discovery.requiredConditions(), LIMITS, () -> false);
        long measuredCpu = cpuAvailable ? cpu.getCurrentThreadCpuTime() - cpuStart : -1;
        row.put("wallNanos", System.nanoTime() - wallStart);
        row.put("threadCpuNanos", measuredCpu);
        row.put("work", Math.addExact(discovery.work(), proposal.work()));
        row.put("sourceSha256", hash(source.getBytes(StandardCharsets.UTF_8)));
        row.put("requiredConditions", discovery.requiredConditions());
        row.put("candidate", proposal.candidate());
        row.put("proposalStatus", proposal.status().name());
        row.put("proposalReason", proposal.reason());
        row.put("sourceOperations", operations(new ExpressionParser().parseExactTerm(source).expression()));
        row.put("candidateOperations", operations(new ExpressionParser().parseExactTerm(proposal.candidate()).expression()));
        var replay = proposal.certificate().isPresent() ? RationalFunctionPlanner.verify(source,
            proposal.certificate().orElseThrow(), LIMITS, () -> false) : null;
        boolean verified = replay != null && replay.valid();
        row.put("replayWork", replay == null ? 0 : replay.work());
        row.put("totalWorkIncludingReplay", Math.addExact(discovery.work() + proposal.work(), replay == null ? 0 : replay.work()));
        row.put("totalWallNanosIncludingReplay", System.nanoTime() - wallStart);
        row.put("totalThreadCpuNanosIncludingReplay", cpuAvailable ? cpu.getCurrentThreadCpuTime() - cpuStart : -1);
        row.put("independentlyReplayed", verified);
        if (proposal.status() == RationalFunctionPlanner.Status.VERIFIED && !verified)
            throw new IllegalStateException("fresh rational certificate did not replay");
    }

    /** Integer powers count their expanded multiply cost; surface spelling cannot create a saving. */
    static int operations(Expr expression) {
        if (!(expression instanceof BinaryExpr binary)) return 0;
        if (binary.operator() == BinaryOperator.POW && binary.right() instanceof NumberExpr exponent
                && exponent.value().isInteger() && exponent.value().signum() >= 0) {
            int power = exponent.value().intValueExact();
            return power == 0 ? 0 : Math.addExact(Math.multiplyExact(power, operations(binary.left())), power - 1);
        }
        return Math.addExact(1, Math.addExact(operations(binary.left()), operations(binary.right())));
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
