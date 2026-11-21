package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.ScopedProofDag;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** The two closed, reconstructible proposal languages accepted by the real Lean boundary. */
sealed interface LeanProofPlan permits LeanLemmaPlan, LeanDagPlan {
    String fileName();
    String material();
    String proof();
    default String hash() { return SolverIr.sha256(material()); }
    default boolean accepts(Obligation obligation) { return SolverIr.SCOPED_OBLIGATION_SCHEMA.equals(obligation.schema()); }
    default void verifyAudit(LeanSolverBackend.AuditDocument audit) throws IOException { }

    static LeanProofPlan read(Obligation request, Path directory) throws IOException {
        var lemma = LeanLemmaPlan.retained(directory);
        Path file = directory.resolve(LeanDagPlan.FILE);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return lemma;
        if (lemma != null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16_384)
            throw new IOException("ambiguous or invalid retained proof DAG");
        try { return new LeanDagPlan(ScopedProofDag.read(request, Files.readString(file))); }
        catch (IllegalArgumentException invalid) { throw new IOException("malformed retained proof DAG", invalid); }
    }
}
