package de.regelsuche.benchmark;

import de.regelsuche.search.reachability.PatternTargetedLocalBridgeSearch;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Budget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable evidence model and deterministic writer for #745 section A. */
public final class SafePreparationProductQualificationReport {
    public static final String SCHEMA =
        "regelsuche.safe-preparation-product-qualification/v1";
    public static final String CONFIGURATION_ID =
        "direct-v1-vs-safe-preparation-v2-matched-work-v1";
    public static final String DIRECT_PROFILE_ID = "DIRECT_V1";
    public static final String SAFE_PROFILE_ID = "SAFE_PREPARATION_V2";
    public static final String SAFE_WORK_ACCOUNTING_REVISION =
        "regelsuche.safe-preparation-work/analyze-plus-verify/v1";

    private SafePreparationProductQualificationReport() {
    }

    public enum ProductDecision {
        SELECT_SAFE_PREPARATION_V2,
        KEEP_OPT_IN_SAFETY_FAILURE,
        KEEP_OPT_IN_PENDING_PRODUCT_COVERAGE
    }

    public record ExperimentCase(
        String id,
        String family,
        String sourceExpression,
        String targetExpression,
        List<String> sourceAssumptions,
        List<String> requiredAssumptions,
        boolean expectDirectSemanticReachability,
        boolean expectSafeSemanticReachability
    ) {
        public ExperimentCase {
            id = requiredText(id, "id");
            family = requiredText(family, "family");
            sourceExpression = requiredText(sourceExpression, "sourceExpression");
            targetExpression = requiredText(targetExpression, "targetExpression");
            sourceAssumptions = immutableStrings(sourceAssumptions, "sourceAssumptions");
            requiredAssumptions = immutableStrings(requiredAssumptions, "requiredAssumptions");
        }
    }

    public record SafeTelemetry(
        long coordinatorCalls,
        long verificationCalls,
        long verificationFailures,
        long directPrincipalCandidates,
        long preparedPrincipalCandidates,
        long analyzeMechanicalWork,
        long verificationReplayMechanicalWork,
        long chargedCoordinatorMechanicalWork,
        long fallbackExecutions,
        long fallbackGeneratedTransitions,
        long fallbackUniqueExpansions
    ) {
        public SafeTelemetry {
            if (coordinatorCalls < 0 || verificationCalls < 0
                    || verificationFailures < 0
                    || directPrincipalCandidates < 0
                    || preparedPrincipalCandidates < 0
                    || analyzeMechanicalWork < 0
                    || verificationReplayMechanicalWork < 0
                    || chargedCoordinatorMechanicalWork < 0
                    || fallbackExecutions < 0
                    || fallbackGeneratedTransitions < 0
                    || fallbackUniqueExpansions < 0
                    || verificationFailures > verificationCalls
                    || verificationCalls != coordinatorCalls
                    || chargedCoordinatorMechanicalWork
                        != saturatedAdd(analyzeMechanicalWork,
                            verificationReplayMechanicalWork)) {
                throw new IllegalArgumentException(
                    "safe preparation telemetry is inconsistent");
            }
        }

        public static SafeTelemetry none() {
            return new SafeTelemetry(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public record RouteOutcome(
        String profileId,
        String visibleInventoryFingerprint,
        String searchStatus,
        boolean syntacticallyReached,
        boolean semanticReached,
        boolean constructionSafe,
        List<String> effectiveAssumptions,
        List<String> missingAssumptions,
        int edgeDepth,
        int primitiveDepth,
        List<String> appliedRuleIds,
        List<String> primitiveRuleIds,
        long exploredStates,
        long generatedCandidates,
        long chargedTotalWork,
        Budget configuredBudget,
        String technicalFailure,
        SafeTelemetry safeTelemetry
    ) {
        public RouteOutcome {
            profileId = requiredText(profileId, "profileId");
            visibleInventoryFingerprint = requiredHash(
                visibleInventoryFingerprint, "visibleInventoryFingerprint");
            searchStatus = requiredText(searchStatus, "searchStatus");
            effectiveAssumptions = immutableStrings(
                effectiveAssumptions, "effectiveAssumptions");
            missingAssumptions = immutableStrings(
                missingAssumptions, "missingAssumptions");
            appliedRuleIds = immutableStrings(appliedRuleIds, "appliedRuleIds");
            primitiveRuleIds = immutableStrings(
                primitiveRuleIds, "primitiveRuleIds");
            configuredBudget = Objects.requireNonNull(configuredBudget, "configuredBudget");
            technicalFailure = technicalFailure == null ? "" : technicalFailure;
            safeTelemetry = Objects.requireNonNull(safeTelemetry, "safeTelemetry");
            if (edgeDepth < -1 || primitiveDepth < 0
                    || exploredStates < 0 || generatedCandidates < 0
                    || chargedTotalWork < 0
                    || semanticReached && !syntacticallyReached
                    || semanticReached && !constructionSafe
                    || semanticReached && !missingAssumptions.isEmpty()
                    || syntacticallyReached && edgeDepth < 0
                    || !syntacticallyReached && edgeDepth != -1
                    || syntacticallyReached
                        && primitiveDepth != primitiveRuleIds.size()) {
                throw new IllegalArgumentException("route outcome is inconsistent");
            }
        }

        public static RouteOutcome technicalFailure(
            String profileId,
            String visibleInventoryFingerprint,
            Budget budget,
            String failure,
            SafeTelemetry telemetry
        ) {
            return new RouteOutcome(
                profileId,
                visibleInventoryFingerprint,
                "TECHNICAL_FAILURE",
                false,
                false,
                false,
                List.of(),
                List.of(),
                -1,
                0,
                List.of(),
                List.of(),
                0,
                0,
                0,
                budget,
                requiredText(failure, "failure"),
                telemetry);
        }

        public boolean technicalFailurePresent() {
            return !technicalFailure.isEmpty();
        }
    }

    public record CaseResult(
        ExperimentCase experimentCase,
        RouteOutcome direct,
        RouteOutcome safe,
        boolean expectationsSatisfied,
        boolean reachabilityRegression,
        boolean correctnessRegression,
        boolean assumptionRegression,
        boolean newlyReachedBySafe
    ) {
        public CaseResult {
            experimentCase = Objects.requireNonNull(experimentCase, "experimentCase");
            direct = Objects.requireNonNull(direct, "direct");
            safe = Objects.requireNonNull(safe, "safe");
            if (!direct.configuredBudget().equals(safe.configuredBudget())) {
                throw new IllegalArgumentException(
                    "paired routes must use the same search budget");
            }
            if (!direct.visibleInventoryFingerprint().equals(
                    safe.visibleInventoryFingerprint())) {
                throw new IllegalArgumentException(
                    "paired routes must use the same visible inventory");
            }
            if (reachabilityRegression
                    != (direct.semanticReached() && !safe.semanticReached())
                    || newlyReachedBySafe
                        != (!direct.semanticReached() && safe.semanticReached())) {
                throw new IllegalArgumentException(
                    "case comparison flags are inconsistent");
            }
        }
    }

    public record Report(
        String schema,
        String configurationId,
        String repositoryRevision,
        String directProfileId,
        String safeProfileId,
        String safeWorkAccountingRevision,
        boolean verificationReplayCharged,
        String visibleInventoryFingerprint,
        String principalInventoryFingerprint,
        String preparationInventoryFingerprint,
        String exactRegistryFingerprint,
        PatternTargetedLocalBridgeSearch.Budget preparationBudget,
        Budget searchBudget,
        boolean directRouteIsNoPreparationAblation,
        List<CaseResult> cases,
        List<String> productBlockers
    ) {
        public Report {
            if (!SCHEMA.equals(schema)
                    || !CONFIGURATION_ID.equals(configurationId)
                    || !DIRECT_PROFILE_ID.equals(directProfileId)
                    || !SAFE_PROFILE_ID.equals(safeProfileId)
                    || !SAFE_WORK_ACCOUNTING_REVISION.equals(
                        safeWorkAccountingRevision)
                    || !verificationReplayCharged
                    || !directRouteIsNoPreparationAblation) {
                throw new IllegalArgumentException(
                    "qualification identity or accounting revision is invalid");
            }
            requireRevision(repositoryRevision);
            visibleInventoryFingerprint = requiredHash(
                visibleInventoryFingerprint, "visibleInventoryFingerprint");
            principalInventoryFingerprint = requiredHash(
                principalInventoryFingerprint, "principalInventoryFingerprint");
            preparationInventoryFingerprint = requiredHash(
                preparationInventoryFingerprint, "preparationInventoryFingerprint");
            exactRegistryFingerprint = requiredHash(
                exactRegistryFingerprint, "exactRegistryFingerprint");
            preparationBudget = Objects.requireNonNull(
                preparationBudget, "preparationBudget");
            searchBudget = Objects.requireNonNull(searchBudget, "searchBudget");
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            productBlockers = immutableStrings(productBlockers, "productBlockers");
            if (cases.isEmpty()) {
                throw new IllegalArgumentException("qualification case matrix must not be empty");
            }
        }

        public boolean evidenceQualified() {
            return cases.stream().allMatch(CaseResult::expectationsSatisfied)
                && cases.stream().noneMatch(CaseResult::reachabilityRegression)
                && cases.stream().noneMatch(CaseResult::correctnessRegression)
                && cases.stream().noneMatch(CaseResult::assumptionRegression)
                && cases.stream().noneMatch(result ->
                    result.safe().technicalFailurePresent())
                && cases.stream().allMatch(result ->
                    result.safe().safeTelemetry().verificationFailures() == 0)
                && cases.stream().allMatch(result ->
                    result.safe().safeTelemetry().verificationCalls()
                        == result.safe().safeTelemetry().coordinatorCalls())
                && newlyReachedCases() >= 1;
        }

        public long newlyReachedCases() {
            return cases.stream().filter(CaseResult::newlyReachedBySafe).count();
        }

        public long commonSolvedCases() {
            return cases.stream()
                .filter(result -> result.direct().semanticReached())
                .filter(result -> result.safe().semanticReached())
                .count();
        }

        public long directSyntacticButSemanticallyRejectedCases() {
            return cases.stream()
                .filter(result -> result.direct().syntacticallyReached())
                .filter(result -> !result.direct().semanticReached())
                .count();
        }

        public long safeSyntacticButSemanticallyRejectedCases() {
            return cases.stream()
                .filter(result -> result.safe().syntacticallyReached())
                .filter(result -> !result.safe().semanticReached())
                .count();
        }

        public long commonDirectWork() {
            return commonWork(true);
        }

        public long commonSafeWork() {
            return commonWork(false);
        }

        private long commonWork(boolean directRoute) {
            long total = 0;
            for (CaseResult result : cases) {
                if (result.direct().semanticReached()
                        && result.safe().semanticReached()) {
                    total = saturatedAdd(total,
                        directRoute
                            ? result.direct().chargedTotalWork()
                            : result.safe().chargedTotalWork());
                }
            }
            return total;
        }

        public ProductDecision productDecision() {
            if (!evidenceQualified()) {
                return ProductDecision.KEEP_OPT_IN_SAFETY_FAILURE;
            }
            return productBlockers.isEmpty()
                ? ProductDecision.SELECT_SAFE_PREPARATION_V2
                : ProductDecision.KEEP_OPT_IN_PENDING_PRODUCT_COVERAGE;
        }

        public void write(Path outputDirectory) throws IOException {
            Objects.requireNonNull(outputDirectory, "outputDirectory");
            Files.createDirectories(outputDirectory);
            Files.writeString(outputDirectory.resolve("qualification.json"),
                toJson(), StandardCharsets.UTF_8);
            Files.writeString(outputDirectory.resolve("qualification.md"),
                toMarkdown(), StandardCharsets.UTF_8);
        }

        public String toJson() {
            StringBuilder json = new StringBuilder();
            json.append("{\n")
                .append("  \"schema\": \"").append(escape(schema)).append("\",\n")
                .append("  \"configurationId\": \"").append(escape(configurationId)).append("\",\n")
                .append("  \"repositoryRevision\": \"").append(repositoryRevision).append("\",\n")
                .append("  \"directProfileId\": \"").append(directProfileId).append("\",\n")
                .append("  \"safeProfileId\": \"").append(safeProfileId).append("\",\n")
                .append("  \"safeWorkAccountingRevision\": \"").append(safeWorkAccountingRevision).append("\",\n")
                .append("  \"verificationReplayCharged\": ").append(verificationReplayCharged).append(",\n")
                .append("  \"visibleInventoryFingerprint\": \"").append(visibleInventoryFingerprint).append("\",\n")
                .append("  \"principalInventoryFingerprint\": \"").append(principalInventoryFingerprint).append("\",\n")
                .append("  \"preparationInventoryFingerprint\": \"").append(preparationInventoryFingerprint).append("\",\n")
                .append("  \"exactRegistryFingerprint\": \"").append(exactRegistryFingerprint).append("\",\n")
                .append("  \"directRouteIsNoPreparationAblation\": ").append(directRouteIsNoPreparationAblation).append(",\n")
                .append("  \"searchBudget\": ").append(searchBudgetJson(searchBudget)).append(",\n")
                .append("  \"preparationBudgetIdentity\": \"").append(preparationBudget.identity()).append("\",\n")
                .append("  \"evidenceQualified\": ").append(evidenceQualified()).append(",\n")
                .append("  \"productDecision\": \"").append(productDecision()).append("\",\n")
                .append("  \"newlyReachedCases\": ").append(newlyReachedCases()).append(",\n")
                .append("  \"commonSolvedCases\": ").append(commonSolvedCases()).append(",\n")
                .append("  \"commonDirectWork\": ").append(commonDirectWork()).append(",\n")
                .append("  \"commonSafeWork\": ").append(commonSafeWork()).append(",\n")
                .append("  \"directSyntacticButSemanticallyRejectedCases\": ").append(directSyntacticButSemanticallyRejectedCases()).append(",\n")
                .append("  \"safeSyntacticButSemanticallyRejectedCases\": ").append(safeSyntacticButSemanticallyRejectedCases()).append(",\n")
                .append("  \"productBlockers\": ").append(stringArray(productBlockers)).append(",\n")
                .append("  \"cases\": [\n");
            for (int index = 0; index < cases.size(); index++) {
                if (index > 0) {
                    json.append(",\n");
                }
                appendCaseJson(json, cases.get(index));
            }
            return json.append("\n  ]\n}\n").toString();
        }

        public String toMarkdown() {
            StringBuilder out = new StringBuilder();
            out.append("# Safe preparation product qualification\n\n")
                .append("Configuration: `").append(configurationId).append("`  \n")
                .append("Repository revision: `").append(repositoryRevision).append("`  \n")
                .append("Matched search budget: `").append(searchBudget).append("`  \n")
                .append("SAFE work accounting: `").append(safeWorkAccountingRevision).append("`  \n")
                .append("Verification replay charged: **").append(verificationReplayCharged).append("**  \n")
                .append("Evidence qualified: **").append(evidenceQualified()).append("**  \n")
                .append("Product decision: **").append(productDecision()).append("**\n\n")
                .append("DIRECT is the explicit no-preparation ablation. Both routes see the same declared concrete rule inventory and search budget. SAFE alone receives the versioned preparation authority; composed candidates retain their complete primitive lineage. SAFE also pays the deterministic coordinator verification replay rather than treating verification as free.\n\n")
                .append("| Case | DIRECT semantic | SAFE semantic | DIRECT primitives | SAFE primitives | DIRECT work | SAFE work | New by SAFE |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
            for (CaseResult result : cases) {
                out.append("| ").append(result.experimentCase().id())
                    .append(" | ").append(result.direct().semanticReached())
                    .append(" | ").append(result.safe().semanticReached())
                    .append(" | ").append(result.direct().primitiveDepth())
                    .append(" | ").append(result.safe().primitiveDepth())
                    .append(" | ").append(result.direct().chargedTotalWork())
                    .append(" | ").append(result.safe().chargedTotalWork())
                    .append(" | ").append(result.newlyReachedBySafe())
                    .append(" |\n");
            }
            out.append("\nCommon solved cases: `").append(commonSolvedCases())
                .append("`; DIRECT work: `").append(commonDirectWork())
                .append("`; SAFE work: `").append(commonSafeWork()).append("`.  \n")
                .append("New semantic reachability under the matched budget: `")
                .append(newlyReachedCases()).append("` case(s).\n\n")
                .append("## Product-default decision\n\n");
            if (productBlockers.isEmpty()) {
                out.append("No retained product blockers remain in this frozen qualification.\n");
            } else {
                out.append("The bounded evidence can be green while the profile remains opt-in. Retained blockers:\n\n");
                for (String blocker : productBlockers) {
                    out.append("- `").append(blocker).append("`\n");
                }
            }
            out.append("\n## Claim boundary\n\n")
                .append("This is a frozen matched-budget comparison for the retained case matrix. It does not establish wall-clock or asymptotic superiority, universal preparation coverage, global rewrite completeness, or safety for algorithmic/typed principals without an explicit applicability contract.\n");
            return out.toString();
        }

        private static void appendCaseJson(StringBuilder json, CaseResult result) {
            ExperimentCase c = result.experimentCase();
            json.append("    {\n")
                .append("      \"id\": \"").append(escape(c.id())).append("\",\n")
                .append("      \"family\": \"").append(escape(c.family())).append("\",\n")
                .append("      \"sourceExpression\": \"").append(escape(c.sourceExpression())).append("\",\n")
                .append("      \"targetExpression\": \"").append(escape(c.targetExpression())).append("\",\n")
                .append("      \"sourceAssumptions\": ").append(stringArray(c.sourceAssumptions())).append(",\n")
                .append("      \"requiredAssumptions\": ").append(stringArray(c.requiredAssumptions())).append(",\n")
                .append("      \"expectDirectSemanticReachability\": ").append(c.expectDirectSemanticReachability()).append(",\n")
                .append("      \"expectSafeSemanticReachability\": ").append(c.expectSafeSemanticReachability()).append(",\n")
                .append("      \"expectationsSatisfied\": ").append(result.expectationsSatisfied()).append(",\n")
                .append("      \"reachabilityRegression\": ").append(result.reachabilityRegression()).append(",\n")
                .append("      \"correctnessRegression\": ").append(result.correctnessRegression()).append(",\n")
                .append("      \"assumptionRegression\": ").append(result.assumptionRegression()).append(",\n")
                .append("      \"newlyReachedBySafe\": ").append(result.newlyReachedBySafe()).append(",\n")
                .append("      \"direct\": ");
            appendRouteJson(json, result.direct(), 6);
            json.append(",\n      \"safe\": ");
            appendRouteJson(json, result.safe(), 6);
            json.append("\n    }");
        }

        private static void appendRouteJson(StringBuilder json, RouteOutcome route, int indent) {
            String p = " ".repeat(indent);
            json.append("{\n")
                .append(p).append("  \"profileId\": \"").append(route.profileId()).append("\",\n")
                .append(p).append("  \"visibleInventoryFingerprint\": \"").append(route.visibleInventoryFingerprint()).append("\",\n")
                .append(p).append("  \"searchStatus\": \"").append(escape(route.searchStatus())).append("\",\n")
                .append(p).append("  \"syntacticallyReached\": ").append(route.syntacticallyReached()).append(",\n")
                .append(p).append("  \"semanticReached\": ").append(route.semanticReached()).append(",\n")
                .append(p).append("  \"constructionSafe\": ").append(route.constructionSafe()).append(",\n")
                .append(p).append("  \"effectiveAssumptions\": ").append(stringArray(route.effectiveAssumptions())).append(",\n")
                .append(p).append("  \"missingAssumptions\": ").append(stringArray(route.missingAssumptions())).append(",\n")
                .append(p).append("  \"edgeDepth\": ").append(route.edgeDepth()).append(",\n")
                .append(p).append("  \"primitiveDepth\": ").append(route.primitiveDepth()).append(",\n")
                .append(p).append("  \"appliedRuleIds\": ").append(stringArray(route.appliedRuleIds())).append(",\n")
                .append(p).append("  \"primitiveRuleIds\": ").append(stringArray(route.primitiveRuleIds())).append(",\n")
                .append(p).append("  \"exploredStates\": ").append(route.exploredStates()).append(",\n")
                .append(p).append("  \"generatedCandidates\": ").append(route.generatedCandidates()).append(",\n")
                .append(p).append("  \"chargedTotalWork\": ").append(route.chargedTotalWork()).append(",\n")
                .append(p).append("  \"technicalFailure\": \"").append(escape(route.technicalFailure())).append("\",\n")
                .append(p).append("  \"safeTelemetry\": ");
            appendTelemetryJson(json, route.safeTelemetry(), indent + 2);
            json.append("\n").append(p).append("}");
        }

        private static void appendTelemetryJson(StringBuilder json, SafeTelemetry value, int indent) {
            String p = " ".repeat(indent);
            json.append("{\n")
                .append(p).append("  \"coordinatorCalls\": ").append(value.coordinatorCalls()).append(",\n")
                .append(p).append("  \"verificationCalls\": ").append(value.verificationCalls()).append(",\n")
                .append(p).append("  \"verificationFailures\": ").append(value.verificationFailures()).append(",\n")
                .append(p).append("  \"directPrincipalCandidates\": ").append(value.directPrincipalCandidates()).append(",\n")
                .append(p).append("  \"preparedPrincipalCandidates\": ").append(value.preparedPrincipalCandidates()).append(",\n")
                .append(p).append("  \"analyzeMechanicalWork\": ").append(value.analyzeMechanicalWork()).append(",\n")
                .append(p).append("  \"verificationReplayMechanicalWork\": ").append(value.verificationReplayMechanicalWork()).append(",\n")
                .append(p).append("  \"chargedCoordinatorMechanicalWork\": ").append(value.chargedCoordinatorMechanicalWork()).append(",\n")
                .append(p).append("  \"fallbackExecutions\": ").append(value.fallbackExecutions()).append(",\n")
                .append(p).append("  \"fallbackGeneratedTransitions\": ").append(value.fallbackGeneratedTransitions()).append(",\n")
                .append(p).append("  \"fallbackUniqueExpansions\": ").append(value.fallbackUniqueExpansions()).append("\n")
                .append(p).append("}");
        }
    }

    static String searchBudgetJson(Budget budget) {
        return "{"
            + "\"maxPrimitiveSteps\":" + budget.maxPrimitiveSteps() + ","
            + "\"maxExactTheoryWorkUnits\":" + budget.maxExactTheoryWorkUnits() + ","
            + "\"maxExploredStates\":" + budget.maxExploredStates() + ","
            + "\"maxCandidatesPerState\":" + budget.maxCandidatesPerState() + ","
            + "\"maxExpandingSteps\":" + budget.maxExpandingSteps() + ","
            + "\"maxWorkUnits\":" + budget.maxWorkUnits()
            + "}";
    }

    static String stringArray(List<String> values) {
        return values.stream()
            .map(value -> "\"" + escape(value) + "\"")
            .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    static String requiredHash(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 identity");
        }
        return value;
    }

    static void requireRevision(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a lowercase commit SHA");
        }
    }

    static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static List<String> immutableStrings(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must not contain blank values");
        }
        return List.copyOf(values);
    }
}
