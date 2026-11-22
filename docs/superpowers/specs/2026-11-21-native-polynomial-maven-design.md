# Native polynomial study in the Maven verification workflow

Recover the native public development study from `75fd81b` on top of the
reviewed pilot and source registration from #1340. The frozen study still runs
75 sources, three profiles and three repetitions. Preserve its protocol, worker,
order, budgets, result schemas and independent verifier.

Use a separate Testcontainers run in the Maven reactor. Copy the tracked
source tree and its registration into a pinned worker. The Java provenance
check reconstructs the registered tree, checks actual file bytes and modes,
and rejects unregistered implementation or configuration. The native runner
checks the expected revision and tree before and after execution. Retain the
raw results archive with separate command diagnostics.

A single transfer of all three historical studies would also change Typed and
Schema source admission. This increment uses the single Native study first.
Keep the current legacy comparison and 648-row Maven pilot as required gates.
The Hosted Native job proves the full 675-row lifecycle. Local fixture checks
cover the boundary and preserve the study's existing development-only claims.
