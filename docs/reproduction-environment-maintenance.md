# Reproduction environment maintenance

Ordinary application and build images receive routine dependency updates.
Scientific reproduction definitions identify an immutable environment retained
with evidence. An image refresh for such an environment requires a new version;
it cannot change the meaning of existing artifacts or receipts.

## Current ownership

| Definitions | Maintenance owner |
| --- | --- |
| `Dockerfile`, `Dockerfile.autopilot`, `Dockerfile.comparative-benchmarks`, `Dockerfile.external-polynomial`, `Dockerfile.external-proofs`, `Dockerfile.external-python`, `Dockerfile.proof`, `Dockerfile.release-readiness` | Routine Dependabot Docker updates, with the existing Java-major restriction and exact image-policy review |
| `Dockerfile.visual-regression` | Routine Docker updates, with the existing Playwright dependency/image equality contract |
| `Dockerfile.target-free-held-out-reproduction`, `reproduction/Dockerfile.reproduction`, `reproduction/environments/v2/Dockerfile.reproduction`, `reproduction/environments/v3/Dockerfile.reproduction` | Explicit versioned reproduction-environment migration |
| `Dockerfile.safe-runtime-qualification` | Explicit versioned public runtime reproduction migration; same pinned base image, separate public qualification authority |

The current v1 reproduction image is
`eclipse-temurin:25.0.3_9-jdk-noble@sha256:3eb81ed94d8c1a34422f19f8188548bdf02cae69c91d0328afdbb7abed90f617`.
Both definitions, all three reproduction v1 schemas and their paths remain
unchanged. The v1 Java verifier and receipt checks retain their existing image and schema identities. Existing artifacts continue
to carry their original source archive, schemas, commands and provenance.

The additive public DIRECT/V4 qualification also retains its own Dockerfile and
schema byte identities from `fc55f6cffe5d69bd2e517d0e36bdb0d5f5de4812`. This adds
two identities without editing any of the five earlier retained files. It does
not reopen the historical experiments or change the fixed public case manifest.

`.github/dependabot.yml` excludes the five frozen definitions through literal
`exclude-paths` entries on the root Docker job. GitHub documents these as file
or directory exclusions relative to the update entry's directory, distinct
from dependency-name `ignore` rules. Its Docker fetcher reads matching files
directly in the selected directory and uses the common fetcher's path filter.
Thus the root frozen Dockerfile is filtered before parsing. The second frozen
definition is also explicitly excluded to retain its maintenance ownership;
adding a separate `/reproduction` update job must not bypass that ownership.
No Dockerfile move or change to historical source-archive paths is needed.
See the [GitHub option reference](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference#exclude-paths),
[Docker fetcher](https://github.com/dependabot/dependabot-core/blob/main/docker/lib/dependabot/docker/file_fetcher.rb)
and [common fetcher](https://github.com/dependabot/dependabot-core/blob/main/common/lib/dependabot/file_fetchers/base.rb).

Excluding all `eclipse-temurin` dependencies or merely excluding them from a
group would not establish this ownership. The former would stop ordinary image
maintenance; the latter would still permit separate update PRs. Dependabot may
propose ordinary image updates, while maintainers still synchronize the exact
entries in `config/quality/container-image-policy.json` before qualification.

## Checkout and CI contract

`MavenFrozenReproductionEnvironmentContractTest` verifies the actual parsed
Dependabot configuration and the declared Docker inventory. The current contract
deliberately accepts one root Docker job and five literal exclusions. Additional
jobs, broader exclusions, dependency allow-lists, redirected targets or disabled
routine Temurin updates require an explicit ownership review. The nine ordinary
Dockerfiles remain eligible for maintenance.

The contract also preserves the SHA-256 of the two v1 Dockerfiles and three v1
reproduction schemas as retained at commit
`8afeda1a538c6726b0f6b8a6c8014dfcced0fbe2`. A partial Dockerfile-plus-policy update,
a simultaneous image update of both lanes, or even a semantically neutral schema
formatting change fails. These byte identities are part of the historical
environment contract; a migration adds identities instead of updating these
expected hashes.

The seven v1 files and the four files for each of v2 and v3 also have explicit
`text eol=lf` attributes. Their
content-addressed bytes therefore survive a checkout with `core.autocrlf=true`.

The existing `MavenContainerImagePolicyContractTest`, Dockerfile path checks,
paired frozen image binding and historical receipt/schema verification remain
mandatory. The new contract is part of the normal Maven build-contract module;
Gradle's `verifyContainerImagePolicy` already delegates to the Maven core reactor.
It adds no test exclusions and weakens no image, schema, byte or receipt check.

Run the focused ownership and image-policy checks with Java 25 and Maven:

```bash
mvn -pl maven-build-contract -am \
  -Dtest=MavenFrozenReproductionEnvironmentContractTest,MavenContainerImagePolicyContractTest,MavenContainerImagePathContractTest \
  test
```

These controls exercise configuration and temporary mutations of checkout
inputs. They do not execute Dependabot's hosted service or regenerate scientific
evidence. Full Gradle/`ciCheck`, Maven/product/Docker, JMH and SymPy qualification
remain separate required authorities.

## Current Gradle 9.9.2 toolchain transition

New independent-reproduction artifacts use [v3](../reproduction/environments/v3/environment.json).
The v3 descriptor adds Gradle 9.9.2 while retaining the existing Temurin image,
architectures, runtime user and network policies. The distribution checksum is
`971fcd7150ca4ba04852c0b2ca0d2d0ab9468e14b03473eab19528809914c76b`, from the
[official Gradle 9.9.2 release metadata](https://github.com/gradle/gradle-distributions/releases/tag/v9.9.2).
The wrapper JAR remains bound to
`621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8`.
The active student starter and its external-consumer verifier require the same
9.9.2 wrapper and copy its distribution checksum unchanged.

The v3 descriptor, Dockerfile, artifact schema and receipt schema have new byte
identities. Every frozen v1 and v2 file retains its original bytes. The v3
predecessor records the v2 descriptor, Dockerfile and schema identities at
`f13c2b6537037f33d1e5b6cb748e1768b59f09b5`; it does not assign an identity to an
unavailable historical bundle. Replay still requires the original bundle or
matching checkout and recorded inputs.

The verifier selects v1, v2 or v3 from the artifact identity. A damaged manifest
uses the exact bundled receipt-schema bytes to retain that version's negative
receipt. It rejects unknown versions, substituted descriptors, wrapper pins,
schema bytes and receipts from another version. The existing lifecycle gate
exercises these historical contracts alongside current v3 artifact determinism
and receipt semantics. These local controls do not claim an independent rerun
of retained historical scientific results.

## Retained Gradle 9.9.1 toolchain transition

The earlier Gradle 9.9.1 transition uses [v2](../reproduction/environments/v2/environment.json).
The v2 descriptor pins the wrapper distribution and JAR, Dockerfile, schemas,
Temurin base image, supported architectures and execution policies. The builder
archives exact `HEAD`. The verifier compares the environment bytes in that
archive with the descriptor and checks the bundled Dockerfile and schemas.
Receipt validation dispatches by artifact version. Retained v1 artifacts and
receipts keep their original environment, Dockerfile and schema identities.
Replay of a v1 artifact requires its original bundle or the matching source
checkout and recorded inputs. The predecessor descriptor records the source and
contract identities for v1 without assigning an identity to an unavailable bundle.
The current held-out receipt already binds the Git revision, exact Gradle
checksum, image ID and Dockerfile/schema hashes for every run. Its v1 schema
allows a new checksum per revision, and its Gradle and Docker gates still run.

## Future environment migrations

When an image or another frozen environment input changes, carry out the
following as one reviewed migration.

1. Record the reason and select the exact new Temurin tag and index digest.
   Introduce a versioned environment descriptor with a new identity and content
   hash. Bind the image, supported platforms, Gradle distribution/checksum,
   runtime user and build/evaluation network policies in that descriptor.
2. Add new versioned definitions for the affected reproduction lanes. Generate their
   pinned image references from the descriptor, or validate every reference
   against it before execution. Have the Java verifier, Python builder and both
   verifiers, schemas and self-tests consume or validate the same descriptor and
   its hash. Reject missing, unknown-version or mismatched bindings. Keep the v1
   definitions, validators and byte identities, and every later frozen version,
   available for historical replay.
3. Introduce new environment/artifact/receipt schema identities and explicit
   version dispatch. A v1 receipt must continue to validate against its original
   schema bytes and image contract; never select the newest environment merely
   because it is the current default. Bind the new schema bytes in new artifacts
   and receipts using the new version's contracts.
4. Retain an explicit predecessor link containing the previous environment,
   schema and available retained artifact identities plus their source revision.
   Describe any
   cross-version result comparison as a comparison between environments. Do not
   assert byte identity across environment versions.
5. Under the explicitly selected migration, regenerate the current evidence in
   the new environment, verify repeated runs within that same environment and retain independent
   verification receipts. Preserve historical evidence for every version.
   Evidence generation,
   held-out use and FINAL TEST execution remain governed by their existing
   authorizations and exposure rules.
6. Extend maintenance ownership to the new frozen definitions while retaining
   every historical exclusion and hash. Add negative controls for mismatched descriptors,
   consumers, schemas, image pairs and version dispatch. Qualify the complete
   migration with all Gradle/`ciCheck`, Maven/product/Docker, JMH and SymPy
   authorities on its final commit before publishing it.
