package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.json.JsonWriter;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical paired search evidence for one genome on one frozen TRAIN suite. */
public record EvolutionTrainFitnessEvidence(
    String schema,
    String suiteHash,
    String genomeHash,
    List<CaseMeasurement> cases,
    Map<FitnessComponent, Integer> rawComponents,
    List<String> blockers,
    String validationStatus,
    String finalTestStatus,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.evolution-train-fitness/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");

    public EvolutionTrainFitnessEvidence {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported TRAIN fitness schema");
        }
        EvolutionGenome.requireSha256(suiteHash, "suiteHash");
        EvolutionGenome.requireSha256(genomeHash, "genomeHash");
        cases = canonicalCases(cases);
        rawComponents = canonicalComponents(rawComponents);
        blockers = canonicalStrings(blockers);
        if (!"NOT_EVALUATED".equals(validationStatus)
                || !"NOT_EVALUATED".equals(finalTestStatus)) {
            throw new IllegalArgumentException(
                "TRAIN evidence cannot contain later-stage outcomes");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            suiteHash, genomeHash, cases, rawComponents, blockers, null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException("TRAIN fitness contentHash mismatch");
        }
    }

    public static EvolutionTrainFitnessEvidence create(
        String suiteHash,
        EvolutionGenome genome,
        List<CaseMeasurement> cases,
        Map<FitnessComponent, Integer> rawComponents,
        List<String> blockers
    ) {
        Objects.requireNonNull(genome, "genome");
        List<CaseMeasurement> canonicalCases = canonicalCases(cases);
        Map<FitnessComponent, Integer> canonicalComponents =
            canonicalComponents(rawComponents);
        List<String> canonicalBlockers = canonicalStrings(blockers);
        String hash = EvolutionGenome.hash(render(
            suiteHash,
            genome.contentHash(),
            canonicalCases,
            canonicalComponents,
            canonicalBlockers,
            null));
        return new EvolutionTrainFitnessEvidence(
            SCHEMA,
            suiteHash,
            genome.contentHash(),
            canonicalCases,
            canonicalComponents,
            canonicalBlockers,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            hash);
    }

    public String toCanonicalJson() {
        return render(
            suiteHash, genomeHash, cases, rawComponents, blockers, contentHash);
    }

    private static List<CaseMeasurement> canonicalCases(
        List<CaseMeasurement> values
    ) {
        List<CaseMeasurement> result = values == null
            ? List.of()
            : values.stream()
                .map(item -> Objects.requireNonNull(item, "case measurement"))
                .sorted(Comparator.comparing(CaseMeasurement::caseId))
                .toList();
        if (new HashSet<>(result.stream().map(CaseMeasurement::caseId).toList()).size()
                != result.size()) {
            throw new IllegalArgumentException("case measurements must have unique IDs");
        }
        return result;
    }

    private static Map<FitnessComponent, Integer> canonicalComponents(
        Map<FitnessComponent, Integer> values
    ) {
        EnumMap<FitnessComponent, Integer> result =
            new EnumMap<>(FitnessComponent.class);
        if (values != null) {
            values.forEach((component, value) -> {
                Objects.requireNonNull(component, "fitness component");
                Objects.requireNonNull(value, "fitness component value");
                if (value < -1000 || value > 1000) {
                    throw new IllegalArgumentException(
                        "fitness component must be in [-1000,1000]");
                }
                result.put(component, value);
            });
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<String> canonicalStrings(List<String> values) {
        return values == null
            ? List.of()
            : values.stream()
                .map(value -> requireText(value, "blocker"))
                .distinct()
                .sorted()
                .toList();
    }

    private static String render(
        String suiteHash,
        String genomeHash,
        List<CaseMeasurement> cases,
        Map<FitnessComponent, Integer> rawComponents,
        List<String> blockers,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("suiteHash", suiteHash)
            .property("genomeHash", genomeHash)
            .array("cases", array -> cases.forEach(item ->
                array.objectValue(object -> object
                    .property("caseId", item.caseId())
                    .property("familyId", item.familyId())
                    .property("baselineStatus", item.baselineStatus())
                    .property("candidateStatus", item.candidateStatus())
                    .property("baselineReached", item.baselineReached())
                    .property("candidateReached", item.candidateReached())
                    .property("baselinePathLength", item.baselinePathLength())
                    .property("candidatePathLength", item.candidatePathLength())
                    .property("baselineExploredStates",
                        item.baselineExploredStates())
                    .property("candidateExploredStates",
                        item.candidateExploredStates())
                    .property("newlySolved", item.newlySolved())
                    .property("correctnessRegression",
                        item.correctnessRegression()))))
            .object("rawComponents", object -> {
                for (FitnessComponent component : FitnessComponent.values()) {
                    Integer value = rawComponents.get(component);
                    if (value != null) {
                        object.property(component.name(), value);
                    }
                }
            })
            .stringArray("blockers", blockers)
            .property("validationStatus", "NOT_EVALUATED")
            .property("finalTestStatus", "NOT_EVALUATED");
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    public record CaseMeasurement(
        String caseId,
        String familyId,
        String baselineStatus,
        String candidateStatus,
        boolean baselineReached,
        boolean candidateReached,
        int baselinePathLength,
        int candidatePathLength,
        long baselineExploredStates,
        long candidateExploredStates,
        boolean newlySolved,
        boolean correctnessRegression
    ) {
        public CaseMeasurement {
            requireId(caseId, "caseId");
            requireId(familyId, "familyId");
            requireText(baselineStatus, "baselineStatus");
            requireText(candidateStatus, "candidateStatus");
            if (baselinePathLength < -1 || candidatePathLength < -1
                    || baselineExploredStates < 0
                    || candidateExploredStates < 0) {
                throw new IllegalArgumentException("invalid TRAIN case metrics");
            }
            if (newlySolved != (!baselineReached && candidateReached)) {
                throw new IllegalArgumentException("newlySolved is inconsistent");
            }
            if (correctnessRegression != (baselineReached && !candidateReached)) {
                throw new IllegalArgumentException(
                    "correctnessRegression is inconsistent");
            }
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid syntax");
        }
    }
}
