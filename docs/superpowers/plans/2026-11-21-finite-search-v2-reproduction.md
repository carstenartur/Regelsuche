# Finite search v2 public reproduction

**Goal.** Run the published v2 producer at `6dce1fae5bf84cbb588129993830c41be45a8bda` unchanged in three independently built source trees. The v2 README binds the original public plan, cases, four-rule inventory and budgets. A technical smoke run preceded this reproduction. The experiment is public and target-directed.

**Source and outputs.** Require Git tree `3098d9e6b330aa4ef700fa47a7562dc9ce9fa31c` and the original [plan](../../research/finite-search-knowledge-v1/plan.json). Use the Maven production entry point in the [v2 contract](../../research/finite-search-knowledge-v2/README.md). Retain the full comparison, source tables, runtime samples, source and tool identities, and build/run logs for each execution.

1. Derive the expected case identities from the frozen corpus and the plan's three extra tuples. Require exact case × arm membership, valid bounded references, full cost fields, paid acquisition/restoration and a corresponding runtime sample per row. Exercise rejection of missing and duplicate rows, missing measurements, inconsistent costs and a changed canonical byte.
2. Build two fresh checkouts sequentially. Build the third inside a digest-pinned Java 25 image using a separate empty Maven repository and the same verified Maven distribution. Run the installed production classpath in a second container by immutable image ID with networking disabled. Keep the complete output and logs even if comparison fails.
3. Require byte equality of all three canonical comparison files. Bind each runtime observation file separately by hash and size. Native calls, controller work, wall time and current-thread allocations use their published units. No statistical speedup or total-work improvement follows from agreement.
4. Require a hosted run and independent artifact review before reporting a reproduced v2 result. The v1 historical output, protocol and published findings keep their existing identities.
