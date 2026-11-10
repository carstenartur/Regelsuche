# Learning evaluation

Use `de.regelsuche.evolution.MixedTaskKnowledgeStudy` for new task-knowledge
promotion experiments. Its default is 16 mixed query blocks and 3 repetitions.
It fixes eight equally weighted promotion roles before learning and uses separate
exact alpha-polynomial inputs for subsequent evaluation. Acquisition, rejected
attempts, promotion trials, reloads and independent output audits remain paid.

The [v2 protocol and reproduction instructions](../docs/research/knowledge-mixed-workload-v2/2026-11-10/protocol.md)
describe the scope, accounting and limitations. Historical v1 protocols and
results remain unchanged; their single-case selection is not the recommended
basis for a new mixed-stream performance claim.

`TaskKnowledgeLifecycleWorker.restore` can select the same fixed workload with
`"promotionWorkload":"regelsuche.knowledge-mixed-workload/v2"`. Each improvement
batch then supplies an empty `promotion` array; nonempty overrides are rejected.
The response reports the selected revision and every fixed case. Omitting this
field preserves the old caller-supplied behavior and labels it `SUPPLIED_LEGACY`.
The generic `TaskKnowledgeSelfImprover` remains usable for independently specified
workloads; its caller is responsible for matching the deployment distribution.
