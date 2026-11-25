# Public configuration-model transfer protocol

## Population and split

All inputs come from [SoftVarE's feature-model collection](https://github.com/SoftVarE-Group/feature-model-benchmark/tree/afa60ee2c836e7bdc4068e0f4f128ea31158d2ad). Count satisfying assignments to *all declared CNF variables*. Auxiliary variables, if present, are included. Counts refer to the encoded formula, without a claim about distinct end-user configurations.

| Partition | Identifier | Repository path |
| --- | --- | --- |
| Training | axTLS | feature_models/original/systems_software/axTLS/Berger2013.dimacs |
| Training | Tankwar | feature_models/dimacs/games/Tankwar/Schulze2012.dimacs |
| Training extension | Routefinding | feature_models/dimacs/navigation/routefinding/Mendonca2009.dimacs |
| Training extension | ERP | feature_models/dimacs/business/erp_system/Pereira2016.dimacs |
| Evaluation | BerkeleyDB | feature_models/dimacs/database/berkeleydb/Hierons2020.dimacs |
| Evaluation | Bank | feature_models/dimacs/finance/bank/AlHajjaji2019.dimacs |
| Evaluation | Printer | feature_models/dimacs/hardware/printer/Mendonca2009.dimacs |

This deliberately small public panel samples different named systems. axTLS is a development case, already examined with an external reordering script. Evaluation names and budgets were selected before measuring their planning or counting outcomes. These are public, non-blind examples, without a private holdout or externally time-stamped preregistration. Retain every selected model, including resource failures and conversion exclusions. Do not modify the planner or learner using evaluation outcomes and then reuse the panel as confirmatory evidence.

A format-only preflight found an input clause exceeding the existing 20-variable factor limit in Tankwar and BerkeleyDB. Before running a production solver on the evaluation partition, we selected the two additional training projects listed above. Both remain in the report irrespective of compatibility. This is an explicitly adaptive extension following a conversion finding. Evaluation projects and budgets stayed fixed.

## Arms, cost and acceptance

* Interpret each clause as a single Boolean factor on its distinct variables. Preserve DIMACS numbering and clauses, including explicit zero-arity clauses, and retain declared free variables. A clause outside the existing factor input contract excludes its complete source from all four Java arms. Do not pre-simplify or decompose it specially.
* Count with ascending original IDs, minimum degree and minimum fill. Separately train the shipped selector on all convertible training projects only, freeze the resulting model, then select and count on each evaluation project. The selector sees no expected counts, project identifier or competing run outcomes for evaluation. Charge the entire training cost once and all selection/solve work. Compare portfolios on equal counts and resource limits.
* Give each standalone count 2,000,000,000 work units, one million logical cells and 120 seconds. Each training trial receives at most the same work as one solution, with an aggregate 24,000,000,000 work-unit / 1,440-second ceiling. This is an offline bounded-capability test. Deterministic logical work is the comparative measure; externally computed D4 work units and JVM wall times are not comparable measurements. Record public input parsing separately from the solver work reported by the common Java APIs.
* Recheck all successful certificates against the original problem in a separate invocation. This is an additional audit; production count already charges its independent replay. Cross-check external model counts where available. Distinct failures cannot be compared as cheap solutions. Report solved counts first, applicable cost comparisons second. Compare the full acquisition-plus-evaluation total with each fixed arm at equal coverage, and keep small-panel limitations visible.
