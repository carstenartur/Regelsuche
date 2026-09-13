"""Version 2 ZIP content identity; this module does not launch or revise a study."""

from __future__ import annotations

import collections
import hashlib
import io
import json
import zipfile
from pathlib import Path


SCHEMA = "regelsuche.quality.jmh-jar-identity/v2"


def jar_identity(path):
    """Bind every central-directory entry in order, including duplicate names.

    Both hashes describe one immutable byte snapshot. Content identity ignores
    ZIP packaging metadata, but preserves decoded original names, directory
    flags and the SHA256 of each individual uncompressed ZipInfo payload.
    """
    raw = Path(path).read_bytes()
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            members = archive.infolist()
            groups = collections.defaultdict(list)
            for entry in members:
                groups[entry.header_offset].append(entry)
            payloads = {}
            for offset, aliases in groups.items():
                selected = aliases[0]
                if len(aliases) > 1:
                    fields = ("orig_filename", "compress_type", "flag_bits", "CRC",
                              "compress_size", "file_size")
                    signatures = {tuple(getattr(entry, field) for field in fields)
                                  for entry in aliases}
                    if len(signatures) != 1:
                        raise zipfile.BadZipFile("conflicting local-header aliases")
                    # Some Gradle jars reuse a physical entry for exact aliases.
                    # Older CPython gives all but one alias a zero-length bound.
                    # Select its already bounded ZipInfo; never change a bound or
                    # suppress the normal partial-overlap/CRC/header checks.
                    bounded = [entry for entry in aliases
                               if isinstance(getattr(entry, "_end_offset", None), int)
                               and entry._end_offset > offset]
                    if len({entry._end_offset for entry in bounded}) != 1:
                        raise zipfile.BadZipFile("unsupported local-header alias boundaries")
                    selected = bounded[0]
                payloads[offset] = hashlib.sha256(archive.read(selected)).hexdigest()
            entries = [dict(name=entry.orig_filename, directory=entry.is_dir(),
                            sha256=payloads[entry.header_offset]) for entry in members]
    except zipfile.BadZipFile as error:
        raise ValueError(f"invalid benchmark jar: {error}") from error
    content = json.dumps(dict(schema=SCHEMA, entries=entries), sort_keys=True,
                         separators=(",", ":"), ensure_ascii=True).encode("ascii")
    counts = collections.Counter(entry["name"] for entry in entries)
    return dict(schema=SCHEMA, path=str(path), sha256=hashlib.sha256(raw).hexdigest(),
                contentSha256=hashlib.sha256(content).hexdigest(), entryCount=len(entries),
                duplicateEntryNameCount=sum(count > 1 for count in counts.values()))
