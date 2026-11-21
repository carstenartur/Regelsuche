# Quality collection control fixtures

The original 22 tests in `scripts/test-quality-acceptance-evidence.py` at
`1b458ab5c9ddee5a4d077bf8fe81abed2472905f` generated these synthetic reports.
The Java test stages tracked policies, historical references, screenshots and
offline database material from the checkout and takes synthetic current values
from the ZIP. This avoids copying the database or baseline images into the
fixture.

The second ZIP records all 44 calls to the original collector made by that
suite, including each file mutation, section availability and native outcome.
The inputs and expectation for each control are independent of the Java
collector. Its positive and negative tests also verify the retained bytes,
fresh-output rule and symbolic output rejection. The finite support-file list
now binds the Java collector, checkout/archival provenance, publication entry
point, descriptor reader and retention map.
