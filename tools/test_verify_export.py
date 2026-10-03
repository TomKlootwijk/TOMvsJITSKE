import hashlib
import io
import json
import stat
import unittest
import warnings
import zipfile

from verify_export import VerificationError, verify_export


def payloads(audio=b"example AAC bytes"):
    name = "recordings/test_000000_123.aac"
    return {
        name: audio,
        name + ".json": json.dumps({"filename": name.rsplit("/", 1)[-1],
                                     "size_bytes": len(audio),
                                     "sha256": hashlib.sha256(audio).hexdigest()}).encode(),
        "export_metadata.json": json.dumps({"schema_version": 1, "finalized_segments": 1,
                                            "excluded_partial_segments": 1}).encode(),
        "events.jsonl": b'{"type":"segment_open"}\n',
    }


def make_archive(files=None, manifest_files=None, extra_manifest=b"", extra_entries=()):
    files = payloads() if files is None else files
    manifest_files = files if manifest_files is None else manifest_files
    manifest = b"".join((hashlib.sha256(data).hexdigest() + "  " + name + "\n").encode()
                        for name, data in manifest_files.items()) + extra_manifest
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for name, data in files.items():
            archive.writestr(name, data)
        archive.writestr("SHA256SUMS", manifest)
        for name, data in extra_entries:
            archive.writestr(name, data)
    output.seek(0)
    return output


def payloads_with_references(interrupted=False):
    files = payloads()
    old_name = "recordings/test_000000_123.aac"
    name = old_name.replace(".aac", ".interrupted.aac") if interrupted else old_name
    sidecar = json.loads(files.pop(old_name + ".json"))
    audio = files.pop(old_name)
    files[name] = audio
    sidecar["filename"] = name.rsplit("/", 1)[-1]
    delta = b'{"type":"segment_finalized"}\n'
    prefix = b"x" * len(delta)
    files["events.jsonl"] = prefix + delta + b'{"type":"session_stop"}\n'
    delta_name = name + f".events-{len(prefix)}-{len(prefix) + len(delta)}.jsonl"
    files[delta_name] = delta
    sidecar["journal_snapshot"] = {
        "filename": delta_name.rsplit("/", 1)[-1], "source": "events.jsonl",
        "start_byte": len(prefix), "end_byte": len(prefix) + len(delta),
        "size_bytes": len(delta), "sha256": hashlib.sha256(delta).hexdigest(),
    }
    opening = b'{"start_epoch_ms":123}\n'
    files["recordings/test_000000_123.open.json"] = opening
    sidecar["opening_metadata_size_bytes"] = len(opening)
    sidecar["opening_metadata_sha256"] = hashlib.sha256(opening).hexdigest()
    files[name + ".json"] = json.dumps(sidecar).encode()
    return files, name, delta_name


class VerifyExportTest(unittest.TestCase):
    def test_valid_archive_and_exclusion_report(self):
        report = verify_export(make_archive())
        self.assertEqual(4, report["files_verified"])
        self.assertEqual(1, report["segments_verified"])
        self.assertEqual(1, report["excluded_partial_segments"])

    def test_tampered_audio(self):
        original = payloads()
        altered = dict(original)
        altered["recordings/test_000000_123.aac"] = b"changed bytes"
        with self.assertRaisesRegex(VerificationError, "SHA-256 mismatch"):
            verify_export(make_archive(altered, original))

    def test_missing_file(self):
        original = payloads()
        missing = dict(original)
        del missing["events.jsonl"]
        with self.assertRaisesRegex(VerificationError, "coverage mismatch"):
            verify_export(make_archive(missing, original))

    def test_unhashed_additional_file(self):
        original = payloads()
        altered = {**original, "unexpected.txt": b"not in manifest"}
        with self.assertRaisesRegex(VerificationError, "coverage mismatch"):
            verify_export(make_archive(altered, original))

    def test_duplicate_zip_member(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            archive = make_archive(extra_entries=[("events.jsonl", b"duplicate")])
        with self.assertRaisesRegex(VerificationError, "Duplicate"):
            verify_export(archive)

    def test_case_colliding_zip_member(self):
        archive = make_archive(extra_entries=[("EVENTS.jsonl", b"duplicate on Windows")])
        with self.assertRaisesRegex(VerificationError, "case-colliding"):
            verify_export(archive)

    def test_duplicate_manifest_member(self):
        digest = hashlib.sha256(payloads()["events.jsonl"]).hexdigest()
        with self.assertRaisesRegex(VerificationError, "Duplicate"):
            verify_export(make_archive(extra_manifest=(digest + "  events.jsonl\n").encode()))

    def test_unsafe_archive_paths(self):
        for unsafe in ("../escape", "/absolute", "C:/drive", "nested/../../escape", "nested\\escape",
                       "nested//file", "./file", "nul\u0000hidden", "trailing.", "CON", "nested/LPT1.txt"):
            # Set the raw filename after construction so Windows zipfile cannot sanitize
            # the intentionally malicious fixture before it reaches the verifier.
            info = zipfile.ZipInfo("placeholder")
            info.filename = unsafe
            with self.subTest(path=unsafe), self.assertRaisesRegex(VerificationError, "Unsafe"):
                verify_export(make_archive(extra_entries=[(info, b"x")]))

    def test_unsafe_manifest_path(self):
        line = ("0" * 64 + "  ../escape\n").encode()
        with self.assertRaisesRegex(VerificationError, "Unsafe"):
            verify_export(make_archive(extra_manifest=line))

    def test_sidecar_digest_must_match_even_if_outer_manifest_rewritten(self):
        files = payloads()
        files["recordings/test_000000_123.aac"] = b"different payload"
        with self.assertRaisesRegex(VerificationError, "sidecar does not match"):
            verify_export(make_archive(files))

    def test_missing_sidecar(self):
        files = payloads()
        del files["recordings/test_000000_123.aac.json"]
        with self.assertRaisesRegex(VerificationError, "Missing recording sidecar"):
            verify_export(make_archive(files))

    def test_symlink_is_rejected(self):
        link = zipfile.ZipInfo("link")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertRaisesRegex(VerificationError, "Symbolic links"):
            verify_export(make_archive(extra_entries=[(link, b"target")]))

    def test_partial_segment_is_not_a_valid_finalized_export(self):
        files = {**payloads(), "recordings/active.aac.partial": b"in progress"}
        with self.assertRaisesRegex(VerificationError, "Unfinished recording"):
            verify_export(make_archive(files))

    def test_truncated_zip_is_rejected(self):
        data = make_archive().getvalue()
        with self.assertRaises(VerificationError):
            verify_export(io.BytesIO(data[:-30]))

    def test_manifest_requires_complete_newline_terminated_records(self):
        with self.assertRaisesRegex(VerificationError, "unterminated"):
            verify_export(make_archive(extra_manifest=b"0" * 64 + b"  other"))

    def test_new_references_validate_for_clean_and_interrupted_segments(self):
        for interrupted in (False, True):
            with self.subTest(interrupted=interrupted):
                files, _, _ = payloads_with_references(interrupted)
                report = verify_export(make_archive(files))
                self.assertEqual(6, report["files_verified"])
                self.assertEqual(1, report["journal_snapshots_verified"])
                self.assertEqual(1, report["opening_metadata_verified"])

    def test_legacy_sidecars_do_not_require_new_reference_fields(self):
        report = verify_export(make_archive())
        self.assertEqual(0, report["journal_snapshots_verified"])
        self.assertEqual(0, report["opening_metadata_verified"])

    def test_changed_delta_with_rewritten_manifest_is_rejected(self):
        files, _, delta_name = payloads_with_references()
        files[delta_name] = b"x" * len(files[delta_name])
        with self.assertRaisesRegex(VerificationError, "Journal snapshot metadata does not match"):
            verify_export(make_archive(files))

    def test_changed_delta_and_updated_digest_still_must_match_source_journal(self):
        files, name, delta_name = payloads_with_references()
        files[delta_name] = b"z" * len(files[delta_name])
        sidecar = json.loads(files[name + ".json"])
        sidecar["journal_snapshot"]["sha256"] = hashlib.sha256(files[delta_name]).hexdigest()
        files[name + ".json"] = json.dumps(sidecar).encode()
        with self.assertRaisesRegex(VerificationError, "does not match its source range"):
            verify_export(make_archive(files))

    def test_journal_reference_requires_declared_member_and_source(self):
        for missing in ("delta", "source"):
            files, _, delta_name = payloads_with_references()
            del files[delta_name if missing == "delta" else "events.jsonl"]
            with self.subTest(missing=missing), self.assertRaises(VerificationError):
                verify_export(make_archive(files))

    def test_journal_range_rejects_invalid_types_bounds_and_source_names(self):
        for key, value in (("start_byte", -1), ("start_byte", True), ("end_byte", 1.0),
                           ("size_bytes", "28"), ("size_bytes", 0), ("end_byte", 10000),
                           ("source", "other.jsonl")):
            files, name, _ = payloads_with_references()
            sidecar = json.loads(files[name + ".json"])
            sidecar["journal_snapshot"][key] = value
            files[name + ".json"] = json.dumps(sidecar).encode()
            with self.subTest(key=key, value=value), self.assertRaises(VerificationError):
                verify_export(make_archive(files))

    def test_journal_reference_cannot_escape_to_another_archive_path(self):
        for value in (None, 12, "../events.jsonl", "nested/events.jsonl", "x\\events.jsonl", "events.jsonl"):
            files, name, _ = payloads_with_references()
            sidecar = json.loads(files[name + ".json"])
            sidecar["journal_snapshot"]["filename"] = value
            files[name + ".json"] = json.dumps(sidecar).encode()
            with self.subTest(value=value), self.assertRaises(VerificationError):
                verify_export(make_archive(files))

    def test_wrong_source_offset_is_rejected_even_if_size_and_hash_match_delta(self):
        files, name, _ = payloads_with_references()
        sidecar = json.loads(files[name + ".json"])
        snapshot = sidecar["journal_snapshot"]
        snapshot["start_byte"] = 0
        snapshot["end_byte"] = snapshot["size_bytes"]
        files[name + ".json"] = json.dumps(sidecar).encode()
        with self.assertRaisesRegex(VerificationError, "does not match its source range"):
            verify_export(make_archive(files))

    def test_opening_reference_rejects_missing_changed_or_incomplete_metadata(self):
        opening_name = "recordings/test_000000_123.open.json"
        for change in ("missing", "changed", "missing_hash", "missing_size", "boolean_size"):
            files, name, _ = payloads_with_references()
            sidecar = json.loads(files[name + ".json"])
            if change == "missing":
                del files[opening_name]
            elif change == "changed":
                files[opening_name] = b"x" * len(files[opening_name])
            elif change == "missing_hash":
                del sidecar["opening_metadata_sha256"]
            elif change == "missing_size":
                del sidecar["opening_metadata_size_bytes"]
            else:
                sidecar["opening_metadata_size_bytes"] = True
            files[name + ".json"] = json.dumps(sidecar).encode()
            with self.subTest(change=change), self.assertRaises(VerificationError):
                verify_export(make_archive(files))

    def test_overlapping_journal_ranges_after_cursor_recovery_are_valid(self):
        files, first_name, _ = payloads_with_references()
        first = json.loads(files[first_name + ".json"])
        end = first["journal_snapshot"]["end_byte"]
        second_name = "recordings/second_000000_124.aac"
        audio = b"second audio"
        delta_name = second_name + f".events-0-{end}.jsonl"
        delta = files["events.jsonl"][:end]
        files[second_name] = audio
        files[delta_name] = delta
        files[second_name + ".json"] = json.dumps({
            "filename": second_name.rsplit("/", 1)[-1], "size_bytes": len(audio),
            "sha256": hashlib.sha256(audio).hexdigest(),
            "journal_snapshot": {
                "filename": delta_name.rsplit("/", 1)[-1], "source": "events.jsonl",
                "start_byte": 0, "end_byte": end, "size_bytes": len(delta),
                "sha256": hashlib.sha256(delta).hexdigest(),
            },
        }).encode()
        files["export_metadata.json"] = json.dumps({"schema_version": 1, "finalized_segments": 2}).encode()
        report = verify_export(make_archive(files))
        self.assertEqual(2, report["journal_snapshots_verified"])
        self.assertEqual(2, report["segments_verified"])


if __name__ == "__main__":
    unittest.main()
