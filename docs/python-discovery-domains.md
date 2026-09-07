# Python-authored Discovery domains

`PythonDiscoveryDomain<C,K>` is a transport-neutral adapter in the existing
`regelsuche-discovery-sdk`. It adds no interpreter or third-party dependency.
The paired Primachsenraum integration uses the real shared GraalPy runtime;
ordinary Java SDK users still need no Python installation or interpreter JAR.

## Responsibilities

Python supplies six fixed callbacks: `initial`, `invariant`, `successors`,
`objective`, `candidate`, and `counterexamples`. Java's normal SDK owns the
search queue, deduplication, state/candidate budgets, path and evidence.

The constructor also requires a **host witness checker**, **host evaluator**,
a candidate codec and a certificate codec/renderer. `FOUND` must have a
nonempty witness verified by the host. `NONE_FOUND` never issues a proof.
Only the separate host evaluator may create a confirmed certificate.
The host checks must use independent mathematical arguments. This API cannot
make arbitrary extension code correct merely by calling it a verifier.

Python invariants and rankings are search heuristics, not proofs of exhaustive
coverage. All Python transitions are explicitly non-semantics-preserving
search proposals; no guest flag can promote them into proved rewrite rules.
A callback error aborts the run. It is never converted to a mathematical
refutation, nonexistence certificate, or a best-effort success.

## Python entrypoint

Package the SDK's `PythonDiscoveryDomain.bridgeSource()` together with a
trusted deployment-owned domain program. The final program binds a fixed
entrypoint, for example:

```python
class MyDomain:
    def initial(self, seed, configuration):
        return {"states": ["0"]}

    def invariant(self, state, configuration):
        return {"accepted": True, "blockers": []}

    def successors(self, state, configuration):
        return {"successors": [{"action": "next", "state": str(int(state)+1), "cost": 1}]}

    def objective(self, state, configuration):
        return {"score": -int(state), "candidateReady": True}

    def candidate(self, state, configuration):
        return {"candidate": state}

    def counterexamples(self, candidate, budget, configuration):
        return {"status": "NONE_FOUND", "attempts": 0, "witness": ""}

invoke_payload = regelsuche_bind_domain(MyDomain())
```

This example deliberately makes **no mathematical claim**. Its host evaluator
still has to establish the actual requested property. A real worked example
in Primachsenraum enumerates moment-formula numerators and produces numerical
counterexamples in Python, while Java checks witnesses and polynomial identities.

## Java boundary

Implement `Transport.programSha256()` from the actual loaded program and
`Transport.invoke(request, timeout)` through a restricted interpreter session.
The deployment, not a mathematical request, chooses the code and entrypoint.
The transport owns cancellation and cleanup; the caller closes it.

Create a `Definition` binding the domain id/revision, program digest, opaque
configuration and explicit limits. Construct the adapter with candidate
`decode/encode`, witness checker, evaluator and certificate functions. Pass
`adapter.domain()` to the ordinary `RegelsucheDiscovery.forDomain(...)` API.
Use a fresh adapter and a single seed for each campaign. An interpreter may be
warm-reused by successive adapters; mutable state in a Python domain is still
the author's responsibility. Stateless callbacks are recommended.

States and candidate wire values are **opaque canonical UTF-8 strings**.
Domain authors put large integers/rationals into exact text encodings, not
binary floating point. The candidate codec must round-trip exactly. No runtime
Python object or `org.graalvm.polyglot.Value` crosses this public boundary.

State, candidate and certificate canonical material includes the full
Definition and its SHA-256 binding. Responses echo the binding, operation and
request hash. Different programs/configurations therefore have different
identities even when an opaque state is the same. This binds declared program
identity; it is not cryptographic attestation of an arbitrary Transport.

## Wire and resource contract

The versioned `regelsuche.python-domain/v1` protocol uses sorted compact JSON,
UTF-8 text, ASCII field names, bounded 32-bit control integers and booleans.
Null, floating point, duplicate/unknown fields, malformed Unicode and
noncanonical encodings are rejected. The existing core JSON reader/writer is
reused; depth is checked **before** parsing and exact canonical re-encoding
rejects permissive parser spellings. Arbitrary mathematical payloads stay strings.

Message bytes, payload bytes, list sizes, callback counts, per-call deadlines
and a callback campaign deadline are explicit and bound into evidence. The
adapter passes the smaller remaining timeout to the transport and rejects late
responses. The transport must actually enforce cancellation: the SDK cannot
interrupt a malicious/blocking Transport by itself. The campaign clock starts
with the initial callback and is checked at subsequent callbacks, not a hard
watchdog for arbitrary host evaluator code. Size failures abort rather than
silently truncating branches. Native Python work is not inferred from callback
counts. Metrics expose only observed traffic and a hash chain of successful
request/response pairs; they are not an independent execution proof.

Capability restrictions, native-extension support and heap/process isolation
remain transport concerns. This adapter does not widen the SymPy or pure-Python
Context permissions and is not a sandbox for hostile code.

## Tests and scope

The SDK's JUnit contract tests use an explicitly simulated data transport and
exercise the real Java search engine, host proof/witness authority, immutable
metrics, binding, size/fanout/count limits, zero budgets, exact codecs and
adversarial wire encodings. They do not claim Python execution. The paired
external consumer executes the packaged bridge and Python search with GraalPy,
then feeds those actual SDK-selected formulas into the existing moment service.

This is a first reusable callback API, not a notebook client, network service,
Python package distribution, full mathematical-object library, or automatic
proof generator. It does not change any existing domain/evidence format; its
own domain identities explicitly include the Python definition.
