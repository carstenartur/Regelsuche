# Maven release metadata parity

The retained comparison used the exact Python writer from `83eb6b4` and the
Java Maven writer on disposable copies of current source. The LF comparison
used full checkouts of `070fb4d6`. The CRLF comparison used the same 42 source
files projected into clean local fixture repositories and the reviewed writer
tree from `3025b628`. The Java writer ran through its actual Maven publication
test entry point in all four cases.

| Metadata operation | Compared files | Matching LF outputs | Matching CRLF outputs |
| --- | ---: | ---: | ---: |
| Prepare version 0.5.4 | 42 | 42 | 42 |
| Prepare 0.5.5-SNAPSHOT | 42 | 42 | 42 |

Each comparison covered 34 reactor POMs and six metadata files. The
`.gitignore` and standalone Playwright bootstrap POM remained unchanged.
The follow-up writer returned `required=true`. The JSON receipt records one
output SHA-256 per file and mode. Each hash matches the corresponding output
from both writers under both input line-ending conventions. The original
per-run receipt hashes are retained alongside the active source identities.

On the reviewed writer, 182 Maven build-contract tests passed and two opt-in
cases were skipped in the default run. The writer's opt-in entry point was
executed separately for both operations. The CRLF controls cover a released
BibTeX date being removed during the snapshot transition. The workflow
contract verifies that `git diff --check` follows the writer.

These are local source and metadata checks on fixtures. They did not create
a project tag, run a release workflow or publish an artifact. Full hosted
verification of the stacked change remains pending.
