# Target-free held-out container reproduction

The target-free held-out matrix is reproduced as three independent executions
bound to one exact repository revision:

1. checkout JVM run A;
2. checkout JVM run B;
3. an offline run in a digest-pinned Linux container.

All three runs must emit exactly these canonical files:

```text
target-free-held-out-plan.json
target-free-held-out-candidate-freeze.json
target-free-held-out-post-freeze-qualification.json
```

The Java verifier rejects a missing or additional file, symlink, malformed or
non-canonical artifact, row-identity drift, an invalid plan/freeze/
qualification binding, premature qualification disclosure, or any byte
difference between the three executions. Its JUnit characterization also
proves that byte drift, an invalid image identity and an additional artifact
fail closed.

## Reproduction command

Run the complete gate from a checkout with Docker available:

```bash
./gradlew --no-configuration-cache \
  verifyTargetFreeHeldOutContainerReproduction
```

The task is also part of `fullCheck` and therefore the authoritative `ciCheck`
lifecycle. The focused Java/JUnit contract is available through either build
surface:

```bash
./gradlew --no-configuration-cache \
  :regelsuche-discovery:test \
  --tests '*TargetFreeHeldOutContainerReproductionVerifierTest'

mvn -pl regelsuche-discovery \
  -Dtest=TargetFreeHeldOutContainerReproductionVerifierTest test
```

No new host-side Python, Node or shell verification semantics are introduced.

The generated evidence is written below:

```text
build/reports/target-free-held-out-container-reproduction/
  host-a/
  host-b/
  container/
  container-image-id.txt
  reproduction-manifest.json
```

## Container identity and isolation

`Dockerfile.target-free-held-out-reproduction` starts from the exact base image:

```text
eclipse-temurin:25.0.3_9-jdk-noble@sha256:3eb81ed94d8c1a34422f19f8188548bdf02cae69c91d0328afdbb7abed90f617
```

The image build may resolve the versioned Gradle dependencies. The evaluated
matrix run itself then uses Gradle offline mode, runs as the unprivileged user
`reproducer` with UID 10001, and is started with Docker networking disabled.
The receipt retains both the pinned base-image digest and the content-addressed
local built-image ID.

The runner preserves the bind-mounted `/out` directory and clears only its
contents before execution. This avoids relying on deletion of a mount point and
keeps the same output contract for host and container runs.

## Reproduction receipt

The schema
`regelsuche.target-free-held-out-container-reproduction/v1` and the matching
immutable Java record model bind:

- the repository revision;
- the six cases, four policies, six checkpoints and 144 rows;
- plan, candidate-freeze and post-freeze qualification identities;
- exact artifact byte lengths and SHA-256 values;
- the Dockerfile and base-image digests;
- the built-image ID, platform and runtime network policy;
- the Gradle-wrapper distribution checksum;
- the common artifact-set hash of both host runs and the container run;
- the host-repeat and host-versus-container byte-equality decisions;
- the schema path, exact schema bytes and receipt content hash.

The verifier reconstructs each existing Java artifact through its canonical
codec, rather than trusting JSON field inspection alone. It then recomputes the
complete receipt hash and rejects a non-canonical round trip.

## Claim boundary

A green receipt establishes exact byte reproduction for the frozen matrix, one
repository revision, the checkout JVM environment and the declared
`linux/amd64` container. It does not establish external mathematical novelty,
global optimality, universal policy superiority, equal CPU cost, or
reproducibility on every operating system and architecture.
