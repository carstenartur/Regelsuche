# Six checked mathematical calculations

This independent Maven project runs six existing typed SDK examples. It needs
**JDK 25** and **Maven 3.9.9 or newer in the 3.x line**. The APIs are part of the
current source SDK and are absent from the published 0.5.3 SDK.

## From an SDK archive containing this project

Unpack the complete archive, keep its `repository/` beside `examples/`, and run
this command from the archive root:

```sh
mvn -f examples/external-consumers/checked-math-java25/pom.xml clean verify
```

The packager binds the example POM to the archive's own SDK version. No version
override or manually assembled classpath is needed. Maven compiles the programs,
runs six result checks, and then executes all six calculations. Third-party
dependencies and Maven plugins need Central access on first use.

## From the current source checkout

First install the source SDK and its BOM into the local Maven repository. Then
run the independent project; it does not inherit the Regelsuche reactor parent:

```sh
mvn -pl regelsuche-optimization-sdk,regelsuche-bom -am install
mvn -f examples/external-consumers/checked-math-java25/pom.xml clean verify
```

This is a local source build, not a claim that this SDK version is published.
The complete example directory can be copied elsewhere after the source SDK
has been installed. To run a single program after `verify`:

```sh
java -cp 'target/classes:target/dependency/*' example.FiniteConstraintCount
```

Run that Java command inside this project. Windows uses `;` instead of `:`.

| Program | Result checked by the example tests |
| --- | --- |
| `FiniteConstraintCount` | Counts `5`, `1`, `4` for all, true-conditioned and false-conditioned assignments |
| `EnergyCredits` | Credits `(2,0)` and selected Max edge `0` |
| `SosPotential` | `V=x²`, minimum decrease `1` on the supplied active domain `x>=1` |
| `BilinearSearch` | Three variable products, convolution `[8,22,15]` |
| `RationalMatrixFormula` | Original inverse remains undefined on singular `X`; defined value `[2,1,0,3]` |
| `DynamicQuotient` | Undefined modulo `T-1`, value `2` modulo `T+1`, with checked factor coverage |

The SDK verifies each source-bound certificate within its explicit budgets.
These finite results have the assumptions in the supplied requests. A stopped
search is not a mathematical refutation. The programs do not rewrite Java source.
