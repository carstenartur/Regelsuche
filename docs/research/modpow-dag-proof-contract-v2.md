# ModPow DAG experiment: conditional proof contract v2

Status: 18 September 2026. Issue #1024 / PR #1025.

## Verified correction

The original verifier checked integer/sign assumptions from the search context,
but its generated transformations retained no assumptions. It also accepted
altered rewrite metadata with the same source/target/application identity.
This is a proof-transport defect, not a counterexample to the modular exponent
law or the frozen DAG cost calculation.

The provider and every generated transformation now retain the normalized fixed
study assumptions. The verifier checks those assumptions, their presence in the
context, same-modulus one-step composition, primitive provenance, application
identity, rewrite kind, growth flag, zero cost delta, equivalence flag, pack and
license. Versioned v2 application and receipt identities bind the assumptions.
The fixed study domain conservatively includes r even in the positive cases;
this is not a claim that r is a necessary premise of the exponent theorem.

The report now uses the existing JsonWriter instead of unchecked string
concatenation. Quoted and control-character profile identifiers remain data.
Its v2 schema includes the actual canonical source and selected ASTs, the
normalized assumptions, and explicit work/cost limitations. This is not a
standalone formal proof certificate: the verifier and its known modular law
remain the mathematical authority, and the experiment still retains only
receipt counts rather than a full exported search trace.

## Test-first evidence

Test-only head: `ff87ec02689b4afa22d7fe1209f21ac0406c79e0`.
Run **35392520247**, artifact **10566209960**:

- Six existing study tests passed.
- Four new actual provider/verifier boundary tests failed by assertion.
- Two new report serialization/evidence tests failed by assertion.
- No test errors or skipped tests.

RED artifact SHA-256:
`28135ec3a3497fc8477b162813fa49563ae437f34ebd4c775264ad48a8dbed4f`.

The preceding run 35392326191 failed only to compile a test using an unavailable
Jackson import. It is not counted as regression evidence. The test was corrected
to use the checkout-owned JsonReader; no dependency was added.

Fixed source head: `babfa3dd0cf39e484e02512f9c47004dbee2a333`.
Run **35392831914**, artifact **10566540859**:
**12 tests passed, zero failures, errors or skips**, followed by successful
fresh v2 report generation. The downloaded JUnit XML and generated JSON were
checked directly, not inferred from the workflow badge.

GREEN artifact SHA-256:
`96115a1d9f499c5e1fbc1c4a6adfd00c6ed712b4b699275c1d6f21133300d184`.

Verified source blobs:

| File | Git blob |
|---|---|
| ModPowDagRediscoveryStudy.java | `03b9e1574e9582729660bb2ac62e111e8351e10d` |
| ModPowDagRediscoveryReport.java | `dfa6deba2f1d6322e7e90ad448fc0ec302bcddb7` |

The final archive/navigation change removes the temporary branch-only workflow
and does not modify those tested Java sources. The normal repository CI is a
separate merge gate; these focused tests do not substitute for it.

## Exact retained v2 result

[The gzip archive](modpow-dag-rediscovery-result-v2.json.gz) losslessly retains
all 11,982 bytes of the generated JSON, including repeated canonical AST data.
It is deterministic gzip with mtime zero, not a manually summarized substitute.

Compressed SHA-256:
`d68111fc45d852614fcceb87c9cae054e502354110a20e58d054be9b75d84fe3`.

Decompressed SHA-256:
`a8f83977ab7b09a21e9aa8d012dcf31edc243ed258fdbc8427b4f99980b4afed`.

The raw JUnit/source workflow ZIPs are identified above but are not copied into
this repository by this archive. The generated result itself is retained here.

Reproduce from the repository root:

```sh
./gradlew --no-daemon --no-configuration-cache :regelsuche-experiments:test \
  --tests 'de.regelsuche.benchmark.ModPow*Test'
./gradlew --no-daemon --no-configuration-cache :regelsuche-experiments:modPowDagRediscovery
gzip -dc docs/research/modpow-dag-rediscovery-result-v2.json.gz > /tmp/modpow-result-v2.json
sha256sum /tmp/modpow-result-v2.json
cmp /tmp/modpow-result-v2.json \
  regelsuche-experiments/build/reports/modpow-dag-rediscovery/modpow-dag-rediscovery-result-v2.json
```

Historical v1 plan and result files remain byte-identical. The plan SHA-256 is
`2080973d63d8f3a036cc584d99e897582d2983c988936bda2d7c33c060d64f46`;
the historical v1 JSON SHA-256 is
`21203ea84afb51969ca66824cb0d34a94b5126273e5ea1bcc7b0e58648dc2389`.

## Unchanged bounded result and limits

| Profile | Original TREE/DAG | Selected TREE | Selected DAG |
|---|---:|---:|---:|
| C31 | 46 | 46 | 31 |
| C61 | 91 | 91 | 61 |
| C93 | 139 | 139 | 93 |
| C123 | 184 | 184 | 123 |
| No shared e-residue | 139 | 139 | 139 |

The frozen microstudy still returns GREEN: three reached states, two generic
successors and two accepted conditional proof receipts per case. Its mechanical
work ledger remains 64, including 46 source/target-node receipt units.

Those units EXCLUDE complete provider traversal, domain-checking internals,
codec/hash/allocation work and post-hoc scoring. They are not a full CPU cost,
bit-complexity measure, or measured speedup. The arithmetic score is a declared
exponent-bit proxy; other operations have zero weight in this narrow model.

All four TEST entries use the same AST with different declared bit profiles.
The result demonstrates post-hoc selection of useful sharing from a complete
one-step relation containing two supplied generic exponent-composition rules.
It does not demonstrate learned transfer, discovery of the exponent law, a
strong superoptimizer comparison, or general search superiority. Learning and
transfer require a separately bounded experiment after this PR's merge gates.
