#!/usr/bin/env python3
"""Verify and organize a Private Recorder export without changing any original bytes."""

import argparse
import csv
import hashlib
import io
import json
import os
import shutil
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path, PurePosixPath
import zipfile

from verify_export import VerificationError, verify_export


CHUNK = 1024 * 1024
INDEX_FIELDS = [
    "start_utc", "end_utc", "start_epoch_ms", "end_epoch_ms", "duration_us",
    "duration_seconds", "status", "original_filename", "original_archive_path",
    "audio_relative_path", "sidecar_relative_path", "opening_metadata_relative_path",
    "journal_delta_relative_path", "size_bytes", "sha256", "timestamp_note",
]


def _hash_stream(stream):
    sha256, md5 = hashlib.sha256(), hashlib.md5(usedforsecurity=False)
    size = 0
    while data := stream.read(CHUNK):
        sha256.update(data)
        md5.update(data)
        size += len(data)
    return size, sha256.hexdigest(), md5.hexdigest()


def _timestamp(value):
    if type(value) is not int or value < 0:
        return ""
    try:
        instant = datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(milliseconds=value)
        return instant.isoformat(timespec="milliseconds").replace("+00:00", "Z")
    except (OverflowError, ValueError):
        return ""


def _integer(value):
    return value if type(value) is int and value >= 0 else ""


def _csv_bytes(fields, rows):
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
    writer.writeheader()
    for row in rows:
        # Metadata is untrusted; prevent formula execution when opened in a spreadsheet.
        safe = {}
        for key, value in row.items():
            if isinstance(value, str) and value.lstrip().startswith(("=", "+", "-", "@")):
                value = "'" + value
            safe[key] = value
        writer.writerow(safe)
    return stream.getvalue().encode("utf-8")


def _small_json(archive, name):
    info = archive.getinfo(name)
    if info.file_size > 4 * CHUNK:
        raise VerificationError(f"Oversized metadata: {name}")
    value = json.loads(archive.read(info).decode("utf-8"))
    if not isinstance(value, dict):
        raise VerificationError(f"Expected metadata object: {name}")
    return value


def _plan(archive):
    names = set(archive.namelist())
    mapping, rows = {}, []

    def assign(original, destination):
        if original in mapping and mapping[original] != destination:
            raise VerificationError(f"Shared metadata would require conflicting paths: {original}")
        mapping[original] = destination

    for name in sorted(n for n in names if n.endswith(".aac")):
        sidecar_name = name + ".json"
        sidecar = _small_json(archive, sidecar_name)
        start, end = (_timestamp(sidecar.get(key)) for key in ("start_epoch_ms", "end_epoch_ms"))
        date = start[:10] if start else "undated"
        folder = f"audio/{date}/"
        basename = PurePosixPath(name).name
        assign(name, folder + basename)
        assign(sidecar_name, folder + basename + ".json")
        stem = basename[:-len(".interrupted.aac")] if basename.endswith(".interrupted.aac") else basename[:-4]
        parent = str(PurePosixPath(name).parent)
        prefix = parent + "/" if parent != "." else ""
        opening_name = prefix + stem + ".open.json"
        opening_path = ""
        if opening_name in names:
            opening_path = folder + PurePosixPath(opening_name).name
            assign(opening_name, opening_path)
        delta_path = ""
        if "journal_snapshot" in sidecar:
            delta_name = prefix + sidecar["journal_snapshot"]["filename"]
            delta_path = folder + PurePosixPath(delta_name).name
            assign(delta_name, delta_path)
        duration = _integer(sidecar.get("duration_us"))
        notes = []
        if not start:
            notes.append("Start timestamp missing or invalid; date not inferred")
        if not end:
            notes.append("End timestamp missing or invalid")
        if start and end and end < start:
            notes.append("End timestamp precedes start timestamp")
        status = sidecar.get("status", "unknown")
        rows.append({
            "start_utc": start, "end_utc": end,
            "start_epoch_ms": _integer(sidecar.get("start_epoch_ms")),
            "end_epoch_ms": _integer(sidecar.get("end_epoch_ms")),
            "duration_us": duration,
            "duration_seconds": f"{duration // 1000000}.{duration % 1000000:06d}" if duration != "" else "",
            "status": status if isinstance(status, str) else "unknown",
            "original_filename": basename, "original_archive_path": name,
            "audio_relative_path": mapping[name], "sidecar_relative_path": mapping[sidecar_name],
            "opening_metadata_relative_path": opening_path,
            "journal_delta_relative_path": delta_path,
            "size_bytes": archive.getinfo(name).file_size, "sha256": sidecar["sha256"],
            "timestamp_note": "; ".join(notes),
        })
    for name in sorted(names - mapping.keys()):
        if name == "SHA256SUMS":
            destination = "records/original-export/SHA256SUMS"
        elif name in ("events.jsonl", "export_metadata.json"):
            destination = name
        else:
            destination = "records/unassigned/" + name
        assign(name, destination)
    rows.sort(key=lambda row: (row["start_utc"] or "9999", row["original_archive_path"]))
    return mapping, rows


def _validate_destinations(paths):
    files, directories = set(), set()
    for name in paths:
        path = PurePosixPath(name)
        if path.is_absolute() or any(p in (".", "..") for p in path.parts) or "\\" in name or ":" in name:
            raise VerificationError(f"Unsafe output path: {name}")
        folded = name.casefold()
        parents = {str(parent).casefold() for parent in path.parents if str(parent) != "."}
        if folded in files or folded in directories or files & parents:
            raise VerificationError(f"Output path collision: {name}")
        files.add(folded)
        directories.update(parents)


def _target(stage, relative):
    destination = stage.joinpath(*PurePosixPath(relative).parts)
    if not destination.resolve().is_relative_to(stage.resolve()):
        raise VerificationError(f"Output escapes its directory: {relative}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    return destination


def _cleanup_stage(stage):
    # Only remove the fresh temporary directory this invocation owns, never an output/archive.
    if stage is not None and stage.exists():
        resolved = stage.resolve()
        if resolved.parent == stage.parent.resolve() and resolved.name.startswith(".organize-"):
            shutil.rmtree(resolved)


def organize_export(archive_path, output_path, filelist_path=None, expected_archive_sha256=None):
    """Return a report after fully verified extraction; reject existing output/report paths.

    filelist_path defaults to a sibling <output-name>.filelist.json, outside the upload root.
    This report includes every output file, including SHA256SUMS.csv, without self-hashing.
    """
    archive_path = Path(archive_path).resolve(strict=True)
    output_path = Path(output_path).absolute()
    output_path = output_path.parent.resolve(strict=True) / output_path.name
    filelist_path = (Path(filelist_path).absolute() if filelist_path else
                     output_path.with_name(output_path.name + ".filelist.json"))
    filelist_path = filelist_path.parent.resolve(strict=True) / filelist_path.name
    for path in (output_path, filelist_path):
        if os.path.lexists(path):
            raise VerificationError(f"Refusing existing destination: {path}")
    if filelist_path == output_path or filelist_path.is_relative_to(output_path):
        raise VerificationError("File-list report must be outside the organized output")
    stage = None
    try:
        with archive_path.open("rb") as original:
            initial_stat = os.fstat(original.fileno())
            archive_size, archive_sha, _ = _hash_stream(original)
            if expected_archive_sha256 and archive_sha != expected_archive_sha256.lower():
                raise VerificationError("Original archive does not match the expected SHA-256")
            original.seek(0)
            verification = verify_export(original)
            original.seek(0)
            with zipfile.ZipFile(original, "r") as archive:
                mapping, rows = _plan(archive)
                generated = {"INDEX.csv": _csv_bytes(INDEX_FIELDS, rows)}
                dates = sorted({row["audio_relative_path"].split("/")[1] for row in rows})
                for date in dates:
                    daily = [row for row in rows if row["audio_relative_path"].split("/")[1] == date]
                    generated[f"records/{date}/INDEX.csv"] = _csv_bytes(INDEX_FIELDS, daily)
                organization = {
                    "schema_version": 1, "original_archive_name": archive_path.name,
                    "original_archive_size_bytes": archive_size, "original_archive_sha256": archive_sha,
                    "organized_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
                    "verification": verification, "segment_count": len(rows),
                    "date_basis": "Unmodified sidecar start_epoch_ms converted to UTC; missing/invalid values use undated",
                    "original_bytes_preserved": True,
                    "scope": "Byte integrity and metadata indexing only; no transcription, listening, or content analysis",
                }
                generated["ORGANIZATION.json"] = (json.dumps(organization, indent=2) + "\n").encode("utf-8")
                generated["README.txt"] = (
                    "Private Recorder - organized copy\n\n"
                    "INDEX.csv lists individual recordings in recorded UTC start order. Daily indexes are in records/.\n"
                    "audio/YYYY-MM-DD/ keeps each original AAC beside its unmodified JSON and journal delta.\n"
                    "Undated means start metadata was unavailable or invalid; no timestamp was inferred.\n"
                    "events.jsonl is the original global journal. journal_snapshot.source refers to this root file;\n"
                    "journal_snapshot.filename refers to the delta beside its audio. Opening metadata retains its name.\n"
                    "records/original-export/SHA256SUMS is the original manifest, unchanged: its paths refer to the ZIP.\n"
                    "SHA256SUMS.csv maps original ZIP paths to current paths and hashes all output files except itself.\n"
                    "Blank original_archive_path identifies newly generated navigation/report files.\n"
                    "Other original members, if any, are preserved beneath records/unassigned/.\n"
                    "The original ZIP remains separate and unchanged. ORGANIZATION.json records its SHA-256.\n"
                    "CSV formula-like metadata values are prefixed with an apostrophe for safe spreadsheet viewing;\n"
                    "the original sidecar bytes are never changed. Filesystem times are copy times, not recording times.\n"
                    "Hashes establish byte consistency, not authenticity, completeness, or timestamp accuracy.\n"
                ).encode("utf-8")
                _validate_destinations(list(mapping.values()) + list(generated) + ["SHA256SUMS.csv"])
                expected = {}
                with archive.open("SHA256SUMS") as manifest:
                    for line in manifest:
                        digest, name = line.decode("utf-8").rstrip("\n").split("  ", 1)
                        expected[name] = digest
                expected["SHA256SUMS"] = hashlib.sha256(archive.read("SHA256SUMS")).hexdigest()
                stage = Path(tempfile.mkdtemp(prefix=".organize-", dir=output_path.parent))
                files = []
                for name, relative in sorted(mapping.items()):
                    destination = _target(stage, relative)
                    with archive.open(name) as source, destination.open("xb") as target:
                        shutil.copyfileobj(source, target, CHUNK)
                    with destination.open("rb") as copied:
                        size, sha256, md5 = _hash_stream(copied)
                    if size != archive.getinfo(name).file_size or sha256 != expected[name]:
                        raise VerificationError(f"Copied bytes do not match verified original: {name}")
                    files.append({"relative_path": relative, "size": size, "sha256": sha256,
                                  "md5": md5, "original_archive_path": name, "kind": "original"})
                for relative, data in sorted(generated.items()):
                    destination = _target(stage, relative)
                    with destination.open("xb") as target:
                        target.write(data)
                    with destination.open("rb") as copied:
                        size, sha256, md5 = _hash_stream(copied)
                    if size != len(data) or sha256 != hashlib.sha256(data).hexdigest():
                        raise VerificationError(f"Generated file write failed: {relative}")
                    files.append({"relative_path": relative, "size": size, "sha256": sha256,
                                  "md5": md5, "original_archive_path": "", "kind": "generated"})
                files.sort(key=lambda item: item["relative_path"])
                manifest_data = _csv_bytes(
                    ["relative_path", "size", "sha256", "md5", "original_archive_path", "kind"], files)
                manifest_path = _target(stage, "SHA256SUMS.csv")
                with manifest_path.open("xb") as target:
                    target.write(manifest_data)
                with manifest_path.open("rb") as copied:
                    size, sha256, md5 = _hash_stream(copied)
                if sha256 != hashlib.sha256(manifest_data).hexdigest():
                    raise VerificationError("Generated checksum manifest write failed")
                files.append({"relative_path": "SHA256SUMS.csv", "size": size, "sha256": sha256,
                              "md5": md5, "original_archive_path": "", "kind": "generated"})
            original.seek(0)
            final_size, final_sha, _ = _hash_stream(original)
            final_stat = os.fstat(original.fileno())
            current_stat = archive_path.stat()
            if ((final_size, final_sha) != (archive_size, archive_sha)
                    or (initial_stat.st_size, initial_stat.st_mtime_ns) != (final_stat.st_size, final_stat.st_mtime_ns)
                    or (final_stat.st_dev, final_stat.st_ino) != (current_stat.st_dev, current_stat.st_ino)):
                raise VerificationError("Original archive changed during organization")
        if os.path.lexists(output_path) or os.path.lexists(filelist_path):
            raise VerificationError("A destination was created during organization; refusing to overwrite")
        report = {**organization, "output_directory": str(output_path), "output_file_count": len(files),
                  "output_bytes": sum(item["size"] for item in files),
                  "file_list_path": str(filelist_path), "files": sorted(files, key=lambda item: item["relative_path"])}
        # Windows rename rejects an existing destination; prior checks also reject it explicitly.
        stage.rename(output_path)
        stage = None
        with filelist_path.open("x", encoding="utf-8", newline="\n") as report_file:
            json.dump(report, report_file, indent=2)
            report_file.write("\n")
        return report
    finally:
        _cleanup_stage(stage)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path, help="New directory; existing paths are rejected")
    parser.add_argument("--file-list", type=Path, help="New JSON report outside output, for cloud upload")
    parser.add_argument("--expected-sha256", help="Previously verified original archive SHA-256")
    args = parser.parse_args(argv)
    try:
        report = organize_export(args.archive, args.output, args.file_list, args.expected_sha256)
    except (VerificationError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"FAILED: {error}", file=sys.stderr)
        return 1
    print(f"ORGANIZED: {report['segment_count']} recordings; {report['output_file_count']} files; "
          f"{report['output_bytes']} bytes. Original ZIP unchanged.")
    print(f"Output: {report['output_directory']}")
    print(f"Upload file list: {report['file_list_path']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
