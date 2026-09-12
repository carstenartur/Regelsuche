# Generic Extension Foundation Implementation Plan

> **Execution contract:** implement PR 1 only. Later architecture PRs get new plans after this PR merges, against the real resulting code.

**Goal:** Deliver the first slice of the approved extension architecture: a search-independent generic extension API, deterministic immutable catalogs, a headless transactional runtime with a strict admission-before-classloading boundary, and a real isolated external consumer of the published artifacts.

**Architecture:** `regelsuche-extension-api` depends only on `regelsuche-core`. `regelsuche-extension-runtime` depends only on the generic extension API/core support and no domain-specific extension point. PR 1 deliberately leaves the existing app/plugin API untouched; PR 2 migrates it later.

**Tech stack:** Java 25, Gradle Wrapper 9.7.1, Maven `[3.9.9,4.0.0)`, JUnit 6, `ServiceLoader`, `URLClassLoader`, SHA-256, existing BOM/student-SDK publication and japicmp authority.

**Spec:** `docs/superpowers/specs/2026-09-11-generic-extension-program-discovery-design.md`

## Global constraints

- Product version stays `0.5.0-SNAPSHOT`; the generic extension contract is API revision `2`.
- Existing plugin API compatibility is not required, but PR 1 does not migrate/remove it yet.
- `regelsuche-extension-api` imports no search, discovery, plugin-api, app, persistence, Spring or Python package.
- `regelsuche-extension-runtime` imports no domain-specific extension-point module.
- External artifact admission snapshots exact bytes before any external plugin bytecode is loaded.
- The runtime loads only its own private copy of admitted bytes and re-hashes that copy before classloading.
- Catalog identity never serializes or calls `toString()` on implementation objects.
- Contribution callbacks are synchronous, thread-confined and closed immediately after return/failure.
- Catalog publication is transactional; failed reload keeps the previous exact catalog/resources active.
- API/core compatibility and required plugin dependencies fail closed.
- Public artifacts are wired into both Gradle and Maven reactors, the BOM, SDK publication and isolated consumer verification.
- Old japicmp checks remain fail-closed. New extension packages are stable but are not compared to a baseline where they did not exist.
- Every production slice follows RED → minimal GREEN → focused verification → wider authority.

---

## Task 1 — Add `regelsuche-extension-api`

### Files

Create:
- `regelsuche-extension-api/build.gradle`
- `regelsuche-extension-api/pom.xml`
- `regelsuche-extension-api/src/main/java/de/regelsuche/extension/ExtensionApi.java`
- `ExtensionPoint.java`
- `ExtensionDescriptor.java`
- `ExtensionContext.java`
- `ExtensionOrigin.java`
- `RegisteredExtension.java`
- `ExtensionCatalog.java`
- `PluginDescriptor.java`
- `PluginDependency.java`
- `RegelsuchePlugin.java`
- `regelsuche-extension-api/src/test/java/de/regelsuche/extension/ExtensionApiContractTest.java`

Modify:
- `settings.gradle`
- root `pom.xml`

### Public contract

```java
public final class ExtensionApi {
    public static final String VERSION = "2";
    public static final String CORE_COMPATIBILITY_VERSION = "1.0.0";
    private ExtensionApi() {}
}

public record ExtensionPoint<T>(String id, Class<T> contractType) {
    public static <T> ExtensionPoint<T> of(String id, Class<T> contractType) {
        return new ExtensionPoint<>(id, contractType);
    }
}

public record ExtensionDescriptor(String id, String name, List<String> tags) {
    public static ExtensionDescriptor named(String id) {
        return new ExtensionDescriptor(id, id, List.of());
    }
}

public interface ExtensionContext {
    <T> void contribute(
        ExtensionPoint<T> point,
        ExtensionDescriptor descriptor,
        T implementation
    );
}

public interface RegelsuchePlugin {
    PluginDescriptor descriptor();
    void contribute(ExtensionContext context);
}
```

`PluginDescriptor` fields: `id`, `name`, `version`, `apiVersion`, `minimumCoreVersion`, `capabilities`, `dependencies`, `provenance`.

`PluginDependency` fields: `pluginId`, `versionConstraint`, `optional`. In PR 1, `versionConstraint` supports `any` and exact-version equality only; unsupported syntax fails closed.

### Step 1 — Wire module in both reactors before production types

Add `regelsuche-extension-api` to Gradle `include(...)` and the SDK-publication project list. Add `<module>regelsuche-extension-api</module>` to the Maven reactor and dependency management using **`${project.version}`**, matching the existing reactor. Do not introduce `${revision}`; this reactor does not define it.

Gradle module: `java-library`, `jacoco`, Java 25, `api project(':regelsuche-core')`, JUnit 6. Maven module: only Regelsuche dependency is `regelsuche-core`.

### Step 2 — RED tests

```java
@Test
void invalidPointIdsAreRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> ExtensionPoint.of("bad id", Runnable.class));
    assertThrows(IllegalArgumentException.class,
        () -> ExtensionPoint.of("", Runnable.class));
}

@Test
void pointRetainsRuntimeContractType() {
    var point = ExtensionPoint.of("example.runnable", Runnable.class);
    assertEquals("example.runnable", point.id());
    assertSame(Runnable.class, point.contractType());
}

@Test
void descriptorCanonicalizesTagsDefensively() {
    var source = new ArrayList<>(List.of(" z ", "a", "a"));
    var descriptor = new ExtensionDescriptor("sample", "Sample", source);
    source.clear();
    assertEquals(List.of("a", "z"), descriptor.tags());
}

@Test
void genericPluginContractContainsOnlyGenericLifecycleMethods() {
    assertEquals("2", ExtensionApi.VERSION);
    assertEquals(Set.of("descriptor", "contribute"),
        Arrays.stream(RegelsuchePlugin.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet()));
}

@Test
void externalOriginRequiresArtifactHashWhenTrustHashIsPresent() {
    assertThrows(IllegalArgumentException.class,
        () -> new ExtensionOrigin(ExtensionOrigin.OriginKind.EXTERNAL_PLUGIN,
            "p", "1", "fixture.jar", "", "sha256:" + "a".repeat(64)));
}
```

Run:

```bash
./gradlew :regelsuche-extension-api:test --no-daemon --tests '*ExtensionApiContractTest'
```

Expected RED: production API classes do not exist.

### Step 3 — Minimal implementation

Use identifier grammar `[A-Za-z0-9][A-Za-z0-9._:/-]{0,191}`. Required textual fields are trimmed and nonblank. Tags/capabilities are trimmed, blank-rejected, deduplicated, sorted and immutable. Dependency collections are defensively copied.

Factories:

```java
public static ExtensionOrigin classpathPlugin(
        String sourceId, String sourceVersion, String sourceReference) {
    return new ExtensionOrigin(OriginKind.CLASSPATH_PLUGIN,
        sourceId, sourceVersion, sourceReference, "", "");
}

public static ExtensionOrigin externalPlugin(
        String sourceId, String sourceVersion, String sourceReference,
        String artifactSha256, String trustEvidenceSha256) {
    return new ExtensionOrigin(OriginKind.EXTERNAL_PLUGIN,
        sourceId, sourceVersion, sourceReference,
        artifactSha256, trustEvidenceSha256);
}
```

### Step 4 — GREEN verification

```bash
./gradlew :regelsuche-extension-api:test --no-daemon
mvn --batch-mode --no-transfer-progress -pl regelsuche-extension-api -am test
```

Commit only after both pass:

```bash
git add settings.gradle pom.xml regelsuche-extension-api
git commit -m "feat: add generic extension API"
```

---

## Task 2 — Deterministic immutable catalogs

Create:
- `regelsuche-extension-api/src/main/java/de/regelsuche/extension/ExtensionCatalogs.java`
- `regelsuche-extension-api/src/test/java/de/regelsuche/extension/ExtensionCatalogTest.java`

Contract:

```java
public interface ExtensionCatalog {
    <T> List<RegisteredExtension<T>> registrations(ExtensionPoint<T> point);
    <T> Optional<RegisteredExtension<T>> find(ExtensionPoint<T> point, String id);
    String canonicalManifest();
    String contentHash();
}

public final class ExtensionCatalogs {
    public static ExtensionCatalog empty();
    public static ExtensionCatalog of(Collection<RegisteredExtension<?>> registrations);
}
```

### Step 1 — RED tests

```java
private static final ExtensionPoint<Runnable> RUNNABLES =
    ExtensionPoint.of("example.runnable", Runnable.class);
private static final ExtensionOrigin ORIGIN =
    ExtensionOrigin.classpathPlugin("fixture", "1", "unit-test");

@Test
void catalogHashIsIndependentOfRegistrationOrder() {
    var a = new RegisteredExtension<>(RUNNABLES,
        ExtensionDescriptor.named("a"), (Runnable) () -> {}, ORIGIN);
    var b = new RegisteredExtension<>(RUNNABLES,
        ExtensionDescriptor.named("b"), (Runnable) () -> {}, ORIGIN);
    assertEquals(ExtensionCatalogs.of(List.of(a, b)).contentHash(),
        ExtensionCatalogs.of(List.of(b, a)).contentHash());
}

@Test
void implementationStringIsNotPartOfManifest() {
    Runnable implementation = new Runnable() {
        @Override public void run() {}
        @Override public String toString() { return "must-not-appear"; }
    };
    var catalog = ExtensionCatalogs.of(List.of(new RegisteredExtension<>(
        RUNNABLES, ExtensionDescriptor.named("r"), implementation, ORIGIN)));
    assertFalse(catalog.canonicalManifest().contains("must-not-appear"));
}

@Test
void duplicateContributionWithinOnePointFailsClosed() {
    var one = new RegisteredExtension<>(RUNNABLES,
        ExtensionDescriptor.named("same"), (Runnable) () -> {}, ORIGIN);
    var two = new RegisteredExtension<>(RUNNABLES,
        ExtensionDescriptor.named("same"), (Runnable) () -> {}, ORIGIN);
    assertThrows(IllegalArgumentException.class,
        () -> ExtensionCatalogs.of(List.of(one, two)));
}

@Test
void onePointIdCannotBindTwoContractTypes() {
    var callablePoint = ExtensionPoint.of("example.runnable", Callable.class);
    var runnable = new RegisteredExtension<>(RUNNABLES,
        ExtensionDescriptor.named("r"), (Runnable) () -> {}, ORIGIN);
    var callable = new RegisteredExtension<>(callablePoint,
        ExtensionDescriptor.named("c"), (Callable<String>) () -> "x", ORIGIN);
    assertThrows(IllegalArgumentException.class,
        () -> ExtensionCatalogs.of(List.of(runnable, callable)));
}
```

Run focused test and confirm RED because `ExtensionCatalogs` is absent.

### Step 2 — Minimal catalog implementation

Canonical manifest includes only point ID, contract-class binary name, contribution descriptor and origin. Sort by point ID, contract class, contribution ID, origin kind/source/version/reference and hashes. Revalidate `contractType.isInstance(implementation)` at snapshot construction. Never use implementation `toString()`.

SHA-256:

```java
String contentHash = "sha256:" + HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256")
        .digest(canonicalManifest.getBytes(StandardCharsets.UTF_8)));
```

### Step 3 — Verify and commit

```bash
./gradlew :regelsuche-extension-api:test --no-daemon
mvn --batch-mode --no-transfer-progress -pl regelsuche-extension-api -am test
git add regelsuche-extension-api
git commit -m "feat: add deterministic extension catalogs"
```

---

## Task 3 — Headless transactional runtime

Create:
- `regelsuche-extension-runtime/build.gradle`
- `regelsuche-extension-runtime/pom.xml`
- `.../runtime/ExtensionRuntime.java`
- `ExtensionRuntimeConfig.java`
- `PluginArtifactAdmission.java`
- `AdmittedPluginArtifact.java`
- `CatalogReloadResult.java`
- `.../runtime/ExtensionRuntimeTest.java`
- `.../runtime/TestPluginJar.java`

Modify `settings.gradle` and root `pom.xml`.

### Runtime contract

```java
public record ExtensionRuntimeConfig(
    String coreCompatibilityVersion,
    boolean loadClasspathPlugins,
    List<RegelsuchePlugin> explicitPlugins,
    List<Path> externalPluginJars,
    PluginArtifactAdmission artifactAdmission,
    ClassLoader parentClassLoader
) {
    public static ExtensionRuntimeConfig explicit(List<RegelsuchePlugin> plugins) {
        return new ExtensionRuntimeConfig(
            ExtensionApi.CORE_COMPATIBILITY_VERSION,
            false,
            plugins,
            List.of(),
            source -> { throw new IllegalStateException("external admission not configured"); },
            ExtensionRuntimeConfig.class.getClassLoader());
    }
}

@FunctionalInterface
public interface PluginArtifactAdmission {
    AdmittedPluginArtifact admit(Path sourceJar) throws Exception;
}
```

`AdmittedPluginArtifact` is a **final class, not a record containing a mutable Path**. It owns a private defensive copy of admitted bytes and host evidence:

```java
public final class AdmittedPluginArtifact {
    private final byte[] admittedBytes;
    private final String artifactSha256;
    private final String trustEvidenceSha256;
    private final String sourceReference;

    public static AdmittedPluginArtifact of(
        byte[] bytes,
        String artifactSha256,
        String trustEvidenceSha256,
        String sourceReference) { ... }

    public byte[] admittedBytes() { return admittedBytes.clone(); }
    public String artifactSha256() { return artifactSha256; }
    public String trustEvidenceSha256() { return trustEvidenceSha256; }
    public String sourceReference() { return sourceReference; }
}
```

The constructor/factory defensively copies input bytes and validates that `artifactSha256` matches those exact bytes.

### Step 1 — RED transaction/lifecycle tests

```java
private static final ExtensionPoint<Runnable> POINT =
    ExtensionPoint.of("fixture.runnable", Runnable.class);

private static RegelsuchePlugin plugin(String id, String contributionId) {
    return new RegelsuchePlugin() {
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(id, id, "1", ExtensionApi.VERSION,
                ExtensionApi.CORE_COMPATIBILITY_VERSION,
                Set.of(), List.of(), "fixture");
        }
        public void contribute(ExtensionContext context) {
            context.contribute(POINT, ExtensionDescriptor.named(contributionId),
                (Runnable) () -> {});
        }
    };
}

@Test
void duplicatePluginIdsRejectInitialSnapshot() {
    assertThrows(IllegalArgumentException.class,
        () -> ExtensionRuntime.open(ExtensionRuntimeConfig.explicit(List.of(
            plugin("same", "a"), plugin("same", "b")))));
}

@Test
void duplicateContributionIdsAcrossPluginsRejectInitialSnapshot() {
    assertThrows(IllegalArgumentException.class,
        () -> ExtensionRuntime.open(ExtensionRuntimeConfig.explicit(List.of(
            plugin("one", "same"), plugin("two", "same")))));
}

@Test
void failedReloadKeepsPreviousCatalogHash() {
    try (var runtime = ExtensionRuntime.open(
            ExtensionRuntimeConfig.explicit(List.of(plugin("good", "ok"))))) {
        String previous = runtime.catalog().contentHash();
        RegelsuchePlugin broken = new RegelsuchePlugin() {
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("broken", "broken", "1",
                    ExtensionApi.VERSION, ExtensionApi.CORE_COMPATIBILITY_VERSION,
                    Set.of(), List.of(), "fixture");
            }
            public void contribute(ExtensionContext context) {
                throw new IllegalStateException("boom");
            }
        };
        var result = runtime.reload(ExtensionRuntimeConfig.explicit(List.of(broken)));
        assertFalse(result.applied());
        assertEquals(previous, runtime.catalog().contentHash());
    }
}
```

Add callback-lifetime tests:

```java
@Test
void retainedContextIsClosedAfterContributionReturns() {
    AtomicReference<ExtensionContext> retained = new AtomicReference<>();
    RegelsuchePlugin p = pluginWithBody("retainer", context -> retained.set(context));
    try (var ignored = ExtensionRuntime.open(ExtensionRuntimeConfig.explicit(List.of(p)))) {
        assertThrows(IllegalStateException.class, () -> retained.get().contribute(
            POINT, ExtensionDescriptor.named("late"), (Runnable) () -> {}));
    }
}

@Test
void contributionContextRejectsForeignThread() {
    AtomicReference<Throwable> observed = new AtomicReference<>();
    RegelsuchePlugin p = pluginWithBody("threaded", context -> {
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                context.contribute(POINT, ExtensionDescriptor.named("foreign"),
                    (Runnable) () -> {});
            } catch (Throwable t) {
                observed.set(t);
            }
        });
        thread.join();
    });
    try (var ignored = ExtensionRuntime.open(ExtensionRuntimeConfig.explicit(List.of(p)))) {
        assertInstanceOf(IllegalStateException.class, observed.get());
    }
}
```

Also test:
- plugin `apiVersion != ExtensionApi.VERSION` fails;
- `minimumCoreVersion` newer than `coreCompatibilityVersion` fails;
- missing required dependency fails;
- optional missing dependency succeeds;
- unsupported non-`any`/non-exact dependency constraint fails.

### Step 2 — RED

```bash
./gradlew :regelsuche-extension-runtime:test --no-daemon
```

Expected RED because runtime production classes do not exist.

### Step 3 — Implement synchronous staging and compatibility

The private staging context records `Thread.currentThread()` and an `open` flag. `contribute` requires both same thread and `open=true`. Runtime invokes plugin callback synchronously and freezes the context in `finally` before extracting staged contributions.

Descriptor checks happen before publication. API version must equal `ExtensionApi.VERSION`; core compatibility uses the explicit config field. Required dependencies are resolved after all descriptors are known. In PR 1 support only `any` and exact version equality.

### Step 4 — Exact-byte admission-before-classloading tests

`TestPluginJar` uses `ToolProvider.getSystemJavaCompiler()` plus `JarOutputStream` to build a temporary provider JAR. Its plugin static initializer writes a sentinel file when loaded.

Rejection test:

```java
@Test
void rejectedExternalArtifactIsNeverClassloaded(@TempDir Path temp) throws Exception {
    Path source = TestPluginJar.build(temp.resolve("source"));
    Path sentinel = temp.resolve("loaded.txt");
    System.setProperty("regelsuche.fixture.sentinel", sentinel.toString());
    try {
        var config = externalConfig(List.of(source), jar -> {
            throw new SecurityException("rejected");
        });
        assertThrows(SecurityException.class, () -> ExtensionRuntime.open(config));
        assertFalse(Files.exists(sentinel));
    } finally {
        System.clearProperty("regelsuche.fixture.sentinel");
    }
}
```

TOCTOU/source-mutation test:

```java
@Test
void sourceMutationAfterAdmissionCannotChangeLoadedBytes(@TempDir Path temp) throws Exception {
    Path source = TestPluginJar.build(temp.resolve("source"));
    byte[] admitted = Files.readAllBytes(source);
    String admittedHash = sha256(admitted);
    var admission = (PluginArtifactAdmission) jar -> {
        byte[] snapshot = Files.readAllBytes(jar);
        Files.writeString(jar, "not-a-jar", StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING);
        return AdmittedPluginArtifact.of(snapshot, admittedHash, "", jar.toString());
    };
    try (var runtime = ExtensionRuntime.open(externalConfig(List.of(source), admission))) {
        assertEquals(admittedHash,
            runtime.catalog().registrations(POINT).getFirst().origin().artifactSha256());
    }
}
```

Implementation requirements:

1. call `artifactAdmission.admit(...)` for **every** configured external JAR before creating any external classloader;
2. take defensive byte copies from the admission results;
3. write each to a private runtime-owned staging directory;
4. hash the private staged file and require exact equality with `artifactSha256`;
5. only then build one `URLClassLoader` over all staged JARs, allowing cross-plugin class dependencies;
6. map each provider class `CodeSource` URL back to exactly one admitted artifact; unknown mappings fail closed;
7. never load the original source path after admission.

Add a two-JAR test where plugin B references a class from admitted plugin-library JAR A. It must load only when both were admitted first and are present in the shared external loader.

### Step 5 — Verify runtime modules

```bash
./gradlew :regelsuche-extension-api:test :regelsuche-extension-runtime:test --no-daemon
mvn --batch-mode --no-transfer-progress -pl regelsuche-extension-runtime -am test
```

Commit:

```bash
git add settings.gradle pom.xml regelsuche-extension-runtime
git commit -m "feat: add headless extension runtime"
```

---

## Task 4 — Publish both artifacts and prove an isolated consumer

Create external example:
- `examples/external-consumers/extension-runtime-java25/settings.gradle`
- `build.gradle`
- `src/main/java/example/Greeting.java`
- `GreetingPlugin.java`
- `Main.java`
- `src/main/resources/META-INF/services/de.regelsuche.extension.RegelsuchePlugin`
- `src/test/java/example/ExtensionRuntimeConsumerTest.java`
- `README.md`

Modify:
- `settings.gradle`
- root `pom.xml`
- `regelsuche-bom/build.gradle`
- `regelsuche-bom/pom.xml`
- `config/sdk/public-api.json`
- `gradle/student-sdk-consumer-verification.gradle`
- `scripts/verify-student-java-sdk-consumer.py`
- `scripts/verify-sdk-api-compatibility.py`

### Step 1 — External consumer RED

`GreetingPlugin`:

```java
public final class GreetingPlugin implements RegelsuchePlugin {
    static final ExtensionPoint<Greeting> POINT =
        ExtensionPoint.of("example.greeting", Greeting.class);

    public PluginDescriptor descriptor() {
        return new PluginDescriptor("greeting-plugin", "Greeting plugin", "1",
            ExtensionApi.VERSION, ExtensionApi.CORE_COMPATIBILITY_VERSION,
            Set.of("greeting"), List.of(), "external-consumer");
    }

    public void contribute(ExtensionContext context) {
        context.contribute(POINT, ExtensionDescriptor.named("hello"),
            name -> "Hello " + name);
    }
}
```

`Main` opens classpath discovery with an explicit core compatibility version and prints contribution ID, origin source ID and catalog hash.

Add both new modules to `SDK_MODULES` in the consumer verifier and add the new consumer to the progressive matrix, then run:

```bash
./gradlew verifyStudentJavaSdkConsumer --no-daemon
```

Expected RED: the new artifacts are not yet in publication/BOM wiring.

### Step 2 — Wire publication/BOM/API policy

Add `regelsuche-extension-api` and `regelsuche-extension-runtime` to:
- Gradle root module list;
- Maven root reactor and dependency management using `${project.version}`;
- Gradle and Maven BOM constraints;
- explicit SDK-publication project list;
- SDK artifact ledger/consumer module list.

`config/sdk/public-api.json` becomes explicit about baseline scope:

```json
{
  "extensionApiVersion": "2",
  "stablePackages": [
    "de.regelsuche.api",
    "de.regelsuche.sdk.discovery",
    "de.regelsuche.extension"
  ],
  "baselineCompatibilityPackages": [
    "de.regelsuche.api",
    "de.regelsuche.sdk.discovery"
  ]
}
```

Preserve existing fields and existing stable plugin-class list in PR 1. Add both new module names to `modules`.

### Step 3 — Preserve japicmp authority correctly

Update `scripts/verify-sdk-api-compatibility.py` so the old 0.4.0 comparison include list is built from `baselineCompatibilityPackages` plus the existing v1 plugin-class list—not from all `stablePackages`.

The new `de.regelsuche.extension` package is validated separately as present/current and governed by `extensionApiVersion == "2"`; it is not compared to a baseline where it did not exist.

Do not remove or weaken:

```text
--error-on-binary-incompatibility
--error-on-source-incompatibility
```

for old contracts.

### Step 4 — GREEN publication/consumer checks

```bash
./gradlew verifyStudentJavaSdkConsumer verifySdkApiCompatibility --no-daemon
mvn --batch-mode --no-transfer-progress -Pfull,sdk-release \
  -pl regelsuche-extension-api,regelsuche-extension-runtime,regelsuche-bom -am verify
```

Expected: external consumer builds from published Maven coordinates with an isolated Gradle cache; runtime dependency graph contains the new extension artifacts but no `app`, Spring, Hibernate or persistence modules.

### Step 5 — Full authority

```bash
./gradlew ciCheck --no-daemon --no-configuration-cache
```

Before merge require fresh success on the final PR head for:
- Checkout-local Gradle authority
- Isolated JMH authority
- Isolated SymPy runtime authority
- Complete Maven product and Docker authority
- final Checkout-local ciCheck

Commit final integration only after focused verification:

```bash
git add settings.gradle pom.xml regelsuche-bom config/sdk gradle scripts \
  examples/external-consumers/extension-runtime-java25
git commit -m "test: verify extension runtime as external consumer"
```

## PR 1 completion criteria

PR 1 is complete only when:

1. extension API/runtime remain headless and domain-independent;
2. catalog manifests are deterministic and implementation-object-independent;
3. duplicate identities and incompatible descriptors fail closed;
4. contribution contexts cannot mutate staging after callback return or from another thread;
5. failed reload retains the previous exact catalog/resources;
6. every external artifact is admitted before any external classloader exists;
7. source mutation after admission cannot alter loaded bytes;
8. private staged bytes are re-hashed before load;
9. cross-JAR dependencies work through the admitted shared loader;
10. provider origin maps to an exact admitted artifact;
11. external consumer uses published Maven coordinates without `includeBuild`/project dependencies;
12. existing discovery/core/plugin-v1 compatibility checks remain authoritative;
13. both Gradle and Maven product authorities are green on the final head;
14. no Primachsenraum/arithmetic code has entered Regelsuche.
