import argparse
import contextlib
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

from transcribe_local import (diagnostic_flags, immutable_write, language_metadata,
                              language_parameter, process, read_manifest, sha256_file)


class TranscriptionBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.source = self.folder / "selected.aac"
        self.source.write_bytes(b"test fixture, not audio")
        self.manifest = self.folder / "selected.json"

    def write_manifest(self, rows):
        self.manifest.write_text(json.dumps({"files": rows}), encoding="utf-8")

    def test_check_only_hashes_exact_allowed_list_without_asr(self):
        self.write_manifest([{"path": str(self.source), "sha256": sha256_file(self.source)}])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, process(argparse.Namespace(manifest=self.manifest, check_only=True)))
        self.assertEqual(b"test fixture, not audio", self.source.read_bytes())

    def test_source_hash_mismatch_stops_before_loading_asr(self):
        self.write_manifest([{"path": str(self.source), "sha256": "0" * 64}])
        with self.assertRaisesRegex(ValueError, "Source hash mismatch"):
            process(argparse.Namespace(manifest=self.manifest, check_only=True))

    def test_relative_or_duplicate_source_paths_rejected(self):
        self.write_manifest([{"path": "selected.aac", "sha256": "0" * 64}])
        with self.assertRaises(ValueError):
            read_manifest(self.manifest)
        row = {"path": str(self.source), "sha256": sha256_file(self.source)}
        self.write_manifest([row, row])
        with self.assertRaisesRegex(ValueError, "Duplicate source"):
            read_manifest(self.manifest)

    def test_publishing_never_overwrites_different_output(self):
        target = self.folder / "transcript.json"
        immutable_write(target, b"original")
        immutable_write(target, b"original")
        with self.assertRaises(FileExistsError):
            immutable_write(target, b"replacement")
        self.assertEqual(b"original", target.read_bytes())
        self.assertEqual([], list(self.folder.glob("*.pending-*")))

    def test_uncertainty_diagnostics_are_retained_separately(self):
        flags = diagnostic_flags({"avg_logprob": -1.3, "no_speech_prob": 0.8,
                                  "compression_ratio": 3, "words": [{"probability": 0.2}]})
        self.assertEqual(["low_model_log_probability", "possible_non_speech",
                          "possible_repetition", "low_probability_words"], flags)

    def test_auto_language_does_not_force_dutch_and_preserves_detection_scores(self):
        self.assertIsNone(language_parameter("auto"))
        self.assertIsNone(language_parameter(" AUTO "))
        self.assertEqual("nl", language_parameter("nl"))
        self.assertEqual("en", language_parameter("EN"))
        info = SimpleNamespace(language="en", language_probability=0.87,
                               all_language_probs=[("en", 0.87), ("nl", 0.09)])
        metadata = language_metadata("auto", info)
        self.assertEqual("auto", metadata["requested_language"])
        self.assertEqual("automatic_per_recording", metadata["language_selection"])
        self.assertEqual(info.all_language_probs, metadata["all_language_probabilities"])
        self.assertEqual(0.87, metadata["language_probability"])
        forced = language_metadata("en", info)
        self.assertEqual("explicit_command_line_setting", forced["language_selection"])
        self.assertIsNone(forced["all_language_probabilities"])


if __name__ == "__main__":
    unittest.main()
