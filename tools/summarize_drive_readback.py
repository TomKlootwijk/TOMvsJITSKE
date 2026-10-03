#!/usr/bin/env python3
"""Validate exact upload/readback coverage and save this evidence transfer's final summary."""
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json

import drive_readback_state as checkpoint


def main():
    manifest_bytes = checkpoint.EXPECTED.read_bytes()
    manifest = json.loads(manifest_bytes)
    expected = {item["relative_path"]: item for item in manifest["files"]}
    rows = checkpoint.lines(checkpoint.READBACK)
    by_path = {item["relative_path"]: item for item in rows}
    if len(rows) != len(by_path) or len({item["id"] for item in rows}) != len(rows):
        raise ValueError("Duplicate paths or Drive IDs in readback journal")
    if set(by_path) != set(expected):
        raise ValueError("Readback does not cover exactly the expected file manifest")
    for path, original in expected.items():
        result = by_path[path]
        if (result.get("verified") is not True or result.get("size") != original["size"]
                or result.get("expected_size") != original["size"]
                or result.get("sha256") != original["sha256"]
                or result.get("expected_sha256") != original["sha256"]):
            raise ValueError(f"Unverified or mismatched file: {path}")
    current = checkpoint.state(0)
    if current["pending"] != 0 or current["uploaded"] != len(expected) or current["verified"] != len(expected):
        raise ValueError("Completed upload journals and readback journal differ")
    audio = [item for item in manifest["files"] if item["relative_path"].endswith(".aac")]
    summary = {
        "schema_version": 1,
        "status": "complete",
        "completed_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "source_archive_name": manifest["original_archive_name"],
        "source_archive_sha256": manifest["original_archive_sha256"],
        "source_filelist": checkpoint.EXPECTED.name,
        "source_filelist_sha256": hashlib.sha256(manifest_bytes).hexdigest(),
        "readback_journal": checkpoint.READBACK.name,
        "readback_journal_sha256": hashlib.sha256(checkpoint.READBACK.read_bytes()).hexdigest(),
        "expected_files": len(expected), "verified_files": len(rows),
        "unique_drive_file_ids": len({item["id"] for item in rows}),
        "verified_bytes": sum(item["size"] for item in rows),
        "audio_recordings": len(audio),
        "audio_by_utc_date": dict(sorted(Counter(item["relative_path"].split("/")[1] for item in audio).items())),
        "original_files_preserved": sum(item["kind"] == "original" for item in manifest["files"]),
        "generated_navigation_and_checksum_files": sum(item["kind"] == "generated" for item in manifest["files"]),
        "missing_files": 0, "duplicate_paths": 0, "hash_or_size_mismatches": 0,
        "first_readback_at_utc": min(item["verified_at_utc"] for item in rows),
        "last_readback_at_utc": max(item["verified_at_utc"] for item in rows),
        "method": "Each uploaded Drive file was fetched in full as raw bytes; decoded size and SHA-256 were compared to the verified local manifest.",
        "scope": "The 1,942 individual files from the fixed export snapshot; later phone recordings and separately uploaded transfer documents are outside this count.",
    }
    output = checkpoint.ROOT / "google-drive-readback-summary.json"
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(summary, stream, indent=2)
        stream.write("\n")
    print(json.dumps(summary, indent=2))
    print(f"Summary saved: {output}")


if __name__ == "__main__":
    main()
