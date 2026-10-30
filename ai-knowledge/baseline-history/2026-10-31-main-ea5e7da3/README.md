# Accepted-main context baseline — 31 October 2026

The reference is successful main `ea5e7da3e199c2e052133317f089ab5c2da8e5be`, not the failing feature branch. Complete CI run `41135715687` succeeded with the previous baseline and unchanged 15,000 context-growth allowance. Its original check records no trend, method-complexity or claim failures.

The downloaded quality-acceptance ZIP was verified against GitHub's artifact digest. All 284 files in its original index were checked for byte length and SHA-256. `provenance.json` binds that original artifact, index and native check/complexity reports. The original reports and index are retained in the referenced CI artifact and downloadable `Regelsuche_Vier_PR_Diagnose_und_Korrekturen.zip` verification bundle, not copied into source files here. The failed #1126 trend is also retained in that bundle and supplies no reference values.

`projected-baseline.json` and the active `ai-knowledge/complexity-baseline.json` are byte-identical. They project the existing numeric baseline fields from the accepted raw complexity report. The footprint model remains v3. This is **not** a claim to have downloaded the original `metrics-snapshot.json`: the full repository artifact exceeds the download size limit. The smaller quality artifact supplies the native CI measurements.

Accepted main measured 711,390 units: +14,885 against 696,505, within the 15,000 allowance. The failed feature merge measured 713,210: +16,705 against the old reference, but +1,820 against accepted main. This separates integrated work from the proposed change; it does not increase a limit or accept the feature's measurements as its own baseline. The previous baseline and earlier history remain available.

No fresh local Java-25 extractor run or independent human review is claimed. The final feature revision still requires the unchanged full CI and review. Its production code, test cases, numerical evidence and benchmarks are unchanged by this baseline refresh. Main itself is not directly modified.
