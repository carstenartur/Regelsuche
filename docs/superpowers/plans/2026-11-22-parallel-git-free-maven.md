# Parallel Git-free Maven verification

> Execute the workflow change with test-driven development and verify the existing Maven contracts before publication.

The Git-free archive has its own source preparation, integrity receipt, complete Maven reactor and report recheck. Run it on a separate hosted runner alongside the checkout Maven authority. Keep both results mandatory for the existing `Checkout-local ciCheck` on routine runs.

## Change and verification

1. Extend the workflow contracts to require independent checkout and archive jobs, exact revision binding, complete archive test evidence and fail-closed convergence. Show the added assertions fail on the serial workflow.
2. Move archive preparation, registration, replay and upload together. Preserve the checkout job's report checkpoint, Maven XML snapshot, page replay and product artifacts.
3. Combine the Web Workbench test with the checkout Maven reactor only after verifying the effective Failsafe test union and preserving the separate Git-free profile. Keep the dedicated image run if the configurations differ in a way that would weaken coverage.
4. Run the affected Maven build contracts. Review the changed workflow for missing producer dependencies, skipped executions, changed test scopes and mixed report sources before retaining results.

Use the same runner platform and prerequisites. Keep benchmark and solver authorities, test and coverage thresholds, the showcase event exception and release commands in place. The hosted run must establish the wall-clock result.
