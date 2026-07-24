package de.regelsuche.benchmark;

import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.FormationResult;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.FormationStatus;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.MacroCandidate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Selects exactly one reusable macro from TRAIN-only formation evidence.
 *
 * <p>The selector accepts no evaluation task, target or paired-result input. It
 * ranks candidates by descending TRAIN support and uses only stable candidate
 * identity fields as deterministic tie-breakers.</p>
 */
final class CandidateIndependentExactOneMacroSelector {
    static final String POLICY =
        "MAX_TRAIN_SUPPORT_THEN_CANONICAL_HASH_THEN_MACRO_ID";

    private CandidateIndependentExactOneMacroSelector() {
    }

    static Selection select(FormationResult formation) {
        Objects.requireNonNull(formation, "formation");
        if (formation.status() != FormationStatus.SELECTED
                || formation.macros().isEmpty()) {
            throw new IllegalArgumentException(
                "exact-one selection requires selected TRAIN macros");
        }

        MacroCandidate selected = formation.macros().stream()
            .sorted(candidateOrder())
            .findFirst()
            .orElseThrow();
        FormationResult exactOne = new FormationResult(
            FormationStatus.SELECTED,
            List.of(selected),
            formation.replayEvidence(),
            "selected exactly one TRAIN macro under policy " + POLICY);
        return new Selection(POLICY, selected, exactOne);
    }

    private static Comparator<MacroCandidate> candidateOrder() {
        return Comparator
            .<MacroCandidate>comparingInt(
                candidate -> candidate.supportingTraceIds().size())
            .reversed()
            .thenComparing(candidate -> candidate.rule().canonicalHash())
            .thenComparing(MacroCandidate::macroId);
    }

    record Selection(
        String policy,
        MacroCandidate candidate,
        FormationResult exactOneFormation
    ) {
        Selection {
            if (!POLICY.equals(policy)) {
                throw new IllegalArgumentException(
                    "unexpected exact-one candidate policy: " + policy);
            }
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(exactOneFormation, "exactOneFormation");
            if (exactOneFormation.status() != FormationStatus.SELECTED
                    || exactOneFormation.macros().size() != 1
                    || !exactOneFormation.macros().getFirst().equals(candidate)) {
                throw new IllegalArgumentException(
                    "selection must retain exactly the selected candidate");
            }
        }
    }
}
