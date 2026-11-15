package de.regelsuche.release;

import java.nio.file.Path;

/**
 * Command-line entry point for the separate domain-generic qualification.
 *
 * <p>The command writes the canonical profile catalog, qualification report,
 * run receipt and all retained domain exports below the selected output
 * directory. It exits with an exception unless the dedicated domain-generic
 * claim is authorized; it never authorizes the algebraic autonomous-campaign
 * claim.</p>
 */
public final class DomainGenericQualificationMain {
    private DomainGenericQualificationMain() {
    }

    public static void main(String[] args) {
        if (args.length == 2 && "--verify-retained".equals(args[0])) {
            requireReady(new DomainGenericQualificationRunner().verifyRetained(Path.of(args[1])));
            return;
        }
        if (args.length > 1) {
            throw new IllegalArgumentException(
                "usage: DomainGenericQualificationMain [output-directory] | --verify-retained directory");
        }
        Path output = args.length == 1
            ? Path.of(args[0])
            : Path.of("build", "reports", "domain-generic-qualification");
        DomainGenericQualificationRunner.QualificationRun run =
            new DomainGenericQualificationRunner().run(output);
        requireReady(run);
    }

    private static void requireReady(DomainGenericQualificationRunner.QualificationRun run) {
        if (!run.domainGenericClaimAuthorized()) {
            throw new IllegalStateException(
                "domain-generic discovery qualification is not ready");
        }
        System.out.println(run.toCanonicalJson());
    }
}
