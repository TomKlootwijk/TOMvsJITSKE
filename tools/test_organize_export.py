import csv
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from organize_export import organize_export
from test_verify_export import make_archive, payloads, payloads_with_references
from verify_export import VerificationError


class OrganizeExportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.archive = self.root / "original.zip"
        self.output = self.root / "individual-files"

    def save(self, files, manifest_files=None):
        self.archive.write_bytes(make_archive(files, manifest_files).getvalue())

    def test_preserves_all_original_bytes_references_and_hashes(self):
        files, audio, delta = payloads_with_references(interrupted=True)
        sidecar = json.loads(files[audio + ".json"])
        sidecar.update(start_epoch_ms=1790456390123, end_epoch_ms=None,
                       duration_us=1234567, status="interrupted")
        files[audio + ".json"] = json.dumps(sidecar).encode()
        self.save(files)
        archive_before = self.archive.read_bytes()
        report = organize_export(self.archive, self.output,
                                 expected_archive_sha256=hashlib.sha256(archive_before).hexdigest())
        originals = {item["original_archive_path"]: item for item in report["files"] if item["kind"] == "original"}
        self.assertEqual(set(files) | {"SHA256SUMS"}, set(originals))
        for name, data in files.items():
            self.assertEqual(data, (self.output / originals[name]["relative_path"]).read_bytes())
        audio_path = Path(originals[audio]["relative_path"])
        self.assertEqual(Path("audio/2026-09-26"), audio_path.parent)
        self.assertEqual(audio_path.parent, Path(originals[delta]["relative_path"]).parent)
        self.assertEqual(archive_before, self.archive.read_bytes())
        for item in report["files"]:
            content = (self.output / item["relative_path"]).read_bytes()
            self.assertEqual(len(content), item["size"])
            self.assertEqual(hashlib.sha256(content).hexdigest(), item["sha256"])
            self.assertEqual(hashlib.md5(content, usedforsecurity=False).hexdigest(), item["md5"])
        with (self.output / "INDEX.csv").open(encoding="utf-8", newline="") as stream:
            row = next(csv.DictReader(stream))
        self.assertEqual("1.234567", row["duration_seconds"])
        self.assertEqual("", row["end_utc"])
        self.assertEqual("interrupted", row["status"])

    def test_undated_and_spreadsheet_formula_are_not_inferred_or_executed(self):
        files = payloads()
        name = "recordings/test_000000_123.aac.json"
        sidecar = json.loads(files[name])
        sidecar.update(start_epoch_ms=True, end_epoch_ms=10**30, status="=1+1")
        files[name] = json.dumps(sidecar).encode()
        self.save(files)
        organize_export(self.archive, self.output)
        self.assertTrue((self.output / "audio/undated/test_000000_123.aac").is_file())
        with (self.output / "INDEX.csv").open(encoding="utf-8", newline="") as stream:
            row = next(csv.DictReader(stream))
        self.assertEqual("'=1+1", row["status"])
        self.assertEqual("", row["start_utc"])
        self.assertEqual("", row["end_utc"])

    def test_existing_destination_rejected_without_overwriting(self):
        self.save(payloads())
        self.output.mkdir()
        sentinel = self.output / "sentinel"
        sentinel.write_bytes(b"unchanged")
        with self.assertRaisesRegex(VerificationError, "existing destination"):
            organize_export(self.archive, self.output)
        self.assertEqual(b"unchanged", sentinel.read_bytes())

    def test_tampered_export_is_rejected_before_any_output(self):
        original = payloads()
        changed = dict(original)
        changed["recordings/test_000000_123.aac"] = b"tampered"
        self.save(changed, original)
        with self.assertRaisesRegex(VerificationError, "SHA-256 mismatch"):
            organize_export(self.archive, self.output)
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob(".organize-*")))

    def test_flattening_collision_is_rejected_before_output(self):
        files = payloads()
        audio = "recordings/test_000000_123.aac"
        files["other/test_000000_123.aac"] = files[audio]
        files["other/test_000000_123.aac.json"] = files[audio + ".json"]
        metadata = json.loads(files["export_metadata.json"])
        metadata["finalized_segments"] = 2
        files["export_metadata.json"] = json.dumps(metadata).encode()
        self.save(files)
        with self.assertRaisesRegex(VerificationError, "collision"):
            organize_export(self.archive, self.output)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
