package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Frozen proposals for one-step lemma application, acquired from a caller-trusted checked proof. */
public final class LeanLemmaPlan implements LeanProofPlan {
    static final String FILE = "lemma-plan.txt";
    private static final String SCHEMA = "regelsuche.lean-lemma-plan/v1";
    private static final int MAX_CANDIDATES = 32;
    private final String trainingCertificate;
    private final List<String> declarations;

    private LeanLemmaPlan(String trainingCertificate, List<String> declarations) {
        this.trainingCertificate = trainingCertificate;
        this.declarations = List.copyOf(declarations);
    }

    /**
     * Read only the dependency list cryptographically bound to the trusted training certificate.
     * Acquisition sees no target. Nothing from this certificate establishes any target theorem.
     */
    public static LeanLemmaPlan learn(Obligation training, String trustedCertificate, Path retained) throws IOException {
        String manifest = read(retained, "certificate.txt");
        if (!SolverIr.sha256(manifest).equals(trustedCertificate)) throw new IOException("training certificate differs");
        var fields = manifest.lines().toList();
        String auditText = read(retained, "audit.json");
        if (fields.size() < 9 || !fields.getFirst().equals("obligation=" + training.contentHash())
                || !fields.get(3).equals("audit=" + SolverIr.sha256(auditText)))
            throw new IOException("training certificate does not bind the obligation and audit");
        var audit = LeanSolverBackend.checkedAuditDocument(auditText, training.contentHash(), null);
        if (!audit.scoped() || !SolverIr.SCOPED_OBLIGATION_SCHEMA.equals(training.schema()))
            throw new IOException("an imported declaration audit is required");
        List<String> names = audit.imports().stream().filter(entry -> entry.module().startsWith("Mathlib."))
            .map(LeanSolverBackend.ImportedDeclaration::name).filter(LeanLemmaPlan::usable)
            .sorted().limit(MAX_CANDIDATES).toList();
        if (names.isEmpty()) throw new IOException("no transferable mathlib declarations");
        return new LeanLemmaPlan(trustedCertificate, names);
    }

    private static boolean usable(String name) {
        return name.length() <= 256 && name.matches("[A-Za-z][A-Za-z0-9_']*(\\.[A-Za-z][A-Za-z0-9_']*)*");
    }

    public List<String> declarations() { return declarations; }
    public String hash() { return SolverIr.sha256(material()); }

    @Override public String fileName() { return FILE; }
    @Override public String material() {
        return SCHEMA + "\n" + trainingCertificate + "\n" + String.join("\n", declarations) + "\n";
    }

    static LeanLemmaPlan retained(Path directory) throws IOException {
        if (Files.notExists(directory.resolve(FILE), LinkOption.NOFOLLOW_LINKS)) return null;
        String input = read(directory, FILE);
        var lines = input.lines().toList();
        if (lines.size() < 3 || lines.size() > MAX_CANDIDATES + 2 || !lines.getFirst().equals(SCHEMA)
                || !lines.get(1).matches("sha256:[0-9a-f]{64}")) throw new IOException("invalid retained lemma plan");
        var names = lines.subList(2, lines.size());
        if (names.stream().anyMatch(name -> !usable(name)) || !names.equals(names.stream().distinct().sorted().toList()))
            throw new IOException("invalid retained lemma declarations");
        var plan = new LeanLemmaPlan(lines.get(1), names);
        if (!plan.material().equals(input)) throw new IOException("non-canonical lemma plan");
        return plan;
    }

    /** No fallback tactic. Confirmation of a transfer requires an acquired lemma to apply. */
    @Override public void verifyAudit(LeanSolverBackend.AuditDocument audit) throws IOException {
        if (audit.imports().stream().noneMatch(d -> declarations.contains(d.name())))
            throw new IOException("the proof term contains none of the proposed declarations");
    }

    @Override public String proof() {
        var source = new StringBuilder("by\n  -- frozen lemma plan " + hash() + "\n  intros\n  solve\n");
        for (String name : declarations) {
            String quoted = "«" + name.replace(".", "».«") + "»";
            source.append("  | apply ").append(quoted).append(" <;> assumption\n");
        }
        return source.toString();
    }

    private static String read(Path dir, String file) throws IOException {
        Path path = dir.resolve(file);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing training artifact " + file);
        return Files.readString(path);
    }
}
