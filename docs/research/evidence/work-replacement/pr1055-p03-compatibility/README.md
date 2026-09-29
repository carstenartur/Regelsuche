# PR #1055: public P03 legacy compatibility evidence

Qualified source head: `2aa5ee849087a3ffb0972b5b410b1d58a710e2a7`.
Qualified source tree: `3b90d3bb3f5056cb43050825dc2794e029d25be2`.
Local equal-tree head: `f7fd207fec9a131e65c5e13cbd8b36e4b44d469f`.

The archive retains the unchanged public Java probe, comparison driver, exact commands, raw compiler/JVM logs, source/toolchain/dependency bindings, the frozen P03 references, both full result sets and a SHA-256 manifest. Two fresh JVM runs on newly compiled final-head sources produced all 15 files byte-identical to P03 (30/30 comparisons), without normalizing any field. The driver checked clean source identity and production/reference hashes before and after execution. Paths in the recorded driver and commands are the actual execution-workspace paths; dependencies and source revisions remain explicitly pinned.

Archive SHA-256: `066979407751285766ffbd1cfda23972fb2cd1cff44c0a8d9fb0cfc89bc6c399`.
Report SHA-256: `32d5930f0d89b43eea08fa9127a4238f3e887f198974cc83d74fe9396fb47260`.

This evidence-only branch does not modify the PR head. It proves preservation of the historical legacy contracts, including their existing work receipts. It does not qualify native total accounting or complete P04. Hosted CI remains a separate merge gate recorded on PR #1055.
