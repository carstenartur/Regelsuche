These two records were captured through the actual `de.regelsuche.App` CLI and
replayed by new CLI processes from the production classes at source revision
`03c2690c5da663c4c5c7a10e51f7ae90cd819d72`. Capture followed the public manifest
freeze at `887283d3e7`. The input is its `native-square-preparation` case.

They are immutable mutation-test inputs for the Python verifier, not a product
qualification result: this isolated capture did not load the complete public
learned/plugin fixture inventory. The qualification tasks create fresh exports
from their own installed application and independently verified public inputs.
