package de.regelsuche.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import de.regelsuche.discovery.domain.SourceBoundReceiptFiles;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunWorkspace;
import de.regelsuche.radar.AstRuleRadarService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

final class CandidateEvidenceRoute {
    private static final JsonMapper JSON = new JsonMapper();

    static void handle(HttpExchange exchange, Path runs, String suffix, AstRuleRadarService radar, int limit) throws IOException {
        try {
            boolean submit = exchange.getRequestMethod().equals("POST");
            var request = submit ? JSON.valueToTree(RetainedRunActions.readJson(exchange, Math.min(limit, 8192))) : null;
            String[] parts = suffix.split("/", -1);
            if (!parts[0].matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid run digest");
            var run = RepresentationDiscoveryRunWorkspace.findRetained(runs, "sha256:" + parts[0]).orElseThrow();
            var files = new SourceBoundReceiptFiles(runs.resolveSibling(runs.getFileName() + "-candidate-reviews"), 512);
            var receipt = submit ? create(request, runs, run, radar, files) : reopen(runs, run, radar, files, parts[2]);
            String digest = receipt.required("contentHash").asText().substring(7);
            exchange.getResponseHeaders().set("X-Regelsuche-Run-Id", run.runId());
            exchange.getResponseHeaders().set("ETag", "\"" + digest + "\"");
            exchange.getResponseHeaders().set("Location", "/api/discovery-runs/" + parts[0] + "/candidate-evidence/" + digest);
            RetainedRunActions.send(exchange, 200, receipt.toString());
        } catch (NoSuchElementException missing) {
            RetainedRunActions.error(exchange, 404, "REVIEW_SOURCE_ABSENT", "retained source or occurrence not found");
        } catch (IllegalArgumentException invalid) {
            RetainedRunActions.error(exchange, 400, "INVALID_REVIEW", invalid.getMessage());
        }
    }

    private static ObjectNode create(JsonNode body, Path runs, RepresentationDiscoveryRunWorkspace run,
            AstRuleRadarService radar, SourceBoundReceiptFiles files) throws IOException {
        var allowed = Set.of("candidateId", "edge", "guard", "premises");
        if (!allowed.containsAll(body.propertyStream().map(entry -> entry.getKey()).toList())) throw new IllegalArgumentException("unknown review input");
        if (!body.path("candidateId").asText().matches("sha256:[0-9a-f]{64}") || !body.path("edge").isIntegralNumber()
                || !body.path("edge").canConvertToInt() || body.path("edge").intValue() < 1) throw new IllegalArgumentException("invalid occurrence selection");
        var guard = body.path("guard");
        if (!guard.isMissingNode() && (!guard.isTextual() || guard.asText().length() > 128)) throw new IllegalArgumentException("invalid guard");
        var premises = premises(body.path("premises"));
        if (!premises.isEmpty() && guard.asText("").isBlank()) throw new IllegalArgumentException("review premise without a guard");
        var receipt = RetainedCandidateEvidence.review(runs, run, body.required("candidateId").textValue(), body.required("edge").intValue(),
            radar, guard.asText(""), premises);
        files.retain(run.runId(), receipt.required("contentHash").asText(), receipt.toString().getBytes(StandardCharsets.UTF_8));
        return receipt;
    }

    private static List<String> premises(JsonNode values) {
        if (!values.isMissingNode() && (!values.isArray() || values.size() > 8
                || values.valueStream().anyMatch(p -> !p.isTextual() || p.asText().isBlank() || p.asText().length() > 128))) {
            throw new IllegalArgumentException("review requires at most eight short predicates");
        }
        return values.valueStream().map(JsonNode::asText).toList();
    }

    private static ObjectNode reopen(Path runs, RepresentationDiscoveryRunWorkspace run, AstRuleRadarService radar,
            SourceBoundReceiptFiles files, String digest) throws IOException {
        String hash = "sha256:" + digest;
        var bytes = files.find(run.runId(), hash).orElseThrow();
        var saved = JSON.readTree(bytes);
        var context = saved.path("supplementalGuardReview");
        var replayed = RetainedCandidateEvidence.review(runs, run, saved.required("candidateId").textValue(),
            saved.required("edgeSequence").intValue(), radar, context.path("requirement").asText(""), premises(context.path("reviewPremises")));
        if (!hash.equals(replayed.required("contentHash").asText()) || !Arrays.equals(bytes, replayed.toString().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("candidate receipt differs from its independently reproduced sources");
        }
        return replayed;
    }
}
