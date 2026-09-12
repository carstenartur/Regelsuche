# Generic Extension and Bounded Program Discovery Architecture

Status: design for implementation
Date: 2026-09-11

## Motivation

Regelsuche already exposes useful extension mechanisms, but they grew in parallel:

- `RegelsuchePlugin` has many domain-specific `register...(...)` methods.
- search-related extensions share registry concepts tied to the rewrite/search stack;
- `PluginRuntime` and trust handling live under `app`, so the full lifecycle is not headless;
- `DiscoveryDomainProvider` has a second, separate `ServiceLoader` path;
- `RewriteProgram` is a useful typed program IR, but its semantics are intentionally expression-rewrite specific.

Primachsenraum is currently the only downstream consumer. We can therefore make a cleaner breaking pre-release API change instead of preserving two extension models. The immediate goal is not an arithmetic special case: Primachsenraum should become the first external consumer of a reusable Regelsuche extension and bounded-program platform.

## Goals

1. Provide one small, search-independent typed extension SPI.
2. Make plugin loading, compatibility, provenance and trust usable without `app`.
3. Keep artifact trust, extension provenance and mathematical correctness separate.
4. Replace the growing plugin registration surface with one contribution callback.
5. Make Discovery a normal typed extension point.
6. Add a finite, canonical, budgeted program API whose semantics are supplied by plugins.
7. Add reusable program formation for Discovery without number theory in Regelsuche.
8. Use Primachsenraum as the first external end-to-end consumer.
9. Preserve deterministic content-addressed evidence and hard framework budgets.
10. Keep `RewriteProgram` separate until real multi-domain evidence justifies a deeper shared IR.

## Non-goals

This work does not add a plugin marketplace, remote package manager, hostile-bytecode sandbox or general-purpose scripting language. A trusted JAR is not a mathematical proof. The bounded program API is intentionally not Turing complete. `RewriteProgram` is not replaced in this work.

Backward source/binary compatibility with the current plugin registration API and the direct `DiscoveryDomainProvider` service-loading contract is explicitly not required.

## Module architecture

```text
                           regelsuche-core
                                |
                                v
                      regelsuche-extension-api
                     /           |            \
                    v            v             v
regelsuche-extension-runtime  regelsuche-plugin-api  regelsuche-program-api
                    \           |             /
                     \          |            /
                      \         v           /
                       regelsuche-discovery-sdk
                                 |
                                 v
                                app

External plugin (for example Primachsenraum)
     |
     +--> regelsuche-extension-api
     +--> regelsuche-plugin-api       only for rewrite/search contributions
     +--> regelsuche-discovery-sdk    for discovery domains
     +--> regelsuche-program-api      for bounded program languages
```

The diagram shows only extension-related dependencies. `regelsuche-plugin-api` still uses the existing search contracts; `regelsuche-discovery-sdk` still uses Discovery core. `regelsuche-discovery-sdk` does not depend on `regelsuche-plugin-api`, and `regelsuche-extension-runtime` depends on no domain-specific extension-point module.

`regelsuche-extension-api` may depend on the minimum core API annotation/version support, but not search, discovery, persistence, app, Spring or Python.

`regelsuche-extension-runtime` depends on `regelsuche-extension-api` and owns headless discovery, compatibility, staging, catalog publication, reload, classloader lifetime and artifact-admission hooks.

`regelsuche-program-api` contains the finite program IR, grammar, executor, work accounting and the `program.language` extension point. It is independent of rewrite search and Discovery.

## Generic extension API

### Plugin contract

```java
package de.regelsuche.extension;

public interface RegelsuchePlugin {
    PluginDescriptor descriptor();
    void contribute(ExtensionContext context);
}
```

`PluginDescriptor` is immutable:

```java
public record PluginDescriptor(
    String id,
    String name,
    String version,
    String apiVersion,
    String minimumCoreVersion,
    Set<String> capabilities,
    List<PluginDependency> dependencies,
    String provenance
) {}
```

The generic extension API revision is `2`. Version-1 plugins are not adapted. A self-reported plugin signature is removed from the contract because already-loaded plugin metadata cannot establish artifact trust.

`minimumCoreVersion` is checked against an explicit host `coreCompatibilityVersion` supplied to the headless runtime. It is not inferred from `app`, a manifest side effect or the Maven artifact version.

Initially `PluginDependency.versionConstraint` supports `any` and exact-version equality only. Unknown/range syntax fails closed until a separately tested constraint grammar is introduced. Required dependencies must be present; optional dependencies may be absent.

### Typed extension points

```java
public record ExtensionPoint<T>(String id, Class<T> contractType) {
    public static <T> ExtensionPoint<T> of(String id, Class<T> contractType) {
        return new ExtensionPoint<>(id, contractType);
    }
}

public record ExtensionDescriptor(
    String id,
    String name,
    List<String> tags
) {}

public interface ExtensionContext {
    <T> void contribute(
        ExtensionPoint<T> point,
        ExtensionDescriptor descriptor,
        T implementation
    );
}
```

Rules:

- extension-point IDs are globally unique stable identifiers;
- one point ID cannot be associated with two contract classes;
- contribution IDs are unique within one extension point;
- runtime type checking uses `contractType.isInstance(implementation)`;
- registration order is not semantic identity.

### Contribution callback lifetime

`ExtensionContext` is a synchronous, thread-confined staging capability, not a long-lived registry.

The runtime creates one private staging context per plugin, records the calling thread, invokes `plugin.contribute(context)` synchronously, and closes/freezes that context in `finally` as soon as the callback returns or throws. After closure, every `contribute(...)` call fails with `IllegalStateException`. Calls from a thread other than the callback thread also fail.

A plugin may retain the Java reference, but the retained reference is unusable. The runtime never publishes from a still-open context. This prevents delayed or asynchronous registration from racing catalog validation/publication.

The plugin cannot inspect the global candidate catalog while contributing, so contribution behavior cannot depend on `ServiceLoader` iteration order.

### Host-owned origin

```java
public record ExtensionOrigin(
    OriginKind kind,
    String sourceId,
    String sourceVersion,
    String sourceReference,
    String artifactSha256,
    String trustEvidenceSha256
) {}
```

Initial `OriginKind` values are `CORE`, `CLASSPATH_PLUGIN`, `EXTERNAL_PLUGIN` and `RULE_FILE`.

Plugin code supplies neither artifact hashes nor trust decisions. `sourceId/sourceVersion` come from the validated plugin descriptor; artifact and trust hashes come from the host runtime/admission layer. External strict-trust plugins require both hashes. Development/classpath origins may legitimately have no artifact attestation.

### Immutable catalog

```java
public record RegisteredExtension<T>(
    ExtensionPoint<T> point,
    ExtensionDescriptor descriptor,
    T implementation,
    ExtensionOrigin origin
) {}

public interface ExtensionCatalog {
    <T> List<RegisteredExtension<T>> registrations(ExtensionPoint<T> point);
    <T> Optional<RegisteredExtension<T>> find(ExtensionPoint<T> point, String id);
    String canonicalManifest();
    String contentHash();
}
```

`canonicalManifest()` contains deterministic metadata only: point ID/type, contribution descriptor and host origin. It never serializes an implementation object and never calls implementation `toString()`. External implementation semantics are bound by the admitted artifact hash.

## Headless runtime and transaction model

### Artifact admission: exact bytes, not mutable paths

External trust is evaluated before any external plugin bytecode is loaded.

The admission boundary returns an immutable snapshot of the exact admitted bytes plus host evidence. It must not hand the runtime a mutable caller-owned path as the authority for later classloading. Conceptually:

```java
public final class AdmittedPluginArtifact {
    private final byte[] admittedBytes;
    private final String artifactSha256;
    private final String trustEvidenceSha256;
    private final String sourceReference;

    public byte[] admittedBytes() { return admittedBytes.clone(); }
}
```

Construction defensively copies the bytes. The runtime obtains a copy, writes it into a private runtime-owned staging directory, hashes the staged file again, and requires equality with `artifactSha256` before constructing any external classloader. The source path is never loaded after admission. This closes the path-replacement/TOCTOU gap.

All configured external JARs are admitted and privately staged before the runtime creates the external plugin classloader. The runtime then creates one loader over the complete set of privately staged admitted JARs so declared plugin dependencies can resolve classes across JARs. Provider origin is mapped back to the corresponding admitted artifact using the provider class `CodeSource` URL; an unknown/unmapped code source fails closed.

The existing detached Ed25519/publisher/revocation implementation moves behind this admission interface in the later migration PR. PR 1 establishes the boundary and tests exact-byte staging.

### Transactional catalog construction

A complete candidate catalog is built off to the side:

1. snapshot/admit every external artifact;
2. privately stage and re-hash all admitted bytes;
3. create the candidate classloader(s);
4. discover/instantiate plugins;
5. validate descriptor API version and explicit core compatibility version;
6. validate required dependencies and supported dependency constraints;
7. invoke each synchronous thread-confined contribution callback into a private staging context;
8. close/freeze each context;
9. type-check all staged contributions and validate duplicate plugin/point/contribution identities;
10. build one immutable `ExtensionCatalog`;
11. atomically publish the candidate snapshot.

Any failure rejects the entire candidate snapshot. Initial startup fails. A failed reload leaves the previous catalog and its resources active. Candidate classloaders/resources are closed on failure; old external resources are closed only after a successful atomic swap.

There is no `first plugin wins` behavior.

## Standard rewrite/search extension points

`regelsuche-plugin-api` becomes the home of domain-specific standard points rather than the generic lifecycle. Examples include rules, transformations, visitors, macros, search strategies, heuristics, cost functions, renderers, explanation providers, parser extensions and example packages.

The old `PluginExtensionRegistry` hierarchy is removed after application migration. Rule files remain a distinct authoring surface but can be projected into the same host-side contribution model with `OriginKind.RULE_FILE`.

## Discovery as an extension point

`DiscoveryDomainProvider` remains a useful domain contract but stops being a second `ServiceLoader` SPI.

```java
public final class DiscoveryExtensionPoints {
    public static final ExtensionPoint<DiscoveryDomainProvider> DOMAIN_PROVIDERS =
        ExtensionPoint.of("discovery.domain-provider", DiscoveryDomainProvider.class);
}
```

An external plugin contributes the provider through its ordinary `contribute(...)` callback. `RegelsucheDiscovery.forDomain(...)` remains available for direct in-process use. Only the duplicate provider-loading mechanism disappears.

A domain obtained through the catalog binds generic extension-origin fields into Discovery evidence: point ID, contribution ID, source/plugin ID and version, source kind/reference, artifact SHA-256 when available, and trust-evidence SHA-256 when available.

Artifact trust remains separate from mathematical confirmation.

## Generic bounded program API

### Program model

The first version supports finite control flow only:

```java
public record BoundedProgram(ProgramNode root) {}

public sealed interface ProgramNode permits InstructionCall, Sequence, Repeat {}

public record InstructionCall(
    String instructionId,
    Map<String, String> parameters
) implements ProgramNode {}

public record Sequence(List<ProgramNode> nodes) implements ProgramNode {}

public record Repeat(
    ProgramNode body,
    int maxIterations
) implements ProgramNode {}
```

There is no unbounded loop, recursion, arbitrary branch expression or reflective call. `Repeat` has a fixed positive maximum. Canonicalization sorts parameter maps, flattens nested sequences, rejects empty sequences and produces stable canonical bytes/hash.

### Frozen grammar

```java
public record ProgramGrammar(
    String id,
    String revision,
    Set<String> instructionIds,
    Map<String, Map<String, List<String>>> allowedParameterValues,
    int maxNodes,
    int maxDepth,
    List<Integer> allowedRepeatBounds
) {}
```

Every legal instruction ID, parameter name/value and repeat bound is predeclared. A program cannot hide an unbounded integer search inside a nominally bounded instruction.

### Instruction semantics and work deltas

Work reporting is part of the instruction result, not an implicit side channel:

```java
public record InstructionResult<S, O>(
    StepResult<S, O> step,
    WorkVector workDelta
) {}

public interface ProgramInstruction<S, O> {
    InstructionResult<S, O> execute(
        S state,
        Map<String, String> parameters,
        WorkBudget remaining
    );
}

public interface ProgramLanguage<I, S, O> {
    String id();
    String revision();
    S initialize(I input);
    Map<String, ProgramInstruction<S, O>> instructions();
    String canonicalInput(I input);
    String canonicalState(S state);
    String canonicalOutput(O output);
}
```

`StepResult` distinguishes continue-with-state, halt-with-output, halt-without-output and budget exhaustion. `InstructionResult.workDelta()` is a deterministic non-negative vector for that call.

### Work ledger and budgets

```java
public record WorkVector(SortedMap<String, Long> counters) {}
public record WorkBudget(SortedMap<String, Long> maxima) {}
```

The executor always increments the framework-owned `programSteps` counter. It merges the returned `workDelta` with checked arithmetic and checks every configured maximum before accepting the step result. Negative counters, overflow or a delta exceeding the remaining budget fail closed.

Domain counters may include `modularMultiplications`, `gcdRemainders`, `solverCalls`, `graphEdgesVisited` or `bytesRead`.

Framework counters are actual enforcement boundaries. Arbitrary Java plugin code can still perform hidden work or lie about domain counters, so scientific claims depending on domain work require trusted/independent replay or another domain verifier. The program runtime is not a security sandbox.

### Executor

```java
ProgramExecution<O> execute(
    ProgramLanguage<I, S, O> language,
    ProgramGrammar grammar,
    BoundedProgram program,
    I input,
    WorkBudget budget
)
```

The executor validates grammar/program compatibility before the first instruction, counts every executed instruction, stops on terminal results and records program hash, grammar identity, language identity/revision, canonical input hash, terminal status, output hash when present and the complete work vector.

## Reusable program formation for Discovery

`regelsuche-discovery-sdk` adds reusable structural formation, not a number-theory synthesis algorithm. `BoundedProgramSearchSpace` owns deterministic legal successor generation from `ProgramGrammar`, canonical deduplication, complete-program extraction and structural lower bounds.

The ordinary Discovery domain supplies TRAIN observations/cases, fitness/objective, counterexample search, independent evaluator/certificate and optional exact host prechecks. Regelsuche keeps ownership of search, budgets, evidence and candidate freeze.

## Relationship to `RewriteProgram`

`RewriteProgram` remains unchanged. The new program API mirrors useful structural properties—typed nodes, bounded repetition, canonical identity and explicit work—but does not depend on `TransformationEngine` or expression-search semantics.

Only after at least two non-rewrite consumers exist should a deeper shared control-flow IR be considered.

## Primachsenraum external plugin

Primachsenraum becomes the first real external consumer. A focused integration module builds a plugin JAR against published/local Regelsuche API artifacts and contributes both its Discovery provider and later its arithmetic `ProgramLanguage`.

The arithmetic language uses exact integer state and a finite parameter grammar. Initial operations are deliberately small: frozen seeds, register copies, `x <- x^2 + c mod n` for frozen `c`, and `gcd(|x-y|, n)` producing only a verified proper factor or no result. There is no trial-division, factorization or primality-oracle instruction.

The control target is rediscovery of Pollard-Rho-like bounded behavior, not a novelty claim. Candidate programs are compared under predeclared work budgets and against fixed equal-work controls. Every factor is independently checked by exact divisibility before it affects fitness or certification.

TRAIN selection and held-out qualification follow the ordering established by Primachsenraum #269:

1. freeze grammar, instruction library identity, work limits, corpus recipe and Regelsuche definition;
2. search/evaluate TRAIN only;
3. persist selected program and Evidence;
4. only then materialize VALIDATION/TEST labels/factor references;
5. independently replay program and factors outside the plugin implementation.

## Evidence and reproducibility

Evidence remains layered:

```text
artifact trust evidence
        |
        v
ExtensionOrigin + catalog manifest
        |
        v
Discovery definition/binding
        |
        v
ProgramLanguage + ProgramGrammar identity
        |
        v
BoundedProgram canonical hash
        |
        v
TRAIN candidate/certificate
        |
        v
candidate freeze
        |
        v
held-out qualification
```

No layer stands in for another. A verified artifact is not a proof; TRAIN confirmation is not held-out qualification; held-out empirical success is not an asymptotic theorem.

## API migration and compatibility policy

Because backward compatibility is not required, obsolete plugin surfaces are removed rather than permanently adapted.

The public API policy distinguishes two concepts:

- `stablePackages`: every package currently promised as public/stable in the current API revision;
- `baselineCompatibilityPackages`: the subset that existed in the pinned historical baseline and is therefore compared against that baseline by japicmp.

When `de.regelsuche.extension` is introduced, it is added to `stablePackages` but not to the old `baselineCompatibilityPackages`. This prevents a new package from being incorrectly compared against a release where it did not exist while keeping old discovery/core/plugin-v1 compatibility checks fully fail-closed.

The migration sequence is:

1. generic extension API + headless runtime boundary;
2. migrate existing plugin surfaces and move cryptographic trust implementation;
3. route Discovery through the generic extension catalog;
4. add bounded-program API and reusable program formation;
5. migrate Primachsenraum as the first real external plugin;
6. run the arithmetic orbit/factor-witness experiment.

## Testing strategy

Extension/runtime tests must prove:

- invalid/duplicate point and contribution identities fail closed;
- duplicate plugin IDs fail closed;
- implementation type mismatch is rejected;
- catalog ordering/hash is deterministic and implementation-object-independent;
- contribution contexts are synchronous/thread-confined, close after callback return and reject late/foreign-thread calls;
- contribution exceptions publish nothing;
- failed reload retains the previous exact catalog;
- external artifacts are fully admitted before any external classloading;
- source-path mutation after admission cannot change loaded bytes;
- the privately staged hash equals `artifactSha256` before classloading;
- cross-JAR plugin dependencies can resolve through the admitted shared external loader;
- provider `CodeSource` maps to exactly one admitted artifact;
- API/core compatibility and required dependencies fail closed.

Program tests must prove canonicalization, grammar bounds, deterministic execution, explicit work-delta merging, checked work arithmetic, per-counter budget exhaustion, framework `programSteps` enforcement and stable hashes.

Primachsenraum end-to-end tests must build the external JAR, admit/load it through strict trust, resolve contributions, run TRAIN-only search, independently verify every proper factor, freeze before held-out data and replay origin/program/evidence hashes independently.

## Acceptance criteria

The architecture is complete when:

1. a Java plugin has one contribution entry point;
2. new extension points do not change `RegelsuchePlugin`;
3. extension API/runtime are headless and domain-independent;
4. external trust/admission precedes classloading and exact admitted bytes are exact loaded bytes;
5. catalog publication is atomic and failed reloads retain the previous snapshot;
6. Discovery uses the generic extension mechanism;
7. the old direct Discovery provider loader is gone;
8. current plugin behavior is migrated to typed catalog projections;
9. bounded programs can be represented, hashed, executed and work-budgeted generically;
10. work deltas have an explicit checked contract;
11. program formation is reusable by ordinary Discovery domains;
12. Primachsenraum loads as an external plugin and retains origin-bound evidence;
13. the arithmetic grammar contains no hidden factorization/primality oracle;
14. every factor is independently verified;
15. held-out information remains unavailable before candidate freeze;
16. final consumer verification uses published API artifacts rather than internal project dependencies.

## Decision summary

Regelsuche should not gain a Primachsenraum-specific subsystem. The reusable core is a search-independent typed extension platform with a headless, exact-byte trusted runtime. Discovery becomes one extension point on that platform. Finite executable programs become a second reusable API layer. Primachsenraum supplies only arithmetic semantics and scientific protocol as an external plugin.
