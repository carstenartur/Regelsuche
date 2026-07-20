# Browser demo E2E reliability

The browser demo suite is part of the checkout-local Gradle verification contract. GitHub Actions does not own retry rules, expected UI state or replay-selection semantics.

## Authoritative command

```bash
./gradlew :app:e2eTest
```

Root `./gradlew test` and `./gradlew fullCheck` include the same Gradle-owned browser tests according to the central verification lifecycle.

## Demo completion contract

A demo run is synchronized through rendered application state rather than a response-listener-only wait:

- the previous ready, math, replay, summary and selected-path state is cleared before every click;
- completion requires a newly prepared run marker, the requested demo identity, a populated current summary and `window.__regelsucheDemoReady=true`;
- an explicit rendered `#demoStatus.status.error` is treated as failure evidence;
- HTTP failures fail immediately, and mathematical/result assertions cannot trigger a retry;
- only a rendered browser transport failure (`Netzwerkfehler:`) permits one retry;
- a second transport failure retains both the first and second diagnostic;
- each ordinary successful demo must expose the exact selected path ID through `window.__lastSelectedPathId`.

The completion wait is bounded to 30 seconds per attempt. There is no workflow-level retry.

## Replay contract

Replay tests load the path ID produced by the current demo. They do not infer recency from sort order and do not scan paths accumulated by earlier tests or a reused checkout.

The helper:

1. opens the replay panel and fetches the path inventory;
2. requires the exact current-demo path ID to be present;
3. replaces the selector with that one path;
4. clears stale replay readiness and canvas state;
5. loads the replay;
6. accepts readiness only when the selector still contains the expected path and a replay step is rendered.

The exact selected-path readiness check also rejects any concurrent or delayed selector population that would replace the requested path. A replay load is bounded to 10 seconds. The complete matrix demo and direct replay flow is bounded to 45 seconds. This replaces the former unbounded multiplication of every stored path by up to thirty replay steps.

## Failure boundaries

Optional third-party rendering resources may remain non-fatal where the UI has an intentional fallback. Same-origin demo and replay failures remain visible and fail the test. The suite must not hide HTTP errors, invalid mathematical output or stale UI state behind retry behavior.

## Characterization

`BrowserDemoFlowTest` includes focused coverage for:

- a stale ready/summary/path state followed by one simulated connection reset and a successful retry;
- an HTTP 503 response that is surfaced and not retried;
- direct matrix replay selection with a deterministic total runtime bound;
- exact replay-path readiness rather than acceptance of any previously rendered replay.
