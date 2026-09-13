# JMH jar content identity v2

This is a separately versioned correction for the admission defect reproduced
after the [retained study v1 failure](jmh-precision-study-v1-outcome.md). The new
`scripts/jmh_precision_study_jar_v2.py` module is not connected to a study runner
or workflow. It grants no further measurement launch. The historical v1 jar
identity, study scripts, policy, thresholds and artifacts remain unchanged.

The v1 identity rejects all duplicate file names. The actual Gradle-generated
JMH jar contains 24 duplicate file names, so v1 cannot admit it. Names include
licenses, service-provider resources and multi-release metadata. Deduplicating
those names or reading only the last occurrence would omit observed contents.

The new identity has schema `regelsuche.quality.jmh-jar-identity/v2`. Its raw
`sha256` and `contentSha256` describe the same immutable byte snapshot. The
content digest binds the complete central-directory entry sequence, including
directories and every occurrence of a duplicate name. Each entry contributes
its original decoded name, directory flag and individual uncompressed payload
SHA256. Changes to an earlier duplicate, order or multiplicity therefore change
the content digest. ZIP timestamps and compression change the raw digest but
do not change the content digest when that entry sequence is identical.

The digest input is ASCII JSON with sorted object keys, compact comma/colon
separators, escaped non-ASCII characters and no trailing newline:
`{"entries":[{"directory":false,"name":"...","sha256":"..."}],"schema":"regelsuche.quality.jmh-jar-identity/v2"}`.
The entries array retains archive order. The returned record additionally
contains the source path, total entry count and number of names occurring more
than once. This identity binds contents; it does not prove equivalent behavior
across Java versions or hardware.

Python documents that `infolist()` preserves archive order and that `ZipInfo`
arguments allow access to duplicate names. The implementation reads actual
`ZipInfo` payloads, never a name lookup. See the
[Python ZIP API documentation](https://docs.python.org/3/library/zipfile.html#zipfile.ZipFile.open).

The actual jar also has 14 groups of exact physical-header aliases: multiple
central-directory records point to the same local header. Strict CPython
3.12.3 rejects some such entries because their internal end boundary equals
their own start. CPython 3.12.14 warns instead. V2 handles this narrow case by
requiring equal original names, compression methods, flags, CRCs and compressed
and uncompressed sizes within an offset group. It selects an existing `ZipInfo`
with the shared positive `_end_offset` already calculated by CPython and reads
that shared physical payload. Every original logical occurrence remains in
the digest sequence.

No boundary is modified, no overlap warning is suppressed, and the normal
header, partial-overlap and CRC checks still execute. Conflicting aliases,
missing or inconsistent positive alias boundaries, corrupt earlier duplicate
payloads and partial overlaps of different local headers fail closed. The
private `_end_offset` read is an explicit compatibility dependency: unsupported
boundary representations are rejected. This slice has been executed on
CPython 3.12.3 and 3.12.14; it does not claim an unexecuted Python 3.10 result.

Run the small ZIP controls without building or starting Java:

```sh
python3 -B scripts/test-jmh-jar-identity-v2.py
```

Add an already built real jar to exercise the actual admission seam:

```sh
python3 -B scripts/test-jmh-jar-identity-v2.py --real-jmh-jar app/build/libs/app-jmh.jar
```

The retained local control uses the jar built once from tree
`dc49fcda8921516185e1dbec31e5bfb1d8214524`, raw SHA256
`8691afc2f5ef3c3fc408343702c4cf5ff72cece1ad48ab5f9e16940d215f4a97`.
It is a jar admission control, not study measurement data. A future study must
explicitly bind this new identity schema in its own new revision; v1 manifests
must never be relabeled or reinterpreted as v2.
