package de.regelsuche.learning;

import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.inventory.RuleInventoryRepository;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.DiscoverySettings;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleCandidateMiner;
import de.regelsuche.mining.SuccessfulTransformationPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Learns reusable macro rules by anti-unifying recurring
 * {@link SuccessfulTransformationPath transformation paths}, accumulates
 * statistics across runs (occurrenceCount, averageImprovement,
 * supportingPathIds, confidenceScore) and promotes rules above a configurable
 * threshold into the {@link RuleInventoryRepository inventory}.
 *
 * <p>The learning loop intentionally reuses the existing
 * {@link RuleCandidateMiner} so anti-unification, parameter relation mining
 * and fresh-example validation are not duplicated. A rule is enabled in the
 * inventory when both {@code occurrenceCount &gt;= minOccurrences} and
 * {@code confidenceScore &gt;= minConfidence} are satisfied; otherwise it
 * stays disabled so {@link de.regelsuche.inventory.InventoryBackedRewriteRuleProvider}
 * does not surface it as a live rewrite rule yet.</p>
 */
public class MacroRuleLearningService {

    public static final int DEFAULT_MIN_OCCURRENCES = 3;
    public static final double DEFAULT_MIN_CONFIDENCE = 0.8;

    private final RuleInventoryRepository inventory;
    private final RuleCandidateMiner miner;
    private final KnownRuleRepository knownRules;
    private final int minOccurrences;
    private final double minConfidence;

    public MacroRuleLearningService(RuleInventoryRepository inventory) {
        this(inventory, new RuleCandidateMiner(new KnownRuleRepository()),
            new KnownRuleRepository(), DEFAULT_MIN_OCCURRENCES, DEFAULT_MIN_CONFIDENCE);
    }

    public MacroRuleLearningService(
        RuleInventoryRepository inventory,
        RuleCandidateMiner miner,
        KnownRuleRepository knownRules,
        int minOccurrences,
        double minConfidence
    ) {
        if (inventory == null || miner == null) {
            throw new IllegalArgumentException("inventory and miner must not be null");
        }
        if (minOccurrences < 1) {
            throw new IllegalArgumentException("minOccurrences must be >= 1");
        }
        if (minConfidence < 0.0 || minConfidence > 1.0) {
            throw new IllegalArgumentException("minConfidence must be in [0,1]");
        }
        this.inventory = inventory;
        this.miner = miner;
        this.knownRules = knownRules == null ? new KnownRuleRepository() : knownRules;
        this.minOccurrences = minOccurrences;
        this.minConfidence = minConfidence;
    }

    /**
     * Learn from the cumulative {@code paths} observed across one or more
     * search runs. Returns the rules touched by this learning step.
     */
    public MacroLearningResult learn(List<SuccessfulTransformationPath> paths) {
        if (paths == null || paths.isEmpty()) {
            return new MacroLearningResult(List.of(), List.of());
        }
        // Make the discovery threshold match this service's so a macro can be
        // mined as soon as we have enough paths (default 3).
        DiscoverySettings settings = new DiscoverySettings(
            true,
            DiscoverySettings.defaults().maxPathLengthForCandidateMining(),
            Math.max(1, minOccurrences),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES
        );
        List<RuleCandidate> candidates = miner.mine(paths, settings);
        // Index paths by id so we can recover the atomic rule-id sequence for
        // each candidate and tag the resulting reusable rule with its domain
        // (equations / inequalities / calculus / linear-algebra / algebra).
        java.util.Map<String, List<String>> rulesByPathId = new java.util.HashMap<>();
        for (SuccessfulTransformationPath p : paths) {
            rulesByPathId.put(p.id(), p.rules());
        }
        de.regelsuche.mining.MacroDomainInferrer inferrer =
            new de.regelsuche.mining.MacroDomainInferrer();
        List<ReusableRule> updated = new ArrayList<>();
        List<ReusableRule> activated = new ArrayList<>();
        for (RuleCandidate candidate : candidates) {
            ReusableRule ruleBefore = findById(candidate.canonicalHash())
                .orElse(null);
            ReusableRule rule = buildOrUpdate(candidate, ruleBefore);
            inventory.save(rule);
            boolean shouldEnable = rule.occurrenceCount() >= minOccurrences
                && rule.confidenceScore() >= minConfidence;
            // Look up the stored rule (save() may have folded duplicates by hash).
            ReusableRule effective = inventory.findAll().stream()
                .filter(r -> r.canonicalHash().equals(rule.canonicalHash()))
                .findFirst()
                .orElse(rule);
            inventory.setEnabled(effective.id(), shouldEnable);
            // Domain tag (Discovery+): infer once from the atomic rule-ids of
            // the supporting paths. Stored as a free-form inventory tag so the
            // UI / inventory queries can filter by domain.
            List<String> mergedRuleIds = new ArrayList<>();
            for (String supportingId : candidate.supportingTransformationIds()) {
                List<String> rules = rulesByPathId.get(supportingId);
                if (rules != null) {
                    mergedRuleIds.addAll(rules);
                }
            }
            String domain = inferrer.inferFromRuleIds(mergedRuleIds);
            inventory.addTag(effective.id(), domain);
            updated.add(effective);
            if (shouldEnable && (ruleBefore == null || !inventory.isEnabled(ruleBefore.id())
                || ruleBefore.occurrenceCount() < minOccurrences)) {
                activated.add(effective);
            }
        }
        return new MacroLearningResult(updated, activated);
    }

    private Optional<ReusableRule> findById(String canonicalHash) {
        for (ReusableRule rule : inventory.findAll()) {
            if (canonicalHash != null && canonicalHash.equals(rule.canonicalHash())) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    private ReusableRule buildOrUpdate(RuleCandidate candidate, ReusableRule existing) {
        Set<String> supportingPathIds = new LinkedHashSet<>();
        if (existing != null) {
            supportingPathIds.addAll(existing.supportingPathIds());
        }
        supportingPathIds.addAll(candidate.supportingTransformationIds());
        int occurrenceCount = supportingPathIds.size();
        // Confidence: VALIDATED_BY_EXAMPLES = 0.6 baseline + 0.1 per supporting path,
        // capped at 1.0. SYMBOLICALLY_VERIFIED bumps to 0.9 minimum.
        double base = switch (candidate.proofStatus()) {
            case SYMBOLICALLY_VERIFIED, FORMALLY_PROVABLE, FORMALLY_PROVED -> 0.9;
            case VALIDATED_BY_EXAMPLES -> 0.6;
            case OBSERVED -> 0.3;
            case REJECTED -> 0.0;
        };
        double confidence = Math.min(1.0, base + 0.1 * Math.max(0, occurrenceCount - 1));
        double averageImprovement = candidate.averageScoreImprovement();
        String id = existing != null ? existing.id() : "macro_" + candidate.canonicalHash();
        Instant createdAt = existing != null ? existing.createdAt() : Instant.now();
        Instant lastUsedAt = existing != null ? existing.lastUsedAt() : null;
        int usageCount = existing != null ? existing.usageCount() : 0;
        return new ReusableRule(
            id,
            candidate.leftPattern(),
            candidate.rightPattern(),
            candidate.parameterRelations(),
            candidate.proofStatus(),
            knownRules.statusFor(candidate.leftPattern(), candidate.rightPattern()),
            candidate.examplesCount(),
            averageImprovement,
            createdAt,
            candidate.canonicalHash(),
            lastUsedAt,
            usageCount,
            occurrenceCount,
            new ArrayList<>(supportingPathIds),
            confidence
        );
    }
}
