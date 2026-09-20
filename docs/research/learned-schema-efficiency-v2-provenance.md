# Learned schema v2 registration provenance

The protocol was locally registered before execution in
`5bc9e21e61131be287b89060ef39f93df111dc25`. Publishing the implementation through
the repository API reconstructed commits on the published branch; the identical
protocol first appears there in
`5ce2980fe68eb78e352f262934cf338f002a72f4`. A Git commit ID covers its tree,
parents, author/committer metadata and message. Identical file bytes therefore
do not require identical containing commit IDs.

Both historical protocol blobs were inspected before any evaluation sequence.
Their SHA256, and the unchanged current protocol SHA256, are
`7e9b51c8f4c66655489350ef4be982411b6ab5ae0585ddb8b725b1ef15d7113c`.

The first invocation stopped at the original local-commit ancestry check before
starting any worker or sequence. It produced no evaluation measurements. Its
failure log was retained and is not overwritten by a subsequent invocation.

The corrected gate still requires an ancestor of the evaluated source HEAD. It
uses the first published protocol commit above, additionally retrieves
`config/benchmarks/learned-schema-efficiency-v2.json` from that historical commit,
and checks the retrieved bytes against the registered SHA256. The current
protocol file must independently match the same SHA256. Neither an unrelated
commit with matching bytes nor an ancestor with different historical bytes can
authorize execution.

Run metadata records the published `freezeCommit`, its checked
`freezeProtocolHash`, and the original local SHA as descriptive
`sourceLocalFreezeCommit`. A fresh published clone need not contain the original
local commit object. Source HEAD/tree identity and the actual copied runtime
bytecode/dependency hash remain separate provenance records.

This integration correction changes no cases, source strings, families,
profiles, budgets, sequence lengths, repetitions, timeouts or acceptance criteria.
It was made while the evaluation had executed zero sequences. Regression tests
use real temporary Git histories to check the valid byte/ancestor combination,
incorrect historical bytes despite a correct current file, and matching bytes
on a nonancestor branch.
