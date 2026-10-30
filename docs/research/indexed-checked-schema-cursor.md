# Indexed checked-schema cursor

This opt-in increment reduces work before an existing checked application. It does
not introduce another learner, search kernel, theorem checker or proof cache.

```java
var plan = CheckedSchemaMatcherPlan.prepareIndexed(
    restoredModel, maximumSchemasPerOccurrence, utilities, includedSchemaIds);
// Prepare once. Independent searches can share the immutable plan.
var encodedProvider = plan.provider();
var nativeProvider = plan.nativeProvider();
long preparationWork = plan.compilationWork();
```

An existing plan can instead use `plan.withIndexedDispatch()`. Calling that on an
already indexed plan returns the same instance and does not rebuild the index.
The old `prepare(...)` method and old work revision are preserved.

## What changes

Each ordered root bucket has an immutable root/left-child/right-child shape mask.
Intersecting three masks excludes impossible candidates before invoking the full
matcher. Literal values, repeated-placeholder equality, function arguments and
all mathematical checks remain the existing matcher's/checker's responsibility.
A surviving bit is not a verified match. The original ranked prefix is capped
before filtering; rejected entries are never replaced by lower-ranked schemas.
Every surviving entry is visited in its original order, including wildcards.

Binary occurrence paths share their prefixes during traversal. Only a matched
application materializes a root-to-leaf list. The owning frame keeps the original
shared path, temporary array and immutable output visible while each completed
phase is charged. Separate positions of an aliased subexpression stay separate.

Compilation includes the previous preparation plus the new shape masks. Each
indexed query has an explicit work charge under
`regelsuche.checked-schema-cursor-work/v3-indexed-shared-paths`. The new matcher
plan revision is `regelsuche.checked-schema-matcher-plan/v2-indexed`. Definition
hashes bind both revisions; old receipts are not relabeled.

## Qualification boundary

The installed mathematical verifier still recognizes indexed providers, and
substitution-domain, instantiation, target-domain, admission and final replay are
not bypassed. However, the old native **execution inventory** deliberately does
not recognize this new dispatch profile as already qualified. Native accounting
must remain incomplete until a separate inventory/reproduction review qualifies
this implementation. This increment does not complete P04 or P05–P12.

## Tests and interpretation

`CheckedSchemaAccelerationTest` invokes the dependency-light executable contract.
It compares the bit-mask result to a linear reference across reproducible random
requirements and every prefix bound, plus empty sets, wildcards, all 64 bits,
mutation of the input requirements, path projection and actual shared ownership.
A constructed 32-schema bucket yields one possible matcher invocation instead of
32 for each of its 32 child-shape pairs. This is a **mechanism example**, not a
learned-corpus, wall-clock or full-lifecycle result.

`CheckedSchemaIndexedDispatchTest` uses the real learner, saves/reloads its model,
and compares indexed versus original applications, ordering and completion. It
also checks native/encoded agreement, tiny pull allowances, independent sessions,
missing assumptions, unsupported domains, paid abandonment and qualification.
The diagnostic records paid preparation and cursor work **including close** for
1, 8 and 32 requests and deliberately does not assert a predetermined winner.
These diagnostics compare two implementations using the same learned model;
they do not establish a learning advantage over an unlearned baseline.

```bash
./gradlew --no-daemon :regelsuche-learning:test \
  --tests de.regelsuche.evolution.CheckedSchemaAccelerationTest \
  --tests de.regelsuche.evolution.CheckedSchemaIndexedDispatchTest
./gradlew --no-daemon :regelsuche-core:test :regelsuche-search:test :regelsuche-learning:test
./gradlew --no-daemon --no-configuration-cache ciCheck
```

For a lifecycle claim, also include learning (including rejected attempts), loading,
compilation, search, independent replay and output; use the same technical changes
for the baseline and learned arm. A smaller candidate mask or shorter displayed
proof alone cannot establish that claim.

## Initial verification scope, 2026-10-30

The new primitive production sources and the executable contract compile locally
with `javac -Xlint:all -Werror` on JDK 21 against the unchanged `RetainedGraph.View`
and `Visitor` interface declarations. No retention implementation is substituted
or tested by that compilation slice. All 162,611 assertions pass. Four deliberate
mutations fail: refilling the prefix, losing wildcards, dropping bit 63 and
reversing a path. Initial RED was missing-API compilation failure; these mutations
are supplemental negative evidence, not retroactive pre-fix behavioral RED.

The full module/native integration and required Java-25 checkout CI have not been
run locally: the container cannot resolve GitHub or bootstrap the complete build.
Hosted CI and independent review are required before merge. No total speedup,
economic learning advantage or complete native qualification is asserted.
