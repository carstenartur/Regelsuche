package de.regelsuche.solver.ir.examples;

import de.regelsuche.solver.ir.PolynomialNormalFormSolverBackend;
import de.regelsuche.solver.ir.SolverBackend;
import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * General algebraic obligations underlying the GK review draft.
 * Uses the existing exact backend, not sampled evaluations or an analytic prover.
 * Denominator clearing here proves polynomial identities only: positivity,
 * nonzero denominators, infinite sums and prime recurrence remain separate goals.
 */
public final class GolombKellerAlgebra {
    public static final String MANUSCRIPT_BASE_BLOB =
        "723603459c381ae827b21107c62fa684c8a99eaa";
    private static final Expression X = new Symbol("x");
    private static final Expression A = new Symbol("a");
    private static final List<SymbolDeclaration> SYMBOLS = List.of(
        new SymbolDeclaration("a", Sort.REAL), new SymbolDeclaration("x", Sort.REAL));

    private GolombKellerAlgebra() { }

    public enum Lemma {
        LOSS_NUMERATOR,
        DENOMINATOR_SPLIT,
        PARITY_RESERVE_NUMERATOR,
        STABILITY_BUDGET,
        GAP_FOUR_FACTOR
    }

    /** The examples have no implicit hypotheses; variables range over all reals. */
    public static Obligation identity(Lemma lemma) {
        Objects.requireNonNull(lemma, "lemma");
        Expression xx = mul(X, X);
        Expression oneMinus = sub(n(1), xx);
        Goal goal = switch (lemma) {
            case LOSS_NUMERATOR -> equal(sub(mul(A, oneMinus), mul(A, xx)),
                mul(A, sub(n(1), mul(n(2), xx))));
            case DENOMINATOR_SPLIT -> equal(mul(n(2), oneMinus),
                add(n(1), sub(n(1), mul(n(2), xx))));
            case PARITY_RESERVE_NUMERATOR -> equal(
                sub(mul(n(5), sub(n(1), mul(n(2), xx))), oneMinus),
                sub(n(4), mul(n(9), xx)));
            case STABILITY_BUDGET -> equal(mul(n(10), mul(n(78), mul(n(5), A))),
                mul(n(156), mul(n(25), A)));
            case GAP_FOUR_FACTOR -> equal(sub(n(1), mul(xx, xx)),
                mul(sub(n(1), X), add(add(n(1), X), add(xx, mul(xx, X)))));
        };
        return obligation(lemma.name().toLowerCase(Locale.ROOT), goal, List.of(),
            RequestedEvidence.SYMBOLIC_CERTIFICATE);
    }

    /** A false polynomial identity, not a claim that it fails at every valuation. */
    public static Obligation falseIdentity(Lemma lemma) {
        Obligation original = identity(lemma);
        return obligation(lemma.name().toLowerCase(Locale.ROOT) + "-false-plus-one",
            equal(original.goal().left(), add(original.goal().right(), n(1))),
            List.of(), RequestedEvidence.SYMBOLIC_CERTIFICATE);
    }

    public static List<Obligation> unsupportedControls() {
        return List.of(
            obligation("explicit-assumption", equal(A, A),
                List.of(new Predicate("positive-a", Relation.GREATER_THAN, A, n(0))),
                RequestedEvidence.SYMBOLIC_CERTIFICATE),
            obligation("division-not-cleared", equal(
                new Binary(BinaryOperator.DIVIDE, A, X),
                new Binary(BinaryOperator.DIVIDE, A, X)),
                List.of(), RequestedEvidence.SYMBOLIC_CERTIFICATE),
            obligation("analytic-call", equal(new Call("exp", List.of(X)), n(1)),
                List.of(), RequestedEvidence.SYMBOLIC_CERTIFICATE),
            obligation("order-not-identity", new Goal(Relation.GREATER_OR_EQUAL,
                mul(X, X), n(0)), List.of(), RequestedEvidence.SYMBOLIC_CERTIFICATE),
            obligation("formal-kernel-proof-not-supplied", equal(A, A),
                List.of(), RequestedEvidence.FORMAL_PROOF));
    }

    private static Obligation obligation(String id, Goal goal, List<Predicate> assumptions,
                                         RequestedEvidence evidence) {
        return Obligation.create("gk-" + id, SYMBOLS, List.of(Theory.REAL_ARITHMETIC),
            assumptions, goal, evidence,
            new SourceProvenance("manuscript-algebra-obligation", "gk-v5-review/" + id,
                SolverIr.sha256("gk-algebra/v1;v4-git-blob=" + MANUSCRIPT_BASE_BLOB)));
    }

    private static Expression n(int value) { return new Literal(Integer.toString(value)); }
    private static Expression add(Expression a, Expression b) {
        return new Binary(BinaryOperator.ADD, a, b);
    }
    private static Expression sub(Expression a, Expression b) {
        return new Binary(BinaryOperator.SUBTRACT, a, b);
    }
    private static Expression mul(Expression a, Expression b) {
        return new Binary(BinaryOperator.MULTIPLY, a, b);
    }
    private static Goal equal(Expression a, Expression b) {
        return new Goal(Relation.EQUALS, a, b);
    }

    /** A fresh exact backend is used; no successful prior run is trusted. */
    public static Path run(Path outputRoot) throws IOException {
        return run(outputRoot, new PolynomialNormalFormSolverBackend());
    }

    /** Injection is for explicit contract testing, not external promotion authority. */
    public static Path run(Path outputRoot, SolverBackend backend) throws IOException {
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(backend, "backend");
        Files.createDirectories(outputRoot);
        Path run = Files.createTempDirectory(outputRoot, "run-");
        List<Obligation> obligations = new ArrayList<>();
        List<ResultStatus> expected = new ArrayList<>();
        for (Lemma lemma : Lemma.values()) {
            obligations.add(identity(lemma)); expected.add(ResultStatus.CONFIRMED);
            obligations.add(falseIdentity(lemma)); expected.add(ResultStatus.REFUTED);
        }
        for (Obligation control : unsupportedControls()) {
            obligations.add(control); expected.add(ResultStatus.UNSUPPORTED);
        }
        try {
            for (int index = 0; index < obligations.size(); index++) {
                Obligation obligation = obligations.get(index);
                Path directory = Files.createDirectory(run.resolve(obligation.obligationId()));
                write(directory.resolve("obligation.json"), obligation.toCanonicalJson());
                SolverExecution execution = backend.execute(obligation);
                // Retain the actual result before comparing it with the expected outcome.
                write(directory.resolve("translation.json"), execution.translation().toCanonicalJson());
                write(directory.resolve("result.json"), execution.result().toCanonicalJson());
                write(directory.resolve("execution.json"), execution.toCanonicalJson());
                if (!execution.obligationHash().equals(obligation.contentHash())
                        || !execution.result().goalHash().equals(obligation.goalHash())
                        || !execution.result().assumptionsHash().equals(obligation.assumptionsHash())) {
                    throw new IllegalStateException("backend returned evidence for a different obligation");
                }
                if (execution.result().status() != expected.get(index)) {
                    throw new IllegalStateException(obligation.obligationId() + ": "
                        + execution.result().status() + " instead of " + expected.get(index));
                }
                boolean unsupported = expected.get(index) == ResultStatus.UNSUPPORTED;
                if (execution.translation().status() != (unsupported
                        ? TranslationStatus.REJECTED : TranslationStatus.LOSSLESS)
                        || (!unsupported && execution.result().certificateHash().isEmpty())
                        || (unsupported && (!execution.result().certificateHash().isEmpty()
                            || execution.translation().issues().isEmpty()))) {
                    throw new IllegalStateException("evidence strength or translation mismatch");
                }
            }
            write(run.resolve("completed.json"),
                "{\"scope\":\"general-polynomial-identities-only\",\"confirmed\":5,"
                + "\"refuted\":5,\"unsupported\":5,\"analyticTheoremProved\":false}");
        } catch (IOException | RuntimeException | Error failure) {
            try {
                write(run.resolve("FAILED.txt"), failure.getClass().getName() + ": "
                    + failure.getMessage());
            } catch (IOException retentionFailure) {
                failure.addSuppressed(retentionFailure);
            }
            throw failure;
        }
        return run;
    }

    private static void write(Path path, String text) throws IOException {
        Files.writeString(path, text + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("expected output root");
        System.out.println(run(Path.of(args[0])));
    }
}
