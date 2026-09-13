package de.regelsuche.plugin;

import de.regelsuche.plugin.PluginCheckpointTransactions.*;
import de.regelsuche.plugin.PluginCheckpointAuthority.AcceptedState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Operation-aware use of the existing verified JAR lifecycle. Recovery only reads
 * the external ledger; no local pending file authorizes an installation.
 * The caller retains its operation ID before submitting work.
 */
public final class PluginDistributionTransactions {
    private final PluginCheckpointTransactions authority;
    private final Scope scope;
    private final ClientFactory clients;
    private PluginDistributionClient localClient;

    public PluginDistributionTransactions(Path path, PluginTrustStore roots, PluginCheckpointTransactions authority,
            PluginDistributionTransport transport, PluginDistributionClient.Limits limits) throws IOException {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.scope = Objects.requireNonNull(authority.scope(), "authority scope");
        if (!scope.rootTrustStoreHash().equals(PluginDistributionJson.hash(roots.toCanonicalJson()))) {
            throw new SecurityException("authority scope differs from pinned roots");
        }
        Objects.requireNonNull(path, "package path");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(limits, "limits");
        this.clients = () -> new PluginDistributionClient(path, scope.trustDomain(), roots, authority::read, transport, limits);
    }

    public Result install(String id, PluginDistributionClient.Sources sources,
            PluginArtifactResolver.ResolutionRequest request) throws IOException {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(request, "request");
        return perform(id, "INSTALL", Map.of("sources", sources, "request", request),
            () -> client().prepareInstall(sources, request));
    }
    public Result remove(String id) throws IOException {
        return perform(id, "REMOVE", Map.of(), () -> client().prepareRemove());
    }
    public Result rollback(String id, String target) throws IOException {
        PluginSignatureManifest.requireSha256(target, "rollback target");
        return perform(id, "ROLLBACK", Map.of("target", target), () -> client().prepareRollback(target));
    }

    /** Missing receipts remain unknown because an operation may still be in flight. */
    public Result recover(String id) {
        PluginCheckpointTransactions.identifier(id);
        try {
            var decision = authority.lookup(id);
            return decision.isEmpty() ? unknown(id) : result(checked(id, decision.orElseThrow()));
        } catch (IOException unavailable) {
            return unknown(id);
        }
    }
    public Optional<PluginInstallationEvidence> active() throws IOException { return client().active(); }
    public byte[] readArtifact(String identity) throws IOException { return client().readArtifact(identity); }

    private synchronized PluginDistributionClient client() throws IOException {
        // Remote receipt lookup must remain possible even when the local cache cannot be opened.
        if (localClient == null) localClient = clients.open();
        return localClient;
    }

    private Result perform(String id, String action, Map<String, ?> inputs, Preparation preparation) throws IOException {
        PluginCheckpointTransactions.identifier(id);
        String material = PluginDistributionJson.canonical(Map.of(
            "schema", "regelsuche.plugin-distribution-intent/v1", "scope", scope, "action", action, "inputs", inputs));
        // URI permits unpaired UTF-16 code units; UTF-8 replacement must not alias a different retained intent.
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(material)) {
            throw new SecurityException("distribution intent must contain well-formed Unicode");
        }
        String intent = PluginDistributionJson.hash(material);
        Optional<Decision> prior;
        try {
            prior = authority.lookup(id);
        } catch (IOException unavailable) {
            return unknown(id);
        }
        if (prior.isPresent()) {
            var decision = checked(id, prior.orElseThrow());
            if (!intent.equals(decision.operation().intentHash())) {
                throw new SecurityException("operation ID already belongs to another distribution intent");
            }
            return result(decision);
        }
        var prepared = preparation.run(); // Original native verifiers and immutable generation writer.
        var operation = new Operation(scope, id, intent, prepared.expected(), new AcceptedState(
            prepared.evidence().contentHash(), prepared.evidence().checkpoint()));
        Decision decision;
        try {
            decision = checked(id, authority.submit(operation));
        } catch (IOException unavailable) {
            decision = new Decision(operation, Outcome.OUTCOME_UNKNOWN);
        }
        if (!operation.equals(decision.operation())) {
            throw new SecurityException("authority returned a different complete operation");
        }
        return result(decision);
    }

    private Decision checked(String id, Decision decision) {
        if (decision == null || !scope.equals(decision.operation().scope())
                || !id.equals(decision.operation().operationId())) {
            throw new SecurityException("authority returned a foreign operation");
        }
        return decision;
    }
    private Result unknown(String id) {
        return new Result(scope, id, Outcome.OUTCOME_UNKNOWN, null, null, "NOT_CONFIRMED");
    }
    private Result result(Decision decision) {
        PluginInstallationEvidence installation = null;
        String local = "NOT_CONFIRMED";
        if (decision.outcome() == Outcome.COMMITTED) {
            try {
                installation = client().retainedEvidence(decision.operation().update());
                local = "AVAILABLE";
            } catch (IOException | RuntimeException missingOrInvalid) {
                // Cache failure cannot undo or misreport a durable authority commit.
                local = "MISSING_OR_INVALID";
            }
        }
        return new Result(scope, decision.operation().operationId(), decision.outcome(), decision, installation, local);
    }
    @FunctionalInterface private interface ClientFactory { PluginDistributionClient open() throws IOException; }
    @FunctionalInterface private interface Preparation { PluginDistributionClient.Prepared run() throws IOException; }

    /** Observational data; only the external authority selects the active generation. */
    public record Result(Scope scope, String operationId, Outcome outcome, Decision decision,
            PluginInstallationEvidence installation, String localEvidenceStatus) {
        public Result {
            Objects.requireNonNull(scope, "scope");
            PluginCheckpointTransactions.identifier(operationId);
            Objects.requireNonNull(outcome, "outcome");
            if (decision == null && outcome != Outcome.OUTCOME_UNKNOWN
                    || decision != null && (!scope.equals(decision.operation().scope())
                        || !operationId.equals(decision.operation().operationId()) || outcome != decision.outcome())) {
                throw new IllegalArgumentException("result must bind the complete authority decision");
            }
            String expectedLocal = outcome != Outcome.COMMITTED ? "NOT_CONFIRMED"
                : installation == null ? "MISSING_OR_INVALID" : "AVAILABLE";
            if (!expectedLocal.equals(localEvidenceStatus) || installation != null
                    && (outcome != Outcome.COMMITTED
                        || !installation.contentHash().equals(decision.operation().update().installationHash())
                        || !installation.checkpoint().equals(decision.operation().update().checkpoint())
                        || !installation.rootTrustStoreHash().equals(scope.rootTrustStoreHash()))) {
                throw new IllegalArgumentException("local evidence differs from committed operation");
            }
        }
        public String toCanonicalJson() {
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("schema", "regelsuche.plugin-distribution-operation-result/v1");
            payload.put("scope", scope); payload.put("operationId", operationId); payload.put("outcome", outcome);
            payload.put("decision", decision); payload.put("installation", installation);
            payload.put("localEvidenceStatus", localEvidenceStatus);
            return PluginDistributionJson.canonical(payload);
        }
    }
}
