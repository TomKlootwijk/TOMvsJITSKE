#!/usr/bin/env python3
"""Verify a new recorder ZIP and emit new files while explicitly referencing prior originals."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import uuid
from datetime import datetime, timezone
import zipfile

from organize_export import (CHUNK, INDEX_FIELDS, _cleanup_stage, _csv_bytes, _hash_stream,
                             _plan, _target, _validate_destinations)
from verify_export import VerificationError, _safe_name, verify_export

SNAPSHOT_FILES = {"events.jsonl", "export_metadata.json", "SHA256SUMS"}


def _json_bytes(value):
    return (json.dumps(value, indent=2) + "\n").encode("utf-8")


def _publish_transaction_file(path, value):
    path = Path(path)
    temp = path.with_name(path.name + "." + uuid.uuid4().hex + ".partial")
    with temp.open("xb") as target:
        target.write(_json_bytes(value))
        target.flush()
        os.fsync(target.fileno())
    os.link(temp, path)


def _load_priors(paths):
    known, manifests = {}, []
    for path in paths:
        path = Path(path).resolve(strict=True)
        data = path.read_bytes()
        report = json.loads(data)
        manifests.append({"path": str(path), "sha256": hashlib.sha256(data).hexdigest()})
        references = list(report.get("reused_originals", [])) + list(report.get("prior_originals_not_in_snapshot", []))
        for item in report["files"]:
            if item.get("kind") == "original":
                references.append({"original_archive_path": item["original_archive_path"],
                                   "size": item["size"], "sha256": item["sha256"],
                                   "prior_output_directory": report["output_directory"],
                                   "prior_relative_path": item["relative_path"], "prior_filelist": str(path)})
        for item in references:
            original = _safe_name(item["original_archive_path"])
            if original in SNAPSHOT_FILES:
                continue
            _safe_name(item["prior_relative_path"])
            if (type(item["size"]) is not int or item["size"] < 0
                    or not re.fullmatch(r"[0-9a-f]{64}", item["sha256"])):
                raise VerificationError(f"Invalid prior size or hash: {original}")
            previous = known.get(original)
            if previous and (previous["size"], previous["sha256"]) != (item["size"], item["sha256"]):
                raise VerificationError(f"Prior manifests disagree about immutable original: {original}")
            if not previous:
                known[original] = dict(item)
    return known, manifests


def _verify_prior(reference):
    root = Path(reference["prior_output_directory"]).resolve(strict=True)
    file = (root / reference["prior_relative_path"]).resolve(strict=True)
    if not file.is_relative_to(root) or not file.is_file():
        raise VerificationError(f"Unsafe or missing prior original: {reference['original_archive_path']}")
    with file.open("rb") as source:
        size, digest, _ = _hash_stream(source)
    if (size, digest) != (reference["size"], reference["sha256"]):
        raise VerificationError(f"Prior organized original no longer matches its manifest: {reference['original_archive_path']}")


def organize_incremental(archive_path, output_path, prior_manifests, filelist_path=None,
                         expected_archive_sha256=None, commit_path=None):
    if not prior_manifests:
        raise VerificationError("At least one prior organized filelist is required")
    known, prior_sources = _load_priors(prior_manifests)
    archive_path = Path(archive_path).resolve(strict=True)
    output_path = Path(output_path).absolute()
    output_path = output_path.parent.resolve(strict=True) / output_path.name
    filelist_path = Path(filelist_path).absolute() if filelist_path else output_path.with_name(output_path.name + ".filelist.json")
    filelist_path = filelist_path.parent.resolve(strict=True) / filelist_path.name
    conflict_path = output_path.with_name(output_path.name + ".conflicts.json")
    if filelist_path == output_path or filelist_path.is_relative_to(output_path):
        raise VerificationError("Filelist must be outside the organized output")
    for target in (output_path, filelist_path, conflict_path):
        if os.path.lexists(target):
            raise VerificationError(f"Refusing existing destination: {target}")
    snapshot = "records/snapshots/" + _safe_name(archive_path.stem)
    stage = None
    committed_stage = False
    try:
        with archive_path.open("rb") as original:
            initial = os.fstat(original.fileno())
            archive_size, archive_sha, _ = _hash_stream(original)
            if expected_archive_sha256 and archive_sha != expected_archive_sha256.lower():
                raise VerificationError("Original archive does not match expected SHA-256")
            original.seek(0)
            verification = verify_export(original)
            original.seek(0)
            with zipfile.ZipFile(original, "r") as archive:
                mapping, all_rows = _plan(archive)
                hashes = {}
                with archive.open("SHA256SUMS") as manifest:
                    for line in manifest:
                        digest, name = line.decode("utf-8").rstrip("\n").split("  ", 1)
                        hashes[name] = digest
                hashes["SHA256SUMS"] = hashlib.sha256(archive.read("SHA256SUMS")).hexdigest()
                conflicts = []
                for name in sorted(set(hashes) & known.keys()):
                    prior = known[name]
                    if (archive.getinfo(name).file_size, hashes[name]) != (prior["size"], prior["sha256"]):
                        conflicts.append({"original_archive_path": name,
                                          "prior_size": prior["size"], "prior_sha256": prior["sha256"],
                                          "new_size": archive.getinfo(name).file_size, "new_sha256": hashes[name]})
                if conflicts:
                    with conflict_path.open("xb") as report:
                        report.write(_json_bytes({"source_archive": archive_path.name,
                                                  "source_archive_sha256": archive_sha, "conflicts": conflicts}))
                    raise VerificationError(f"Changed immutable originals; no files extracted. See {conflict_path}")
                rows = [row for row in all_rows if row["original_archive_path"] not in known]
                selected = set(hashes) - known.keys()
                # Keep every sibling reference beside a new AAC, even if a shared metadata file was previously saved.
                reverse = {destination: source for source, destination in mapping.items()}
                for row in rows:
                    for column in ("audio_relative_path", "sidecar_relative_path", "opening_metadata_relative_path",
                                   "journal_delta_relative_path"):
                        if row[column]:
                            selected.add(reverse[row[column]])
                reused = [known[name] for name in sorted((set(hashes) & known.keys()) - selected)]
                missing = [known[name] for name in sorted(known.keys() - set(hashes))]
                for reference in reused:
                    _verify_prior(reference)
                selected_mapping = {name: mapping[name] for name in sorted(selected)}
                for name in SNAPSHOT_FILES & selected:
                    selected_mapping[name] = snapshot + "/" + name
                generated = {"INDEX.csv": _csv_bytes(INDEX_FIELDS, rows)}
                for date in sorted({row["audio_relative_path"].split("/")[1] for row in rows}):
                    generated[f"records/{date}/INDEX.csv"] = _csv_bytes(INDEX_FIELDS, [
                        row for row in rows if row["audio_relative_path"].split("/")[1] == date])
                organization = {
                    "schema_version": 1, "incremental": True,
                    "original_archive_name": archive_path.name, "original_archive_size_bytes": archive_size,
                    "original_archive_sha256": archive_sha,
                    "organized_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
                    "verification": verification, "segment_count": len(rows),
                    "snapshot_segment_count": len(all_rows), "reused_segment_count": len(all_rows) - len(rows),
                    "reused_original_count": len(reused), "prior_originals_not_in_snapshot_count": len(missing),
                    "snapshot_records_directory": snapshot, "prior_manifests": prior_sources,
                    "scope": "New recordings only; immutable prior originals are byte-matched and referenced without copying",
                }
                generated["ORGANIZATION.json"] = _json_bytes(organization)
                reuse_report = {"snapshot_records_directory": snapshot,
                                "reused_originals": reused, "prior_originals_not_in_snapshot": missing}
                generated["REUSED_ORIGINALS.json"] = _json_bytes(reuse_report)
                generated["README.txt"] = (
                    "Private Recorder - incremental organized copy\n\n"
                    "INDEX.csv contains only new recordings, sorted by recorded UTC start time.\n"
                    "AAC files and their unchanged sidecars/opening metadata/journal deltas are together in audio/YYYY-MM-DD/.\n"
                    "REUSED_ORIGINALS.json maps earlier matching originals to their prior output folders and hashes;\n"
                    "those originals were re-read and matched before reuse. Keep the earlier folders and ZIPs.\n"
                    f"This snapshot's original global events.jsonl, export_metadata.json and SHA256SUMS are in {snapshot}/.\n"
                    "A sidecar's journal_snapshot.source (events.jsonl) refers to that snapshot journal.\n"
                    "journal_snapshot.filename refers to the delta beside the audio. Metadata bytes have not been edited.\n"
                    "The original SHA256SUMS still uses ZIP paths; new SHA256SUMS.csv maps copied originals to current paths.\n"
                    "The entire source ZIP was verified and retained unchanged. Previously saved immutable names with changed\n"
                    "bytes cause an error; they are never silently skipped. Prior originals absent from this snapshot are listed.\n"
                    "No dates are inferred for missing start metadata. Filesystem times are copy times.\n"
                    "No listening, transcription, or content analysis was performed. Hashes prove byte consistency only.\n"
                ).encode("utf-8")
                _validate_destinations(list(selected_mapping.values()) + list(generated) + ["SHA256SUMS.csv"])
                stage = Path(tempfile.mkdtemp(prefix=".organize-", dir=output_path.parent))
                files = []
                for name, relative in selected_mapping.items():
                    destination = _target(stage, relative)
                    with archive.open(name) as source, destination.open("xb") as target:
                        shutil.copyfileobj(source, target, CHUNK)
                    with destination.open("rb") as copied:
                        size, digest, md5 = _hash_stream(copied)
                    if (size, digest) != (archive.getinfo(name).file_size, hashes[name]):
                        raise VerificationError(f"Copied bytes differ from original: {name}")
                    files.append({"relative_path": relative, "size": size, "sha256": digest, "md5": md5,
                                  "original_archive_path": name, "kind": "original"})
                for relative, data in sorted(generated.items()):
                    destination = _target(stage, relative)
                    with destination.open("xb") as target:
                        target.write(data)
                    with destination.open("rb") as copied:
                        size, digest, md5 = _hash_stream(copied)
                    if size != len(data) or digest != hashlib.sha256(data).hexdigest():
                        raise VerificationError(f"Generated file write failed: {relative}")
                    files.append({"relative_path": relative, "size": size, "sha256": digest, "md5": md5,
                                  "original_archive_path": "", "kind": "generated"})
                files.sort(key=lambda item: item["relative_path"])
                checksum = _csv_bytes(["relative_path", "size", "sha256", "md5", "original_archive_path", "kind"], files)
                destination = _target(stage, "SHA256SUMS.csv")
                with destination.open("xb") as target:
                    target.write(checksum)
                with destination.open("rb") as copied:
                    size, digest, md5 = _hash_stream(copied)
                if digest != hashlib.sha256(checksum).hexdigest():
                    raise VerificationError("Generated checksum manifest write failed")
                files.append({"relative_path": "SHA256SUMS.csv", "size": size, "sha256": digest, "md5": md5,
                              "original_archive_path": "", "kind": "generated"})
            original.seek(0)
            end_size, end_sha, _ = _hash_stream(original)
            final, current = os.fstat(original.fileno()), archive_path.stat()
            if ((end_size, end_sha) != (archive_size, archive_sha)
                    or (initial.st_size, initial.st_mtime_ns) != (final.st_size, final.st_mtime_ns)
                    or (final.st_dev, final.st_ino) != (current.st_dev, current.st_ino)):
                raise VerificationError("Original archive changed during organization")
        if os.path.lexists(output_path) or os.path.lexists(filelist_path):
            raise VerificationError("A destination appeared during organization; refusing to overwrite")
        report = {**organization, "output_directory": str(output_path), "output_file_count": len(files),
                  "output_bytes": sum(item["size"] for item in files), "file_list_path": str(filelist_path),
                  "reused_originals": reused, "prior_originals_not_in_snapshot": missing,
                  "files": sorted(files, key=lambda item: item["relative_path"])}
        if commit_path is not None:
            # Optional standalone recovery journal closes the directory/manifest publication gap.
            # Never replaces an earlier journal or changes the default organizer behavior.
            _publish_transaction_file(commit_path, {"stage_directory": str(stage), "report": report})
            committed_stage = True
        stage.rename(output_path)
        stage = None
        if commit_path is not None:
            _publish_transaction_file(filelist_path, report)
        else:
            with filelist_path.open("xb") as output:
                output.write(_json_bytes(report))
        return report
    finally:
        if not committed_stage:
            _cleanup_stage(stage)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--prior", type=Path, action="append", required=True, help="Prior organized .filelist.json; repeat as needed")
    parser.add_argument("--file-list", type=Path)
    parser.add_argument("--expected-sha256")
    args = parser.parse_args(argv)
    try:
        result = organize_incremental(args.archive, args.output, args.prior, args.file_list, args.expected_sha256)
    except (VerificationError, OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f"FAILED: {error}", file=sys.stderr)
        return 1
    print(f"ORGANIZED: {result['segment_count']} new recordings; {result['reused_segment_count']} prior recordings reused; "
          f"{result['output_file_count']} output files; {result['output_bytes']} bytes.")
    print(f"Output: {result['output_directory']}")
    print(f"Upload filelist: {result['file_list_path']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
