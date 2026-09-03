package de.regelsuche.evolution;

import de.regelsuche.evolution.SchematicProofPlan.Hole;
import de.regelsuche.evolution.SchematicProofPlan.HoleSort;
import de.regelsuche.evolution.SchematicProofPlan.Obligation;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.scalar.ExactRationalDomain;
import de.regelsuche.scalar.ExactRationalParseEvidence;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed bindings and checker-outcome references for one schematic
 * proof plan.
 *
 * <p>This record does not load the referenced evidence, prove its contents,
 * compile a plan or create an executable rewrite.</p>
 */
public record SchematicProofPlanResolution(
    String schema,
    String planHash,
    List<String> requiredHoleIds,
    List<String> requiredObligationIds,
    List<HoleBinding> bindings,
    List<ObligationOutcome> outcomes,
    ResolutionState state,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.schematic-proof-plan-resolution/v1";
    private static final Pattern OCCURRENCE_PATH = Pattern.compile(
        "root|(?:0|[1-9][0-9]*)(?:\\.(?:0|[1-9][0-9]*))*");
    private static final Pattern DETAIL_CODE = Pattern.compile(
        "[A-Z][A-Z0-9_]{2,127}");
    private static final int MAX_BINDINGS = 1_024;
    private static final int MAX_OUTCOMES = 2_048;
    private static final int MAX_VALUE_BYTES = 1_000_000;
    private static final int MAX_RESOLUTION_BYTES = 4_000_000;

    public SchematicProofPlanResolution {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported plan-resolution schema");
        }
        planHash = SchematicProofPlan.requireSha256(
            planHash,
            "planHash");
        requiredHoleIds = SchematicProofPlan.normalizeIds(
            requiredHoleIds,
            "requiredHoleIds",
            false);
        requiredObligationIds = SchematicProofPlan.normalizeIds(
            requiredObligationIds,
            "requiredObligationIds",
            false);
        bindings = normalizeBindings(bindings);
        outcomes = normalizeOutcomes(outcomes);
        requireSubset(
            bindings.stream().map(HoleBinding::holeId).toList(),
            requiredHoleIds,
            "bindings");
        requireSubset(
            outcomes.stream().map(ObligationOutcome::obligationId).toList(),
            requiredObligationIds,
            "outcomes");
        ResolutionState expected = deriveState(
            requiredHoleIds,
            requiredObligationIds,
            bindings,
            outcomes);
        if (state != expected) {
            throw new IllegalArgumentException(
                "resolution state does not match contents");
        }
        contentHash = SchematicProofPlan.requireSha256(
            contentHash,
            "contentHash");
        String payload = render(
            planHash,
            requiredHoleIds,
            requiredObligationIds,
            bindings,
            outcomes,
            state,
            null);
        requireSize(payload, MAX_RESOLUTION_BYTES);
        if (!SchematicProofPlan.hash(payload).equals(contentHash)) {
            throw new IllegalArgumentException(
                "contentHash does not match resolution");
        }
        requireSize(
            render(
                planHash,
                requiredHoleIds,
                requiredObligationIds,
                bindings,
                outcomes,
                state,
                contentHash),
            MAX_RESOLUTION_BYTES);
    }

    public static SchematicProofPlanResolution create(
        SchematicProofPlan plan,
        List<HoleBinding> bindings,
        List<ObligationOutcome> outcomes
    ) {
        Objects.requireNonNull(plan, "plan");
        List<HoleBinding> normalizedBindings = normalizeBindings(bindings);
        List<ObligationOutcome> normalizedOutcomes =
            normalizeOutcomes(outcomes);
        Map<String, Hole> holes = indexHoles(plan.holes());
        Map<String, Obligation> obligations =
            indexObligations(plan.obligations());

        for (HoleBinding binding : normalizedBindings) {
            Hole hole = holes.get(binding.holeId());
            if (hole == null) {
                throw new IllegalArgumentException(
                    "binding references unknown hole");
            }
            validateBinding(binding, hole);
        }
        for (ObligationOutcome outcome : normalizedOutcomes) {
            Obligation obligation = obligations.get(
                outcome.obligationId());
            if (obligation == null) {
                throw new IllegalArgumentException(
                    "outcome references unknown obligation");
            }
            validateOutcome(outcome, obligation);
        }

        ResolutionState state = deriveState(
            plan.holeIds(),
            plan.obligationIds(),
            normalizedBindings,
            normalizedOutcomes);
        String payload = render(
            plan.contentHash(),
            plan.holeIds(),
            plan.obligationIds(),
            normalizedBindings,
            normalizedOutcomes,
            state,
            null);
        requireSize(payload, plan.limits().maxCanonicalBytes());
        SchematicProofPlanResolution result =
            new SchematicProofPlanResolution(
                SCHEMA,
                plan.contentHash(),
                plan.holeIds(),
                plan.obligationIds(),
                normalizedBindings,
                normalizedOutcomes,
                state,
                SchematicProofPlan.hash(payload));
        requireSize(
            result.toCanonicalJson(),
            plan.limits().maxCanonicalBytes());
        return result;
    }

    /**
     * Replays all plan-relative structural checks. A true result is neither
     * mathematical proof nor execution authorization.
     */
    public boolean isStructurallyCompleteFor(
        SchematicProofPlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        try {
            SchematicProofPlanResolution replayed = create(
                plan,
                bindings,
                outcomes);
            return equals(replayed)
                && state == ResolutionState.COMPLETE_REFERENCES;
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    public String toCanonicalJson() {
        return render(
            planHash,
            requiredHoleIds,
            requiredObligationIds,
            bindings,
            outcomes,
            state,
            contentHash);
    }

    private static Map<String, Hole> indexHoles(
        List<Hole> holes
    ) {
        Map<String, Hole> result = new HashMap<>();
        holes.forEach(hole -> result.put(hole.id(), hole));
        return result;
    }

    private static Map<String, Obligation> indexObligations(
        List<Obligation> obligations
    ) {
        Map<String, Obligation> result = new HashMap<>();
        obligations.forEach(obligation ->
            result.put(obligation.id(), obligation));
        return result;
    }

    private static String render(
        String planHa²È="25½¥Ù…±¥‘…Ñ•=ÕÉÉ•¹•A…Ñ  (€€€€€€€MÑÉ¥¹œÙ…±Õ”°(€€€€€€€¥¹Ğµ…á•ÁÑ (€€€€¤ì(€€€€€€€¥˜€ …=UII9}AQ ¹µ…Ñ¡•È¡Ù…±Õ”¤¹µ…Ñ¡•Ì ¤¤ì(€€€€€€€€€€€Ñ¡É½Ü¹•Ü%±±•…±ÉÕµ•¹Ñá•ÁÑ¥½¸ (€€€€€€€€€€€€€€€€‰½ÕÉÉ•¹”Á…Ñ ¥Ì¹½Ğ…¹½¹¥…°ˆ¤ì(€€€€€€€ô(€€€€€€€¥¹Ğ‘•ÁÑ €ôÙ…±Õ”¹•ÅÕ…±Ì ‰É½½Ğˆ¤(€€€€€€€€€€€€ü€À(€€€€€€€€€€€€èÙ…±Õ”¹ÍÁ±¥Ğ ‰qp¸ˆ¤¹±•¹Ñ ì(€€€€€€€¥˜€¡‘•ÁÑ €øµ…á•ÁÑ ¤ì(€€€€€€€€€€€Ñ¡É½Ü¹•Ü%±±•…±ÉÕµ•¹Ñá•ÁÑ¥½¸ (€€€€€€€€€€€€€€€€‰½ÕÉÉ•¹”Á…Ñ •á••‘Ì‘•ÁÑ ‰Õ‘•Ğˆ¤ì(€€€€€€€ô(€€€ô((€€€ÁÉ¥Ù…Ñ”ÍÑ…Ñ¥ŒÙ½¥Ù…±¥‘…Ñ•=ÕÉÉ•¹•A…¥È (€€€€€€€MÑÉ¥¹œÙ…±Õ”°(€€€€€€€¥¹Ğµ…á•ÁÑ (€€€€¤ì(€€€€€€€MÑÉ¥¹mtÁ…Ñ¡Ì€ôÙ…±Õ”¹ÍÁ±¥Ğ ‰qqğˆ°€´Ä¤ì(€€€€€€€¥˜€¡Á…Ñ¡Ì¹±•¹Ñ €„ô€È¤ì(€€€€€€€€€€€Ñ¡É½Ü¹•Ü%±±•…±ÉÕµ•¹Ñá•ÁÑ¥½¸ (€€€€€€€€€€€€€€€€‰½ÕÉÉ•¹”Á…¥ÈÉ•ÅÕ¥É•ÌÑİ¼Á…Ñ¡Ìˆ¤ì(€€€€€€€ô(€€€€€€€Ù…±¥‘…Ñ•=ÕÉÉ•¹•A…Ñ ¡Á…Ñ¡ÍlÁt°µ…á•ÁÑ ¤ì(€€€€€€€Ù…±¥‘…Ñ•=ÕÉÉ•¹•A…Ñ ¡Á…Ñ¡ÍlÅt°µ…á•ÁÑ ¤ì(€€€€€€€¥˜€¡½µÁ…É•=ÕÉÉ•¹•A…Ñ¡Ì¡Á…Ñ¡ÍlÁt°Á…Ñ¡ÍlÅt¤€øô€À¤ì(€€€€€€€€€€€Ñ¡É½Ü¹•Ü%±±•…±ÉÕµ•¹Ñá•ÁÑ¥½¸ (€€€€€€€€€€€€€€€€‰½ÕÉÉ•¹”Á…¥ÈµÕÍĞ‰”‘¥ÍÑ¥¹Ğ…¹¹Õµ•É¥…±±ä½É‘•É•ˆ¤ì(€€€€€€€ô(€€€€€€€¥˜€¡½ÕÉÉ•¹•Í=Ù•É±…À¡Á…Ñ¡ÍlÁt°Á…Ñ¡ÍlÅt¤¤ì(€€€€€€€€€€€Ñ¡É½Ü¹•Ü%±±•…±ÉÕµ•¹Ñá•ÁÑ¥½¸ (€€€€€€€€€€€€€€€€‰½ÕÉÉ•¹”Á…¥ÈÁ…Ñ¡ÌµÕÍĞ‰”‘¥Í©½¥¹Ğˆ¤ì(€€€€€€€ô(€€€ô((€€€ÁÉ¥Ù…Ñ”ÍÑ…Ñ¥Œ¥¹Ğ½µÁ…É•=ÕÉÉ•¹•A…Ñ¡Ì (€€€€€€€MÑÉ¥¹œ±•™Ğ°(€€€€€€€MÑÉ¥¹œÉ¥¡Ğ(€€€€¤ì(€€€€€€€¥˜€¡±•™Ğ¹•ÅÕ…±Ì¡É¥¡Ğ¤¤ì(€€€€€€€€€€€É•ÑÕÉ¸€Àì(€€€€€€€ô(€€€€€€€¥˜€¡±•™Ğ¹•ÅÕ…±Ì ‰É½½Ğˆ¤¤ì(€€€€€€€€€€€É•ÑÕÉ¸€´Äì(€€€€€€€ô(€€€€€€€¥˜€¡É¥¡Ğ¹•ÅÕ…±Ì ‰É½½Ğˆ¤¤ì(€€€€€€€€€€€É•ÑÕÉ¸€Äì(€€€€€€€ô(€€€€€€€MÑÉ¥¹mt±•™Ñ%¹‘•á•Ì€ô±•™Ğ¹ÍÁ±¥Ğ ‰qp¸ˆ¤ì(€€€€€€€MÑÉ¥¹mtÉ¥¡Ñ%¹‘•á•Ì€ôÉ¥¡Ğ¹ÍÁ±¥Ğ ‰qp¸ˆ¤ì(€€€€€€€¥¹ĞÍ¡…É•€ô5…Ñ ¹µ¥¸ (€€€€€€€€€€€±•™Ñ%¹‘•á•Ì¹±•¹Ñ °(€€€€€€€€€€€É¥¡Ñ%¹‘•á•Ì¹±•¹Ñ ¤ì(€€€€€€€™½È€¡¥¹Ğ¥¹‘•à€ô€Àì¥¹‘•à€ğÍ¡…É•ì¥¹‘•à¬¬¤ì(€€€€€€€€€€€¥¹Ğ½µÁ…É¥Í½¸€ô½µÁ…É•…¹½¹¥…±%¹‘•à (€€€€€€€€€€€€€€€±•™Ñ%¹‘•á•Ím¥¹‘•át°(€€€€€€€€€€€€€€€É¥¡Ñ%¹‘•á•Ím¥¹‘•át¤ì(€€€€€€€€€€€¥˜€¡½µÁ…É¥Í½¸€„ô€À¤ì(€€€€€€€€€€€€€€€É•ÑÕÉ¸½µÁ…É¥Í½¸ì(€€€€€€€€€€€ô(€€€€€€€ô(€€€€€€€É•ÑÕÉ¸%¹Ñ••È¹½µÁ…É”¡±•™Ñ%¹‘•á•Ì¹±•¹Ñ °É¥¡Ñ%¹‘•á•Ì¹±•¹Ñ ¤ì(€€€ô((€€€ÁÉ¥Ù…Ñ”ÍÑ…Ñ¥Œ¥¹Ğ½µÁ…É•…¹½¹¥…±%¹‘•à (€€€€€€€MÑÉ¥¹œ±•™Ğ°(€€€€€€€MÑÉ¥¹œÉ¥¡Ğ(€€€€¤ì(€€€€€€€¥¹Ğ±•¹Ñ¡½µÁ…É¥Í½¸€ô%¹Ñ••È¹½µÁ…É” (€€€€€€€€€€€±•™Ğ¹±•¹Ñ  ¤°(€€€€€€€€€€€É¥¡Ğ¹±•¹Ñ  ¤¤ì(€€€€€€€É•ÑÕÉ¸±•¹Ñ¡½µÁ…É¥Í½¸€„ô€À(€€€€€€€€€€€€ü±•¹Ñ¡½µÁ…É¥Í½¸(€€€€€€€€€€€€è±•™Ğ¹½µÁ…É•Q¼¡É¥¡Ğ¤ì(€€€ô((€€€ÁÉ¥Ù…Ñ”ÍÑ…Ñ¥Œ‰½½±•…¸½ÕÉÉ•¹•Í=Ù•É±…À (€€€€€€€MÑÉ¥¹œ±•™Ğ°(€€€€€€€MÑÉ¥¹œÉ¥¡Ğ(€€€€¤ì(€€€€€€€É•ÑÕÉ¸±•™Ğ¹•ÅÕ…±Ì ‰É½½Ğˆ¤(€€€€€€€€€€€ñğÉ¥¡Ğ¹•ÅÕ…±Ì ‰É½½Ğˆ¤(€€€€€€€€€€€ñğ±•™Ğ¹ÍÑ…ÉÑÍ]¥Ñ ¡É¥¡Ğ€¬€ˆ¸ˆ¤(€€€€€€€€€€€ñğÉ¥¡Ğ¹ÍÑ…ÉÑÍ]¥Ñ ¡±•™Ğ€¬€ˆ¸ˆ¤ì(€€€ô((€€€ÁÉ¥Ù…Ñ”ÍÑ…Ñ¥ŒÙ½¥Ù…±¥‘…Ñ•=ÕÑ½µ” (€€€€€€€=‰±¥…Ñ¥½¹=ÕÑ½µ”½ÕÑ½µ”°(€€€€€€€=‰±¥…Ñ¥½¸½‰±¥…Ñ¥½¸(€€€€¤ì(€€€€€€€¥˜€ …½ÕÑ½µ”¹¡•­•É…Á…‰¥±¥Ñä ¤¹•ÅÕ…±Ì (€€€€€€€€€€€€€€€½‰±¥…Ñ¥½¸¹¡•­•É…Á…‰¥±¥Ñä ¤¤¤ì(€€€€€€€€€€€Ñ¡É½Ü¹•Ü%±±•…±ÉÕµ•¹Ñá•ÁÑ¥½¸ (€€€€€€€€€€€€€€€€‰¡•­•È…Á…‰¥±¥Ñä‘¥™™•ÉÌ™É½´½‰±¥…Ñ¥½¸ˆ¤ì(€€€€€€€ô(€€€€€€€¥˜€ …½ÕÑ½µ”¹¡•­•ÉI•Ù¥Í¥½¹!…Í  ¤¹•ÅÕ…±Ì (€€€€€€€€€€€€€€€½‰±¥…Ñ¥½¸¹¡•­•ÉI•Ù¥Í¥½¹!…Í  ¤¤¤ì(€€€€€€€€€€€Ñ¡É½Ü¹•Ü%±±•…±ÉÕµ•¹Ñá•ÁÑ¥½¸ (€€€€€€€€€€€€€€€€‰¡•­•ÈÉ•Ù¥Í¥½¸‘¥™™•ÉÌ™É½´½‰±¥…Ñ¥½¸ˆ¤ì(€€€€€€€ô(€€€ô((€€€ÁÉ¥Ù…Ñ”ÍÑ…Ñ¥ŒI•Í½±ÕÑ¥½¹MÑ…Ñ”‘•É¥Ù•MÑ…Ñ” (€€€€€€€1¥ÍĞñMÑÉ¥¹œøÉ•ÅÕ¥É•‘!½±•%‘Ì°(€€€€€€€1¥ÍĞñMÑÉ¥¹œøÉ•ÅÕ¥É•‘=‰±¥…Ñ¥½¹%‘Ì°(€€€€€€€1¥ÍĞñ!½±•	¥¹‘¥¹œø‰¥¹‘¥¹Ì°(€€€€€€€1¥ÍĞñ=‰±¥…Ñ¥½¹=ÕÑ½µ”ø½ÕÑ½µ•Ì(€€€€¤ì(€€€€€€€¥˜€¡½ÕÑ½µ•Ì¹ÍÑÉ•…´ ¤¹…¹å5…Ñ ¡½ÕÑ½µ”€´ø(€€€€€€€€€€€€€€€½ÕÑ½µ”¹ÍÑ…ÑÕÌ ¤€„ô=ÕÑ½µ•MÑ…ÑÕÌ¹=9%I5¤¤ì(€€€€€€€€€€€É•ÑÕÉ¸I•Í½±ÕÑ¥½¹MÑ…Ñ”¹	1=-ì(€€€€€€€ô(€€€€€€€M•ĞñMÑÉ¥¹œø‰½Õ¹€ôM•Ğ¹½Áå=˜ (€€€€€€€€€€€‰¥¹‘¥¹Ì¹ÍÑÉ•…´ ¤¹µ…À¡!½±•	¥¹‘¥¹œèé¡½±•%¤¹Ñ½1¥ÍĞ ¤¤ì(€€€€€€€M•ĞñMÑÉ¥¹œø‘•¥‘•€ôM•Ğ¹½Áå=˜ (€€€€€€€€€€€½ÕÑ½µ•Ì¹ÍÑÉ•…´ ¤(€€€€€€€€€€€€€€€€¹µ…À¡=‰±¥…Ñ¥½¹=ÕÑ½µ”èé½‰±¥…Ñ¥½¹%¤(€€€€€€€€€€€€€€€€¹Ñ½1¥ÍĞ ¤¤ì(€€€€€€€É•ÑÕÉ¸‰½Õ¹¹•ÅÕ…±Ì¡M•Ğ¹½Áå=˜¡É•ÅÕ¥É•‘!½±•%‘Ì¤¤(€€€€€€€€€€€€€€€€˜˜‘•¥‘•¹•ÅÕ…±Ì¡M•Ğ¹½Áå=˜¡É•ÅÕ¥É•‘=‰±¥…Ñ¥½¹%‘Ì¤¤(€€€€€€€€€€€€üI•Í½±ÕÑ¥½¹MÑ…Ñ”¹=5A1Q}II9L(€€€€€€€€€€€€èI•Í½±ÕÑ¥½¹MÑ…Ñ”¹AIQ%0ì(€€€ô((€€€ÁÉ¥Ù…Ñ”ÍÑ…Ñ¥ŒÙ½¥É•ÅÕ¥É•MÕ‰Í•Ğ (€€€€€€€1¥ÍĞñMÑÉ¥¹œø…ÑÕ…°°(€€€€€€€1¥ÍĞñMÑÉ¥¹œøÉ•ÅÕ¥É•°(€€€€€€€MÑÉ¥¹œ¹…µ”(€€€€¤ì(€€€€€€€¥˜€ …¹•Ü!…Í¡M•Ğğø¡É•ÅÕ¥É•¤¹½¹Ñ…¥¹Í±°¡…ÑÕ…°¤¤ì(€€€€€€€€€€€Ñ¡É½Ü¹•Ü%±±•…±ÉÕµ•¹Ñá•ÁÑ¥½¸ (€€€€€€€€€€€€€€€¹…µ”€¬€ˆ½¹Ñ…¥¸Õ¹­¹½İ¸%Ìˆ¤ì(€€€€€€€ô(€€€ô((€€€ÁÉ¥Ù…Ñ”ÍÑ…Ñ¥Œ¥¹ĞÕÑ˜á1•¹Ñ  (€€€€€€€MÑÉ¥¹œÙ…±Õ”(€€€€¤ì(€€€€€€€É•ÑÕÉ¸Ù…±Õ”¹•Ñ	åÑ•Ì¡MÑ…¹‘…É‘¡…ÉÍ•ÑÌ¹UQ|à¤¹±•¹Ñ ì(€€€ô((€€€ÁÉ¥Ù…Ñ”ÍÑ…Ñ¥ŒÙ½¥É•ÅÕ¥É•M¥é” (€€€€€€€MÑÉ¥¹œÙ…±Õ”°(€€€€€€€¥¹Ğµ…á¥µÕµ	åÑ•Ì(€€€€¤ì(€€€€€€€¥˜€¡ÕÑ˜á1•¹Ñ ¡Ù…±Õ”¤€øµ…á¥µÕµ	åÑ•Ì¤ì(€€€€€€€€€€€Ñ¡É½Ü¹•Ü%±±•…±ÉÕµ•¹Ñá•ÁÑ¥½¸ (€€€€€€€€€€€€€€€€‰…¹½¹¥…°É•Í½±ÕÑ¥½¸•á••‘Ì‰åÑ”±¥µ¥Ğˆ¤ì(€€€€€€€ô(€€€ô((€€€ÁÕ‰±¥Œ•¹Õ´I•Í½±ÕÑ¥½¹MÑ…Ñ”ì(€€€€€€€AIQ%0°(€€€€€€€	1=-°(€€€€€€€=5A1Q}II9L(€€€ô((€€€ÁÕ‰±¥Œ•¹Õ´=ÕÑ½µ•MÑ…ÑÕÌì(€€€€€€€=9%I5°(€€€€€€€IUQ°(€€€€€€€U9-9=]8°(€€€€€€€U9MUAA=IQ°(€€€€€€€	UQ}%9=91UM%Y°(€€€€€€€II=H(€€€ô((€€€ÁÕ‰±¥ŒÉ•½É!½±•	¥¹‘¥¹œ (€€€€€€€MÑÉ¥¹œ¡½±•%°(€€€€€€€!½±•M½ÉĞÍ½ÉĞ°(€€€€€€€MÑÉ¥¹œ…¹½¹¥…±Y…±Õ”°(€€€€€€€MÑÉ¥¹œ•Ù¥‘•¹•!…Í (€€€€¤ì(€€€€€€€ÁÕ‰±¥Œ!½±•	¥¹‘¥¹œì(€€€€€€€€€€€¡½±•%€ôM¡•µ…Ñ¥AÉ½½™A±…¸¹É•ÅÕ¥É•% (€€€€€€€€€€€€€€€¡½±•%°(€€€€€€€€€€€€€€€€‰¡½±•%ˆ¤ì(€€€€€€€€€€€Í½ÉĞ€ô=‰©•ÑÌ¹É•ÅÕ¥É•9½¹9Õ±°¡Í½ÉĞ°€‰Í½ÉĞˆ¤ì(€€€€€€€€€€€¥˜€¡…¹½¹¥…±Y…±Õ”€ôô¹Õ±°(€€€€€€€€€€€€€€€€€€€ñğ…¹½¹¥…±Y…±Õ”¹¥Í	±…¹¬ ¤(€€€€€€€€€€€€€€€€€€€ñğ€……¹½¹¥…±Y…±Õ”¹•ÅÕ…±Ì (€€€€€€€€€€€€€€€€€€€€€€€…¹½¹¥…±Y…±Õ”¹ÍÑÉ¥À ¤¤¤ì(€€€€€€€€€€€€€€€Ñ¡É½Ü¹•Ü%±±•…±ÉÕµ•¹Ñá•ÁÑ¥½¸ (€€€€€€€€€€€€€€€€€€€€‰…¹½¹¥…±Y…±Õ”µÕÍĞ‰”¹½¹‰±…¹¬…¹ÑÉ¥µµ•ˆ¤ì(€€€€€€€€€€€ô(€€€€€€€€€€€¥˜€¡…¹½¹¥…±Y…±Õ”¹¡…ÉÌ ¤¹…¹å5…Ñ  (€€€€€€€€€€€€€€€€€€€¡…É…Ñ•Èèé¥Í%M=½¹ÑÉ½°¤¤ì(€€€€€€€€€€€€€€€Ñ¡É½Ü¹•Ü%±±•…±ÉÕµ•¹Ñá•ÁÑ¥½¸ (€€€€€€€€€€€€€€€€€€€€‰…¹½¹¥…±Y…±Õ”½¹Ñ…¥¹Ì„½¹ÑÉ½°¡…É…Ñ•Èˆ¤ì(€€€€€€€€€€€ô(€€€€€€€€€€€¥˜€¡ÕÑ˜á1•¹Ñ ¡…¹½¹¥…±Y…±Õ”¤€ø5a}Y1U}	eQL¤ì(€€€€€€€€€€€€€€€Ñ¡É½Ü¹•Ü%±±•…±ÉÕµ•¹Ñá•ÁÑ¥½¸ (€€€€€€€€€€€€€€€€€€€€‰…¹½¹¥…±Y…±Õ”•á••‘Ì…‰Í½±ÕÑ”‰åÑ”±¥µ¥Ğˆ¤ì(€€€€€€€€€€€ô(€€€€€€€€€€€•Ù¥‘•¹•!…Í €ôM¡•µ…Ñ¥AÉ½½™A±…¸¹É•ÅÕ¥É•M¡„ÈÔØ (€€€€€€€€€€€€€€€•Ù¥‘•¹•!…Í °(€€€€€€€€€€€€€€€€‰•Ù¥‘•¹•!…Í ˆ¤ì(€€€€€€€ô(€€€ô((€€€ÁÕ‰±¥ŒÉ•½É=‰±¥…Ñ¥½¹=ÕÑ½µ” (€€€€€€€MÑÉ¥¹œ½‰±¥…Ñ¥½¹%°(€€€€€€€=ÕÑ½µ•MÑ…ÑÕÌÍÑ…ÑÕÌ°(€€€€€€€MÑÉ¥¹œ¡•­•É…Á…‰¥±¥Ñä°(€€€€€€€MÑÉ¥¹œ¡•­•ÉI•Ù¥Í¥½¹!…Í °(€€€€€€€MÑÉ¥¹œ¡•­•Éá•ÕÑ¥½¹!…Í °(€€€€€€€MÑÉ¥¹œ‘•Ñ…¥±½‘”(€€€€¤ì(€€€€€€€ÁÕ‰±¥Œ=‰±¥…Ñ¥½¹=ÕÑ½µ”ì(€€€€€€€€€€€½‰±¥…Ñ¥½¹%€ôM¡•µ…Ñ¥AÉ½½™A±…¸¹É•ÅÕ¥É•% (€€€€€€€€€€€€€€€½‰±¥…Ñ¥½¹%°(€€€€€€€€€€€€€€€€‰½‰±¥…Ñ¥½¹%ˆ¤ì(€€€€€€€€€€€ÍÑ…ÑÕÌ€ô=‰©•ÑÌ¹É•ÅÕ¥É•9½¹9Õ±°¡ÍÑ…ÑÕÌ°€‰ÍÑ…ÑÕÌˆ¤ì(€€€€€€€€€€€¡•­•É…Á…‰¥±¥Ñä€ôM¡•µ…Ñ¥AÉ½½™A±…¸¹É•ÅÕ¥É•Q½­•¸ (€€€€€€€€€€€€€€€¡•­•É…Á…‰¥±¥Ñä°(€€€€€€€€€€€€€€€€‰¡•­•É…Á…‰¥±¥Ñäˆ¤ì(€€€€€€€€€€€¡•­•ÉI•Ù¥Í¥½¹!…Í €ô(€€€€€€€€€€€€€€€M¡•µ…Ñ¥AÉ½½™A±…¸¹É•ÅÕ¥É•M¡„ÈÔØ (€€€€€€€€€€€€€€€€€€€¡•­•ÉI•Ù¥Í¥½¹!…Í °(€€€€€€€€€€€€€€€€€€€€‰¡•­•ÉI•Ù¥Í¥½¹!…Í ˆ¤ì(€€€€€€€€€€€¡•­•Éá•ÕÑ¥½¹!…Í €ô(€€€€€€€€€€€€€€€M¡•µ…Ñ¥AÉ½½™A±…¸¹É•ÅÕ¥É•M¡„ÈÔØ (€€€€€€€€€€€€€€€€€€€¡•­•Éá•ÕÑ¥½¹!…Í °(€€€€€€€€€€€€€€€€€€€€‰¡•­•Éá•ÕÑ¥½¹!…Í ˆ¤ì(€€€€€€€€€€€¥˜€¡‘•Ñ…¥±½‘”€ôô¹Õ±°(€€€€€€€€€€€€€€€€€€€ñğ€…Q%1}=¹µ…Ñ¡•È¡‘•Ñ…¥±½‘”¤¹µ…Ñ¡•Ì ¤¤ì(€€€€€€€€€€€€€€€Ñ¡É½Ü¹•Ü%±±•…±ÉÕµ•¹Ñá•ÁÑ¥½¸ (€€€€€€€€€€€€€€€€€€€€‰‘•Ñ…¥±½‘”µÕÍĞ‰”…¸ÕÁÁ•É…Í”ÍÑ…‰±”½‘”ˆ¤ì(€€€€€€€€€€€ô(€€€€€€€ô(€€€ô)ô(