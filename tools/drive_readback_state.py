#!/usr/bin/env python3
"""Local checkpointing for byte verification of this task's completed Drive uploads."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1] / "evidence" / "2026-09-27"
UPLOADS = [ROOT / name for name in (
    "google-drive-files.jsonl", "google-drive-sept26-files.jsonl", "google-drive-sept27-files.jsonl")]
READBACK = ROOT / "google-drive-readback.jsonl"
EXPECTED = ROOT / "individual-files.filelist.json"


def lines(path):
    if not path.exists():
        return []
    records = []
    with path.open("rb") as stream:
        for raw in stream:
            if not raw.endswith(b"\n"):
                break  # Another process may currently be appending the final record.
            records.append(json.loads(raw.decode("utf-8")))
    return records


def paths(root=None, journals=None):
    root = Path(root).resolve(strict=True) if root is not None else ROOT
    names = journals or ([path.name for path in UPLOADS] if root == ROOT else ["google-drive-files.jsonl"])
    if any(Path(name).name != name or "/" in name or "\\" in name for name in names):
        raise ValueError("Upload journal names must be filenames within the snapshot directory")
    return root, root / "individual-files.filelist.json", root / "google-drive-readback.jsonl", [root / name for name in names]


def state(limit, root=None, journals=None):
    root, expected_path, readback_path, upload_paths = paths(root, journals)
    manifest = json.loads(expected_path.read_text(encoding="utf-8"))
    expected = {item["relative_path"]: item for item in manifest["files"]}
    successes = {item["relative_path"]: item for item in lines(readback_path) if item.get("verified") is True}
    if set(successes) - set(expected):
        raise ValueError("Readback journal contains paths outside this snapshot manifest")
    uploaded, pending, seen_ids = {}, [], {}
    combined = [item for journal in upload_paths for item in lines(journal)]
    for item in combined:
        path = item.get("relative_path")
        if not (item.get("upload_confirmed") is True or item.get("status") == "uploaded"):
            continue
        if path not in expected:
            raise ValueError("Confirmed upload is outside this snapshot manifest")
        original = expected[path]
        if item.get("size") != original["size"] or item.get("sha256") != original["sha256"]:
            raise ValueError(f"Upload record differs from original manifest: {path}")
        if not re.fullmatch(r"[A-Za-z0-9_-]+", item.get("id", "")):
            raise ValueError("Invalid Drive file id in local checkpoint")
        if item["id"] in seen_ids and seen_ids[item["id"]] != path:
            raise ValueError("One Drive id is recorded for different local paths")
        seen_ids[item["id"]] = path
        if path in uploaded and uploaded[path]["id"] != item["id"]:
            raise ValueError(f"Duplicate uploaded path with different file id: {path}")
        uploaded[path] = item
    valid_successes = 0
    for path, item in uploaded.items():
        verified = successes.get(path)
        if verified and (verified.get("id"), verified.get("size"), verified.get("sha256")) == (
                item["id"], item["size"], item["sha256"]):
            valid_successes += 1
            continue
        pending.append({key: item[key] for key in ("id", "relative_path", "size", "sha256")})
    return {"expected": len(expected), "uploaded": len(uploaded), "verified": valid_successes,
            "pending": len(pending), "batch": pending[:limit]}


def record(encoded, root=None, journals=None):
    root, expected_path, readback_path, upload_paths = paths(root, journals)
    batch = json.loads(base64.b64decode(encoded, validate=True))
    current = lines(readback_path)
    done = {(item["id"], item["relative_path"]) for item in current if item.get("verified") is True}
    pending = {item["relative_path"]: item for item in state(1000000, root, journals)["batch"]}
    # Validate the entire batch before writing any receipt.
    for item in batch:
        if item.get("verified") is not True or item.get("sha256") != item.get("expected_sha256") or item.get("size") != item.get("expected_size"):
            raise ValueError("Only matching full-byte readbacks may be checkpointed")
        if (item["id"], item["relative_path"]) not in done:
            expected = pending.get(item["relative_path"])
            if not expected or any(item.get(key) != expected[key] for key in ("id", "size", "sha256")):
                raise ValueError("Readback differs from the current upload and original manifest")
    with readback_path.open("a", encoding="utf-8", newline="\n") as stream:
        count = 0
        for item in batch:
            if item.get("verified") is not True or item.get("sha256") != item.get("expected_sha256"):
                raise ValueError("Only confirmed matching hashes may be checkpointed")
            if item.get("size") != item.get("expected_size"):
                raise ValueError("Only confirmed matching sizes may be checkpointed")
            if (item["id"], item["relative_path"]) in done:
                continue
            stream.write(json.dumps(item, separators=(",", ":")) + "\n")
            done.add((item["id"], item["relative_path"]))
            count += 1
        stream.flush()
        os.fsync(stream.fileno())
    return {"recorded": count}


def summary(root=None, journals=None):
    root, expected_path, readback_path, upload_paths = paths(root, journals)
    status = state(0, root, journals)
    if status["verified"] != status["expected"] or status["uploaded"] != status["expected"]:
        raise ValueError("Cannot summarize completion while uploads or readbacks are pending")
    manifest = json.loads(expected_path.read_text(encoding="utf-8"))
    rows = lines(readback_path)
    if len(rows) != status["expected"] or len({row["id"] for row in rows}) != len(rows):
        raise ValueError("Readback journal contains duplicate or unsuccessful records")
    report = {"status": "complete", "completed_at_utc": datetime.now(timezone.utc).isoformat(),
              "source_archive_name": manifest.get("original_archive_name"),
              "source_archive_sha256": manifest.get("original_archive_sha256"),
              "source_scope": manifest.get("scope"),
              "source_filelist_sha256": hashlib.sha256(expected_path.read_bytes()).hexdigest(),
              "readback_journal_sha256": hashlib.sha256(readback_path.read_bytes()).hexdigest(),
              "expected_files": status["expected"], "verified_files": len(rows),
              "verified_bytes": sum(row["size"] for row in rows),
              "audio_recordings": sum(row["relative_path"].endswith(".aac") for row in rows),
              "missing_files": 0, "hash_or_size_mismatches": 0,
              "method": "Complete Google Drive raw-file readback; size and SHA-256 compared to the local snapshot manifest"}
    output = root / "google-drive-readback-summary.json"
    with output.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2)
        stream.write("\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("next", "record", "summary"))
    parser.add_argument("payload", nargs="?")
    parser.add_argument("--limit", type=int, default=8)
    parser.add_argument("--root", type=Path, required=True, help="Explicit snapshot evidence directory")
    parser.add_argument("--journal", action="append", help="Upload journal filename; repeat for parallel uploaders")
    args = parser.parse_args()
    if args.action == "next":
        result = state(args.limit, args.root, args.journal)
    elif args.action == "record":
        result = record(args.payload, args.root, args.journal)
    else:
        result = summary(args.root, args.journal)
    print(json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    main()
