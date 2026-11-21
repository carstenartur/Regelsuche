# Native evidence authority after 0.3.0

Issue #749 requires one lifecycle with the same failure semantics. The portable reactor runs Java/JUnit. External tools and qualification that need real services belong to the explicit full lifecycle. A failed infrastructure check is technical failure. Source archives receive the exact source revision and run the same complete reactor.

| Acceptance | Current code | Replacement and proof required |
| --- | --- | --- |
| A, application JMH | app/src/jmh, gradle/ci-verification.gradle, gradle/quality-gates.gradle | Compile the same annotation-processed JMH 1.36 sources in Maven. Fork real JDK 25 measurements, preserve every latency and GC allocation row, run the existing Java validators. Replay original positive and negative captures in Failsafe against original policies. |
| A, remaining quality | gradle/ci-verification.gradle and its applied Gradle scripts, scripts/collect-quality-acceptance-evidence.py | Move each producer and semantic validator together. Validate actual path inventory, raw samples, policy thresholds and failing receipts. |
| B, external systems | rules in .github/workflows/gradle.yml, app/src/e2eTest, regs modules for SymPy, integration and solver tests | Pinned Docker boundaries for actual browser, CPython/GraalPy and proof binaries. Run each consumer through JUnit. Exercise unavailable and actual successful execution independently. |
| C, authority | gradle.yml and release.yml, maven-build-contract and final quality aggregator | Switch each verified producer and its outputs, then the remaining call sites. Existing release, SDK replay and source-archive obligations travel with the switch. |
| D, retirement | gradlew, build.gradle, gradle/ and interpreter inventory contracts | Remove a required path after its full Maven replacement is green. Preserve immutable historical reproduction material and clearly marked convenience helpers. |
| E, reproduction | source archive step, README and contributor/release instructions | Repeat ordinary and full verify from a clean checkout and a Git-free source archive. Companion documentation names remaining transitions until the final gates pass. |

The isolated JMH execution currently also runs a separate diagnostic factorization matrix and historical verifier controls. An application latency/allocation producer cannot qualify those obligations by implication.
