# Shared lifecycle for embedded Python adapters

## Scope

`de.regelsuche.python.runtime.ManagedPythonRuntime` extracts serialized session
ownership, deadlines, byte checks and cancellation from two concrete consumers:
Regelsuche's native-enabled SymPy factorization and Primachsenraum's pure-Python
moment reconstruction. This is not a new formula language or a complete Python
Discovery SDK. Neither mathematical algorithms nor certificate verifiers move
into this layer.

The helper is deliberately plain Java in `regelsuche-core`, which both consumers
already depend on. It imports neither Polyglot nor Python nor an application
module. Consequently the six-module SDK dependency closure and existing Maven /
Gradle reactor remain unchanged. Loading the Java SDK does not initialize Python.
GraalPy and its packages remain optional adapter dependencies. A separate public
runtime artifact can be extracted later without making it a prerequisite of this
lifecycle contract; no second heavyweight Python runtime is added here.

## Ownership and adapter responsibilities

A `SessionFactory` allocates a fresh lightweight holder. Its `initialize()` method
constructs the context and imports the fixed deployment-owned Python program on
one daemon **platform** thread. The holder is published for cancellation before
initialization. It must publish newly constructed resources before evaluating
Python and close resources created after retirement. `invoke(String)` receives
only structured data. Contexts, Polyglot values and session handles do not escape
the adapter into mathematical requests or responses.

The common helper reuses the holder after success, retires it after timeout,
interruption, oversized output or execution failure, and rejects calls after
close. A delayed old factory/initialization cannot retire or install a successor's
holder. A caller that times out while waiting for the gate never cancels the
owner's invocation. Once a caller owns the gate, an expired invocation deadline
retires that idle generation even when no new task could be submitted.

The same monotonic deadline covers queue waiting, worker submission, imports and
execution. Invalid durations fail before allocation. Cleanup/cancellation is
best-effort and can outlast the nominal deadline. `close()` waits for the serialized
caller; this is not a hard wall-clock or hostile-code memory-isolation guarantee.
A failing cleanup is attached to the authoritative technical exception rather
than replacing it. Throwable causes are available to trusted adapter diagnostics;
a public-facing adapter must not expose guest text or host paths inadvertently.

## Permissions are not shared

The SymPy adapter retains its private extracted package directory, managed Engine,
`python.IsolateNativeModules`, provisioned `patchelf`, native access, guest threads
and explicit PATH. It continues to deny host interop and full environment
inheritance. Its configured `SymPyFactorizationPolicy` remains the payload byte
authority; `Integer.MAX_VALUE` delegates the common helper's optional byte check
without re-encoding large strings solely to repeat that policy.

The pure-Python moment adapter retains **denied** host IO, host class lookup,
environment inheritance, process creation and native-extension access. It applies
its own finite input/output limits through the common helper. Sharing lifecycle
code must never widen this permission profile to match SymPy.

See [the SymPy adapter](sympy-factorization-adapter.md) for native packaging and
trust-flow details. GraalVM documents cross-thread cancellation via
[Context.close(true)](https://www.graalvm.org/sdk/javadoc/org/graalvm/polyglot/Context.html#close(boolean)).
The Session close contract is specific to owned contexts, not a general license
to close an Engine while another adapter still uses it.

## Verification and changed behavior

No Python source, exact wire data, factorization policy or verifier is changed.
The existing SymPy bootstrap, timeout/recovery and factorization tests remain
unchanged and must execute against the refactored runtime. The Primachsenraum
consumer separately checks host capability denial, direct SDK/formula binding,
Java certificate verification and byte parity with all 17 CPython reference
reports. Those integration results must be inspected, not inferred from unit tests.

The intentional behavior change for SymPy is that **queue waiting now consumes
the caller's deadline**, instead of waiting indefinitely before starting its
Future timeout. Invalid nonpositive timeouts are rejected explicitly. Cold/warm
initialization and invocation timings remain diagnostics, not certificate inputs.
No end-to-end speedup is claimed.

Twelve dependency-free lifecycle scenarios are called by twelve JUnit methods:
warm reuse/close, UTF-8 boundaries, output rejection, execution failure, bootstrap
failure, blocked invocation cancellation, delayed startup retirement, queue
ownership, interrupted caller, concurrent callers, cleanup failure and invalid
configuration. Latches establish ordering; no production thread sleeps are used.
The dependency-free scenarios were compiled with `javac 21 -Xlint:all` and executed
locally. That is not a local Java-25/GraalPy execution; the actual CI is authoritative
for both real adapters.

```bash
mvn --batch-mode --no-transfer-progress -pl regelsuche-core -am \
  -Dtest=ManagedPythonRuntimeTest test
```

The common runtime has no mathematical authority. A completed Python response
still needs its independent domain verifier. A technical timeout is not the same
as a mathematical search-budget diagnostic, and neither establishes nonexistence.
