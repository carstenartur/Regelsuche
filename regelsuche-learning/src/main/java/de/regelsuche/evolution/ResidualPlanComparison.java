package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.evolution.ResidualPlanController.Arm;
import de.regelsuche.evolution.ResidualPlanController.Candidate;
import de.regelsuche.evolution.ResidualPlanController.Report;
import de.regelsuche.solver.ir.SolverIr;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Source-only acquisition and evaluation. Historical formulas are supplied only to the separate observer. */
public final class ResidualPlanComparison {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final String PROTOCOL = "a672a8c78ee28734bb779f9870c9a6e321078f13";
    private static final List<String> TRAIN = List.of("x^2+x^4+x^6+x^8", "4*y^2+9*y^4+16*y^6+36*y^8", "a^2+b^2+c^2+d^2");
    record Score(int mask, boolean recall, long admittedFailures) { }
    record Plan(String schema, String protocol, String trainingHash, int mask, boolean trainingComplete, long acquisitionWork) { }
    private ResidualPlanComparison() { }

    public static void main(String[] args) throws Exception {
        var dir = Path.of(args[1]);
        if (args.length == 2 && args[0].equals("train")) {
            train(dir);
            System.out.println(SolverIr.sha256(Files.readString(dir.resolve("plan.json"))));
        } else if (args.length == 3) evaluate(args[0], dir, args[2]);
        else throw new IllegalArgumentException("train DIRECTORY | validation|final DIRECTORY TRUSTED_PLAN_HASH");
    }

    static void train(Path dir) throws Exception {
        var scores = new ArrayList<Score>();
        for (int mask = 0; mask < 8; mask++) scores.add(new Score(mask, true, 0));
        var observations = new ArrayList<ObjectNode>();
        long work = 0;
        for (String source : TRAIN) {
            var gold = new ResidualPlanController(source).run(Arm.ALL_AT_ONCE, 0, observations.size());
            var wanted = gold.candidates().stream().map(Candidate::root).sorted().toList();
            boolean oracleComplete = gold.status().equals("COMPLETE");
            var item = caseHeader(source, gold);
            var attempts = item.putArray("arms");
            attempts.add(report(gold));
            work = Math.addExact(work, gold.work());
            for (int mask = 0; mask < 8; mask++) {
                var result = new ResidualPlanController(source).run(Arm.LEARNED, mask, observations.size());
                attempts.add(report(result).put("mask", mask));
                work = Math.addExact(work, result.work());
                var seen = result.candidates().stream().map(Candidate::root).sorted().toList();
                var before = scores.get(mask);
                boolean retains = before.recall() && oracleComplete && result.status().equals("COMPLETE") && wanted.equals(seen);
                long rejected = result.decisions().stream().filter(d -> d.status().equals("EXACT_REJECTED")).count();
                scores.set(mask, new Score(mask, retains, before.admittedFailures() + rejected));
            }
            observations.add(item);
        }
        var winner = scores.stream().filter(Score::recall).min(Comparator.comparingLong(Score::admittedFailures)
            .thenComparingInt(s -> Integer.bitCount(s.mask())).thenComparingInt(Score::mask));
        String data = encode(Map.of("protocol", PROTOCOL, "split", "TRAIN", "cases", observations, "maskScores", scores));
        save(dir, "train.json", data);
        save(dir, "plan.json", encode(new Plan("residual-controller-mask/v1", PROTOCOL, SolverIr.sha256(data),
            winner.map(Score::mask).orElse(0), winner.isPresent(), work)));
    }

    static void evaluate(String split, Path dir, String hash) throws Exception {
        String frozen = Files.readString(dir.resolve("plan.json"));
        if (!SolverIr.sha256(frozen).equals(hash)) throw new IllegalArgumentException("residual plan hash differs");
        var plan = JSON.readValue(frozen, Plan.class);
        if (!plan.protocol().equals(PROTOCOL) || !plan.trainingHash().equals(SolverIr.sha256(Files.readString(dir.resolve("train.json")))))
            throw new IllegalArgumentException("residual training evidence or protocol differs");
        var sources = switch (split) {
            case "validation" -> List.of("u^2+u^6+u^10+u^14", "r^2+s^2+t^2+v^2");
            case "final" -> List.of("(a^2+b^2)*(c^2+d^2)", "(4*p^2+9*q^2)*(25*r^2+49*s^2)", "j^2+k^2+l^2+m^2");
            default -> throw new IllegalArgumentException("unexpected residual split");
        };
        var observations = new ArrayList<ObjectNode>();
        for (String source : sources) {
            ObjectNode item = null;
            for (Arm arm : Arm.values()) {
                var result = new ResidualPlanController(source).run(arm, plan.mask(), observations.size());
                if (item == null) item = caseHeader(source, result);
                item.withArray("arms").add(report(result));
            }
            observations.add(item);
        }
        save(dir, split + ".json", encode(Map.of("plan", hash, "split", split, "cases", observations,
            "chargeTrainingOnceToLearned",plan.acquisitionWork())));
    }

    private static ObjectNode caseHeader(String source, Report run) {
        ObjectNode item = JSON.createObjectNode().put("source", source).put("prepared", run.preparedSource());
        item.set("localEffects", JSON.valueToTree(run.localEffects()));
        return item;
    }
    private static ObjectNode report(Report report) {
        ObjectNode node = JSON.valueToTree(report);
        node.remove(List.of("localEffects", "preparedSource"));
        return node;
    }
    private static String encode(Object value) throws Exception {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
    }
    static void save(Path dir, String name, String text) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        if (Files.exists(file) && Files.readString(file).equals(text)) return;
        Files.writeString(file, text, StandardOpenOption.CREATE_NEW);
    }
}
