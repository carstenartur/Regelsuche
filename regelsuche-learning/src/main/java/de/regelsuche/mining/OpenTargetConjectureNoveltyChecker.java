package de.regelsuche.mining;

import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Strict project-internal novelty check, separate from truth and external novelty. */
public final class OpenTargetConjectureNoveltyChecker {
    public static final String SCHEMA = "regelsuche.open-target-conjecture-novelty/v1";

    private final RulePatternParser parser = new RulePatternParser();

    public NoveltyReport check(
        OpenTargetConjecture conjecture,
        KnownRuleRepository activeInventory,
        List<PriorCandidate> priorCandidates
    ) {
        validate(conjecture);
        Objects.requireNonNull(activeInventory, "activeInventory");
        List<PriorCandidate> prior = priorCandidates == null
            ? List.of()
            : priorCandidates.stream()
                .sorted(Comparator.comparing(PriorCandidate::source)
                    .thenComparing(PriorCandidate::candidateId))
                .toList();
        Signature candidate;
        try {
            candidate = signature(conjecture.leftPattern(), conjecture.rightPattern());
        } catch (IllegalArgumentException exception) {
            return new NoveltyReport(
                SCHEMA,
                conjecture.conjectureId(),
                NoveltyStatus.INCONCLUSIVE_UNPARSEABLE,
                "",
                "",
                activeInventory.all().size(),
                prior.size(),
                List.of(),
                "NOT_EVALUATED",
                "candidate pattern could not be parsed: " + exception.getMessage());
        }

        List<ReferenceCandidate> references = new ArrayList<>();
        activeInventory.all().stream()
            .sorted(Comparator.comparing(KnownRule::id))
            .forEach(rule -> references.add(new ReferenceCandidate(
                "ACTIVE_INVENTORY", rule.id(), rule.leftPattern(), rule.rightPattern())));
        prior.forEach(item -> references.add(new ReferenceCandidate(
            item.source(), item.candidateId(), item.leftPattern(), item.rightPattern())));

        List<NoveltyMatch> matches = new ArrayList<>();
        for (ReferenceCandidate reference : references) {
            Signature known;
            try {
                known = signature(reference.leftPattern(), reference.rightPattern());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (candidate.exact().equals(known.exact())) {
                matches.add(new NoveltyMatch(
                    reference.source(), reference.candidateId(), MatchRelation.EXACT));
            } else if (candidate.alpha().equals(known.alpha())) {
                matches.add(new NoveltyMatch(
                    reference.source(), reference.candidateId(), MatchRelation.ALPHA_EQUIVALENT));
            }
        }
        List<NoveltyMatch> orderedMatches = matches.stream()
            .sorted(Comparator.comparing(NoveltyMatch::source)
                .thenComparing(NoveltyMatch::candidateId)
                .thenComparing(match -> match.relation().name()))
            .toList();
        NoveltyStatus status = orderedMatches.stream()
            .anyMatch(match -> match.relation() == MatchRelation.EXACT)
                ? NoveltyStatus.EXACT_DUPLICATE
                : orderedMatches.isEmpty()
                    ? NoveltyStatus.NOVEL_WITHIN_PROJECT
                    : NoveltyStatus.ALPHA_EQUIVALENT_DUPLICATE;
        return new NoveltyReport(
            SCHEMA,
            conjecture.conjectureId(),
            status,
            hash(candidate.exact()),
            hash(candidate.alpha()),
            activeInventory.all().size(),
            prior.size(),
            orderedMatches,
            "NOT_EVALUATED",
            status == NoveltyStatus.NOVEL_WITHIN_PROJECT
                ? "no exact or alpha-equivalent project candidate was found"
                : "project-internal duplicate evidence is listed in matches");
    }

    private Signature signature(String leftPattern, String rightPattern) {
        RulePatternNode left = parser.parse(leftPattern);
        RulePatternNode right = parser.parse(rightPattern);
        String exact = render(left, new LinkedHashMap<>(), false)
            + "->" + render(right, new LinkedHashMap<>(), false);
        Map<String, String> alphaNames = new LinkedHashMap<>();
        String alpha = render(left, alphaNames, true)
            + "->" + render(right, alphaNames, true);
        return new Signature(exact, alpha);
    }

    private String render(
        RulePatternNode node,
        Map<String, String> alphaNames,
        boolean alphaNormalize
    ) {
        return switch (node.kind()) {
            case PLACEHOLDER -> "P:" + (alphaNormalize
                ? alphaNames.computeIfAbsent(node.name(), ignored -> "p" + alphaNames.size())
                : node.name());
            case VARIABLE -> "V:" + node.name();
            case NUMBER -> "N:" + BigDecimal.valueOf(node.numericValue())
                .stripTrailingZeros().toPlainString();
            case FUNCTION -> "F:" + node.name() + "(" + node.arguments().stream()
                .map(argument -> render(argument, alphaNames, alphaNormalize))
                .reduce((left, right) -> left + "," + right).orElse("") + ")";
            case BINARY -> "B:" + node.operator().name() + "("
                + render(node.left(), alphaNames, alphaNormalize) + ","
                + render(node.right(), alphaNames, alphaNormalize) + ")";
        };
    }

    private static void validate(OpenTargetConjecture conjecture) {
        Objects.requireNonNull(conjecture, "conjecture");
        if (!"OBSERVED_CONJECTURE".equals(conjecture.candidateStatus())
                || !"EQUIVALENCE_PRESERVING_CONVERGENT_PATHS".equals(
                    conjecture.evidenceStatus())
                || conjecture.supportCount() < 2
                || conjecture.distinctAlphaSupport() < 2) {
            throw new IllegalArgumentException(
                "novelty requires an independently supported open-target conjecture");
        }
    }

    private static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum NoveltyStatus {
        NOVEL_WITHIN_PROJECT,
        EXACT_DUPLICATE,
        ALPHA_EQUIVALENT_DUPLICATE,
        INCONCLUSIVE_UNPARSEABLE
    }

    public enum MatchRelation {
        EXACT,
        ALPHA_EQUIVALENT
    }

    public record PriorCandidate(
        String source,
        String candidateId,
        String leftPattern,
        String rightPattern
    ) {
        public PriorCandidate {
            requireText(source, "source");
            requireText(candidateId, "candidateId");
            requireText(leftPattern, "leftPattern");
            requireText(rightPattern, "rightPattern");
        }
    }

    public record NoveltyMatch(String source, String candidateId, MatchRelation relation) {
        public NoveltyMatch {
            requireText(source, "source");
            requireText(candidateId, "candidateId");
            Objects.requireNonNull(relation, "relation");
        }
    }

    public record NoveltyReport(
        String schema,
        String conjectureId,
        NoveltyStatus status,
        String exactSignatureHash,
        String alphaSignatureHash,
        int checkedActiveRules,
        int checkedPriorCandidates,
        List<NoveltyMatch> matches,
        String externalNoveltyStatus,
        String explanation
    ) {
        public NoveltyReport {
            Objects.requireNonNull(status, "status");
            matches = matches == null ? List.of() : List.copyOf(matches);
            externalNoveltyStatus = externalNoveltyStatus == null
                ? "NOT_EVALUATED"
                : externalNoveltyStatus;
            explanation = explanation == null ? "" : explanation;
        }
    }

    private record ReferenceCandidate(
        String source,
        String candidateId,
        String leftPattern,
        String rightPattern
    ) {
    }

    private record Signature(String exact, String alpha) {
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
