package de.regelsuche.benchmark;

import static de.regelsuche.benchmark.ModPowTransferFiles.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.mining.TypedOutputPattern;
import de.regelsuche.mining.TypedPatternGeneralizer;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-separated TRAIN/freeze/TEST research; no executable learned rule is promoted.
 * The public v1 command accepts only the originally committed corpus bytes.
 * A changed corpus requires a separately versioned experiment, not a v1 verdict.
 */
public final class ModPowFrozenTransfer {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    // Content identities from corpus commit cbf07fda; independent of caller-supplied paths.
    private static final String TRAIN_SHA256 =
        "c2932bb1a8a4412a819da52fb0b75ec62dae768b9e2261f19b1e7c6b6725c978";
    private static final String TEST_SHA256 =
        "0ae4cdc2fc7335d672dfdadc99fb6b8c58b880e72605db9d74e155e21121472f";
    private ModPowFrozenTransfer() {}

    public static void main(String[] args) throws IOException {
        require(args.length == 3 && args[0].equals("train") || args.length == 5 && args[0].equals("test"),
            "usage: train TRAIN.json model.json | test model.json SHA256 TEST.json result.json");
        Path destination = Path.of(args[args.length - 1]);
        if (Files.exists(destination)) throw new FileAlreadyExistsException(destination.toString());
        ObjectNode result = args[0].equals("train") ? train(Path.of(args[1]), TRAIN_SHA256)
            : evaluate(Path.of(args[1]), args[2], Path.of(args[3]), TEST_SHA256);
        Files.write(destination, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(result),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** Development-fixture helper; official v1 execution goes through main. */
    static ObjectNode train(Path corpus) throws IOException {
        return train(corpus, null);
    }

    private static ObjectNode train(Path corpus, String expectedCorpusHash) throws IOException {
        byte[] bytes = readCorpus(corpus, expectedCorpusHash, "TRAIN");
        var rows = rows(bytes, "TRAIN"); require(rows.size() == 2, "v1 fixes exactly two TRAIN observations");
        var examples = new ArrayList<TypedPatternGeneralizer.Example>();
        var model = JSON.createObjectNode().put("schema", MODEL_SCHEMA).put("trainSha256", hash(bytes));
        var witnesses = model.putArray("witnesses"); int shuffled = 0;
        for (var row : rows) {
            Baseline run = baseline(row);
            require(run.approved().size() == 2, "TRAIN primitive replay did not approve both alternatives");
            Expr best = best(row, run.approved());
            require(cost(row, best).dag() < cost(row, row.source()).dag(), "TRAIN has no target-blind saving");
            examples.add(new TypedPatternGeneralizer.Example(row.source(), best, row.assumptions()));
            var witness = witnesses.addObject().put("id", row.id())
                .put("sourceAst", CODEC.encodeExpression(row.source())).put("selectedAst", CODEC.encodeExpression(best))
                .put("sourceDag", cost(row, row.source()).dag()).put("selectedDag", cost(row, best).dag())
                .put("primitiveGenerated", run.generated());
            witness.set("assumptions", JSON.valueToTree(row.assumptions()));
            witness.put("primitiveRule", ModPowCompositionReplay.verify(row.source(), best, row.assumptions()).orElseThrow().primitiveRule());
            Row broken = shuffled(row); Baseline control = baseline(broken);
            require(control.approved().size() == 2, "SHUFFLED replay failed");
            if (cost(broken, best(broken, control.approved())).dag() < cost(broken, broken.source()).dag()) shuffled++;
        }
        require(shuffled == 0, "relationship-breaking control gained unexpectedly");
        var learned = new TypedPatternGeneralizer().generalize(examples).orElseThrow();
        model.set("sourceTemplate", template(learned.source())); model.set("targetTemplate", template(learned.target()));
        model.put("shuffledWitnessCount", shuffled);
        // Decode the exact format as an untrusted syntax proposal before recording the artifact.
        new TypedOutputPattern(candidate(model));
        return model;
    }

    /** Development-fixture helper; its arbitrary corpora do not qualify as the frozen study. */
    static ObjectNode evaluate(Path frozen, String expectedHash, Path test) throws IOException {
        return evaluate(frozen, expectedHash, test, null);
    }

    private static ObjectNode evaluate(Path frozen, String expectedHash, Path test,
            String expectedCorpusHash) throws IOException {
        byte[] bytes = read(frozen);
        require(expectedHash != null && expectedHash.matches("[0-9a-f]{64}") && hash(bytes).equals(expectedHash),
            "frozen model checksum mismatch"); // Must precede all TEST access.
        ObjectNode model = (ObjectNode) JSON.readTree(bytes);
        var learned = new TypedOutputPattern(candidate(model));
        byte[] testBytes = readCorpus(test, expectedCorpusHash, "TEST"); var rows = rows(testBytes, "TEST");
        var report = JSON.createObjectNode().put("schema", "regelsuche.modpow-transfer-result/v1")
            .put("modelSha256", expectedHash).put("testSha256", hash(testBytes));
        var results = report.putArray("cases"); int positives = 0, transferred = 0; boolean controls = true;
        for (Row row : rows) {
            Baseline baseline = baseline(row); Expr primitiveBest = best(row, baseline.approved());
            var proposals = learned.find(row.source(), TypedOutputPattern.MAXIMUM_ASSIGNMENTS);
            var checked = new ArrayList<Expr>(); boolean unchanged = true;
            var result = results.addObject().put("id", row.id()).put("positive", row.positive())
                .put("sourceAst", CODEC.encodeExpression(row.source()));
            var receipts = result.putArray("checkedApplications");
            for (var proposal : proposals.applications()) {
                var replay = ModPowCompositionReplay.verify(row.source(), proposal.target(), row.assumptions());
                if (replay.isEmpty()) continue;
                for (int i = 0; i < row.source().arguments().size(); i++) {
                    if (!proposal.positions().contains(i)
                            && row.source().arguments().get(i) != proposal.target().arguments().get(i)) unchanged = false;
                }
                checked.add(proposal.target());
                var receipt = receipts.addObject().put("targetAst", CODEC.encodeExpression(proposal.target()))
                    .put("primitiveRule", replay.orElseThrow().primitiveRule());
                receipt.set("assumptions", JSON.valueToTree(replay.orElseThrow().assumptions()));
                receipt.set("positions", JSON.valueToTree(proposal.positions()));
            }
            Expr selected = best(row, checked);
            long original = cost(row, row.source()).dag(), primitiveCost = cost(row, primitiveBest).dag(), selectedCost = cost(row, selected).dag();
            boolean parity = selectedCost == primitiveCost;
            boolean success = proposals.complete() && !checked.isEmpty() && selectedCost < original && parity && unchanged;
            if (row.positive()) { positives++; if (success) transferred++; }
            else controls &= proposals.complete() && checked.isEmpty() && selectedCost == original;
            result.put("sourceDag", original).put("primitiveOptimumDag", primitiveCost).put("learnedDag", selectedCost)
                .put("sourceTree", cost(row, row.source()).tree()).put("learnedTree", cost(row, selected).tree())
                .put("primitiveOptimumParity", parity).put("transferSucceeded", success)
                .put("primitiveGenerated", baseline.generated()).put("primitiveApproved", baseline.approved().size())
                .put("learnedProposed", proposals.applications().size()).put("learnedApproved", checked.size())
                .put("assignmentAttempts", proposals.assignmentAttempts()).put("matcherSteps", proposals.matcherSteps())
                .put("proposalEnumerationComplete", proposals.complete()).put("untouchedOutputsPreserved", unchanged)
                .put("selectedAst", CODEC.encodeExpression(selected));
        }
        require(positives > 0, "no positive TEST cases");
        String verdict = !controls || transferred == 0 ? "RED" : transferred == positives ? "GREEN" : "YELLOW";
        report.put("positiveCases", positives).put("positiveTransfers", transferred).put("controlsClean", controls).put("verdict", verdict);
        report.put("scope", "Structural transfer only; no runtime or fixed-budget speed claim. NO_LEARNING is the complete one-step primitive optimum.");
        return report;
    }

    /** Hash and parse the same bounded byte array: there is no second, unchecked file read. */
    private static byte[] readCorpus(Path corpus, String expectedHash, String split) throws IOException {
        byte[] bytes = read(corpus);
        require(expectedHash == null || hash(bytes).equals(expectedHash), split + " corpus checksum mismatch");
        return bytes;
    }

    private record Baseline(int generated, List<Expr> approved) {}

    private static Baseline baseline(Row row) {
        // This preflight also bounds role-renaming/cost recursion independently of matching.
        requireBounded(row.source());
        require(productSites(row.source()) == 1, "v1 requires exactly one product-exponent site");
        PatternExpr a = PatternExpr.var("A"), u = PatternExpr.var("U"), v = PatternExpr.var("V"), n = PatternExpr.var("N");
        PatternExpr source = PatternExpr.fn("modpow", a, PatternExpr.op(BinaryOperator.MUL, u, v), n);
        var rules = List.<de.regelsuche.transform.RewriteRule>of(
            new PatternRewriteRule(ModPowDagRediscoveryStudy.RULE_LEFT, source,
                PatternExpr.fn("modpow", PatternExpr.fn("modpow", a, u, n), v, n), RewriteKind.NORMALIZE, true, 0, false),
            new PatternRewriteRule(ModPowDagRediscoveryStudy.RULE_RIGHT, source,
                PatternExpr.fn("modpow", PatternExpr.fn("modpow", a, v, n), u, n), RewriteKind.NORMALIZE, true, 0, false));
        var steps = new AstRewriteTransport(rules, 1024, 4096).generate(row.source());
        require(steps.size() == 2 && steps.stream().map(AstRewriteTransport.Step::rule).distinct().count() == 2,
            "primitive enumeration did not expose both factor orders; no completeness claim");
        var approved = new ArrayList<Expr>();
        for (var step : steps) {
            if (ModPowCompositionReplay.verify(row.source(), step.target(), row.assumptions()).isPresent()) approved.add(step.target());
        }
        return new Baseline(steps.size(), List.copyOf(approved));
    }

    private static Expr best(Row row, List<Expr> alternatives) {
        var candidates = new ArrayList<Expr>(); candidates.add(row.source()); candidates.addAll(alternatives);
        return candidates.stream().min(Comparator.comparingLong((Expr e) -> cost(row, e).dag())
            .thenComparingLong(e -> cost(row, e).tree()).thenComparing(CODEC::encodeExpression)).orElseThrow();
    }

    private static ModPowDagRediscoveryStudy.ProgramCost cost(Row row, Expr program) {
        var names = new HashMap<String, String>(); row.roles().forEach((role, name) -> names.put(name, role));
        return ModPowDagRediscoveryStudy.cost(rename(program, names),
            new ModPowDagRediscoveryStudy.BitProfile(row.id(), row.qBits(), row.eBits(), row.rBits()));
    }

    private static Expr rename(Expr expression, Map<String, String> names) {
        if (expression instanceof VariableExpr v) {
            require(names.containsKey(v.name()), "undeclared program variable"); return new VariableExpr(names.get(v.name()));
        }
        if (expression instanceof BinaryExpr b) return new BinaryExpr(rename(b.left(), names), b.operator(), rename(b.right(), names));
        if (expression instanceof FunctionExpr f) return new FunctionExpr(f.name(), f.arguments().stream().map(e -> rename(e, names)).toList());
        return expression;
    }

    private static Row shuffled(Row row) {
        require(row.source().arguments().size() == 2, "TRAIN must have two outputs");
        var outputs = new ArrayList<>(row.source().arguments());
        require(outputs.get(1) instanceof FunctionExpr, "TRAIN retained residue required");
        FunctionExpr residue = (FunctionExpr) outputs.get(1);
        require(residue.name().equals("modpow") && residue.arguments().size() == 3, "TRAIN retained residue required");
        var arguments = new ArrayList<>(residue.arguments()); arguments.set(1, new VariableExpr(row.roles().get("r")));
        outputs.set(1, new FunctionExpr(residue.name(), arguments));
        return row.withSource(new FunctionExpr(row.source().name(), outputs));
    }

    private static int productSites(Expr expression) {
        int count = expression instanceof FunctionExpr f && f.name().equals("modpow") && f.arguments().size() == 3
            && f.arguments().get(1) instanceof BinaryExpr b && b.operator() == BinaryOperator.MUL ? 1 : 0;
        if (expression instanceof BinaryExpr b) count += productSites(b.left()) + productSites(b.right());
        if (expression instanceof FunctionExpr f) for (Expr arg : f.arguments()) count += productSites(arg);
        return count;
    }

    private record Pending(Expr node, int depth) {}
    private static void requireBounded(Expr expression) {
        var pending = new java.util.ArrayDeque<Pending>(); pending.push(new Pending(expression, 0)); int nodes = 0;
        while (!pending.isEmpty()) {
            var current = pending.pop(); require(++nodes <= 512 && current.depth() <= 48, "v1 program structural bound exceeded");
            if (current.node() instanceof BinaryExpr b) {
                pending.push(new Pending(b.left(), current.depth() + 1)); pending.push(new Pending(b.right(), current.depth() + 1));
            } else if (current.node() instanceof FunctionExpr f) {
                require(f.arguments().size() <= 512 - nodes, "v1 program arity bound exceeded");
                for (Expr arg : f.arguments()) pending.push(new Pending(arg, current.depth() + 1));
            }
        }
    }
}
