# P04 declared execution — source-bound local evidence

Base: `fba017704a95f58cb99d1f66cdeece013cdb901e`. Corrected local source:
`d65456505bfb018d1baa78842ede56f2f2f249de`; tree-identical remote:
`df2b73a4f27badb7a615a2467583dd0f55dbfe0f`.

`manifest.json` binds every archive member and the source bundle by SHA256.
`raw-evidence.tar.gz` preserves the clean baseline, API-compilation failures,
initial full-suite failures, three behavioral review RED tests, correction GREEN,
all3260 module tests, frozen public inputs/two fresh JVMs, independent review,
AI quality reports and replayable runners. Failed attempts are not discarded.
`source-history.bundle` preserves both reconstruction commits above the base.

The full-suite run preceded the correction commit; its exact Java-file hashes
were confirmed identical afterwards. The corpus and AI gate bind that commit.
No thresholds, selectors, exceptions or baseline were weakened.

Default V7 remains partial and reproduces the previous 24/26 diagnostic and 0/26
frozen-budget comparisons. V8/V4 qualifies only the declared owned execution
inventory, not arbitrary callbacks, CPU time, JVM bytes or economic learning.
This archive is not hosted CI, merge approval or completion of P04/P05–P12.
