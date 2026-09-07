# Retained mathematical CI fixture

`ci-window-128.json.gz` contains the exact legacy/first TEST receipts and
optimality proofs for window 0..128 from carstenartur/primachsenraum:

- source commit: `34f7c3ac05c57792a356c41a21017d142c1709ba`;
- workflow: `34020660091`, artifact: `9985428382`;
- original archive SHA-256:
  `ad2ec5cb9813c34c3f9caa6b58c000540e4dc3b17a8e1f5d00b28daebf2b208a`;
- uncompressed fixture SHA-256:
  `9e3fbf20f7be1168bc63d5b2ab6788a52da3a12ea64db556ebcfe13197af810d`.

The data-only workbench envelope retains the source manifest identifier and
copies these two runs without changing receipt/proof bytes. Gzip has mtime 0;
its only purpose is reducing the size of the repetitive decimal/hex text.
Both Java's standard GZIPInputStream and Node's standard zlib read it.

This is a previously executed mathematical example, not a new benchmark or
an assertion of authenticated source provenance by the browser. Tests replay
its finite upper-bound proof and reject modified proofs, including when their
hashes have been recalculated. The maximum 28 is a regression expectation,
never an input to a searcher.
