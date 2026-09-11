package de.regelsuche.sdk.discovery.python;

import static de.regelsuche.sdk.discovery.python.PythonDomainWire.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DiscoveryDomain.Evaluation;
import de.regelsuche.sdk.discovery.DiscoveryBudgets;
import de.regelsuche.sdk.discovery.RegelsucheDiscovery;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Protocol tests use a deterministic transport, not an alleged Python runtime.
 * The paired external consumer executes the real packaged bridge with GraalPy.
 */
class PythonDiscoveryDomainTest {
    private static final String HASH = "a".repeat(64);
    private static final PythonDiscoveryDomain.Limits LIMITS = PythonDiscoveryDomain.Limits.small();
    private static final DiscoverySeed SEED = DiscoverySeed.create("seed", "python-control", "finite-control", "unit-test");

    static class FakeTransport implements PythonDiscoveryDomain.Transport {
        Function<Map<String, Object>, Map<String, Object>> change = Function.identity();
        Function<String, String> raw = Function.identity();
        final List<String> operations = new ArrayList<>();
        final List<Integer> counterexampleBudgets = new ArrayList<>();
        boolean skipWitness;
        public String programSha256() { return HASH; }
        public String invoke(String request, Duration timeout) {
            assertFalse(timeout.isNegative() || timeout.isZero());
            var input = read(request, 262_144);
            var args = object(input.get("arguments"));
            String op = (String) input.get("operation");
            operations.add(op);
            Map<String, Object> value;
            int state = args.containsKey("state") ? Integer.parseInt((String) args.get("state")) : 0;
            value = switch (op) {
                case "initial" -> Map.of("states", List.of("0"));
                case "invariant" -> Map.of("accepted", true, "blockers", List.of());
                case "objective" -> Map.of("score", -state, "candidateReady", state > 0);
                case "successors" -> Map.of("successors", state >= 2 ? List.of()
                        : List.of(Map.of("action", "next", "state", Integer.toString(state + 1), "cost", 1)));
                case "candidate" -> Map.of("candidate", args.get("state"));
                case "counterexamples" -> {
                    counterexampleBudgets.add(integer(args.get("budget"), 0, Integer.MAX_VALUE));
                    yield Map.of("status", "1".equals(args.get("candidate")) && !skipWitness ? "FOUND" : "NONE_FOUND",
                            "attempts", 1, "witness", "1".equals(args.get("candidate")) && !skipWitness ? "1!=2" : "");
                }
                default -> throw new IllegalStateException(op);
            };
            return raw.apply(canonical(change.apply(new TreeMap<>(Map.of("protocol", PythonDiscoveryDomain.PROTOCOL,
                    "binding", input.get("binding"), "operation", op, "requestSha256", sha256(request), "result", value)))));
        }
    }

    private static PythonDiscoveryDomain<Integer, String> adapter(FakeTransport transport) {
        return adapter(transport, LIMITS, Integer::parseInt, Object::toString);
    }
    private static PythonDiscoveryDomain<Integer, String> adapter(FakeTransport transport,
            PythonDiscoveryDomain.Limits limits, Function<String, Integer> decode, Function<Integer, String> encode) {
        return new PythonDiscoveryDomain<>(new PythonDiscoveryDomain.Definition("python-control", "v1", HASH, "control", limits),
                transport, decode, encode, (candidate, witness) -> candidate == 1 && witness.equals("1!=2"),
                candidate -> candidate == 2 ? Evaluation.confirmed("checked-2", "independent finite control", Map.of())
                        : Evaluation.refuted("host rejects candidate", Map.of()), "FINITE_CONTROL", Function.identity(), Function.identity());
    }
    private static PythonDiscoveryDomain<Integer, String> adapter(FakeTransport transport,
            PythonDiscoveryDomain.HostCounterexamplePrecheck<Integer> precheck) {
        return adapter(new PythonDiscoveryDomain.Definition("python-control", "v2-host-precheck", HASH,
                "control;hostPrecheck=" + precheck.id(), LIMITS), transport, precheck);
    }
    private static PythonDiscoveryDomain<Integer, String> adapter(PythonDiscoveryDomain.Definition definition,
            FakeTransport transport, PythonDiscoveryDomain.HostCounterexamplePrecheck<Integer> precheck) {
        return new PythonDiscoveryDomain<>(definition, transport, Integer::parseInt, Object::toString,
                (candidate, witness) -> candidate == 1 && witness.equals("1!=2"), precheck,
                candidate -> candidate == 2 ? Evaluation.confirmed("checked-2", "independent finite control", Map.of())
                        : Evaluation.refuted("host rejects candidate", Map.of()),
                "FINITE_CONTROL", Function.identity(), Function.identity());
    }
    private static void start(PythonDiscoveryDomain<?, ?> adapter) { adapter.domain().generator().generate(SEED); }

    @Test void allSixCallbacksDriveAnActualSdkSearch() {
        var transport = new FakeTransport();
        var adapter = adapter(transport);
        var run = RegelsucheDiscovery.forDomain(adapter.domain()).campaign("python-control")
                .seed("seed", SEED.payload(), "unit-test").budget(DiscoveryBudgets.small()).run();
        assertTrue(run.isConfirmed(), run::canonicalEvidence);
        assertEquals(2, run.selectedCandidate().orElseThrow());
        assertEquals("checked-2", run.selectedCertificate().orElseThrow());
        assertEquals(Set.of("initial", "invariant", "objective", "successors", "candidate", "counterexamples"),
                Set.copyOf(transport.operations));
        assertTrue(run.canonicalEvidence().contains(adapter.definition().bindingSha256()));
        assertEquals(transport.operations.size(), adapter.metrics().calls());
    }
    @Test void noWitnessDoesNotBypassHostProof() {
        var transport = new FakeTransport(); transport.skipWitness = true;
        var run = RegelsucheDiscovery.forDomain(adapter(transport).domain()).campaign("false-negative-control")
                .seed("seed", SEED.payload(), "unit-test").budget(DiscoveryBudgets.small()).run();
        assertTrue(run.isConfirmed()); assertEquals(2, run.selectedCandidate().orElseThrow());
    }
    @Test void falseWitnessIsNotAnAuthorizedRefutation() {
        var transport = new FakeTransport();
        transport.change = response -> {
            if (response.get("operation").equals("counterexamples"))
                response.put("result", Map.of("status", "FOUND", "attempts", 1, "witness", "fabricated"));
            return response;
        };
        var adapter = adapter(transport); start(adapter);
        assertThrows(PythonDiscoveryDomain.CallbackFailure.class, () -> adapter.domain().counterexampleGenerator().search(1, 2));
    }
    @Test void hostEvaluatorIsMandatory() {
        assertThrows(NullPointerException.class, () -> new PythonDiscoveryDomain<Integer, String>(
                new PythonDiscoveryDomain.Definition("python-control", "v1", HASH, "control", LIMITS),
                new FakeTransport(), Integer::parseInt, Object::toString, (c, w) -> true, null,
                "CONTROL", Function.identity(), Function.identity()));
    }
    @Test void programAndConfigurationArePartOfStateIdentity() {
        var one = adapter(new FakeTransport());
        var two = new PythonDiscoveryDomain<Integer, String>(
                new PythonDiscoveryDomain.Definition("python-control", "v1", HASH, "other", LIMITS),
                new FakeTransport(), Integer::parseInt, Object::toString, (c,w) -> false,
                c -> Evaluation.refuted("control", Map.of()), "CONTROL", Function.identity(), Function.identity());
        assertNotEquals(one.domain().stateCodec().contentHash("0"), two.domain().stateCodec().contentHash("0"));
        assertThrows(IllegalArgumentException.class, () -> new PythonDiscoveryDomain.Definition("x", "v1", "bad", "", LIMITS));
        assertThrows(IllegalArgumentException.class, () -> new PythonDiscoveryDomain<Integer, String>(
                new PythonDiscoveryDomain.Definition("x", "v1", "b".repeat(64), "", LIMITS), new FakeTransport(),
                Integer::parseInt, Object::toString, (c,w) -> false, c -> Evaluation.refuted("x", Map.of()),
                "CONTROL", Function.identity(), Function.identity()));
    }
    @Test void stateProposalsCannotClaimSemanticPreservation() {
        var adapter = adapter(new FakeTransport()); start(adapter);
        var next = adapter.domain().operators().getFirst().apply("0").getFirst();
        assertFalse(next.semanticsPreserving());
        assertTrue(next.assumptions().contains("PYTHON_SEARCH_PROPOSAL_NOT_A_PROOF"));
    }
    @Test void staleResponseBindingsAndExtraFieldsFailClosed() {
        for (String field : List.of("binding", "operation", "requestSha256", "protocol", "certificate")) {
            var t = new FakeTransport(); t.change = r -> { r.put(field, "forged"); return r; };
            assertThrows(PythonDiscoveryDomain.CallbackFailure.class, () -> start(adapter(t)), field);
        }
    }
    @Test void callbackCountCannotSilentlyTruncateSearch() {
        var limited = new PythonDiscoveryDomain.Limits(262144, 16384, 128, 1, 30000, 120000);
        var adapter = adapter(new FakeTransport(), limited, Integer::parseInt, Object::toString); start(adapter);
        var failure = assertThrows(PythonDiscoveryDomain.CallbackFailure.class, () -> adapter.domain().objective().assess("0"));
        assertEquals(PythonDiscoveryDomain.Failure.LIMIT, failure.failure());
        assertEquals(1, adapter.metrics().calls());
    }
    @Test void fanoutAndAttemptLimitsAreValidatedRatherThanClamped() {
        var t = new FakeTransport();
        t.change = r -> { r.put("result", Map.of("states", List.of("0", "1"))); return r; };
        var limits = new PythonDiscoveryDomain.Limits(262144, 16384, 1, 10, 30000, 120000);
        assertThrows(IllegalArgumentException.class, () -> start(adapter(t, limits, Integer::parseInt, Object::toString)));
        var attempts = new FakeTransport();
        var adapter = adapter(attempts); start(adapter);
        attempts.change = r -> { r.put("result", Map.of("status", "NONE_FOUND", "attempts", 3, "witness", "")); return r; };
        assertThrows(IllegalArgumentException.class, () -> adapter.domain().counterexampleGenerator().search(2, 2));
    }
    @Test void transportFailureNeverBecomesANegativeMathematicalResult() {
        var t = new FakeTransport(); t.raw = ignored -> { throw new IllegalStateException("guest details"); };
        var failure = assertThrows(PythonDiscoveryDomain.CallbackFailure.class, () -> start(adapter(t)));
        assertEquals(PythonDiscoveryDomain.Failure.TRANSPORT, failure.failure());
        assertFalse(failure.getMessage().contains("guest details"));
    }
    @Test void zeroCounterexampleBudgetDoesNotCallPython() {
        var adapter = adapter(new FakeTransport()); start(adapter);
        int before = adapter.metrics().calls();
        assertEquals(0, adapter.domain().counterexampleGenerator().search(1, 0).attempts());
        assertEquals(before, adapter.metrics().calls());
    }
    @Test void freshCampaignRequiresFreshAdapterAndMetricsAreImmutable() {
        var adapter = adapter(new FakeTransport()); start(adapter);
        assertThrows(PythonDiscoveryDomain.CallbackFailure.class, () -> start(adapter));
        assertThrows(UnsupportedOperationException.class, () -> adapter.metrics().callsByOperation().put("fake", 1));
    }
    @Test void candidateCodecRequiresAnExactRoundTrip() {
        var adapter = adapter(new FakeTransport(), LIMITS, Integer::parseInt, n -> "0" + n); start(adapter);
        assertThrows(IllegalArgumentException.class, () -> adapter.domain().candidateExtractor().extract(
                new de.regelsuche.discovery.domain.DiscoveryDomain.CandidateContext<>("0", "2", List.of())));
    }
    @Test void canonicalJsonRejectsAmbiguousAndUnsupportedSpellings() {
        for (String bad : List.of("{\"x\":1,\"x\":2}", "{\"x\":1.0}", "{\"x\":null}", "{\"x\":+1}",
                "{\"x\":01}", "{\"x\":\"\\q\"}", "{\"x\":true} ", "{\"x\":2147483648}",
                "{\"x\":\"\\ud800\"}", "{\"x\":[] ,\"y\":1}"))
            assertThrows(RuntimeException.class, () -> read(bad, 1024), bad);
        var data = Map.of("items", List.of("ä", "😀", "\n\t\b\f\r", Integer.MIN_VALUE), "flag", true);
        assertEquals(canonical(data), canonical(read(canonical(data), 1024)));
    }
    @Test void nestedAndOversizedMessagesFailBeforeRecursiveParsing() {
        assertThrows(IllegalArgumentException.class, () -> read("{\"x\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}", 1024));
        assertThrows(IllegalArgumentException.class, () -> read("x".repeat(1025), 1024));
        assertThrows(IllegalArgumentException.class, () -> bytes("ä".repeat(513), 1024));
    }
    @Test void noBudgetExhaustionCanExposeACertificate() {
        var run = RegelsucheDiscovery.forDomain(adapter(new FakeTransport()).domain()).campaign("tiny-control")
                .seed("seed", SEED.payload(), "unit-test").budget(DiscoveryBudgets.tiny()).run();
        assertFalse(run.isConfirmed()); assertTrue(run.selectedCertificate().isEmpty());
    }

    @Test void trustedPrecheckRejectsBeforeGuestCounterexampleCallback() {
        var transport = new FakeTransport();
        var precheck = new PythonDiscoveryDomain.HostCounterexamplePrecheck<Integer>(
                "exact-periodicity-no-go-v1",
                (candidate, budget) -> candidate == 1
                        ? Optional.of(CounterexampleResult.found(
                                1, "1!=2", Map.of("proof", "constructive-collision")))
                        : Optional.empty());
        var adapter = adapter(transport, precheck);
        var run = RegelsucheDiscovery.forDomain(adapter.domain()).campaign("host-precheck-control")
                .seed("seed", SEED.payload(), "host-precheck-test").budget(DiscoveryBudgets.small()).run();
        assertTrue(run.isConfirmed(), run::canonicalEvidence);
        assertEquals(2, run.selectedCandidate().orElseThrow());
        assertEquals(1L, transport.operations.stream().filter("counterexamples"::equals).count(),
                "candidate 1 must be rejected without a Python counterexample callback");
        assertTrue(run.canonicalEvidence().contains("exact-periodicity-no-go-v1"));
        assertTrue(run.canonicalEvidence().contains("constructive-collision"));
    }
    @Test void nonApplicablePrecheckDelegatesToGuestWithUnchangedBudget() {
        var transport = new FakeTransport();
        int[] precheckBudget = {-1};
        var adapter = adapter(transport, new PythonDiscoveryDomain.HostCounterexamplePrecheck<>(
                "non-applicable-control-v1", (candidate, budget) -> {
                    precheckBudget[0] = budget;
                    return Optional.empty();
                }));
        start(adapter);

        var result = adapter.domain().counterexampleGenerator().search(2, 7);

        assertEquals(7, precheckBudget[0]);
        assertEquals(List.of(7), transport.counterexampleBudgets);
        assertEquals("non-applicable-control-v1", result.metrics().get("hostPrecheck"));
    }
    @Test void precheckIdentityIsAutomaticallyBoundWhileLegacyNoPrecheckBindingStaysStable() {
        var definition = new PythonDiscoveryDomain.Definition(
                "python-control", "binding-control", HASH, "same-configuration", LIMITS);
        var legacyTransport = new FakeTransport();
        var legacy = new PythonDiscoveryDomain<Integer, String>(definition, legacyTransport,
                Integer::parseInt, Object::toString, (candidate, witness) -> false,
                candidate -> Evaluation.refuted("control", Map.of()),
                "FINITE_CONTROL", Function.identity(), Function.identity());
        var first = adapter(definition, new FakeTransport(), new PythonDiscoveryDomain.HostCounterexamplePrecheck<>(
                "precheck-a-v1", (candidate, budget) -> Optional.empty()));
        var second = adapter(definition, new FakeTransport(), new PythonDiscoveryDomain.HostCounterexamplePrecheck<>(
                "precheck-b-v1", (candidate, budget) -> Optional.empty()));
        assertTrue(legacy.domain().stateCodec().canonicalForm("state").contains(definition.bindingSha256()));
        assertNotEquals(legacy.domain().stateCodec().canonicalForm("state"),
                first.domain().stateCodec().canonicalForm("state"));
        assertNotEquals(first.domain().stateCodec().canonicalForm("state"),
                second.domain().stateCodec().canonicalForm("state"));
        start(legacy);
        assertFalse(legacy.domain().counterexampleGenerator().search(2, 7).metrics().containsKey("hostPrecheck"));
        assertEquals(List.of(7), legacyTransport.counterexampleBudgets);
        assertThrows(IllegalArgumentException.class, () -> new PythonDiscoveryDomain.HostCounterexamplePrecheck<Integer>(
                "none", (candidate, budget) -> Optional.empty()));
    }
    @Test void hostPrecheckWitnessStillRequiresIndependentWitnessChecker() {
        var transport = new FakeTransport();
        var adapter = adapter(transport, new PythonDiscoveryDomain.HostCounterexamplePrecheck<>(
                "bad-witness-control-v1",
                (candidate, budget) -> Optional.of(CounterexampleResult.found(1, "fabricated", Map.of()))));
        start(adapter);
        assertThrows(IllegalArgumentException.class,
                () -> adapter.domain().counterexampleGenerator().search(1, 2));
        assertFalse(transport.operations.contains("counterexamples"),
                "an invalid host precheck must fail before invoking the guest");
    }
    @Test void hostPrecheckCannotOverspendTheSuppliedAttemptBudget() {
        var transport = new FakeTransport();
        var adapter = adapter(transport, new PythonDiscoveryDomain.HostCounterexamplePrecheck<>(
                "overspend-control-v1",
                (candidate, budget) -> Optional.of(CounterexampleResult.inconclusive(3, "too much work", Map.of()))));
        start(adapter);
        assertThrows(IllegalArgumentException.class,
                () -> adapter.domain().counterexampleGenerator().search(1, 2));
        assertFalse(transport.operations.contains("counterexamples"));
    }

    @Test void packagedBridgeIsIncludedWithoutAnInterpreterDependency() throws Exception {
        String source = PythonDiscoveryDomain.bridgeSource();
        assertTrue(source.contains("def regelsuche_bind_domain"));
        assertFalse(source.contains("eval("));
        assertFalse(source.contains("exec("));
    }
}
