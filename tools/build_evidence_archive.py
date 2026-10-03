"""Create and read back a ZIP from an explicit, hash-pinned list of local files."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
from datetime import datetime, timezone
import zipfile


def digest_file(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    spec = json.loads(args.manifest.read_text(encoding="utf-8-sig"))
    files = spec["files"]
    if not files:
        raise ValueError("Explicit file list is empty")
    seen = set()
    for item in files:
        name = item["archive_path"]
        parts = PurePosixPath(name)
        if (parts.is_absolute() or ".." in parts.parts or ":" in name
                or "\\" in name or str(parts) != name or name in seen
                or name in {"PACKAGE_MANIFEST.json", "SHA256SUMS"}):
            raise ValueError("Unsafe or duplicate archive path: " + name)
        seen.add(name)
        path = Path(item["path"])
        if not path.is_absolute() or not path.is_file():
            raise ValueError("Source must be an existing absolute file")
        if path.stat().st_size != item["size"] or digest_file(path) != item["sha256"]:
            raise ValueError("Source mismatch: " + name)
    if args.output.exists() or args.report.exists():
        raise FileExistsError("Archive or verification report already exists")
    partial = args.output.with_name(args.output.name + ".partial")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    package = {key: value for key, value in spec.items() if key != "files"}
    package["files"] = [{key: value for key, value in item.items() if key != "path"}
                        for item in files]
    package["hash_note"] = "Hashes detect byte changes; original audio and source metadata are retained unchanged."
    package_bytes = (json.dumps(package, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    expected = {item["archive_path"]: (item["size"], item["sha256"]) for item in files}
    expected["PACKAGE_MANIFEST.json"] = (len(package_bytes), hashlib.sha256(package_bytes).hexdigest())
    sums = "".join(sha + "  " + name + "\n" for name, (_, sha) in sorted(expected.items())).encode("utf-8")
    expected["SHA256SUMS"] = (len(sums), hashlib.sha256(sums).hexdigest())
    with zipfile.ZipFile(partial, "x", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        for item in files:
            archive.write(item["path"], item["archive_path"])
        archive.writestr("PACKAGE_MANIFEST.json", package_bytes)
        archive.writestr("SHA256SUMS", sums)
    checked = []
    with zipfile.ZipFile(partial) as archive:
        if len(archive.namelist()) != len(expected) or set(archive.namelist()) != set(expected):
            raise ValueError("ZIP coverage mismatch")
        for name, (size, sha) in sorted(expected.items()):
            with archive.open(name) as stream:
                actual = hashlib.file_digest(stream, "sha256").hexdigest()
            if archive.getinfo(name).file_size != size or actual != sha:
                raise ValueError("ZIP readback mismatch: " + name)
            checked.append({"archive_path": name, "size": size, "sha256": sha, "verified": True})
    for item in files:
        if digest_file(item["path"]) != item["sha256"]:
            raise ValueError("Source changed during packaging: " + item["archive_path"])
    os.link(partial, args.output)  # Exclusive publication; never replace an existing archive.
    partial.unlink()
    report = {"status": "all_archive_members_sha256_verified", "verified_at_utc": datetime.now(timezone.utc).isoformat(),
              "archive": str(args.output.resolve()), "archive_bytes": args.output.stat().st_size,
              "archive_sha256": digest_file(args.output), "input_manifest_sha256": digest_file(args.manifest),
              "file_count": len(checked), "original_sources_rehashed_after_packaging": len(files), "files": checked}
    with args.report.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({key: value for key, value in report.items() if key != "files"}))


if __name__ == "__main__":
    main()
