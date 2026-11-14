# Modular-program transfer with arithmetic AC recognition

The frozen model reached the optimum of complete one-step primitive search on all
six positive programs with associative and commutative (AC) arithmetic recognition.
Exact matching reached it on three. Both profiles made zero proposals on the two
negative programs. This is a public replication on new source programs using the
previously released model. The historical v1 result remains 3/4.

| Program | Output count | Source DAG cost | Primitive optimum | Exact | Arithmetic AC |
| --- | ---: | ---: | ---: | ---: | ---: |
| T01 | 2 | 49 | 34 | 34 | 34 |
| T02 | 2 | 52 | 36 | 52 | 36 |
| T03 | 4 | 55 | 38 | 55 | 38 |
| T04 | 4 | 58 | 40 | 40 | 40 |
| T05 | 6 | 61 | 42 | 61 | 42 |
| T06 | 8 | 64 | 44 | 44 | 44 |
| T07 | 2 | 67 | 67 | 67 | 67 |
| T08 | 5 | 70 | 70 | 70 | 70 |

T02, T03 and T05 place the reused exponent on the other side of the product.
The matched proposals preserve every other output object. The independent
modular-composition checker binds each accepted step to the concrete premises.
The two TRAIN observations with broken reuse produced no saving.
Both profiles attempted 136 output assignments across eight programs. The
AC matcher additionally explored 88 structural branches. DAG cost describes
the selected program. The additional work of learning, matching and verification
requires a separate finite-budget comparison.

The protocol, corpus and expected model hash were committed before evaluation.
The learner regenerated the original 5,027-byte model using only the v1 TRAIN
file. Two separate evaluation JVMs produced identical bytes. The
[protocol](protocol.json) binds the input hashes and decision criteria.
[Preregistration commit](https://github.com/carstenartur/Regelsuche/commit/e4ebe15d6bd8afe8f8f5528af18037119d964448)

Run the reproduction and its premise and binding controls from the repository
root with JDK 25 and Maven.

```sh
mvn -pl app -am -Dtest=ModPowAcTransferTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The raw report is generated at
`app/target/discovery-artifacts/modpow-transfer-ac-v2/result.json`.
Its expected SHA-256 is `ff9682844a965fdaeefe7cb1bff8564739d5fea834534f03e721d0195d3c41a7`.
