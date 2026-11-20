package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Rechecks a retained generated theorem using a separately provisioned, trusted Lean project. */
public final class LeanEvidenceRebuilder {
    private static final String TOOLCHAIN = "lean-toolchain";
    private static final String MANIFEST = "lake-manifest.json";
    private final LeanSolverBackend toolchain;

    public LeanEvidenceRebuilder(Path project, Path evidenceRoot) {
        this(new LeanSolverBackend(project, evidenceRoot));
    }

    /** Uses the explicitly configured, trusted Lean command and evidence directory. */
    public LeanEvidenceRebuilder(LeanSolverBackend toolchain) {
        this.toolchain = Objects.requireNonNull(toolchain);
    }

    public record Receipt(Path directory, String receiptHash) { }

    /**
     * Rebuilds source from a confirmed attempt whose certificate hash the caller already trusts.
     * The receipt is separate from solver confirmation. The caller provisions the second project
     * and establishes the integrity of its installed Lean and mathlib code.
     */
    public Receipt rebuild(Obligation expected, String originalCertificateHash, Path retained) throws Exception {
        String obligation = expected.toCanonicalJson();
        require(obligation.equals(read(retained, "obligation.json")), "retained obligation differs");
        String originalAuditText = read(retained, "audit.json");
        var originalAudit = LeanSolverBackend.checkedAuditDocument(originalAuditText, expected.contentHash(), null);
        String source = new LeanSourceRenderer().source(expected, originalAudit.nonce());
        require(source.equals(read(retained, "proof.lean")), "retained source differs from the generated theorem");
        require(originalAuditText.equals(LeanSolverBackend.checkedProcessAudit(read(retained, "proof.stdout"),
            expected, originalAudit.nonce()).text()), "retained audit differs from Lean output");

        String version = read(retained, "version.stdout").strip();
        require(version.startsWith("Lean (version "), "retained Lean version is missing");
        String retainedCertificate = read(retained, "certificate.txt");
        require(SolverIr.sha256(retainedCertificate).equals(originalCertificateHash), "original Lean certificate differs");
        var lines = retainedCertificate.lines().toList();
        require(lines.size() >= 9, "Lean certificate has no certified rebuild environment");
        String configurationHash = hashField(lines.get(4), "configuration=");
        String originalProjectHash = hashField(lines.get(lines.size() - 3), "project=");
        String toolchainPin = read(retained, TOOLCHAIN);
        String dependencyPin = read(retained, MANIFEST);
        var originalEnvironment = new LeanSolverBackend.PinnedEnvironment(originalProjectHash,
            SolverIr.sha256(toolchainPin), SolverIr.sha256(dependencyPin));
        require(Files.isRegularFile(retained.resolve("proof.olean"), LinkOption.NOFOLLOW_LINKS),
            "retained compiled proof is missing");
        String originalCertificate = LeanSolverBackend.certificate(expected, source,
            LeanSolverBackend.compiledHash(retained), originalAuditText, configurationHash, version, originalEnvironment);
        require(originalCertificate.equals(retainedCertificate), "Lean artifacts differ from the original certificate");

        String rebuildConfiguration = toolchain.configurationHash();
        var rebuildEnvironment = toolchain.pinnedEnvironment();
        require(!rebuildEnvironment.projectHash().equals(originalProjectHash), "rebuild requires a separate Lean project");
        require(rebuildEnvironment.toolchainHash().equals(originalEnvironment.toolchainHash())
            && rebuildEnvironment.dependencyHash().equals(originalEnvironment.dependencyHash()),
            "rebuild project Lean pins differ from the original certificate");
        Path output = toolchain.newEvidenceDirectory("lean-rebuild-");
        Files.writeString(output.resolve("obligation.json"), obligation);
        String independent = originalAudit.scoped() ? LeanStatementReconstruction.theorem(expected) : "";
        String rebuildSource = source + independent;
        Files.writeString(output.resolve("proof.lean"), rebuildSource);
        Files.writeString(output.resolve(TOOLCHAIN), toolchainPin);
        Files.writeString(output.resolve(MANIFEST), dependencyPin);

        var observedVersion = toolchain.version(output);
        require(observedVersion.success() && version.equals(observedVersion.stdout().strip()), "Lean version differs");
        var proof = toolchain.compile(output);
        require(proof.success(), "Lean rebuild failed in " + output);
        require(rebuildConfiguration.equals(toolchain.configurationHash())
            && rebuildEnvironment.equals(toolchain.pinnedEnvironment()) && rebuildEnvironment.pinsMatch(output),
            "Lean rebuild configuration changed");
        require(rebuildSource.equals(read(output, "proof.lean")), "Lean rebuild source changed");
        var audit = LeanSolverBackend.checkedProcessAudit(proof.stdout(), expected, originalAudit.nonce());
        require(audit.imports().equals(originalAudit.imports()), "Lean rebuild imported declarations differ");
        require(audit.axioms().equals(originalAudit.axioms()), "Lean rebuild axiom dependencies differ");
        require(Files.isRegularFile(output.resolve("proof.olean"), LinkOption.NOFOLLOW_LINKS)
            && Files.size(output.resolve("proof.olean")) > 0, "Lean rebuild compiled proof is missing or empty");
        String rebuiltCertificate = LeanSolverBackend.certificate(expected, rebuildSource, LeanSolverBackend.compiledHash(output),
            audit.text(), rebuildConfiguration, observedVersion.stdout(), rebuildEnvironment);
        Files.writeString(output.resolve("audit.json"), audit.text());
        Files.writeString(output.resolve("certificate.txt"), rebuiltCertificate);
        String schema = originalAudit.scoped() ? "regelsuche.lean-evidence-rebuild/v2" : "regelsuche.lean-evidence-rebuild/v1";
        String receipt = schema + "\noriginal=" + originalCertificateHash
            + "\nrebuild=" + SolverIr.sha256(rebuiltCertificate) + "\n"
            + (originalAudit.scoped() ? "reconstructed=" + SolverIr.sha256(independent) + "\n" : "");
        Files.writeString(output.resolve("rebuild.txt"), receipt);
        return new Receipt(output, SolverIr.sha256(receipt));
    }

    private static String hashField(String line, String prefix) throws IOException {
        require(line.startsWith(prefix) && line.substring(prefix.length()).matches("sha256:[0-9a-f]{64}"),
            "invalid retained Lean certificate field " + prefix);
        return line.substring(prefix.length());
    }

    private static String read(Path directory, String name) throws IOException {
        Path path = directory.resolve(name);
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "missing Lean evidence file " + name);
        return Files.readString(path);
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
