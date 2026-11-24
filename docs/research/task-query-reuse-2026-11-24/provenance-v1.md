# V1 source provenance: review clarification

Recorded on 2026-11-24, after review. This is a separate clarification; the
historical `v1-reproduction.json`, protocol, query report, summary and original
audit receipt remain byte-for-byte unchanged.

## What is and is not established

The historical receipt contains
`mainSourceAndPomSha256 = 8e46c0162161431b087eee458f55aad2ce5d951b5a78fb82464ec3d0e4513abe`.
Its original file-selection and byte-encoding recipe was not retained. No
original script containing that recipe was found among the preserved local
evidence. Bounded checks of plausible file scopes, ordering, separators and
content/digest encodings did not reproduce that value. These checks are not an
exhaustive proof about every possible recipe.

**The original V1 source-hash binding remains unconfirmed.** The value must not
be described as verified with the V2 recipe, and no newly calculated value
replaces it in the historical receipt. In particular, the surviving evidence
does not independently establish that the original, undocumented hash selected
exactly the source bytes in the saved development commit at measurement time.

The preserved V1 development snapshot itself can be identified reproducibly:

| Field | Verified value |
| --- | --- |
| Local development commit | `e3c643a8dd866572aa3cb762d994fed9aacae676` |
| Git tree | `5d04c154a64874f2e894a272e79b6bccc91f5c12` |
| Main-Java/POM files | 2,075 |
| Source hash with the explicit recipe below | `e0d571a27646e2eac637814909c8394d5aa9c0e3ea5839e47e66ebe3a0bc405a` |
| Historical receipt file SHA256 | `c3d38ce7e8e9a7dad585eb201198f8c9aed92fcf9ce62e609776f0180500367d` |

This is a newly documented identity of the **retained development snapshot**.
It is not retroactive validation of the undocumented measurement-time hash.
The original commit is available in `source-history.bundle`, whose SHA256 is
`b4833417ed3c49b5e2e080df55bec630da28d9d70833c3b61fa6f3ca5ee61543`
(366,358 bytes). The bundle requires base commit
`f183aedd1ff2ab061ed903559b96a11d28974e7f`. Local source-history IDs are not public
GitHub-link claims; publication can use a different commit identity.

## Reproduce the retained snapshot identity

Read tracked paths from the stated V1 Git tree, selecting files where the path
contains `/src/main/` and ends with `.java`, or ends with `pom.xml`. Sort relative
paths lexicographically. For each path, append its UTF-8 bytes, one NUL byte,
the exact Git blob bytes, and one NUL byte to a SHA256 input. No file timestamp,
filesystem traversal order, build output or current working-tree content enters
the calculation. This is the explicit V2 encoding applied to the saved V1 tree.

Run from a repository containing the retained V1 commit:

```sh
python3 - <<'PY'
import hashlib
import io
import subprocess

revision = 'e3c643a8dd866572aa3cb762d994fed9aacae676'
paths = sorted(path for path in subprocess.check_output(
    ['git', 'ls-tree', '-r', '--name-only', revision], text=True).splitlines()
    if ('/src/main/' in path and path.endswith('.java')) or path.endswith('pom.xml'))
objects = io.BytesIO(subprocess.check_output(
    ['git', 'cat-file', '--batch'],
    input=''.join(revision + ':' + path + '\n' for path in paths).encode()))
digest = hashlib.sha256()
for path in paths:
    header = objects.readline().split()
    assert header[1] == b'blob'
    content = objects.read(int(header[2]))
    assert objects.read(1) == b'\n'
    digest.update(path.encode('utf-8'))
    digest.update(b'\0')
    digest.update(content)
    digest.update(b'\0')
assert objects.read() == b''
assert len(paths) == 2075
assert digest.hexdigest() == 'e0d571a27646e2eac637814909c8394d5aa9c0e3ea5839e47e66ebe3a0bc405a'
print(len(paths), digest.hexdigest())
PY
```

The companion `provenance-v1.json` records this algorithm, the snapshot/bundle
identities, the historical receipt's unchanged hash and the explicit limits.
The decompressed V1 report and raw stdout continue to match their historical
receipt hashes. Their internal consistency and the independently checked
mathematical outputs do not by themselves close the missing source-hash recipe.
No benchmark was rerun or relabelled as part of this clarification.
