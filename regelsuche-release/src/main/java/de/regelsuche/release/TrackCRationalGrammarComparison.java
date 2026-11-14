package de.regelsuche.release;

import static de.regelsuche.release.CandidateIndependentRationalAssumptionAdapterMain.evaluationTask;
import static de.regelsuche.release.CandidateIndependentRationalAssumptionAdapterMain.rationalCases;
import static de.regelsuche.release.CandidateIndependentRationalAssumptionAdapterMain.readObject;
import static de.regelsuche.release.CandidateIndependentRationalAssumptionAdapterMain.renderCandidate;
import static de.regelsuche.release.CandidateIndependentRationalAssumptionAdapterMain.renderEvaluation;
import static de.regelsuche.release.CandidateIndependentRationalAssumptionAdapterMain.renderFormationEvidence;
import static de.regelsuche.release.CandidateIndependentRationalAssumptionAdapterMain.renderResourceUse;
import static de.regelsuche.release.CandidateIndependentRationalAssumptionAdapterMain.requireContentHash;
import static de.regelsuche.release.CandidateIndependentRationalAssumptionAdapterMain.semanticHash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.FormationResult;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.FormationSeed;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.ResourceBudget;
import de.regelsuche.transform.RationalNormalizationHypothesisOperator.Forms;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Target-free comparison of the two production rational rewrite forms and their union. */
public final class TrackCRationalGrammarComparison {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PLAN = "cb4aeb6cc607f7b42da33000a493f1402e6534aee5513c1feec68836146dd124";
    private static final String CHALLENGE = "rational-assumption-rewrites";
    private static final ResourceBudget TASK_BUDGET = new ResourceBudget(5, 245, 45);
    private static final List<Forms> GRAMMAR = List.of(Forms.CANCELLATION, Forms.EQUAL_DENOMINATOR, Forms.COMBINED);

    enum Arm { PRODUCTION_FULL_OPERATOR, ENUMERATE_OPERATOR_GRAMMAR, SEEDED_RANDOM_OPERATOR_GRAMMAR }

    private record Frozen(Forms forms, FormationResult formation, ObjectNode receipt) {}

    private TrackCRationalGrammarComparison() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: TrackCRationalGrammarComparison <corpus-directory> <output-json>");
        }
        Files.write(Path.of(args[1]), JSON.writeValueAsBytes(run(Path.of(args[0]))));
    }

    static ObjectNode run(Path directory) throws IOException {
        Path protocol = directory.resolve("track-c-rational-grammar/comparison-plan.json");
        requireDigest(protocol, PLAN);
        ObjectNode plan = readObject(protocol);
        Path benchmarkPath = directory.resolve("benchmark-source.json");
        requireDigest(benchmarkPath, plan.required("benchmarkBytes").asText().substring(7));
        ObjectNode corpus = pinned(directory, "case-corpus.json", plan, "caseCorpusContentHash");
        pinned(directory, "corpus-freeze-receipt.json", plan, "freezeReceiptContentHash");
        pinned(directory, "rational-assumption-primitives.json", plan, "primitiveProfileContentHash");
        String benchmarkId = readObject(benchmarkPath).required("benchmarkId").asText();
        List<FormationSeed> seeds = projectTrain(corpus);

        List<Frozen> arms = new ArrayList<>();
        for (int campaign = 1; campaign <= 4; campaign++) {
            String id = CHALLENGE + String.format(Locale.ROOT, "-campaign-%02d", campaign);
            String seed = semanticHash(JSON.valueToTree(Map.of("benchmarkId", benchmarkId,
                "campaignId", id, "challengeId", CHALLENGE, "index", campaign)));
            for (Arm arm : Arm.values()) {
                arms.add(freeze(seeds, arm, id, seed));
            }
        }

        ObjectNode output = JSON.createObjectNode();
        output.put("schema", "regelsuche.track-c-rational-grammar/v1");
        output.put("planBytes", "sha256:" + PLAN);
        output.set("formationInputs", JSON.valueToTree(seeds));
        ArrayNode freezes = output.putArray("frozenArms");
        arms.forEach(arm -> freezes.add(arm.receipt()));
        output.put("formationFreezeHash", semanticHash(freezes));

        List<ObjectNode> cases = rationalCases(corpus);
        ArrayNode panels = output.putArray("evaluations");
        for (Frozen arm : arms) {
            var adapter = new RationalAssumptionRewriteAdapter(arm.forms());
            ObjectNode panel = panels.addObject();
            panel.put("frozenArmHash", semanticHash(arm.receipt()));
            ArrayNode tasks = panel.putArray("tasks");
            for (ObjectNode item : cases) {
                for (JsonNode entry : item.required("evaluationInput").required("tasks")) {
                    ObjectNode task = (ObjectNode) entry;
                    tasks.add(renderEvaluation(item, task,
                        adapter.evaluate(evaluationTask(task), arm.formation(), TASK_BUDGET)));
                }
            }
        }
        return output;
    }

    static List<FormationSeed> projectTrain(ObjectNode corpus) {
        List<FormationSeed> seeds = new ArrayList<>();
        for (JsonNode item : corpus.required("cases")) {
            if (!CHALLENGE.equals(item.required("challengeId").asText())
                    || !"TRAIN".equals(item.required("split").asText())) {
                continue;
            }
            for (JsonNode seed : item.required("formationInput").required("seedExpressions")) {
                String id = "input-" + (seeds.size() + 1);
                List<String> assumptions = new ArrayList<>();
                seed.required("assumptions").forEach(value -> assumptions.add(value.textValue()));
                seeds.add(new FormationSeed(id, seed.required("expression").textValue(), assumptions, id));
            }
        }
        if (seeds.size() != 4) {
            throw new IllegalArgumentException("expected four TRAIN expressions");
        }
        return List.copyOf(seeds);
    }

    private static Frozen freeze(List<FormationSeed> seeds, Arm arm, String campaign, String seed) {
        ObjectNode row = JSON.createObjectNode();
        row.put("campaignId", campaign);
        row.put("arm", arm.name());
        row.put("configuredSeed", seed);
        ArrayNode proposals = row.putArray("proposals");
        int states = 60;
        int candidates = 60;
        FormationResult selected = null;
        FormationResult last = null;
        Forms program = null;
        for (Forms proposed : schedule(arm, seed)) {
            var adapter = new RationalAssumptionRewriteAdapter(proposed);
            // Remaining resources are forwarded to the real adapter for every proposal and duplicate.
            last = adapter.formCandidate(seeds, new ResourceBudget(5, states, candidates));
            states = last.resourceUse().remainingExploredStates();
            candidates = last.resourceUse().remainingCandidateEvaluations();
            ObjectNode proposal = proposals.addObject();
            proposal.put("program", proposed.name());
            proposal.put("status", last.status().name());
            proposal.put("detail", last.detail());
            proposal.set("resourceUse", renderResourceUse(last.resourceUse()));
            proposal.set("evidence", renderFormationEvidence(last.evidence()));
            if (last.candidate().isPresent()) {
                proposal.set("candidate", renderCandidate(last.candidate().orElseThrow()));
                if (selected == null) {
                    selected = last;
                    program = proposed;
                }
            } else {
                proposal.putNull("candidate");
            }
        }
        row.put("selected", program == null ? null : program.name());
        row.put("executedFormationStates", 60 - states);
        row.put("executedFormationCandidates", 60 - candidates);
        // The adapter's NO_CANDIDATE route records explicit zero-work evaluation rows.
        return selected == null ? new Frozen(Forms.COMBINED, last, row) : new Frozen(program, selected, row);
    }

    private static List<Forms> schedule(Arm arm, String seed) {
        return switch (arm) {
            case PRODUCTION_FULL_OPERATOR -> List.of(Forms.COMBINED);
            case ENUMERATE_OPERATOR_GRAMMAR -> GRAMMAR;
            case SEEDED_RANDOM_OPERATOR_GRAMMAR -> {
                var random = new Random(Long.parseUnsignedLong(seed.substring(7, 23), 16));
                List<Forms> draws = new ArrayList<>();
                for (int index = 0; index < 3; index++) {
                    draws.add(GRAMMAR.get(random.nextInt(GRAMMAR.size())));
                }
                yield List.copyOf(draws);
            }
        };
    }

    private static ObjectNode pinned(Path root, String file, ObjectNode plan, String field) throws IOException {
        var data = readObject(root.resolve(file));
        requireContentHash(data, file);
        if (!data.required("contentHash").equals(plan.required(field))) {
            throw new IllegalArgumentException("input differs from plan: " + file);
        }
        return data;
    }

    private static void requireDigest(Path file, String expected) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            if (!expected.equals(HexFormat.of().formatHex(digest))) {
                throw new IllegalArgumentException("changed registered bytes: " + file);
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
