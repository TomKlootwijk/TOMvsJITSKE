import csv
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from organize_export import organize_export
from organize_incremental_export import organize_incremental
from test_verify_export import make_archive
from verify_export import VerificationError


def add_segment(files, label):
    files = dict(files)
    audio_name = f"recordings/{label}.aac"
    audio = ("example AAC bytes " + label).encode()
    journal = files.get("events.jsonl", b"")
    delta = (json.dumps({"type": "segment_finalized", "name": label}) + "\n").encode()
    delta_name = audio_name + f".events-{len(journal)}-{len(journal) + len(delta)}.jsonl"
    opening_name = f"recordings/{label}.open.json"
    opening = b'{"start_epoch_ms":1790456390123}\n'
    files[audio_name] = audio
    files[opening_name] = opening
    files[delta_name] = delta
    files[audio_name + ".json"] = json.dumps({
        "filename": Path(audio_name).name, "size_bytes": len(audio), "sha256": hashlib.sha256(audio).hexdigest(),
        "start_epoch_ms": 1790456390123, "duration_us": 1000000, "status": "finalized",
        "opening_metadata_size_bytes": len(opening), "opening_metadata_sha256": hashlib.sha256(opening).hexdigest(),
        "journal_snapshot": {"filename": Path(delta_name).name, "source": "events.jsonl",
                             "start_byte": len(journal), "end_byte": len(journal) + len(delta),
                             "size_bytes": len(delta), "sha256": hashlib.sha256(delta).hexdigest()},
    }).encode()
    files["events.jsonl"] = journal + delta
    files["export_metadata.json"] = json.dumps({"schema_version": 1,
        "finalized_segments": sum(name.endswith(".aac") for name in files)}).encode()
    return files


class IncrementalExportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.old_files = add_segment({}, "first")
        old_zip = self.save("old", self.old_files)
        self.old_report = organize_export(old_zip, self.root / "old-output")
        self.prior = self.root / "old-output.filelist.json"

    def save(self, name, files):
        path = self.root / (name + ".zip")
        path.write_bytes(make_archive(files).getvalue())
        return path

    def test_only_new_bundle_is_copied_and_prior_bytes_are_referenced(self):
        files = add_segment(self.old_files, "second")
        archive = self.save("new", files)
        before = archive.read_bytes()
        result = organize_incremental(archive, self.root / "new-output", [self.prior])
        self.assertEqual((1, 1, 4), (result["segment_count"], result["reused_segment_count"], result["reused_original_count"]))
        output = Path(result["output_directory"])
        copied = {item["original_archive_path"]: item for item in result["files"] if item["kind"] == "original"}
        self.assertNotIn("recordings/first.aac", copied)
        self.assertEqual(1, len(list(output.rglob("*.aac"))))
        self.assertEqual(4, len(result["reused_originals"]))
        self.assertEqual([], result["prior_originals_not_in_snapshot"])
        for name, item in copied.items():
            if name != "SHA256SUMS":
                self.assertEqual(files[name], (output / item["relative_path"]).read_bytes())
        self.assertEqual(files["events.jsonl"], (output / "records/snapshots/new/events.jsonl").read_bytes())
        with (output / "INDEX.csv").open(newline="", encoding="utf-8") as stream:
            rows = list(csv.DictReader(stream))
        self.assertEqual(["second.aac"], [row["original_filename"] for row in rows])
        self.assertEqual(before, archive.read_bytes())

    def test_changed_immutable_metadata_produces_conflict_report_without_extracting(self):
        files = add_segment(self.old_files, "second")
        name = "recordings/first.aac.json"
        sidecar = json.loads(files[name])
        sidecar["status"] = "changed"
        files[name] = json.dumps(sidecar).encode()
        with self.assertRaisesRegex(VerificationError, "Changed immutable"):
            organize_incremental(self.save("changed", files), self.root / "conflict", [self.prior])
        self.assertFalse((self.root / "conflict").exists())
        report = json.loads((self.root / "conflict.conflicts.json").read_text())
        self.assertEqual(name, report["conflicts"][0]["original_archive_path"])

    def test_rehashed_changed_old_audio_is_rejected_even_when_new_zip_verifies(self):
        files = add_segment(self.old_files, "second")
        audio = "recordings/first.aac"
        files[audio] = b"different original bytes"
        sidecar = json.loads(files[audio + ".json"])
        sidecar.update(size_bytes=len(files[audio]), sha256=hashlib.sha256(files[audio]).hexdigest())
        files[audio + ".json"] = json.dumps(sidecar).encode()
        with self.assertRaisesRegex(VerificationError, "Changed immutable"):
            organize_incremental(self.save("changed-audio", files), self.root / "conflict", [self.prior])
        self.assertFalse((self.root / "conflict").exists())
        report = json.loads((self.root / "conflict.conflicts.json").read_text())
        self.assertIn(audio, [item["original_archive_path"] for item in report["conflicts"]])

    def test_missing_prior_members_are_reported_without_deleting_prior_copies(self):
        files = {"events.jsonl": self.old_files["events.jsonl"],
                 "export_metadata.json": b'{"schema_version":1,"finalized_segments":0}'}
        result = organize_incremental(self.save("empty", files), self.root / "empty-output", [self.prior])
        self.assertEqual(0, result["segment_count"])
        self.assertEqual(4, result["prior_originals_not_in_snapshot_count"])
        self.assertEqual(1, len(list((self.root / "old-output").rglob("*.aac"))))

    def test_latest_incremental_manifest_retains_transitive_reuse(self):
        second = add_segment(self.old_files, "second")
        organize_incremental(self.save("second", second), self.root / "second-output", [self.prior])
        third = add_segment(second, "third")
        result = organize_incremental(self.save("third", third), self.root / "third-output",
                                      [self.root / "second-output.filelist.json"])
        self.assertEqual((1, 2, 8), (result["segment_count"], result["reused_segment_count"], result["reused_original_count"]))
        self.assertEqual(1, len(list((self.root / "third-output").rglob("*.aac"))))

    def test_changed_prior_local_copy_is_not_silently_reused(self):
        prior_audio = next(item for item in self.old_report["files"] if item["relative_path"].endswith(".aac"))
        (Path(self.old_report["output_directory"]) / prior_audio["relative_path"]).write_bytes(b"changed")
        with self.assertRaisesRegex(VerificationError, "Prior organized original"):
            organize_incremental(self.save("new", add_segment(self.old_files, "second")),
                                 self.root / "new-output", [self.prior])
        self.assertFalse((self.root / "new-output").exists())


if __name__ == "__main__":
    unittest.main()
