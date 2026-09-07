package de.regelsuche.sdk.discovery.python;

import static de.regelsuche.sdk.discovery.python.PythonDomainWire.*;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleStatus;
import de.regelsuche.discovery.domain.DiscoveryDomain.Evaluation;
import de.regelsuche.discovery.domain.DiscoveryDomain.InvariantResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.ObjectiveAssessment;
import de.regelsuche.discovery.domain.DiscoveryDomain.Successor;
import de.regelsuche.sdk.discovery.DiscoveryDomainBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * A data-only Python callback protocol adapted to the normal Java discovery SDK.
 * No interpreter dependency or mathematical proof authority is introduced here.
 * Use one instance and one seed per campaign; the caller owns the transport.
 */
public final class PythonDiscoveryDomain<C, K> {
    public static final String PROTOCOL = "regelsuche.python-domain/v1";
    public static final String BRIDGE_RESOURCE = "/de/regelsuche/sdk/discovery/python/bridge.py";

    /** The implementation binds the actual trusted program and enforces the timeout. */
    public interface Transport {
        String programSha256();
        String invoke(String request, Duration timeout);
    }

    /** Logical protocol limits; these are not an isolated guest heap guarantee. */
    public record Limits(int maxMessageBytes, int maxPayloadBytes, int maxItems,
                         int maxCalls, int callbackMillis, int campaignMillis) {
        public Limits {
            require(maxMessageBytes >= 1024 && maxMessageBytes <= 1_000_000, "message byte limit");
            require(maxPayloadBytes >= 1 && maxPayloadBytes <= Math.min(65_536, maxMessageBytes), "payload byte limit");
            require(maxItems >= 1 && maxItems <= 1024, "item limit");
            require(maxCalls >= 1 && maxCalls <= 100_000, "callback count limit");
            require(callbackMillis >= 1 && callbackMillis <= 120_000, "callback deadline");
            require(campaignMillis >= callbackMillis && campaignMillis <= 120_000, "campaign deadline");
        }
        public static Limits small() { return new Limits(262_144, 16_384, 128, 4096, 30_000, 120_000); }
        Map<String, Object> material() {
            return Map.of("maxMessageBytes", maxMessageBytes, "maxPayloadBytes", maxPayloadBytes,
                    "maxItems", maxItems, "maxCalls", maxCalls, "callbackMillis", callbackMillis,
                    "campaignMillis", campaignMillis);
        }
    }

    public record Definition(String domainId, String revision, String programSha256,
                             String configuration, Limits limits) {
        public Definition {
            Objects.requireNonNull(limits);
            require(domainId != null && domainId.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}"), "domain id");
            require(revision != null && revision.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}"), "revision");
            require(programSha256 != null && programSha256.matches("[0-9a-f]{64}"), "program digest");
            text(configuration, limits.maxPayloadBytes, true);
        }
        public String canonical() {
            return PythonDomainWire.canonical(Map.of("protocol", PROTOCOL, "domainId", domainId,
                    "revision", revision, "programSha256", programSha256,
                    "configuration", configuration, "limits", limits.material()));
        }
        public String bindingSha256() { return sha256(canonical()); }
    }

    public enum Failure { PROTOCOL, TRANSPORT, LIMIT, REUSED }
    public static final class CallbackFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final Failure failure;
        private CallbackFailure(Failure failure, String operation) {
            super("Python domain callback " + failure + ": " + operation);
            this.failure = failure;
        }
        public Failure failure() { return failure; }
    }

    /** Observed boundary traffic only, not a replay of work inside Python. */
    public record Metrics(int calls, long requestBytes, long responseBytes,
                          Map<String, Integer> callsByOperation, String transcriptSha256) {
        public Metrics { callsByOperation = Map.copyOf(callsByOperation); }
    }

    private final Definition definition;
    private final Transport transport;
    private final Function<String, C> decodeCandidate;
    private final Function<C, String> encodeCandidate;
    private final BiPredicate<C, String> checkWitness;
    private final DiscoveryDomain<String, C, K> domain;
    private final AtomicBoolean started = new AtomicBoolean();
    private final Map<String, Integer> counts = new TreeMap<>();
    private final String binding;
    private long deadline, requestBytes, responseBytes;
    private int calls;
    private String transcript = sha256(PROTOCOL);

    /**
     * The host evaluator and witness checker are mandatory and must be independent
     * of candidate construction. No guest callback can issue a confirmed certificate.
     * Candidate encoding must be an exact round trip through decoding.
     */
    public PythonDiscoveryDomain(Definition definition, Transport transport,
            Function<String, C> decodeCandidate, Function<C, String> encodeCandidate,
            BiPredicate<C, String> checkWitness, Function<C, Evaluation<K>> evaluator,
            String certificateKind, Function<K, String> certificateCanonical,
            Function<K, String> certificateRendering) {
        this.definition = Objects.requireNonNull(definition);
        this.transport = Objects.requireNonNull(transport);
        this.decodeCandidate = Objects.requireNonNull(decodeCandidate);
        this.encodeCandidate = Objects.requireNonNull(encodeCandidate);
        this.checkWitness = Objects.requireNonNull(checkWitness);
        Objects.requireNonNull(evaluator);
        Objects.requireNonNull(certificateCanonical);
        Objects.requireNonNull(certificateRendering);
        require(definition.programSha256.equals(transport.programSha256()), "transport program binding mismatch");
        binding = definition.bindingSha256();
        domain = DiscoveryDomainBuilder.<String, C, K>domain(definition.domainId, definition.revision)
                .generator(seed -> {
                    if (!started.compareAndSet(false, true)) throw failure(Failure.REUSED, "initial");
                    deadline = System.nanoTime() + Duration.ofMillis(definition.limits.campaignMillis).toNanos();
                    Map<String, Object> reply = call("initial", Map.of("seed", payload(seed.payload())));
                    fields(reply, "states");
                    List<String> states = new ArrayList<>();
                    for (Object value : list(reply.get("states"), definition.limits.maxItems)) states.add(payload(value));
                    return List.copyOf(states);
                })
                .stateCodec(state -> bound("state", payload(state)))
                .invariant("python-proposal-filter", state -> {
                    Map<String, Object> reply = call("invariant", Map.of("state", payload(state)));
                    fields(reply, "accepted", "blockers");
                    List<String> blockers = new ArrayList<>();
                    for (Object value : list(reply.get("blockers"), definition.limits.maxItems)) blockers.add(payload(value));
                    return new InvariantResult(bool(reply.get("accepted")), blockers);
                })
                .operator("python-successors", this::successors)
                .objective(state -> {
                    Map<String, Object> reply = call("objective", Map.of("state", payload(state)));
                    fields(reply, "score", "candidateReady");
                    return new ObjectiveAssessment(integer(reply.get("score"), Integer.MIN_VALUE, Integer.MAX_VALUE),
                            bool(reply.get("candidateReady")), Map.of("pythonBinding", binding));
                })
                .candidate(context -> {
                    Map<String, Object> reply = call("candidate", Map.of("state", payload(context.currentState())));
                    fields(reply, "candidate");
                    String wire = payload(reply.get("candidate"));
                    C candidate = Objects.requireNonNull(decodeCandidate.apply(wire));
                    require(wire.equals(payload(encodeCandidate.apply(candidate))), "candidate wire round trip");
                    return candidate;
                }, candidate -> bound("candidate", payload(encodeCandidate.apply(candidate))))
                .counterexamples(this::counterexamples)
                .evaluator(candidate -> Objects.requireNonNull(evaluator.apply(candidate), "host evaluation"))
                .certificate(certificateKind,
                        certificate -> bound("certificate", certificateCanonical.apply(certificate)), certificateRendering)
                .build();
    }

    public Definition definition() { return definition; }
    public DiscoveryDomain<String, C, K> domain() { return domain; }
    public synchronized Metrics metrics() { return new Metrics(calls, requestBytes, responseBytes, counts, transcript); }

    /** Package the bridge once; concrete interpreters remain optional and caller-owned. */
    public static String bridgeSource() throws IOException {
        try (InputStream input = PythonDiscoveryDomain.class.getResourceAsStream(BRIDGE_RESOURCE)) {
            if (input == null) throw new IOException("missing Python discovery bridge resource");
            byte[] bytes = input.readNBytes(65_537);
            if (bytes.length > 65_536) throw new IOException("Python discovery bridge exceeds resource limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private String payload(Object value) { return text(value, definition.limits.maxPayloadBytes, false); }
    private String bound(String role, String material) {
        bytes(material, definition.limits.maxMessageBytes);
        String wrapped = canonical(Map.of("binding", binding, "definition", definition.canonical(), "role", role, "payload", material));
        bytes(wrapped, definition.limits.maxMessageBytes);
        return wrapped;
    }

    private List<Successor<String>> successors(String state) {
        Map<String, Object> reply = call("successors", Map.of("state", payload(state)));
        fields(reply, "successors");
        List<Successor<String>> successors = new ArrayList<>();
        HashSet<String> actions = new HashSet<>();
        for (Object item : list(reply.get("successors"), definition.limits.maxItems)) {
            Map<String, Object> value = object(item);
            fields(value, "action", "state", "cost");
            String action = text(value.get("action"), 128, false);
            require(actions.add(action), "duplicate successor action");
            // Python may guide the search, but cannot certify a preserving rewrite.
            successors.add(new Successor<>(action, payload(value.get("state")),
                    integer(value.get("cost"), -1_000_000, 1_000_000), false,
                    List.of("PYTHON_SEARCH_PROPOSAL_NOT_A_PROOF"), Map.of("pythonBinding", binding)));
        }
        return List.copyOf(successors);
    }

    private CounterexampleResult counterexamples(C candidate, Integer budget) {
        require(budget >= 0, "negative counterexample budget");
        if (budget == 0) return CounterexampleResult.inconclusive(0, "no counterexample budget", Map.of());
        Map<String, Object> reply = call("counterexamples", Map.of("candidate", payload(encodeCandidate.apply(candidate)), "budget", budget));
        fields(reply, "status", "attempts", "witness");
        CounterexampleStatus status = CounterexampleStatus.valueOf(text(reply.get("status"), 32, false));
        int attempts = integer(reply.get("attempts"), 0, budget);
        String witness = text(reply.get("witness"), definition.limits.maxPayloadBytes, true);
        if (status == CounterexampleStatus.NONE_FOUND) require(witness.isEmpty(), "unexpected witness");
        if (status == CounterexampleStatus.UNSUPPORTED) require(attempts == 0, "unsupported attempt count");
        if (status == CounterexampleStatus.FOUND) {
            require(attempts > 0 && !witness.isBlank(), "missing attempted counterexample");
            if (!checkWitness.test(candidate, witness)) throw failure(Failure.PROTOCOL, "unverified-counterexample");
        }
        return new CounterexampleResult(status, attempts, witness,
                Map.of("pythonBinding", binding, "witnessCheckedByHost", Boolean.toString(status == CounterexampleStatus.FOUND)));
    }

    private synchronized Map<String, Object> call(String operation, Map<String, Object> arguments) {
        require(started.get(), "start a campaign before invoking callbacks");
        if (calls >= definition.limits.maxCalls || deadline - System.nanoTime() <= 0)
            throw failure(Failure.LIMIT, operation);
        String request = canonical(Map.of("protocol", PROTOCOL, "binding", binding,
                "operation", operation, "configuration", definition.configuration, "arguments", arguments));
        int sent = bytes(request, definition.limits.maxMessageBytes);
        String requestHash = sha256(request);
        long remaining = Math.min(Duration.ofMillis(definition.limits.callbackMillis).toNanos(), deadline - System.nanoTime());
        if (remaining <= 0) throw failure(Failure.LIMIT, operation);
        calls++;
        counts.merge(operation, 1, Integer::sum);
        requestBytes += sent;
        long callbackDeadline = System.nanoTime() + remaining;
        String response;
        try { response = transport.invoke(request, Duration.ofNanos(remaining)); }
        catch (RuntimeException ex) { throw failure(Failure.TRANSPORT, operation); }
        if (callbackDeadline - System.nanoTime() <= 0) throw failure(Failure.LIMIT, operation);
        try {
            responseBytes += bytes(response, definition.limits.maxMessageBytes);
            Map<String, Object> reply = read(response, definition.limits.maxMessageBytes);
            fields(reply, "protocol", "binding", "operation", "requestSha256", "result");
            require(PROTOCOL.equals(reply.get("protocol")) && binding.equals(reply.get("binding"))
                    && operation.equals(reply.get("operation")) && requestHash.equals(reply.get("requestSha256")), "callback response binding");
            transcript = sha256(transcript + "\n" + operation + "\n" + requestHash + "\n" + sha256(response));
            return object(reply.get("result"));
        } catch (RuntimeException ex) { throw failure(Failure.PROTOCOL, operation); }
    }

    private static CallbackFailure failure(Failure kind, String operation) { return new CallbackFailure(kind, operation); }
}
