package de.regelsuche.release;

import java.nio.file.Path;

/** Command-line entry point for the separate domain-generic qualification. */
public final class DomainGenericQualificationMain {
    private DomainGenericQualificationMain() {
    }

    public static void main(String[] args) {
        if (args.length > 1) {
            throw new IllegalArgumentException(
                "usage: DomainGenericQualificationMain [output-directory]");
        }
        Path output = args.length == 1
            ? Path.of(args[0])
            : Path.of("build", "reports", "domain-generic-qualification");
        DomainGenericQualificationRunner.QualificationRun run =
            new DomainGenericQualificationRunner().run(output);
        if (!run.domainGenericClaimAuthorized()) {
            throw new IllegalStateException(
                "domain-generic discovery qualification is not ready");
        }
        System.out.println(run.toCanonicalJson());
    }
}
