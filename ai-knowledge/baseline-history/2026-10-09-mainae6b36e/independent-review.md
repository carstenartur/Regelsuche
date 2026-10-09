# Independent accepted-main baseline review

Review scope: read-only local inspection of the proposed accepted-main baseline under `docs/ai-knowledge.md`. No source/baseline edits, build executions or remote actions were performed by the reviewer. Review date: 2026-10-09. This record retains the initial rejection and the completed review of the clean replacement run below.

**Final decision: APPROVE promotion of the exact clean accepted-main snapshot and its corrected provenance.** The approved snapshot is `47ca2375967a95763395ad65bad1bff60a0f8e82ed9246684b0ea8dfea2f28ef` (682835 tokens). Both initial findings are resolved. Approval covers only a byte-for-byte copy of this generated snapshot, under the unchanged policies and source/tool bindings reviewed here. It does not approve any feature PR, P04 completion or performance claim. Post-merge main CI remains a separate pending integration gate; the parent must attach its final successful result before integration. The exact source tree already has complete successful source-PR CI.

**Historical decision for the first run: BLOCK promotion.** Its snapshot bytes match the generated output, but that output includes two ignored local documents outside the accepted Git tree. Successful gates and clean ordinary Git status did not repair that source binding. Its second provenance defect was that `policySha256` hashed the lifecycle script instead of the actual plugin threshold configuration. The rejected snapshot remains rejected even though the replacement is approved.

## Findings and required correction

1. **Important — measurement includes uncommitted local documents.** `build/ai-knowledge/docs.json` has 418 entries. Exactly two paths are absent from `git ls-tree -r --name-only ae6b36eae23ed96aed2f0815442ab67634f87065`: `.superpowers/sdd/2026-09-20-work-replacement/progress.md` and `.superpowers/sdd/2026-09-20-work-replacement/review-p04-validation.md`. Both are ignored by the checkout-local `.git/info/exclude` rule `.superpowers/`, explaining the empty status. The pinned extractor's `RepositoryFileInventoryScanner` uses its own fixed exclusions for Git, Gradle, build and target directories; it does not apply Git ignore rules. `ReportAnalyzer` assigns each document 180 estimated tokens. The raw counts reproduce the reported result exactly: `1683*260 + 1134*140 + 418*180 + 293*35 + 17*80 = 683195`; these two documents contribute 360 tokens. Other metrics can also be affected. **Do not arithmetically edit the snapshot.** Retain this run as rejected, remove the untracked inputs from the measurement root without changing tracked source, and rerun the complete unchanged gate. Review all generated source paths against the accepted tree afterward.
2. **Important — incomplete/mislabelled policy binding.** `policy.gradle` equals `gradle/ai-knowledge-verification.gradle` and its SHA-256 is correct, but this is lifecycle wiring. The actual absolute/trend thresholds are in `ai-knowledge-verification/build.gradle`, SHA-256 `2aaf02538386418eeb3a5de354942514ba697cedd5cd658edf0cd44e2cdbfdd5`. That is also the policy hash used by the prior accepted baseline. Retain distinct bindings for the plugin configuration and lifecycle, plus the selector, coverage, claim and hotspot files listed below. No change to those source files is required.

Post-merge main CI was still pending in the reviewed `current-main-ci.json`; final qualification is therefore not claimed by this review. The parent is collecting the final result. The retained source-PR CI already qualifies the byte-identical source tree: PR #1076 head `151826e648da62784574573b0980da42d3b68beb`, workflow `37938454239`, attempt 1, all six authorities and `Checkout-local ciCheck` successful. Optional JMH studies and report publication were skipped, not executed qualification.

## Verified source, policy and tool bindings

Accepted source commit: `ae6b36eae23ed96aed2f0815442ab67634f87065`; tree: `eb052f81047973154d99105273de5851b0c9b9a3`. The PR head above is its second parent and has the same tree, independently confirmed from local Git objects. Ordinary source status is empty; the ignored-input finding above remains decisive.

Extractor HEAD and exact `v0.1.10` tag: `b409bed957c31d63ce7b6ef37205890f0f0ebd9a`; tree: `0cb8e41b91c831705f9c86633580181372da6e14`; current tracked/untracked status is clean. `projectVersion=0.1.10`. The local initializer selects JDK 25 only for the extractor; the pinned build retains `options.release = 17`. The adapter selects Gradle 9.7.1, matching the repository wrapper, and supplies runtime/truststore/proxy settings. It does not change tasks, selectors or thresholds. The initial receipt recorded source-before and source-after cleanliness and one extractor cleanliness value; the corrected receipt explicitly records source/tool before and after observations and explains the reuse of the preceding extractor observation. Historical observations are recorded execution evidence, not events personally witnessed by this reviewer; current cleanliness and identities were independently rechecked.

All repository files below match the accepted source commit byte for byte. All except `settings.gradle` also match prior accepted main `ab48fb0a47eec12cb63c30de253de50d2a72db1c`. The only settings differences are the accepted optimization-SDK module additions; the extractor enablement and selection are unchanged.

| Repository binding | SHA-256 |
| --- | --- |
| `ai-knowledge-verification/build.gradle` — plugin thresholds | `2aaf02538386418eeb3a5de354942514ba697cedd5cd658edf0cd44e2cdbfdd5` |
| `gradle/ai-knowledge-verification.gradle` — lifecycle | `f1df0d336d519f10a0bf35129e1b80cd8cbbad5d0d3939e0fffd2cd5d7ee91b8` |
| `gradle.properties` — extractor pin | `2d0c622ff20ce78d0432c004df2dba7af170bdb50f5ce68faffdc5bcd1f1b285` |
| `settings.gradle` — plugin/checkout selection | `94a43f75ca24cdba052ff86535c03cf287ac1b207ab571fd7b56d19f67a195cc` |
| `ai-knowledge/capabilities.seed.yaml` | `16d64630109485ca14a5c1566d64084f3b932d7edf52bf70facd4274de0b8d0b` |
| `ai-knowledge/claims.seed.yaml` | `013935ebad67ff3868602be8c68b73dba723610ad5ba728113f87e114f3dc108` |
| `ai-knowledge/claims.json` | `cce03fad40abaa23770707154b2cab60afe37f1b0cbc8fb4d8865a886480b0c1` |
| `config/ai-knowledge-capability-coverage.json` | `9c1705776a062b5784cd9bad1cdc847878338f1ce32ac1fc66e7769ed98adbed` |
| `config/quality/complexity-hotspots.json` | `67cfc003891fcb8765a0d9999ce68fe4d65f357d189af21d099d6e3dbb3b50d4` |
| `scripts/verify-ai-knowledge-artifacts.py` | `845708cb45420565d5636539b00b60034162db762132bf781b6d3f9b4a7b3f7b` |
| `scripts/test_ai_knowledge_coverage.py` | `ec21b3b2050f528c221e118c5e87948847e9de32384ee5eb6b382eea2210bca4` |
| `scripts/verify-complexity-hotspots.py` | `d792435d075d8102bbe5504af83f84d6c660a20b1234e8516f8a5d5ac3775bfa` |

The configured absolute limits remain normalized debt 21.7, maximum method cognitive/cyclomatic complexity 65/35, and average complexity 4.0/4.0. Trend limits remain normalized debt +10, concept radius +3, tokens +15000. `failOnWarnings=false` is unchanged; the six existing warnings remain present. Hotspot allowances remain cognitive +2/cyclomatic +1 with no exceptions. Neither `ai-knowledge/model-profiles.yaml` nor `.yml` exists, so the pinned built-in model profiles apply.

| Extractor binding, relative to its repository | SHA-256 |
| --- | --- |
| `core/src/main/java/org/aiknowledge/core/repositoryscan/RepositoryFileInventoryScanner.java` | `b89b02132c1f4f4fc753ef3bbe7b4752a8696dc9b3a12d1c37b79052a56756e0` |
| `core/src/main/java/org/aiknowledge/core/ReportAnalyzer.java` | `7d7bc7cd0220a5424c287c1c019d3bf488c578b81f3a303633cbd85ee13bd700` |
| `core/src/main/java/org/aiknowledge/core/TrendAnalyzer.java` | `17450bbe6c00bd16ee9ba45d6eb520b0e9b4403ba8c956cd71011ef0bc2179ae` |
| `core/src/main/java/org/aiknowledge/core/ModelProfileSupport.java` | `bef8660d98b698de5fcca5fb092436de2e99ba18b7c9bcbf97cc224f8439b4c0` |
| `core/src/main/java/org/aiknowledge/core/KnowledgeExtractionPipeline.java` | `ac405ba819f41cd9f5deb59682dac10b36be6e7facf1f015a04ec9f85fb6cdf9` |
| `gradle-plugin/src/main/java/org/aiknowledge/gradle/AiKnowledgePlugin.java` | `4f418fe79ebab1439d032d9340c5a4781936d247d823e0eb04d269cb6c78d850` |
| `gradle-plugin/src/main/java/org/aiknowledge/gradle/AiKnowledgeExtension.java` | `eee09031ba2bd1402325160c7f1f09dc6429eddec35a9554b7124a4ab84aad58` |

External runtime bindings: initializer SHA-256 `f59cce856894b4da8d841fe6db5ba57c6c561fe80ddfbbc7196432030273ba95`; adapter SHA-256 `678bac3cca4367e02467e9213a94cf5c24a79099fd0a5de25dfdf128f834d1ab`; selected JDK `release` file SHA-256 `10442d5ef7930f4220a3336d65f020b24812660824ea995a51ba29f278aed30b` (Temurin 25.0.2+10). The first two match the retained manifest.

## First-run artifact checks and rejection boundary

All ten entries in the first provenance artifact map matched their SHA-256 values. The old baseline is unchanged: `ffe19a30017b1ce269f95bf42aad0e98994506a045df8530b008020c37b06399`, byte-identical between the live baseline, retained previous baseline and 2026-09-29 accepted snapshot. `trend.baseline` reproduces every metric/model value; the extractor intentionally omits `schemaVersion` while parsing the baseline. `trend.current` equals the full proposed snapshot.

The rejected 683195-token snapshot is `ecadb7772e8d1e6abf767c4cfa09226652a87fe2cc7306bbe0ac42499b036b9c`; it and retained `check.json`/`trend.json` match the root generated bytes. Its full gate completed successfully with eight coverage controls and 17 hotspots, and +14330 tokens against the unchanged old baseline. These facts remain true, but do not establish an exact-tree measurement. The failed PR #1068 diagnostic snapshot is separate (`8b3380779acc75fb189c2f02694c1790c3c00425d1862f8424631442acd651bd`, 684645 tokens, +15780 > 15000); no value from it was copied into the proposed snapshot.

| First-run raw artifact | SHA-256 |
| --- | --- |
| `docs.json` — includes the two non-Git documents | `3922d937a76bd78e224bffb1da9c3b3d5c1e0697a8284f0e3b45f0e9c8e5ea30` |
| `complexity.json` | `9f8ea312e74890f023dba4043f340281bfbdd8a74a11bca3e093ef10e1cce787` |
| `index.json` | `8e6201e3ebfb1d7c981242b264820041b04e206caa45bd9725215a946d780abb` |
| `classes.json` — all 1683 source paths tracked | `884e22cdb1857704bf1fd1e32323775c8a0d02fa40bad79b4e71ba0303a671f6` |
| `tests.json` — all 1134 source paths tracked | `396e06dd57f59f569f74de885a70d7b855d2f3ec1da8c640ac6dc7b2bc914b69` |
| `modules.json` — all 67 build paths tracked | `42ee03c3025cbb364dbcbee88bd9c6cdfbe7b23e15963bf52fde6e5cf71db48f` |

For re-review, retain fresh raw inventories, check/trend/snapshot/complexity/hotspot reports, complete gate log, clean source/tool before-and-after receipts, the preceding baseline, precise runtime command, policy bindings above and final source CI. The eventual baseline copy must have the exact newly reviewed generated hash. This review never qualifies a feature PR, changed limits or roadmap completion.

## Final review of the clean replacement run

The parent retained the full initial run under `rejected-contaminated-run/`, moved the two nontracked working documents outside the repository, and repeated the complete gate without changing either Git tree or any tracked policy. I independently verified the resulting evidence and current checkouts. The source is still `ae6b36eae23ed96aed2f0815442ab67634f87065` / `eb052f81047973154d99105273de5851b0c9b9a3`; the extractor remains the same clean exact v0.1.10 commit/tree. The prior baseline remains byte-identical and unchanged.

The fresh raw inventories contain 1683 classes, 1134 tests, **416 documents**, and 67 module build paths. Every reported source/build/document path belongs to the accepted Git tree; no ignored or untracked source remains in those inventories. The source counts reproduce `1683*260 + 1134*140 + 416*180 + 293*35 + 17*80 = 682835`. This is a newly generated snapshot, not a numerical edit of the rejected one. Its current trend values equal the complete snapshot; every old baseline metric and model value matches the retained preceding baseline.

| Check | Result |
| --- | --- |
| Complete unchanged root `aiKnowledgeCheck` | Successful, 12 actionable tasks: five executed gate tasks and seven up-to-date extractor build tasks |
| Coverage controls / hotspots | 8 passed / 17 passed; no exceptions |
| Estimated context tokens | 682835; +13970 from 668865, below unchanged +15000 limit |
| Concept radius | 99; +2 from 97, below unchanged +3 limit |
| Normalized context debt | 15.41 versus 15.77; same `context-footprint-v3` model, below absolute 21.7 and trend +10 limits |
| Check / trend violations | Zero / zero; six existing nonfatal warnings disclosed |
| Raw-byte checks | Retained snapshot, check, trend, complexity and all four inventories match current generated output |
| Provenance checks | All 20 artifact hashes, all 12 repository bindings and all seven extractor bindings verified |

The corrected manifest now binds actual plugin policy as `policySha256=2aaf0253...` and lifecycle separately as `lifecycleSha256=f1df0d33...`; `plugin-policy.gradle` and `policy.gradle` retain the corresponding bytes. All selectors, claim rules, coverage contracts, hotspot policy and extractor implementations match the hashes above. The JDK release and runtime/initializer bindings remain correct. Both initial findings are therefore resolved for this replacement.

| Approved/reviewed artifact | SHA-256 |
| --- | --- |
| `metrics-snapshot.json` — exact approved baseline input | `47ca2375967a95763395ad65bad1bff60a0f8e82ed9246684b0ea8dfea2f28ef` |
| `check.json` | `0f36d1a2959dc21299f4473f4cbd0333c3b573a68ce9816a912d82744ab6ebf6` |
| `trend.json` | `65cf0dacf86511958d362db737b511d46d1f545fd35f9f44c6e27397a16de689` |
| `complexity.json` | `8e1f7a58f89fb62fecf77e20eb52aded0af6575c30995765008ef37da1a53bc8` |
| `index.json` | `6db3176474a818f3812e2f53a4eff0b1fb25b72462d7876ef2834cc729c973f9` |
| `docs.json` — all 416 paths tracked | `e9f28b15f1179adc8b45940ffb5a1b91dbc8bdb66783901005d653ada376536e` |
| `aiKnowledgeCheck.log` | `983b13c7d41ba1abad950c862364f338988e28c5eec9f1f743463bbc680b67ae` |
| Corrected `provenance.json` reviewed before final main-CI attachment | `87003815beb92f305b8403911440b56db60fa3489344734e15c71721071d1ddd` |

Class/test/module inventory hashes are unchanged from the table above; only the two local documents disappeared. The original contaminated docs, complexity and index hashes were independently reverified in the rejection archive. The rejected PR #1068 evidence remains separately retained and supplies no approved value.

No additional local build is required for this evidence review. The final main-CI attachment may legitimately update the manifest's CI artifact and manifest hash; it must not change the approved snapshot, source/tool/policy bindings or historical evidence. Verify the approved snapshot hash again after the eventual baseline copy.
