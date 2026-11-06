package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.ExactTheoryEvidenceProvider;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Valid zero identities with different declared theory costs exercise real frontier label selection. */
public class TypedAlternativeLineageTest {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final Expr X = new VariableExpr("x");
    private static final Expr A = new BinaryExpr(X, BinaryOperator.ADD, new NumberExpr(0));
    private static final Expr B = new BinaryExpr(new NumberExpr(0), BinaryOperator.ADD, X);
    private static final MoveProvider.Descriptor DESCRIPTOR = new MoveProvider.Descriptor("zero-theory", "zero",
        SearchMove.SourceKind.EXPERT, SearchMove.ProofStrength.VERIFIED, List.of(), SearchMove.ValueEvidence.UNKNOWN, "test");

    private record Evidence(ExactTheoryEvidence.Binding binding) { }

    /** Test-only installed authority; only this fixture can create its private evidence objects. */
    public static final class EvidenceProvider implements ExactTheoryEvidenceProvider {
        @Override public Optional<ExactTheoryEvidence.Binding> bind(Object evidence) {
            return evidence instanceof Evidence fixture ? Optional.of(fixture.binding()) : Optional.empty();
        }
    }

    @Test void alternativeWitnessKeepsTheAffordableTheoryPrefixForBothContinuationContracts(@TempDir Path directory) throws Exception {
        // The fixture authority belongs only to the child JVM, never to other search tests.
        var service = directory.resolve("META-INF/services/" + ExactTheoryEvidenceProvider.class.getName());
        Files.createDirectories(service.getParent());
        Files.writeString(service, EvidenceProvider.class.getName() + "\n");
        var output = directory.resolve("lineage.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", directory + File.pathSeparator + System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            TypedAlternativeLineageTest.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "isolated lineage check timed out");
            assertEquals(0, process.exitValue(), () -> readOutput(output));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String readOutput(Path output) {
        try { return Files.readString(output); }
        catch (java.io.IOException failure) { return failure.toString(); }
    }

    public static void main(String[] arguments) {
        var high = move(A, B, 9);
        var low = move(A, B, 1);
        var finish = move(B, X, 5);
        TypedMoveSearch.TypedProvider provider = new TypedMoveSearch.TypedProvider() {
            @Override public MoveProvider.Descriptor descriptor() { return DESCRIPTOR; }
            @Override public MoveProvider.Batch candidates(MoveState state, MoveContext context) {
                var expression = CODEC.decodeExpression(state.expression());
                return new MoveProvider.Batch(expression.equals(A) ? List.of(high, low)
                    : expression.equals(B) ? List.of(finish) : List.of(), TransformationWorkMetrics.ZERO, true);
            }
        };
        var problem = new TypedMoveSearch.Problem(A,
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION), List.of(provider),
            TypedMoveSearch.Policy.INVENTORY_ORDER, (source, move, context) -> {
                var target = CODEC.decodeExpression(move.transformation().transformedExpression());
                boolean valid = source.expression().equals(A) && target.equals(B)
                    || source.expression().equals(B) && target.equals(X);
                return new MoveVerifier.Verification(valid, 1, List.of("zero-identity"), "zero replay");
            }, state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.EAGER_CONTROL,
            new MoveSearch.Budget(10, 3, 10, 100, 100_000));
        for (var contract : SearchContinuationContract.values()) {
            var result = new TypedSourceOnlySearch().searchAlternatives(problem,
                state -> new TypedSourceOnlySearch.Score(state.expression().equals(X) ? 0 : 1, 1),
                0, contract, 20_000, 8);
            assertTrue(result.withinBudget());
            var candidate = result.candidates().stream().filter(c -> c.state().expression().equals(X)).findFirst().orElseThrow();
            assertEquals(2, candidate.witness().size());
            assertEquals(6, candidate.witness().stream().mapToLong(s -> s.move().executionWork().exactTheoryWorkUnits()).sum(),
                "the independently replayed path must obey the original theory bound of 10");
            assertEquals(1, candidate.witness().getFirst().move().executionWork().exactTheoryWorkUnits());
        }
    }

    private static SearchMove move(Expr source, Expr target, long work) {
        var binding = new ExactTheoryEvidence.Binding(CODEC.encodeExpression(source), CODEC.encodeExpression(target),
            DESCRIPTOR.id(), "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64), work, "{}");
        return SearchMove.from(Transformation.exactTheory(ExactTheoryEvidence.fromVerified(new Evidence(binding))), DESCRIPTOR, 0);
    }
}
