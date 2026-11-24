# AI Knowledge context reference after #1361

The reference comes from integrated main commit `9d1d4ac0ece4fd056cf9f952795772feba6baa82`,
tree `eb8e739a8084b063c5f480837fc1da2f78cbcd48`. The checkout in
[#1361 CI 45030696246](https://github.com/carstenartur/Regelsuche/actions/runs/45030696246)
was GitHub's test merge `28c7446500ab435f4fca30f093b5a2509f3387be`. It had that exact
tree and passed all eleven source jobs, `Checkout-local ciCheck` and CodeQL. The
separate [main push CI](https://github.com/carstenartur/Regelsuche/actions/runs/45049711682)
was cancelled.

The downloaded quality artifact `14764099341` binds the clean test-merge checkout
to the retained [check](accepted-check.json) and [complexity report](accepted-complexity.json).
All 293 indexed files matched their lengths and SHA-256 hashes. The native check
passed at **861,185** estimated context tokens and concept radius **119**, up
14,175 tokens and two radius units against the historical reference used by #1361.
The released extractor 0.1.10 projected [the active snapshot](metrics-snapshot.json)
from the original complexity report. [Provenance](provenance.json) records both CI
identities, the archive and native report hashes, and the prior references.

The later [main Gradle job](https://github.com/carstenartur/Regelsuche/actions/runs/45062927268/job/137334067341)
measured **873,185** tokens, 17,980 above the then-active 855,205 reference. The
[original failing trend report](rejected-main-trend.json) records the 15,000 limit.
Replaying the native trend calculation against the independently qualified 861,185
reference yields **+12,000** tokens and **+2** radius units with the same limits.
That replay uses the retained measurement. The updated checkout is subject to its
own complete extraction and integration CI.
