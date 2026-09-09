# Primachsenraum as an external Regelsuche SDK consumer

This standalone Java 25 project exercises the student-facing Regelsuche
Discovery SDK without depending on the Regelsuche source tree or its `app`
module.

The domain searches a bounded Miller-Rabin base plan for odd values up to
100,000. Base 2 is refuted by the concrete composite counterexample 2047. The
next selected plan, bases 2 and 3, is independently and exhaustively checked
against a separate exact primality oracle over the whole finite interval.

The certificate is deliberately finite:

```text
EXHAUSTIVE_FINITE_RANGE_VALIDATION_NOT_UNBOUNDED_PROOF
```

No general two-base primality theorem is claimed.

The repository workflow currently pins the merged Regelsuche SDK slice at
commit `1a31f5a0bc9f1953947175e16d09850a9bbd7d91`. Until a tagged public Maven
coordinate exists, it publishes that exact six-module SDK dependency closure
to an isolated local Maven repository. It then copies this consumer into a
fresh workspace, builds it with an empty `GRADLE_USER_HOME` and
`--refresh-dependencies`, and resolves the `de.regelsuche` group exclusively
from that local repository. The verification rejects missing or additional
Regelsuche runtime modules, rejects `app`, Spring, Hibernate and persistence,
and retains SHA-256 hashes for the binary, POM, sources and Javadoc artifacts.
