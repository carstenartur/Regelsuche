import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Factor;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Problem;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Result;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanLearning;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanner.Strategy;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintVerifier;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/** Replays the seven-source, unprojected public CNF study recorded in protocol.md. */
public final class ConstraintPlanningStudy {
    private static final IterationLimits COUNT_LIMIT = new IterationLimits(2_000_000_000L, 1_000_000, 120_000);
    private static final IterationLimits TRAIN_LIMIT = new IterationLimits(24_000_000_000L, 1_000_000, 1_440_000);
    private static final int FACTOR_WIDTH_LIMIT = 20;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private record Entry(String partition, String name, int variables, int clauses, int width, Optional<Problem> problem) {}
    private record Timing(long wall, long cpu) {
        static Timing start() {
            return new Timing(System.nanoTime(), THREADS.getCurrentThreadCpuTime());
        }
        String elapsed() {
            return (System.nanoTime() - wall) + "\t" + (THREADS.getCurrentThreadCpuTime() - cpu);
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Timing parseClock = Timing.start();
        List<Entry> entries = new ArrayList<>();
        for (String row : Files.readAllLines(dir.resolve("inputs.tsv")).stream().skip(1).toList()) {
            String[] col = row.split("\t");
            byte[] source;
            try (var in = new GZIPInputStream(Files.newInputStream(dir.resolve("inputs/" + col[1] + ".dimacs.gz")))) {
                source = in.readAllBytes();
            }
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
            if (!digest.equals(col[3]) || source.length != Long.parseLong(col[4])) throw new IOException("Input changed: " + col[1]);
            entries.add(parse(col[0], col[1], new String(source, StandardCharsets.ISO_8859_1)));
        }
        System.out.println("PARSE\t" + parseClock.elapsed());
        Map<String, BigInteger> agreedCounts = new HashMap<>();
        for (Entry e : entries) System.out.printf("INPUT\t%s\t%s\t%d\t%d\t%d\t%s%n", e.partition(), e.name(),
                e.variables(), e.clauses(), e.width(), e.problem().isPresent() ? "SUPPORTED" : "FACTOR_WIDTH_EXCEEDED");
        var training = entries.stream().filter(e -> e.partition().equals("Training") && e.problem().isPresent())
                .map(e -> new FiniteConstraintPlanLearning.Example(e.name(), e.problem().orElseThrow())).toList();
        var clock = Timing.start();
        var learned = FiniteConstraintPlanLearning.train(training, TRAIN_LIMIT, COUNT_LIMIT.maximumWork(), () -> false);
        System.out.printf("ACQUIRE\t%s\t%d\t%s%n", learned.reason(), learned.work(), clock.elapsed());
        for (var obs : learned.observations()) {
            for (var trial : obs.trials()) {
                System.out.printf("TRIAL\t%s\t%s\t%s\t%s\t%d\t%s%n", obs.source(), trial.strategy(),
                        trial.status(), trial.reason(), trial.work(), obs.best().map(Enum::name).orElse("-"));
            }
        }
        for (Entry e : entries) {
            if (e.problem().isEmpty()) continue;
            var p = e.problem().orElseThrow();
            for (Strategy strategy : Strategy.values()) {
                clock = Timing.start();
                Result r = FiniteConstraintCounter.count(p, strategy, COUNT_LIMIT, () -> false);
                report(e, strategy.name(), "-", "-", r, r.work(), clock.elapsed(), agreedCounts);
            }
            if (e.partition().equals("Evaluation") && learned.model().isPresent()) {
                clock = Timing.start();
                var run = FiniteConstraintPlanLearning.count(learned.model().orElseThrow(), p, COUNT_LIMIT, () -> false);
                String decision = run.decision().map(d -> d.strategy().name()).orElse("-");
                String origin = run.decision().map(d -> d.trainingSource()).orElse("-");
                report(e, "LEARNED", decision, origin, run.result(), run.work(), clock.elapsed(), agreedCounts);
            }
        }
    }

    private static void report(Entry e, String arm, String selection, String origin, Result result, long charged,
            String duration, Map<String, BigInteger> agreed) {
        long replayWork = 0;
        String count = "-";
        if (result.certificate().isPresent()) {
            var replay = FiniteConstraintVerifier.verify(e.problem().orElseThrow(), result.certificate().orElseThrow(), COUNT_LIMIT, () -> false);
            if (!replay.valid()) throw new AssertionError(e.name() + " failed original-source replay: " + replay.reason());
            replayWork = replay.work();
            BigInteger present = result.count().orElseThrow();
            BigInteger prior = agreed.putIfAbsent(e.name(), present);
            if (prior != null && !prior.equals(present)) throw new AssertionError("Different counts for " + e.name());
            count = present.toString();
        }
        System.out.printf("RUN\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%d\t%d\t%s%n", e.partition(), e.name(), arm,
                selection, origin, result.status(), result.reason(), count, charged, replayWork, duration);
    }

    private static Entry parse(String partition, String name, String text) {
        int variableCount = -1, declared = -1, width = 0;
        List<int[]> clauses = new ArrayList<>();
        var currentClause = new ArrayList<Integer>();
        for (String line : text.lines().toList()) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("c")) continue;
            String[] fields = s.split("\\s+");
            if (fields[0].equals("p")) {
                if (variableCount >= 0 || fields.length != 4 || !fields[1].equals("cnf")) throw new IllegalArgumentException(name);
                variableCount = Integer.parseInt(fields[2]);
                declared = Integer.parseInt(fields[3]);
                continue;
            }
            for (String token : fields) {
                int literal = Integer.parseInt(token);
                if (literal != 0) {
                    if (Math.abs((long) literal) > variableCount) throw new IllegalArgumentException(name);
                    currentClause.add(literal);
                } else {
                    int[] clause = currentClause.stream().mapToInt(Integer::intValue).toArray();
                    width = Math.max(width, Math.toIntExact(Arrays.stream(clause).map(Math::abs).distinct().count()));
                    clauses.add(clause);
                    currentClause.clear();
                }
            }
        }
        if (variableCount < 0 || !currentClause.isEmpty() || clauses.size() != declared) throw new IllegalArgumentException(name);
        Optional<Problem> problem = Optional.empty();
        if (width <= FACTOR_WIDTH_LIMIT) {
            problem = Optional.of(new Problem(variableCount, clauses.stream().map(ConstraintPlanningStudy::factor).toList(), Map.of()));
        }
        return new Entry(partition, name, variableCount, declared, width, problem);
    }

    private static Factor factor(int[] clause) {
        var ids = new TreeSet<Integer>();
        for (int literal : clause) ids.add(Math.abs(literal) - 1);
        List<Integer> scope = List.copyOf(ids);
        int positive = 0, negative = 0;
        for (int literal : clause) {
            int bit = 1 << scope.indexOf(Math.abs(literal) - 1);
            if (literal > 0) positive |= bit;
            else negative |= bit;
        }
        List<BigInteger> weights = new ArrayList<>(1 << scope.size());
        for (int mask = 0; mask < (1 << scope.size()); mask++) {
            boolean satisfied = (mask & positive) != 0 || (~mask & negative) != 0;
            weights.add(satisfied ? BigInteger.ONE : BigInteger.ZERO);
        }
        return new Factor(scope, weights);
    }
}
