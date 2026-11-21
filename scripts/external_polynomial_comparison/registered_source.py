"""Read the original Git object bytes transported by the Maven experiment worker."""
import base64
import gzip
import hashlib
import json
from pathlib import Path


def _object(kind, raw):
    return hashlib.sha1(kind.encode('ascii') + b' ' + str(len(raw)).encode('ascii') + b'\0' + raw).hexdigest()


def require_ancestor(repo, receipt, ancestor):
    """Prove an actual chain to the freeze from the current, separately registered source."""
    root = Path(repo)
    source = json.loads((root/'build/authority-evidence/quality-source-tree.json').read_bytes())
    head = source['revision']
    current = base64.b64decode(source['commit'], validate=True)
    if source['schema'] != 'regelsuche.quality.source-tree/v1' or _object('commit', current) != head:
        raise ValueError('current source commit bytes differ')
    witness = json.loads(Path(receipt).read_bytes())
    original = json.loads(gzip.decompress((root/'config/benchmarks/polynomial-freeze-source-objects.json.gz').read_bytes()))
    if (original['schema'] != 'regelsuche.polynomial-original-source-objects/v1'
            or witness['schema'] != 'regelsuche.polynomial-source-witness/v1' or witness['revision'] != head):
        raise ValueError('polynomial proof was not registered for this source')
    parents = {}
    for collection in (original['objects'], witness['objects']):
        for oid, record in collection.items():
            raw = base64.b64decode(record['data'], validate=True)
            kind = record['type']
            if kind not in ('commit', 'tree', 'blob') or _object(kind, raw) != oid:
                raise ValueError('polynomial source object bytes differ')
            if kind == 'commit':
                header = raw.split(b'\n\n', 1)[0].splitlines()
                parents[oid] = [line[7:].decode('ascii') for line in header if line.startswith(b'parent ')]
    seen = set()
    search = [head]
    while search:
        revision = search.pop()
        if revision in seen or revision not in parents:
            continue
        if revision == ancestor:
            return head
        seen.add(revision)
        search.extend(parents[revision])
    raise ValueError('published polynomial freeze is not a proved ancestor of the current source')
