package de.regelsuche.discovery.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.domain.ExpressionRewriteDiscoveryDomain.ExpressionCandidate;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactParsedUnivariatePolynomialView;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Assesses an immutable workspace, independently of the discovery receipt's original authority. */
public final class DomainSnapshotAssessment {
    public static final String SCHEMA = "regelsuche.domain-snapshot-assessment/v1";
    private final String canonicalJson;
    private final String contentHash;

    private DomainSnapshotAssessment(ObjectNode value) {
        contentHash = DomainCanonical.sha256(DomainExportWorkspace.write(value));
        value.put("contentHash", contentHash);
        canonicalJson = DomainExportWorkspace.write(value);
    }

    public String toCanonicalJson() { return canonicalJson; }
    public String contentHash() { return contentHash; }

    public static DomainSnapshotAssessment assess(DomainExportWorkspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        var root = DomainExportWorkspace.JSON.createObjectNode().put("schema", SCHEMA);
        root.set("source", DomainDownstreamValidation.sourceBinding(workspace));
        var validation = gate(root, "validation");
        var counterexamples = gate(root, "counterexamples");
        var proof = gate(root, "proof");
        var ablation = gate(root, "ablation");
        boolean reproduced = false;
        if (!workspace.replaySupported()) {
            root.put("replayStatus", workspace.scopedLogicDomain() ? "REPLAY_NOT_CONFIGURED" : "OUTSIDE_REPLAY_BUDGET");
        } else {
            try {
                var observed = workspace.replayObserved();
                var replay = observed.result();
                reproduced = true;
                root.put("replayStatus", "IDENTICAL_CANONICAL_EVIDENCE");
                JsonNode source = DomainExportWorkspace.read(replay.evidence().toCanonicalJson());
                root.set("resources", source.required("resources"));
                root.set("certificate", source.required("certificate"));
                counterexamples.put("status", "REPRODUCED_ATTEMPTS");
                counterexamples.set("attempts", source.required("candidateAttempts"));
                observed.firstRefutation().ifPresent(attempt ->
                    counterexamples.set("independentCheck", DomainRejectedCandidateCheck.check(attempt)));
                validation.put("status", replay.evidence().outcome().name()).put("method", "EXACT_DISCOVERY_REPLAY");
                if (replay.selectedCandidate().isEmpty()) {
                    proof.put("status", "NO_SELECTED_CANDIDATE");
                } else if (replay.selectedCandidate().orElseThrow() instanceof ExpressionCandidate expression) {
                    expressionProof(workspace, expression, proof, validation);
                } else {
                    var model = DomainExportWorkspace.read(DomainModelProof.prove(workspace).toCanonicalJson());
                    proof.put("status", model.required("status").asText()).set("modelReceipt", model);
                    if (model.path("finiteCheck").isObject()) {
                        validation.put("status", model.at("/finiteCheck/status").asText()).put("method", DomainFiniteDataCheck.SCHEMA);
                    }
                }
                var control = DomainExportWorkspace.read(workspace.depthZeroControl().toCanonicalJson());
                ablation.put("status", "EXECUTED").put("control", "MAX_DEPTH_ZERO");
                for (String field : new String[]{"budget", "resources", "outcome", "selectedCandidateHash", "contentHash"}) {
                    ablation.set(field.equals("contentHash") ? "controlEvidenceHash" : field, control.required(field));
                }
            } catch (DomainExportWorkspace.ReplayMismatch mismatch) {
                root.put("replayStatus", "REPLAY_MISMATCH").put("actualEvidenceHash", mismatch.actual().contentHash());
            }
        }
        var handoff = DiscoveryLifecycleHandoff.from(workspace.evidence());
        gate(root, "lifecycle").put("status", lifecycle(reproduced, validation, proof))
            .put("fromHandoffHash", handoff.contentHash()).put("sourceStage", handoff.stage().name())
            .put("sourceDisposition", handoff.disposition().name());
        gate(root, "release").put("status", reproduced ? "REPRODUCIBLE_RESEARCH_RECORD" : "BLOCKED")
            .put("scope", "Retained source, resource comparison and delimited checker findings");
        boolean selected = reproduced && !workspace.evidence().selectedCandidateHash().isEmpty();
        var novelty = gate(root, "externalNovelty");
        novelty.put("status", selected ? "POST_FREEZE_REVIEW_REQUIRED" : "NO_VERIFIED_SUBJECT");
        if (selected) {
            novelty.putObject("subject").put("runId", workspace.runId())
                .put("candidateId", workspace.evidence().selectedCandidateHash()).put("artifactHash", workspace.evidence().contentHash());
        }
        var promotion = gate(root, "promotion").put("status", "BLOCKED");
        var blockers = promotion.putArray("blockers");
        if (!selected) blockers.add("NO_VERIFIED_CANDIDATE");
        if (!proof.path("status").asText().startsWith("PROVED_")) blockers.add("NO_CHECKED_DOMAIN_PROOF");
        blockers.add("MISSING_INDEPENDENT_PROMOTION_RECORD");
        gate(root, "publicEvidence").put("status", "BLOCKED").putArray("blockers")
            .add("MISSING_PUBLIC_PROMOTION_RECORD").add("MISSING_EXTERNAL_NOVELTY_DECISION");
        return new DomainSnapshotAssessment(root);
    }

    private static String lifecycle(boolean reproduced, ObjectNode validation, ObjectNode proof) {
        if (!reproduced) return "SOURCE_NOT_REPRODUCED";
        return switch (proof.required("status").asText()) {
            case "PROVED_POLYNOMIAL_IDENTITY" -> "CHECKED_IDENTITY";
            case "PROVED_MODEL" -> "CHECKED_CONDITIONAL_MODEL";
            default -> switch (validation.required("status").asText()) {
                case "REFUTED", "REFUTED_FINITE_DATA" -> "REPRODUCED_REFUTATION";
                case "CONFIRMED", "CONFIRMED_FINITE_DATA" -> "VALIDATED_WITHIN_SOURCE_SCOPE";
                default -> "REPRODUCED_OBSERVATION";
            };
        };
    }

    private static ObjectNode gate(ObjectNode root, String name) {
        return root.putObject(name).put("contract", SCHEMA + "#" + name).put("status", "NOT_EVALUATED");
    }

    private static void expressionProof(DomainExportWorkspace workspace, ExpressionCandidate candidate,
            ObjectNode proof, ObjectNode validation) {
        String original = workspace.evidence().seed().payload();
        String requested = workspace.evidence().domainEvidence().properties().get("expectedTarget");
        proof.put("checker", ExactParsedUnivariatePolynomialView.VIEW_ID)
            .put("scope", "Polynomial identity over Q with polynomial-ring powers and nonzero constant denominators")
            .put("originalSource", original).put("requestedTarget", requested)
            .put("selectedSource", candidate.sourceExpression()).put("selectedTarget", candidate.targetExpression());
        var assumptions = proof.putArray("rewriteAssumptions");
        candidate.assumptions().forEach(assumptions::add);
        var checks = proof.putArray("normalForms");
        var results = new LinkedHashMap<String, ExactParsedUnivariatePolynomialView.Analysis>();
        var parser = new ExpressionParser();
        var checker = new ExactParsedUnivariatePolynomialView();
        // The original seed is parsed afresh. Formatting performed by the discovery engine cannot authorize equality.
        for (String text : new String[]{original, candidate.sourceExpression(), candidate.targetExpression(), requested}) {
            if (results.containsKey(text)) continue;
            if (text.length() > 256) {
                proof.put("status", "OUTSIDE_PROOF_BUDGET");
                return;
            }
            var analysis = checker.analyze(parser.parseExactTerm(text));
            results.put(text, analysis);
            var item = checks.addObject().put("input", text).put("status", analysis.status().name())
                .put("detail", analysis.detailCode()).put("certificateHash", analysis.certificateHash());
            if (analysis.supported()) item.put("normalForm", analysis.polynomial().orElseThrow().canonicalMaterial());
        }
        if (results.values().stream().anyMatch(analysis -> !analysis.supported())) {
            proof.put("status", "OUTSIDE_CHECKED_POLYNOMIAL_FRAGMENT");
            return;
        }
        var baseline = results.get(original).polynomial().orElseThrow();
        if (results.values().stream().anyMatch(analysis -> !baseline.equals(analysis.polynomial().orElseThrow()))) {
            proof.put("status", "NORMAL_FORMS_DIFFER");
            validation.put("status", "INDEPENDENT_CHECK_NOT_CONFIRMED").put("method", ExactParsedUnivariatePolynomialView.VIEW_ID);
            return;
        }
        proof.put("status", "PROVED_POLYNOMIAL_IDENTITY");
        validation.put("status", "CONFIRMED_POLYNOMIAL_IDENTITY").put("method", ExactParsedUnivariatePolynomialView.VIEW_ID);
    }
}
