package de.regelsuche.sdk.discovery.signal;

import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.signal.FourierQuery;
import de.regelsuche.discovery.signal.PeriodicSignal;
import de.regelsuche.discovery.signal.PeriodicSignal.Term;
import de.regelsuche.discovery.signal.SignalWork;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.discovery.RegelsucheDiscovery;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/** Reproducible positive and negative transfer examples; optional canonical evidence directory. */
public final class PeriodicSignalExample {
    private PeriodicSignalExample() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("optional argument: evidence output directory");
        Path directory = args.length == 1 ? Path.of(args[0]) : null;
        if (directory != null) Files.createDirectories(directory);
        var training = List.of(repeated(12, 3, 12));
        run("repeated-periods", new PeriodicSignalStudy(training,
            List.of(repeated(20, 5, 8))), directory);
        run("negative-transfer", new PeriodicSignalStudy(training,
            List.of(new FourierQuery(new PeriodicSignal(7,
                List.of(new Term(7, ExactRational.ONE))), List.of(0)))), directory);
    }

    private static void run(String name, PeriodicSignalStudy study, Path directory) throws Exception {
        var run = RegelsucheDiscovery.forDomain(PeriodicSignalDomain.typedDomain())
            .campaign("periodic-" + name).seed(name, study, "built-in/periodic-signals/v1")
            .budget(new DiscoveryBudget(1, 4, 4, 2, 2, 128)).run();
        if (!run.isConfirmed()) throw new IllegalStateException(run.evidence().toCanonicalJson());
        var certificate = run.selectedCertificate().orElseThrow();
        var comparison = certificate.comparison();
        System.out.println(name + ": " + run.outcome() + ", selected=" + certificate.candidate().plan());
        System.out.println("measurement                     units    operand-bits    max-bits");
        print("training selection (both)", certificate.selectionWork());
        print("holdout direct construction", comparison.direct().construction());
        print("holdout direct evaluation", comparison.direct().evaluation());
        print("holdout merged construction", comparison.merged().construction());
        print("holdout merged evaluation", comparison.merged().evaluation());
        print("holdout selected total", comparison.selectedWork());
        print("holdout comparison (both)", comparison.comparisonWork());
        print("holdout exact verification", certificate.verificationWork());
        for (var attempt : run.evidence().candidateAttempts()) {
            System.out.println("audit and evaluation metrics: " + attempt.metrics());
        }
        System.out.println("search resources: " + run.executedWork());
        System.out.println("evidence: " + certificate.evidenceStrength());
        System.out.println("Counts are diagnostic units, not CPU time or FFT complexity.");
        if (directory != null) {
            Files.writeString(directory.resolve(name + ".json"), run.evidence().toCanonicalJson(),
                StandardCharsets.UTF_8);
        }
    }

    private static void print(String label, SignalWork work) {
        System.out.printf(Locale.ROOT, "%-30s %7d %15d %11d%n", label, work.units(),
            work.operandBits(), work.maxOperandBits());
    }

    private static FourierQuery repeated(int length, int period, int count) {
        return new FourierQuery(new PeriodicSignal(length, IntStream.range(0, count)
            .mapToObj(i -> new Term(period, ExactRational.integer(i + 1))).toList()),
            IntStream.range(0, length).boxed().toList());
    }
}
