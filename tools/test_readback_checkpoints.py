import base64
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import drive_readback_state as checkpoint
from finalize_backup_receipts import verify_onedrive_report


class ReadbackCheckpointTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.file = {"relative_path": "audio/2026-09-27/a.aac", "size": 1,
                     "sha256": hashlib.sha256(b"a").hexdigest()}
        manifest = {"original_archive_name": "source.zip", "original_archive_sha256": "0" * 64,
                    "files": [self.file]}
        (self.root / "individual-files.filelist.json").write_text(json.dumps(manifest))
        self.upload = {**self.file, "id": "Drive_file_123", "status": "uploaded"}
        (self.root / "google-drive-files.jsonl").write_text(json.dumps(self.upload) + "\n")

    def receipt(self):
        return {**self.upload, "verified": True, "expected_size": self.file["size"],
                "expected_sha256": self.file["sha256"]}

    @staticmethod
    def encoded(rows):
        return base64.b64encode(json.dumps(rows).encode()).decode()

    def test_explicit_snapshot_status_schema_and_resumable_receipts(self):
        self.assertEqual(1, checkpoint.state(8, self.root)["pending"])
        receipt = self.receipt()
        self.assertEqual(1, checkpoint.record(self.encoded([receipt]), self.root)["recorded"])
        self.assertEqual(0, checkpoint.record(self.encoded([receipt]), self.root)["recorded"])
        self.assertEqual(1, checkpoint.state(8, self.root)["verified"])
        self.assertEqual(1, checkpoint.summary(self.root)["verified_files"])

    def test_self_consistent_wrong_hash_is_rejected_against_original_manifest(self):
        receipt = self.receipt()
        receipt["sha256"] = receipt["expected_sha256"] = "1" * 64
        with self.assertRaisesRegex(ValueError, "original manifest"):
            checkpoint.record(self.encoded([receipt]), self.root)
        self.assertFalse((self.root / "google-drive-readback.jsonl").exists())

    def test_incomplete_google_summary_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "pending"):
            checkpoint.summary(self.root)

    def test_onedrive_requires_exact_paths_and_hashes_not_only_verified_boolean(self):
        expected = {self.file["relative_path"]: self.file}
        self.assertTrue(verify_onedrive_report({"verified": True, "files": [self.file]}, expected))
        with self.assertRaisesRegex(ValueError, "exactly cover"):
            verify_onedrive_report({"verified": True, "files": []}, expected)
        wrong = {**self.file, "sha256": "1" * 64}
        with self.assertRaisesRegex(ValueError, "differs"):
            verify_onedrive_report({"verified": True, "files": [wrong]}, expected)


if __name__ == "__main__":
    unittest.main()
