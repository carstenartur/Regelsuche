# Reproduction environment maintenance

Ordinary application and build images receive routine dependency updates.
Scientific reproduction definitions identify an immutable environment retained
with evidence. An image refresh for such an environment requires a new version;
it cannot change the meaning of existing v1 artifacts or receipts.

## Current ownership

| Definitions | Maintenance owner |
| --- | --- |
| `Dockerfile`, `Dockerfile.autopilot`, `Dockerfile.comparative-benchmarks`, `Dockerfile.proof`, `Dockerfile.release-readiness` | Routine Dependabot Docker updates, with the existing Java-major restriction and exact image-policy review |
| `Dockerfile.visual-regression` | Routine Docker updates, with the existing Playwright dependency/image equality contract |
| `Dockerfile.target-free-held-out-reproduction`, `reproduction/Dockerfile.reproduction` | Explicit versioned reproduction-environment migration |

The current v1 reproduction image is
`eclipse-temurin:25.0.3_9-jdk-noble@sha256:3eb81ed94d8c1a34422f19f8188548bdf02cae69c91d0328afdbb7abed90f617`.
Both definitions, all three reproduction v1 schemas and their paths remain
unchanged. The v1 Java verifier, Python builder/verifiers and receipt checks
retain their existing image and schema identities. Existing artifacts continue
to carry their original source archive, schemas, commands and provenance.

`.github/dependabot.yml` excludes the two frozen definitions through literal
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
deliberately accepts one root Docker job and two literal exclusions. Additional
jobs, broader exclusions, dependency allow-lists, redirected targets or disabled
routine Temurin updates require an explicit ownership review. The six ordinary
Dockerfiles remain eligible for maintenance.

The contract also preserves the SHA-256 of the two v1 Dockerfiles and three v1
reproduction schemas as retained at commit
`8afeda1a538c6726b0f6b8a6c8014dfcced0fbe2`. A partial Dockerfile-plus-policy update,
a simultaneous image update of both lanes, or even a semantically neutral schema
formatting change fails. These byte identities are part of the historical
environment contract; a migration adds identities instead of updating these
expected hashes.

The five frozen files also have explicit `text eol=lf` attributes. Their
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

## Future intentional v2 migration

No v2 image, descriptor, schema, receipt or evidence run is selected by this
maintenance change. When a reproduction-environment refresh is explicitly
selected, carry out the following as one reviewed migration:

1. Record the reason and select the exact new Temurin tag and index digest.
   Introduce a versioned environment descriptor, for example under
   `reproduction/environments/v2/`, with a new environment identity and content
   hash. Bind the image, supported platforms, Gradle distribution/checksum,
   runtime user and build/evaluation network policies in that descriptor.
2. Add versioned v2 definitions for both reproduction lanes. Generate their
   pinned image references from the descriptor, or validate every reference
   against it before execution. Have the Java verifier, Python builder and both
   verifiers, schemas and self-tests consume or validate the same descriptor and
   its hash. Reject missing, unknown-version or mismatched bindings. Keep the v1
   definitions, validators and byte identities available for historical replay.
3. Introduce new environment/artifact/receipt schema identities and explicit
   version dispatch. A v1 receipt must continue to validate against its original
   schema bytes and image contract; never select the newest environment merely
   because it is the current default. Bind the v2 schema bytes in new artifacts
   and receipts using the new version's contracts.
4. Retain an explicit predecessor link containing the v1 environment, schema and
   retained artifact identities plus their source revision. Describe any
   cross-version result comparison as a comparison between environments. Do not
   assert byte identity between v1 and v2.
5. Under the explicitly selected migration, regenerate the current evidence in
   v2, verify repeated runs within that same environment and retain independent
   verification receipts. Preserve historical v1 evidence. Evidence generation,
   held-out use and FINAL TEST execution remain governed by their existing
   authorizations and exposure rules.
6. Extend maintenance ownership to the new frozen definitions while retaining
   every v1 exclusion and hash. Add negative controls for mismatched descriptors,
   consumers, schemas, image pairs and version dispatch. Qualify the complete
   migration with all Gradle/`ciCheck`, Maven/product/Docker, JMH and SymPy
   authorities on its final commit before publishing it.
