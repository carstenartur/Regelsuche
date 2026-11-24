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
| `FiniteConstraintCount` | Counts `5`, `1`, `4` for all, true-conditioned and false-conditioned assignments. On a 20-leaf star, minimum degree verifies `1,048,577` within the same budget at which ascending order hits the table limit. |
| `EnergyCredits` | Credits `(2,0)` and selected Max edge `0` |
| `SosPotential` | `V=x²`, minimum decrease `1` on the supplied active domain `x>=1` |
| `BilinearSearch` | Search with supplied forms and discovery from coefficients alone. Joint replay of the lower and upper bounds proves three rational bilinear products suffice and are necessary. Evaluating the discovered plan gives `[8,22,15]`. The example also selects and executes integer schedules for operands bounded by 8 and 2,048 magnitude bits. Each schedule reports predicted limb work, allocated words and objects. The bounded portfolios select four and three variable products respectively. Both executions agree with direct `BigInteger` convolution, including a signed 2,048-bit input. |
| `RationalMatrixFormula` | Original inverse remains undefined on singular `X`; defined value `[2,1,0,3]` |
| `DynamicQuotient` | Undefined modulo `T-1`, value `2` modulo `T+1`, with checked factor coverage |

The SDK verifies each source-bound certificate within its explicit budgets.
These finite results have the assumptions in the supplied requests. A stopped
search is not a mathematical refutation. The programs do not rewrite Java source.

### Integer bilinear schedules

The public `BilinearProgramSdk.selectInteger` call takes the tensor, an upper bound on products, a bound on examined subsets, explicit left and right operand magnitude limits and a search budget. In the example the product cap is four, so the direct coordinate product plan can compete with plans using fewer products. The selected straight-line schedule carries a static estimate for the supplied profile. The example executes that schedule through `BilinearProgramSdk.evaluateInteger` and checks all three coefficients against their independent, direct formulas. A client can reuse the returned plan with other inputs satisfying the same bounds. Product-count optimality over the rationals, the cheapest integer schedule encountered under one profile, and measured JVM performance are separate results.
