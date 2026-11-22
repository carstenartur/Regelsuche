# Maven Student SDK archive implementation plan

> **For agentic workers:** Use the implementation steps below with test-driven development and independent review.

**Goal.** Assemble the existing Student SDK ZIP from staged Maven artifacts through native Java and keep the existing consumer contract.

**Spec.** [Archive contract](../specs/2026-11-21-student-sdk-maven-archive-design.md)

- [x] Exercise a failing release-workflow authority check and negative fixture controls for missing artifacts, version drift, and the pinned consumer.
- [x] Implement the Java archive assembler and its explicit Maven invocation. Keep the previous Python implementation as the comparison oracle and historical-tag fallback.
- [x] Check both writers on the same release and snapshot fixtures, including every member's extracted bytes, mode, timestamp, compression method, and exact manifest/checksum data.
- [ ] Verify a clean Maven-staged repository in the release configuration. The local attempt could not resolve Central dependencies through the workspace network. The fixture repositories contain synthetic artifacts and cannot establish this step.
- [ ] Run the affected Maven workflow contracts and independently review the implementation before it is published.
- [ ] Execute the required hosted Maven archive qualification on the integrated source. Its evidence contains the exact commit/tree, native source hashes, packaging and consumer logs, plus the parity byte and metadata inventory. It uses the previous producer as a test oracle and verifies the Java ZIP's isolated consumer repository.

Local verification passed 50 of 52 affected Maven cases. The two opt-in entry points were skipped in the default invocation. The parity suite additionally invoked the Java packaging entry point for both fixture versions and passed all ten Python cases. The namespace, directory-index and CRC controls reproduced failures on the earlier Java implementation before passing.
