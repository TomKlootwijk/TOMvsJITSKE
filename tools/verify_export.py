#!/usr/bin/env python3
"""Read-only verifier for Private Recorder ZIP exports; Python standard library only."""

import argparse
import hashlib
import json
import re
import stat
import sys
import zipfile
from pathlib import Path


class VerificationError(ValueError):
    """The export is malformed, incomplete, or does not match its stored hashes."""


def _safe_name(name):
    if not name or "\\" in name or ":" in name or name.startswith("/"):
        raise VerificationError(f"Unsafe archive path: {name!r}")
    for part in name.split("/"):
        reserved = {"CON", "PRN", "AUX", "NUL"} | {
            f"{prefix}{number}" for prefix in ("COM", "LPT") for number in range(1, 10)
        }
        if (not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,239}", part)
                or ".." in part or part.endswith(".") or part.split(".", 1)[0].upper() in reserved):
            raise VerificationError(f"Unsafe archive path: {name!r}")
    return name


def _read_small(archive, info, limit=4 * 1024 * 1024):
    if info.file_size > limit:
        raise VerificationError(f"Metadata is too large: {info.filename}")
    with archive.open(info) as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise VerificationError(f"Metadata is too large: {info.filename}")
    return data


def _json_object(data, name):
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationError(f"Invalid JSON in {name}: {error}") from error
    if not isinstance(value, dict):
        raise VerificationError(f"Expected a JSON object in {name}")
    return value


def _nonnegative_integer(value):
    return type(value) is int and value >= 0


def _verify_optional_references(sidecar, audio_name, entries, expected, journal_ranges):
    """Check new optional fields without requiring them in legacy version-1 exports."""
    parent, _, basename = audio_name.rpartition("/")
    prefix = parent + "/" if parent else ""
    if "journal_snapshot" in sidecar:
        snapshot = sidecar["journal_snapshot"]
        if not isinstance(snapshot, dict):
            raise VerificationError(f"Invalid journal snapshot metadata: {audio_name}")
        filename = snapshot.get("filename")
        if (not isinstance(filename, str) or "/" in filename
                or not filename.endswith(".jsonl") or filename == "events.jsonl"):
            raise VerificationError(f"Invalid journal snapshot filename: {audio_name}")
        _safe_name(filename)
        target = prefix + filename
        start, end, size = (snapshot.get(key) for key in ("start_byte", "end_byte", "size_bytes"))
        if (snapshot.get("source") != "events.jsonl"
                or not all(_nonnegative_integer(value) for value in (start, end, size))
                or end < start or end - start != size):
            raise VerificationError(f"Invalid journal snapshot range: {audio_name}")
        if target not in entries:
            raise VerificationError(f"Missing journal snapshot: {target}")
        if (entries[target].file_size != size or snapshot.get("sha256") != expected[target]):
            raise VerificationError(f"Journal snapshot metadata does not match bytes: {target}")
        if "events.jsonl" not in entries or end > entries["events.jsonl"].file_size:
            raise VerificationError(f"Journal snapshot range exceeds exported journal: {target}")
        journal_ranges.append((start, end, expected[target], target))

    opening_keys = ("opening_metadata_sha256", "opening_metadata_size_bytes")
    if any(key in sidecar for key in opening_keys):
        stem = basename[:-len(".interrupted.aac")] if basename.endswith(".interrupted.aac") else basename[:-len(".aac")]
        target = prefix + stem + ".open.json"
        if target not in entries:
            raise VerificationError(f"Missing referenced opening metadata: {target}")
        size = sidecar.get("opening_metadata_size_bytes")
        if (not _nonnegative_integer(size) or entries[target].file_size != size
                or sidecar.get("opening_metadata_sha256") != expected[target]):
            raise VerificationError(f"Opening metadata reference does not match bytes: {target}")
        return 1
    return 0


def _verify_journal_ranges(archive, entries, ranges):
    """Compare every delta to its exact source range, reading the full journal at most once.

    Overlapping ranges are valid after interrupted cursor commits. Maintain a digest per
    distinct active range so overlap never causes repeated seeking through a large ZIP member.
    """
    if not ranges:
        return
    starts, ends = {}, {}
    references = {}
    for start, end, digest, target in ranges:
        key = (start, end)
        if key not in references:
            starts.setdefault(start, []).append(key)
            ends.setdefault(end, []).append(key)
            references[key] = []
        references[key].append((digest, target))
    active = {}
    position = 0
    with archive.open(entries["events.jsonl"]) as source:
        for boundary in sorted(starts.keys() | ends.keys()):
            while position < boundary:
                chunk = source.read(min(1024 * 1024, boundary - position))
                if not chunk:
                    raise VerificationError("Exported journal ended before a referenced range")
                for digest in active.values():
                    digest.update(chunk)
                position += len(chunk)
            for key in starts.get(boundary, ()):
                active[key] = hashlib.sha256()
            for key in ends.get(boundary, ()):
                actual = active.pop(key).hexdigest()
                for expected, target in references[key]:
                    if actual != expected:
                        raise VerificationError(f"Journal snapshot does not match its source range: {target}")


def verify_export(path):
    """Verify manifest coverage, safe paths, payloads and all declared sidecar references.

    Returns a compact report; never extracts, changes, or deletes anything.
    Matching hashes are not proof of authenticity, completeness, or admissibility.
    """
    try:
        with zipfile.ZipFile(path, "r") as archive:
            entries = {}
            folded_names = set()
            for info in archive.infolist():
                # ZipInfo can normalize backslashes on Windows or truncate at a NUL.
                # Validate the original central-directory spelling before that happens.
                name = _safe_name(info.orig_filename)
                if name != info.filename:
                    raise VerificationError(f"Unsafe normalized archive path: {name!r}")
                if name in entries or name.casefold() in folded_names:
                    raise VerificationError(f"Duplicate or case-colliding ZIP entry: {name}")
                if info.is_dir():
                    raise VerificationError(f"Unexpected directory entry: {name}")
                mode = info.external_attr >> 16
                if stat.S_ISLNK(mode):
                    raise VerificationError(f"Symbolic links are not allowed: {name}")
                if info.flag_bits & 1:
                    raise VerificationError(f"Encrypted ZIP entry is unsupported: {name}")
                entries[name] = info
                folded_names.add(name.casefold())
            if "SHA256SUMS" not in entries or "export_metadata.json" not in entries:
                raise VerificationError("Missing SHA256SUMS or export_metadata.json")

            expected = {}
            # Stream the manifest, including large exports, with a bound per line.
            with archive.open(entries["SHA256SUMS"]) as manifest:
                while True:
                    raw = manifest.readline(8193)
                    if not raw:
                        break
                    if len(raw) > 8192 or not raw.endswith(b"\n"):
                        raise VerificationError("Oversized or unterminated SHA256SUMS line")
                    try:
                        line = raw[:-1].decode("utf-8")
                    except UnicodeDecodeError as error:
                        raise VerificationError("SHA256SUMS is not UTF-8") from error
                    match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
                    if not match:
                        raise VerificationError("Malformed SHA256SUMS line")
                    digest, name = match.groups()
                    _safe_name(name)
                    if name == "SHA256SUMS" or name in expected:
                        raise VerificationError(f"Duplicate or self-referential manifest entry: {name}")
                    expected[name] = digest
            payload_names = set(entries) - {"SHA256SUMS"}
            if set(expected) != payload_names:
                missing = sorted(payload_names - set(expected))
                absent = sorted(set(expected) - payload_names)
                raise VerificationError(f"Manifest coverage mismatch; unhashed={missing!r}, absent={absent!r}")

            total_bytes = 0
            for name, expected_digest in expected.items():
                digest = hashlib.sha256()
                size = 0
                with archive.open(entries[name]) as stream:
                    while True:
                        data = stream.read(1024 * 1024)
                        if not data:
                            break
                        digest.update(data)
                        size += len(data)
                if size != entries[name].file_size:
                    raise VerificationError(f"ZIP size mismatch: {name}")
                if digest.hexdigest() != expected_digest:
                    raise VerificationError(f"SHA-256 mismatch: {name}")
                total_bytes += size

            metadata = _json_object(_read_small(archive, entries["export_metadata.json"]), "export_metadata.json")
            if metadata.get("schema_version") != 1:
                raise VerificationError("Unsupported export schema version")
            segments = 0
            opening_metadata = 0
            journal_ranges = []
            for name in payload_names:
                if name.endswith(".partial"):
                    raise VerificationError(f"Unfinished recording unexpectedly included: {name}")
                if not name.endswith(".aac"):
                    continue
                segments += 1
                sidecar_name = name + ".json"
                if sidecar_name not in entries:
                    raise VerificationError(f"Missing recording sidecar: {sidecar_name}")
                sidecar = _json_object(_read_small(archive, entries[sidecar_name]), sidecar_name)
                if (sidecar.get("filename") != name.rsplit("/", 1)[-1]
                        or sidecar.get("size_bytes") != entries[name].file_size
                        or sidecar.get("sha256") != expected[name]):
                    raise VerificationError(f"Recording sidecar does not match audio: {sidecar_name}")
                opening_metadata += _verify_optional_references(sidecar, name, entries, expected, journal_ranges)
            if metadata.get("finalized_segments") != segments:
                raise VerificationError("Export segment count does not match archive")
            _verify_journal_ranges(archive, entries, journal_ranges)
            return {"files_verified": len(expected), "bytes_verified": total_bytes,
                    "segments_verified": segments,
                    "journal_snapshots_verified": len(journal_ranges),
                    "opening_metadata_verified": opening_metadata,
                    "excluded_partial_segments": metadata.get("excluded_partial_segments", 0),
                    "excluded_incomplete_finalizations": metadata.get("excluded_incomplete_finalizations", 0)}
    except VerificationError:
        raise
    except (OSError, EOFError, RuntimeError, ValueError, zipfile.BadZipFile) as error:
        raise VerificationError(f"Cannot read export: {error}") from error


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, help="ZIP exported by Private Recorder")
    args = parser.parse_args(argv)
    try:
        report = verify_export(args.archive)
    except VerificationError as error:
        print(f"FAILED: {error}", file=sys.stderr)
        return 1
    print(f"VERIFIED: {report['files_verified']} files, {report['segments_verified']} recordings, "
          f"{report['bytes_verified']} bytes match the archive's hashes.")
    print(f"Excluded at export: {report['excluded_partial_segments']} partial recordings; "
          f"{report['excluded_incomplete_finalizations']} incomplete finalizations.")
    print("This verifies byte integrity only, not authenticity, completeness, timestamps, or legal admissibility.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
