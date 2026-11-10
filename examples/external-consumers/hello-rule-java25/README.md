# hello-rule-java25

An independent Java 25 consumer of the **released 0.5.2 SDK**. Copy this whole
directory anywhere; it has no dependency on a Regelsuche source checkout.
Download and unpack [regelsuche-sdk-0.5.2.zip](https://github.com/carstenartur/Regelsuche/releases/download/v0.5.2/regelsuche-sdk-0.5.2.zip),
then verify its outer hash and internal `SHA256SUMS.txt` as described in the
[quickstart](../../../docs/java-sdk-quickstart.md).

With JDK 25 and Maven 3.9.9 or newer from the 3.x line, run in this directory:

```bash
mvn --batch-mode --no-transfer-progress \
  -Dregelsuche.repository=file:///absolute/path/regelsuche-sdk-0.5.2/repository \
  clean package
java -cp "target/classes:target/dependency/*" example.HelloRule
```

Windows uses `;` instead of `:` in the Java classpath. Use an absolute `file:` URI
for `regelsuche.repository`. The default points at the repository directory when
this example is inside an extracted SDK bundle. Maven downloads its plugins,
JUnit and transitive third-party dependencies from Central; the ZIP alone is not
a fully offline Maven installation. The imported BOM is
`de.regelsuche:regelsuche-bom:0.5.2`; the direct dependency is
`de.regelsuche:regelsuche-plugin-api:0.5.2`.

`package` runs the two JUnit tests, compiles the plugin and copies runtime JARs.
The ServiceLoader descriptor is required and is included in `src/main/resources`.
Expected CLI output:

```text
rule=hello-add-zero
before=x + 0
after=x
replay=VERIFIED
negative=REJECTED
proof=AUTHOR_SUPPLIED_IDENTITY
```

The CLI also checks the expected result and rejects `x + 1`. A replay reruns
one retained rule/input pair. It does not establish the mathematical validity
of arbitrary executable plugin code. Here the author supplies the identity
`a + 0 = a`; there is no external proof-backend claim.

For the existing Gradle consumer contract, invoke the wrapper from the extracted
SDK root (the old `../../gradlew` path from this directory was one level short):

```bash
./gradlew --project-dir examples/external-consumers/hello-rule-java25 clean test run \
  -PregelsucheRepository=/absolute/path/regelsuche-sdk-0.5.2/repository \
  -PregelsucheVersion=0.5.2
```

The Gradle verification additionally checks the four expected Regelsuche runtime
modules. This new Maven POM is maintained in source after 0.5.2; it is not claimed
to have been present in the immutable 0.5.2 ZIP. Download/copy the current example
for the Maven route; the released SDK binaries remain 0.5.2.
