# Generic extension SPI v2: external Java 25 consumer

This independent example loads a consumer-owned typed `Greeting` contribution
through **SPI v2**, `de.regelsuche.extension.RegelsuchePlugin`, and the headless
`regelsuche-extension-runtime`. It does not use the Regelsuche application,
Spring, persistence, Hibernate, reactor dependencies or `includeBuild`.
For the older application rule API v1 (`de.regelsuche.plugin.RegelsuchePlugin`),
use [hello-rule-java25](../hello-rule-java25/README.md) instead. The two interfaces
have different packages, contracts and ServiceLoader descriptor names.

## Prerequisites and released artifacts

Use **JDK 25** and the verified
[regelsuche-sdk-0.5.2.zip from release v0.5.2](https://github.com/carstenartur/Regelsuche/releases/tag/v0.5.2).
Download and verify it using the [SDK quickstart](../../../docs/java-sdk-quickstart.md).
Its repository contains:

```text
de.regelsuche:regelsuche-bom:0.5.2
de.regelsuche:regelsuche-extension-runtime:0.5.2
```

The runtime's Regelsuche dependency chain is extension runtime → extension API →
core. The released `ExtensionApi.VERSION` is `2`; this API revision is different
from product version `0.5.2`. Keep all three module versions aligned through the
BOM. The general SDK ZIP supplies the Maven repository; the separate optimisation
SDK ZIP is not the distribution for this example.

## Preferred route: Maven

Install Maven **3.9.9+ from the 3.x line**. Copy this whole current example directory
anywhere, enter it and supply an absolute `file:` URI for the extracted SDK
repository:

```bash
mvn --batch-mode --no-transfer-progress \
  -Dregelsuche.repository=file:///absolute/path/regelsuche-sdk-0.5.2/repository \
  -Dregelsuche.version=0.5.2 clean package
java -cp "target/classes:target/dependency/*" example.Main
```

On Windows, replace `:` with `;` in the Java classpath. `package` runs the existing
JUnit test and copies the runtime dependencies. The independent POM imports the
BOM and has no Regelsuche parent. It was added **after 0.5.2**; use the current
complete example directory for Maven support. The immutable 0.5.2 ZIP already
contains the Java source, test and Gradle build, but not this new POM.

The Maven repository property is `regelsuche.repository` (a URI), and the version
property is `regelsuche.version`; both differ from the Gradle property names
below. Version defaults to `0.5.2`. The default repository location is three
parents above this example, appropriate inside the extracted SDK tree; a copied
standalone directory should use the explicit absolute override shown above.
Maven plugins, JUnit and transitive third-party artifacts still need Central on
first use. The released SDK parent also imports third-party BOMs; the ZIP is not
a fully offline Maven cache.

## Existing released route: SDK Gradle wrapper

From the **root of the extracted `regelsuche-sdk-0.5.2` directory**, run:

```bash
./gradlew --project-dir examples/external-consumers/extension-runtime-java25 \
  clean test run \
  -PregelsucheRepository=/absolute/path/regelsuche-sdk-0.5.2/repository \
  -PregelsucheVersion=0.5.2
```

Use `gradlew.bat` on Windows. The SDK contains the wrapper; no separate Gradle
installation is needed, but its first download and third-party resolution need
network access. Gradle expects a filesystem path for `regelsucheRepository` and
uses `regelsucheVersion` for the product version. Always pass both overrides
when using the released example or copying it outside the SDK tree. The source
Gradle build retains its checkout-verification defaults: `./repository` inside
the example and `0.5.0-SNAPSHOT`. The release packager rewrites that version to
its product version; neither default replaces the explicit released repository
and `0.5.2` overrides above. The original build defaults are preserved so future
release packaging can keep substituting its own version.
The Gradle test additionally rejects any Regelsuche runtime closure other than
the three modules listed above.

## Direct Java smoke run

For a minimal diagnostic run without Maven or Gradle, enter the example directory
and use the three actual released JARs (POSIX shell, JDK 25 on `PATH`):

```bash
SDK_REPOSITORY=/absolute/path/regelsuche-sdk-0.5.2/repository
SDK_CP="$SDK_REPOSITORY/de/regelsuche/regelsuche-core/0.5.2/regelsuche-core-0.5.2.jar"
for module in regelsuche-extension-api regelsuche-extension-runtime; do
  SDK_CP="$SDK_CP:$SDK_REPOSITORY/de/regelsuche/$module/0.5.2/$module-0.5.2.jar"
done
mkdir -p target/smoke
javac -cp "$SDK_CP" -d target/smoke src/main/java/example/*.java
cp -R src/main/resources/. target/smoke/
java -cp "target/smoke:$SDK_CP" example.Main
```

Copying the resources is required: the file
`META-INF/services/de.regelsuche.extension.RegelsuchePlugin` names
`example.GreetingPlugin`. Omitting it prevents discovery of the contribution.
This smoke run exercises the real provider and typed catalogue lookup; it does
not execute JUnit or replace Maven/Gradle qualification.

## Actual output and catalogue identity

The unchanged example source was compiled and run on Java 25 against the actual
three released 0.5.2 JARs on 10 November 2026. It printed:

```text
extension=hello
origin=greeting-plugin
greeting=Hello Ada
catalog=sha256:382c54a84050795402d4b9850f0139f4a92bfd8b24bc7c476e0ce0c917194c5a
```

`GreetingPlugin.contribute(ExtensionContext)` registers the `hello` implementation
under the consumer-owned `Greeting` contract. `Main` retrieves that typed
registration, invokes it and closes the runtime. The catalogue hash describes
this catalogue snapshot; it is not a publisher signature or a mathematical
certificate. Changes to the contribution or descriptor can change it.
The existing JUnit test also reloads the same classpath snapshot and requires
successful publication with the same hash.

The independent Maven package attempt in this environment was blocked while
resolving imported BOMs because Maven Central DNS was unavailable. A successful
direct Java run is reported above; a successful Maven/JUnit run is not claimed
by that local recording. The separate
[released Maven qualification](../../../docs/released-sdk-maven-qualification.md)
now builds both complete consumers in isolated directories and empty caches,
requires actual JUnit reports, checks the exact runtime closure and invokes the
CLI. Its CI receipt must pass independently of the earlier smoke run.

## Trust and integration boundary

`ExtensionRuntimeConfig.classpath(...)` loads **trusted in-process classpath
code**. This sample is external to the Regelsuche source checkout, but it does
not exercise external-JAR admission. Classpath discovery is not signature
verification, a sandbox or permission to execute an untrusted downloaded plugin.

A host that selects `externalPluginJars` must supply `PluginArtifactAdmission`
and the required explicit shared host types. The runtime admits immutable byte
snapshots before loading external providers. Admission is a provenance/trust
boundary, not a sandbox for hostile Java code. See
[generic extensions](../../../docs/generic-extensions.md) for admission,
transactional reload and lifetime rules.

This headless v2 greeting does not install an application rule, migrate v1
plugins or `.regelsuche` rule packs, populate a hosted catalogue, or hand a
published artifact automatically into the running Workbench. Those product and
distribution integrations retain their separately documented limits in the
[plugin index](../../../docs/plugin-index.md).
