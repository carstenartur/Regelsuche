# Non-qualifying setup attempt: missing learning service providers

The files in this directory are the original supplemental run, preserved without
changing decisions or measurements. That runtime omitted the learning module's
`META-INF/services` resources. Consequently `ServiceLoader` did not install
`CheckedSchemaTheoryEvidenceProvider`, so valid learned schema applications were
not admitted and the learner fell back to primitive search. `-Xprefer:source`
alone cannot fix absent runtime service metadata or discover provider source files
that are loaded reflectively.

**Do not use this attempt as evidence that the learning candidate or seed batches
lack benefit.** Its reported no-candidate outcome came from incomplete runtime
setup. The source/goal audits still describe the primitive outputs observed in
that attempt, but it is not a qualifying comparison of the intended learning
runtime. The previous textual conclusion was provisional and is superseded by
`../results.md` and `../corrected-current-providers/`.

The corrected reproduction removes every bundled `de.regelsuche` class and
project service descriptor, compiles current project sources and all current
service providers, and supplies current search and learning resource directories.
The integration test now requires a genuinely promoted candidate and therefore
also rejects this incomplete-provider setup.
