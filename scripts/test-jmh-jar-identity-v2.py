#!/usr/bin/env python3
"""ZIP identity controls; optional existing JMH jar inspection starts no process."""

from __future__ import annotations

import argparse
import collections
import hashlib
import struct
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

from jmh_precision_study_jar_v2 import jar_identity


class JarIdentityTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.sequence = 0

    def archive(self, entries, *, year=2020, compression=zipfile.ZIP_STORED):
        self.sequence += 1
        path = self.root / f"fixture-{self.sequence}.jar"
        with zipfile.ZipFile(path, "w") as archive, warnings.catch_warnings():
            warnings.filterwarnings("ignore", message="Duplicate name:", category=UserWarning)
            for name, payload in entries:
                entry = zipfile.ZipInfo(name, date_time=(year, 1, 1, 0, 0, 0))
                entry.compress_type = compression
                archive.writestr(entry, payload)
        return path

    def identity(self, path):
        try:
            return jar_identity(path)
        except (ValueError, OSError) as error:
            self.fail(f"valid archive rejected: {error}")

    def aliased_archive(self, *, conflicting_crc=False):
        """Two central-directory records refer to the same exact local entry."""
        path = self.archive([("resource", b"shared physical payload")])
        raw = path.read_bytes()
        end_offset = raw.rfind(b"PK\x05\x06")
        end = list(struct.unpack("<4s4H2IH", raw[end_offset:]))
        duplicate = bytearray(raw[end[6]:end_offset])
        if conflicting_crc:
            duplicate[16] ^= 1
        end[3] = end[4] = 2
        end[5] += len(duplicate)
        path.write_bytes(raw[:end_offset] + duplicate + struct.pack("<4s4H2IH", *end))
        return path

    def test_duplicate_resources_are_admitted_under_a_distinct_schema(self):
        path = self.archive([("META-INF/", b""), ("META-INF/LICENSE", b"first"),
                             ("META-INF/LICENSE", b"second")])
        result = self.identity(path)
        self.assertEqual("regelsuche.quality.jmh-jar-identity/v2", result.get("schema"))
        self.assertEqual(3, result.get("entryCount"))
        self.assertEqual(1, result.get("duplicateEntryNameCount"))
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), result.get("sha256"))

    def test_modifying_an_earlier_duplicate_changes_content_identity(self):
        before = self.archive([("resource", b"first"), ("resource", b"last")])
        after = self.archive([("resource", b"changed"), ("resource", b"last")])
        self.assertNotEqual(self.identity(before)["contentSha256"],
                            self.identity(after)["contentSha256"])

    def test_duplicate_order_changes_content_identity(self):
        before = self.archive([("resource", b"first"), ("resource", b"last")])
        after = self.archive([("resource", b"last"), ("resource", b"first")])
        self.assertNotEqual(self.identity(before)["contentSha256"],
                            self.identity(after)["contentSha256"])

    def test_removing_an_identical_duplicate_changes_content_identity(self):
        before = self.archive([("resource", b"same"), ("resource", b"same")])
        after = self.archive([("resource", b"same")])
        self.assertNotEqual(self.identity(before)["contentSha256"],
                            self.identity(after)["contentSha256"])

    def test_distinct_entry_order_is_bound(self):
        before = self.archive([("first", b"one"), ("second", b"two")])
        after = self.archive([("second", b"two"), ("first", b"one")])
        self.assertNotEqual(self.identity(before)["contentSha256"],
                            self.identity(after)["contentSha256"])

    def test_directory_entries_are_bound(self):
        before = self.archive([("directory/", b""), ("directory/file", b"content")])
        after = self.archive([("directory/file", b"content")])
        self.assertNotEqual(self.identity(before)["contentSha256"],
                            self.identity(after)["contentSha256"])

    def test_zip_timestamps_and_compression_only_change_raw_identity(self):
        entries = [("resource", b"same payload"), ("resource", b"another payload")]
        before = self.identity(self.archive(entries))
        timestamp = self.identity(self.archive(entries, year=2025))
        compressed = self.identity(self.archive(entries, compression=zipfile.ZIP_DEFLATED))
        for after in (timestamp, compressed):
            self.assertNotEqual(before["sha256"], after["sha256"])
            self.assertEqual(before["contentSha256"], after["contentSha256"])

    def test_exact_local_header_aliases_retain_both_logical_entries_without_warnings(self):
        alias = self.aliased_archive()
        copies = self.archive([("resource", b"shared physical payload"),
                               ("resource", b"shared physical payload")])
        with warnings.catch_warnings(record=True) as recorded:
            warnings.simplefilter("always")
            result = self.identity(alias)
        self.assertEqual([], [str(item.message) for item in recorded])
        self.assertEqual(2, result["entryCount"])
        self.assertEqual(1, result["duplicateEntryNameCount"])
        self.assertEqual(self.identity(copies)["contentSha256"], result["contentSha256"])

    def test_aliases_with_conflicting_payload_metadata_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "invalid benchmark jar: conflicting local-header aliases"):
            jar_identity(self.aliased_archive(conflicting_crc=True))

    def test_aliases_without_a_valid_physical_boundary_are_rejected(self):
        path = self.aliased_archive()
        raw = bytearray(path.read_bytes())
        central = raw.index(b"PK\x01\x02")
        second = raw.index(b"PK\x01\x02", central + 1)
        for position in (central, second):
            struct.pack_into("<I", raw, position + 42, central)
        path.write_bytes(raw)
        with self.assertRaisesRegex(ValueError, "invalid benchmark jar: unsupported local-header alias boundaries"):
            jar_identity(path)

    def test_partial_overlap_of_different_local_headers_is_rejected(self):
        path = self.archive([("first", b"one"), ("second", b"two")])
        raw = bytearray(path.read_bytes())
        central = raw.index(b"PK\x01\x02")
        struct.pack_into("<I", raw, central + 20, 1000)
        path.write_bytes(raw)
        with self.assertRaisesRegex(ValueError, "invalid benchmark jar: Overlapped entries"):
            jar_identity(path)

    def test_corrupt_earlier_duplicate_cannot_hide_behind_a_valid_last_entry(self):
        path = self.archive([("resource", b"first"), ("resource", b"last")])
        with zipfile.ZipFile(path) as archive:
            first = archive.infolist()[0]
            data_offset = first.header_offset + 30 + len(first.filename.encode()) + len(first.extra)
        data = bytearray(path.read_bytes())
        data[data_offset] ^= 1
        path.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "invalid benchmark jar"):
            jar_identity(path)

    def test_invalid_zip_is_rejected(self):
        path = self.root / "not-a-jar.jar"
        path.write_bytes(b"not a ZIP archive")
        with self.assertRaisesRegex(ValueError, "invalid benchmark jar"):
            jar_identity(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--real-jmh-jar", type=Path,
                        help="also inspect an already built JMH jar; never builds or runs it")
    args, unittest_args = parser.parse_known_args()
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(JarIdentityTests)
    if args.real_jmh_jar is not None:
        class ActualJmhJarTest(unittest.TestCase):
            def runTest(self):
                self.assertTrue(args.real_jmh_jar.is_file(), "existing real JMH jar is required")
                with zipfile.ZipFile(args.real_jmh_jar) as archive:
                    entries = archive.infolist()
                    duplicate_names = sum(count > 1 for count in
                                          collections.Counter(entry.filename for entry in entries).values())
                    self.assertIn("org/openjdk/jmh/Main.class", archive.namelist())
                    self.assertGreater(duplicate_names, 0, "real control must exercise duplicate entries")
                try:
                    result = jar_identity(args.real_jmh_jar)
                except (ValueError, OSError) as error:
                    self.fail(f"actual JMH jar rejected: {error}")
                self.assertEqual("regelsuche.quality.jmh-jar-identity/v2", result.get("schema"))
                self.assertEqual(len(entries), result.get("entryCount"))
                self.assertEqual(duplicate_names, result.get("duplicateEntryNameCount"))
                self.assertEqual(hashlib.sha256(args.real_jmh_jar.read_bytes()).hexdigest(),
                                 result.get("sha256"))
        suite.addTest(ActualJmhJarTest())
    if unittest_args:
        parser.error(f"unrecognized arguments: {' '.join(unittest_args)}")
    return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
