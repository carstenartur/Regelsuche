# Regelsuche: start here

Regelsuche is a Java research workbench for symbolic search, checked reuse of
learned knowledge, and derivation of cheaper computation plans. It keeps the
examined alternatives, rule applications, assumptions and evidence available
for inspection. The project asks when reuse saves total work after acquisition,
selection, loading and verification have been counted.

**See a real run without installing anything:** download/open the
[self-contained recorded demo](demos/recorded-discovery.html) in a browser.
It contains a retained Sophie-Germain search trace and an actual run of an
external consumer against the released SDK 0.5.2. It is a recording, not a live
server. [Provenance and reproduction](demos/README.md) accompany it.

**Compare benefit and failed transfer:** [download the public discovery comparison](https://raw.githubusercontent.com/carstenartur/Regelsuche/main/app/src/main/resources/web/public-discovery-comparison.html)
and open the HTML file, or follow the public comparison link in the Workbench’s
demo area or **Gespeicherte Runs**. Expand individual composite-factor cases
or follow the actual retained cross-family search paths, including the negative
controls and recorded stopping conditions.

## Choose a workflow

| Goal | Start | Inspect |
| --- | --- | --- |
| Explore a symbolic derivation | [Getting Started](getting-started.md), then [User Workflows](user-workflows.md) | Full expression states, local rule positions, paths, replay and assumptions |
| Study learned-rule reuse | [Autonomous discovery walkthrough](autonomous-discovery-walkthrough.md) and [research methodology](research-methodology.md) | Frozen inputs, candidate lineage, independent checking, controls and total costs |
| Derive a numeric computation plan | [Optimization SDK](../regelsuche-optimization-sdk/README.md) and [finite sums and iterations](../regelsuche-optimization-sdk/FINITE_SERIES.md) | Typed numeric domain, checked plan, independent verifier and source-adapter obligations |

For an independent Java integration, use the **[released SDK 0.5.3 on Maven
Central and the complete Maven quickstart](java-sdk-quickstart.md)**. The runnable
example needs JDK 25 and Maven 3.9.9+ from the 3.x line; it does not need a
Regelsuche source build, database or web server. The current example POM imports
`io.github.carstenartur:regelsuche-bom:0.5.3`. Maven resolves plugins and
dependencies on the first run. The historical 0.5.2 archive recipe remains
available in the same guide.

To explore the newer source APIs, the [seven checked mathematics examples](../examples/external-consumers/checked-math-java25/README.md)
provide one Maven command after installing the source SDK, or directly from an
SDK archive containing the examples. They do not run against the 0.5.3 binaries.

## What the evidence means

A retained path or replay shows what executed. A finite empirical check applies
to its declared test domain. An exact independent verifier establishes only its
supported mathematical contract and assumptions. A formal proof status requires
an actual successful proof backend for the bound obligation. These are separate
claims: `NONE_FOUND` is not proof, and an exhausted budget is not refutation.
A guarded equality cannot be read without its guard. Source transformations
must additionally preserve their language's numeric semantics and observable
behavior; a mathematical SDK certificate alone does not certify emitted Java.

The current source contains exact rational AST numbers, bounded native univariate
factorisation over integers/rationals, finite-field methods and a Gröbner engine
for small rational ideals. It does not provide a universal CAS capability claim.
[Architecture](architecture.md), [mathematical algorithms](mathematical-algorithms.md)
and [capability status](generated/capability-status.md) state the supported scope.
The source on `main` is `0.5.4-SNAPSHOT`; use the
[0.5.3 source and release documentation](https://github.com/carstenartur/Regelsuche/tree/v0.5.3)
when consuming its binaries.

Measured results apply to their named tasks and cost model. Public development
cases do not establish general speedup, transfer to untouched families or
external mathematical novelty. Human usability validation is still
[not conducted](java-sdk-human-dx.md). Prepared demo links and focused tasks
are available there for a future evaluation; no outreach is implied.

Most detailed manuals are currently in German. The recorded demo and the
[external consumer README](../examples/external-consumers/hello-rule-java25/README.md)
are in English. [Documentation index](README.md) · [project README](../README.md).
