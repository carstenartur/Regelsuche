package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import java.util.Objects;

/** One executable evolutionary candidate: rule genome plus strategy topology. */
public record EvolutionRewriteProgramCandidate(
    String schema,
    EvolutionGenome genome,
    EvolutionRewriteProgramPlan plan,
    String alphaStructuralHash,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-candidate/v1";

    public EvolutionRewriteProgramCandidate {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported evolution rewrite-program candidate schema");
        }
        Objects.requireNonNull(genome, "genome");
        Objects.requireNonNull(plan, "plan");
        if (!genome.contentHash().equals(plan.genomeHash())) {
            throw new IllegalArgumentException(
                "candidate plan is bound to a different genome");
        }
        EvolutionGenome.requireSha256(
            alphaStructuralHash, "alphaStructuralHash");
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expectedAlpha = EvolutionGenome.hash(alphaMaterial(genome, plan));
        if (!expectedAlpha.equals(alphaStructuralHash)) {
            throw new IllegalArgumentException(
                "candidate alphaStructuralHash does not match genome and plan");
        }
        String expectedContent = EvolutionGenome.hash(canonicalPayload(
            genome, plan, alphaStructuralHash));
        if (!expectedContent.equals(contentHash)) {
            throw new IllegalArgumentException(
                "candidate contentHash does not match genome and plan");
        }
    }

    public static EvolutionRewriteProgramCandidate create(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan plan
    ) {
        Objects.requireNonNull(genome, "genome");
        Objects.requireNonNull(plan, "plan");
        if (!genome.contentHash().equals(plan.genomeHash())) {
            throw new IllegalArgumentException(
                "candidate plan is bound to a different genome");
        }
        String alphaHash = EvolutionGenome.hash(alphaMaterial(genome, plan));
        String payload = canonicalPayload(genome, plan, alphaHash);
        return new EvolutionRewriteProgramCandidate(
            SCHEMA,
            genome,
            plan,
            alphaHash,
            EvolutionGenome.hash(payload));
    }

    public String toCanonicalJson() {
        return render(genome, plan, alphaStructuralHash, contentHash);
    }

    private static String alphaMaterial(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan plan
    ) {
        return SCHEMA
            + "\ngenomeAlphaStructuralHash=" + genome.alphaStructuralHash()
            + "\nplanAlphaStructuralHash=" + plan.alphaStructuralHash();
    }

    private static String canonicalPayload(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan plan,
        String alphaHash
    ) {
        return render(genome, plan, alphaHash, null);
    }

    private static String render(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan plan,
        String alphaHash,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("genomeHash", genome.contentHash())
            .property("genomeAlphaStructuralHash", genome.alphaStructuralHash())
            .property("planHash", plan.contentHash())
            .property("planAlphaStructuralHash", plan.alphaStructuralHash())
            .property("alphaStructuralHash", alphaHash);
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }
}
