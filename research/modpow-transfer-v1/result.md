# Frozen modular-program transfer v1: YELLOW

## Executed result

The preregistered process-separated evaluation transfers **3 of 4 positive program contexts**. The no-reuse negative and relationship-breaking TRAIN control are clean. This is partial structural transfer of a supplied modular exponent law, not a new theorem, automatic rule promotion, or measured speedup.

| TEST context | Source DAG proxy | Complete primitive optimum | Frozen learned result | Approved learned proposals |
|---|---:|---:|---:|---:|
| RENAMED | 46 | 31 | 31 | 1 |
| REORDERED | 91 | 61 | 61 | 1 |
| EXTRA_OUTPUT | 139 | 93 | 93 | 1 |
| REVERSED_FACTORS | 184 | 123 | 184 | 0 |
| NO_REUSE | 139 | 139 | 139 | 0 |

Both primitive compositions were generated and independently approved in every row. The successful learned proposals reach the same optimum; they do not beat the complete primitive control. The extra output retains its physical slot and exact Expr object. TREE costs show no artificial improvement from counting reuse. Assignment attempts were 2, 2, 6, 2, 2; matcher-step counters were 2, 2, 6, 2, 2. These counters omit substantial work and are not a CPU comparison.

The reversed-factor input has the product `beta*alpha` while its separately required residue uses `beta`. TRAIN formed an exact structural pattern whose separately required residue is the right product factor. No proposal matches the reversed input. The primitive control still reaches 123; learned selection falls back to the unmodified input at 184. No code, corpus, or pattern was tuned after this observation. An additional commutativity-aware recognition or second learned orientation requires a separately versioned experiment.

## What was frozen and when

The protocol and two source-only corpus files were committed before official execution; completed corpus commit: `cbf07fdad3d80010e3af1b521c1fe9105a315db5`. No target expressions are fields in either corpus. Public development fixtures informed this implementation: this is a preregistered process-separated small evaluation, **not an independently blinded benchmark**.

Official TRAIN executed using only train.json. The official TEST file was absent from the local study directory at that point. The actual generated model, including two canonical source/target witnesses, concrete sample premises, and learned templates, was then committed in **7e4bd61d6eb8077d44583ce42fd376223fd9997c before the official TEST process ran**.

Model: 5,027 decompressed bytes, SHA-256 `e027a7d4b2b03b5b826eb6c5c7513c7f2241be32667e3813226efa77205f2068`.

Official TRAIN SHA-256: `c2932bb1a8a4412a819da52fb0b75ec62dae768b9e2261f19b1e7c6b6725c978`.
Official TEST SHA-256: `0ae4cdc2fc7335d672dfdadc99fb6b8c58b880e72605db9d74e155e21121472f`.

TEST ran in a separate JVM. The expected model digest is checked before reading TEST. A second TEST-only process, without retraining, produced a byte-identical result; the model bytes were unchanged.

Result: **12,070 decompressed bytes**, SHA-256 `1f79ed2ecac15a74c130cc56f6815f78c6751d61faa92c6bbfee880bc9912db1`. Deterministic gzip SHA-256: `ae7610a054d833bf862ef84433c7e5fff7134501d0d7c6bdbbca59939cffe898`. The raw model and result are retained losslessly as model.json.gz and result.json.gz. freeze.json records the earlier model freeze.

## Actual implementation and test scope

The runner reuses the existing AstRewriteTransport, two generic product-composition rules, TypedPatternGeneralizer, exact TypedOutputPattern, ModPowCompositionReplay and unchanged #1025 cost proxy. Canonical role renaming occurs solely for the historical cost calculation; it does not normalize matching or teach an orientation. The reader rejects target fields, duplicate JSON keys, invalid split, non-injective roles, malformed bit profiles and oversized inputs. Output files use CREATE_NEW and cannot overwrite frozen evidence.

Six development tests first ran against a deliberately empty local API scaffold and failed by assertion. After implementation, all six pass. A fresh combined run passed **31 genuine JUnit tests**: those six plus 25 existing output-pattern, arithmetic-study, proof-contract and report tests; zero failures, errors, skips or aborts. Those tests do not read the official TEST corpus.

Runtime actually used: **OpenJDK 21.0.11, JUnit 6.1.3 and Jackson 2.22.2**. Local network resolution prevents a complete Gradle build. This is source-subset compilation with actual repository sources and pinned dependency jars recovered from retained CI evidence, not a replacement implementation, complete module suite, or Java-25 repository CI. Archive 10584622334 (run 35442856353) was rehashed: `4948629e091188d39ef432fa24082280d408a48d30e814c3a2a26ed03815cde6`. Relevant integrated source updates were checked against their Git blobs.

Executed and committed new Java blobs:

| File | Blob |
|---|---|
| ModPowTransferFiles.java | 8acfca1127fc7ed81e52a1a2047b46784b59e279 |
| ModPowFrozenTransfer.java | 669e4e2055c46fb0ec7f9aeb7e75387e9f003166 |
| ModPowFrozenTransferTest.java | f485b5796d77a8c372fe681019398cea0285a17f |

No existing implementation or frozen #1025 evidence was edited. Integration #1034 is unchanged; full current-head Java-25 CI remains required before merge.

## Reproduction in a normal checkout

The optional init script adds two research tasks without changing default build tasks. Its hosted Gradle execution is a separate pending qualification, not part of the local subset claim above. Use a fresh output directory:

```sh
D=$(mktemp -d)
ROOT="$PWD"
./gradlew --no-daemon --no-configuration-cache :app:test \
  --tests 'de.regelsuche.benchmark.ModPowFrozenTransferTest'
./gradlew --no-daemon --no-configuration-cache -I scripts/modpow-transfer-v1.init.gradle \
  :app:modPowFrozenTrain \
  -PtransferTrain="$ROOT/docs/research/modpow-transfer-v1/train.json" \
  -PtransferModel="$D/model.json"
sha256sum "$D/model.json"
# This invocation does not retrain. The digest is from the committed freeze, not newly inferred from TEST.
./gradlew --no-daemon --no-configuration-cache -I scripts/modpow-transfer-v1.init.gradle \
  :app:modPowFrozenTest \
  -PtransferModel="$D/model.json" \
  -PtransferSha=e027a7d4b2b03b5b826eb6c5c7513c7f2241be32667e3813226efa77205f2068 \
  -PtransferTest="$ROOT/docs/research/modpow-transfer-v1/test.json" \
  -PtransferResult="$D/result.json"
gzip -dc docs/research/modpow-transfer-v1/result.json.gz > "$D/retained.json"
cmp "$D/retained.json" "$D/result.json"
```

A non-GREEN scientific verdict is retained as data, not converted into a build failure. Invalid inputs, failed prerequisites or hash mismatches fail closed. Model sample assumptions do not authorize arbitrary substitutions; the unchanged independent arithmetic auditor checks every concrete application against supplied TEST premises.

The original issue's strict superiority over an exhausted primitive optimum is impossible for this one-step derived macro. The preregistration explicitly preserves that stronger control and reports parity instead. A runtime or fixed-budget search benefit, automatic premise inference and broader transfer remain unproven.
