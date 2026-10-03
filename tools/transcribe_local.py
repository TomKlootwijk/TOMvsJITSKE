#!/usr/bin/env python3
"""Transcribe explicitly listed files locally, preserving their original language and bytes.

Example: transcribe_local.py allowed.json --model LOCAL_MODEL --output TRANSCRIPTS --language auto
Use --language nl or --language en only when an explicit language override is intended.
"""

import argparse
import dataclasses
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import re
import sys
import time
from datetime import datetime, timezone
import uuid


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_manifest(path):
    document = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    rows = document.get("files") if isinstance(document, dict) else None
    if not isinstance(rows, list) or not rows:
        raise ValueError("Manifest must contain a nonempty files list")
    checked, seen = [], set()
    for row in rows:
        source = Path(row["path"])
        digest = row["sha256"].lower()
        if not source.is_absolute() or not source.is_file():
            raise ValueError("Each path must identify an existing absolute local file")
        if source.suffix.lower() not in {".aac", ".wav", ".m4a", ".mp3", ".flac"}:
            raise ValueError("Manifest contains an unsupported audio filename")
        if not re.fullmatch(r"[a-f0-9]{64}", digest):
            raise ValueError("Each source needs its expected SHA-256")
        source = source.resolve()
        if os.path.normcase(str(source)) in seen:
            raise ValueError("Duplicate source path in manifest")
        seen.add(os.path.normcase(str(source)))
        checked.append({"path": str(source), "sha256": digest})
    return checked


def immutable_write(path, data):
    """Publish complete bytes atomically; an existing differing file is never replaced."""
    path = Path(path)
    if path.exists():
        if path.read_bytes() != data:
            raise FileExistsError(f"Existing output differs: {path.name}")
        return
    temporary = path.with_name(path.name + ".pending-" + uuid.uuid4().hex)
    try:
        with temporary.open("xb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        try:
            os.link(temporary, path)
        except FileExistsError:
            if path.read_bytes() != data:
                raise
    finally:
        temporary.unlink(missing_ok=True)


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def language_parameter(requested):
    requested = requested.strip().lower()
    if not requested:
        raise ValueError("Language must be auto or an explicit language code")
    return None if requested == "auto" else requested


def language_metadata(requested, info):
    automatic = language_parameter(requested) is None
    return {
        "requested_language": "auto" if automatic else language_parameter(requested),
        "language": info.language,
        "language_selection": "automatic_per_recording" if automatic else "explicit_command_line_setting",
        "language_probability": info.language_probability,
        "all_language_probabilities": info.all_language_probs if automatic else None,
        "language_probability_note": (
            "Model-estimated language-detection probabilities are not transcript-accuracy scores."
            if automatic else
            "Language was explicitly selected; this value is not an independent language-detection result."
        ),
    }


def timestamp(seconds):
    milliseconds = round(seconds * 1000)
    hours, milliseconds = divmod(milliseconds, 3600000)
    minutes, milliseconds = divmod(milliseconds, 60000)
    secs, milliseconds = divmod(milliseconds, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}.{milliseconds:03d}"


def diagnostic_flags(segment):
    flags = []
    if segment.get("avg_logprob", 0) < -1.0:
        flags.append("low_model_log_probability")
    if segment.get("no_speech_prob", 0) > 0.5:
        flags.append("possible_non_speech")
    if segment.get("compression_ratio", 0) > 2.4:
        flags.append("possible_repetition")
    if any(word.get("probability", 1) < 0.5 for word in segment.get("words") or []):
        flags.append("low_probability_words")
    return flags


def transcript_text(result):
    lines = ["AUTOMATIC TRANSCRIPT — NOT HUMAN VERIFIED", "",
             "Source: " + result["source"]["path"],
             "Source SHA-256: " + result["source"]["sha256"],
             "Timestamps are relative to this recording. No speaker identities are assigned.",
             "Review flags are model diagnostics, not calibrated accuracy percentages.", ""]
    for segment in result["segments"]:
        flags = segment.get("review_flags", [])
        line = f'[{timestamp(segment["start"])}–{timestamp(segment["end"])}] {segment["text"].strip()}'
        if flags:
            line += " [REVIEW: " + ", ".join(flags) + "]"
        lines.append(line)
    if not result["segments"]:
        lines.append("[No speech segments returned by the model; this does not establish that the audio contains no speech.]")
    return ("\n".join(lines) + "\n").encode("utf-8")


def process(args):
    rows = read_manifest(args.manifest)
    for row in rows:
        if sha256_file(row["path"]) != row["sha256"]:
            raise ValueError("Source hash mismatch: " + row["path"])
    if args.check_only:
        print(json.dumps({"validated_files": len(rows), "transcribed": 0}), flush=True)
        return 0

    # Explicit local model path plus offline flags prevents model/tokenizer network fallbacks.
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    from faster_whisper import WhisperModel

    model_path = Path(args.model).resolve(strict=True)
    model_files = {}
    for name in ("model.bin", "config.json", "tokenizer.json", "vocabulary.txt"):
        model_files[name] = sha256_file(model_path / name)
    selected_language = language_parameter(args.language)
    parameters = {"language": selected_language, "task": "transcribe", "beam_size": 5,
                  "temperature": 0.0, "condition_on_previous_text": False,
                  "word_timestamps": True, "vad_filter": True,
                  "vad_parameters": {"min_silence_duration_ms": 2000},
                  "initial_prompt": None, "hotwords": None}
    provenance = {"model_path": str(model_path), "model_files_sha256": model_files,
                  "device": "cpu", "compute_type": "int8", "cpu_threads": args.threads,
                  "requested_language": "auto" if selected_language is None else selected_language,
                  "packages": {name: importlib.metadata.version(name)
                               for name in ("faster-whisper", "ctranslate2", "av")},
                  "parameters": parameters}
    settings_digest = hashlib.sha256(json_bytes(provenance)).hexdigest()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    model = None
    summary = {"schema_version": 1, "manifest": str(Path(args.manifest).resolve()),
               "settings_sha256": settings_digest, "completed": [], "failed": []}
    for index, source in enumerate(rows):
        key = Path(source["path"]).stem + "_" + source["sha256"][:12]
        result_path, text_path = output / (key + ".json"), output / (key + ".txt")
        started = time.monotonic()
        try:
            if sha256_file(source["path"]) != source["sha256"]:
                raise ValueError("Source hash changed since manifest validation")
            if result_path.exists():
                result = json.loads(result_path.read_text(encoding="utf-8"))
                if (result.get("status") != "complete" or result.get("source") != source
                        or result.get("settings_sha256") != settings_digest):
                    raise FileExistsError("Existing result belongs to a different source or ASR setup")
                immutable_write(text_path, transcript_text(result))
                resumed = True
            else:
                if model is None:
                    model = WhisperModel(str(model_path), device="cpu", compute_type="int8",
                                         cpu_threads=args.threads, num_workers=1, local_files_only=True)
                segments, info = model.transcribe(source["path"], **parameters)
                values = []
                for segment in segments:
                    value = dataclasses.asdict(segment)
                    value["review_flags"] = diagnostic_flags(value)
                    values.append(value)
                if sha256_file(source["path"]) != source["sha256"]:
                    raise ValueError("Source hash changed during transcription")
                result = {"schema_version": 1, "status": "complete", "source": source,
                          "settings_sha256": settings_digest, "provenance": provenance,
                          "created_utc": datetime.now(timezone.utc).isoformat(),
                          "processing_seconds": round(time.monotonic() - started, 3),
                          "human_verified": False, "speaker_identification": "not_performed",
                          "diagnostic_note": "Model scores are not calibrated accuracy percentages. VAD may omit speech. All text and timing need review against audio.",
                          "audio_duration_seconds": info.duration,
                          "duration_after_vad_seconds": info.duration_after_vad,
                          **language_metadata(args.language, info),
                          "segments": values}
                # JSON is the durable commit; a missing text derivative can be regenerated on resume.
                immutable_write(result_path, json_bytes(result))
                immutable_write(text_path, transcript_text(result))
                resumed = False
            entry = {"source": source["path"], "result": str(result_path), "text": str(text_path),
                     "segment_count": len(result["segments"]),
                     "review_segment_count": sum(bool(s["review_flags"]) for s in result["segments"]),
                     "resumed": resumed, "elapsed_seconds": round(time.monotonic() - started, 3)}
            summary["completed"].append(entry)
            print(json.dumps({"completed": index + 1, "total": len(rows), **entry}), flush=True)
        except Exception as error:
            failure = {"source": source["path"], "error": type(error).__name__ + ": " + str(error)}
            summary["failed"].append(failure)
            print(json.dumps({"failed": True, **failure}), flush=True)
    name = "run-summary_" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ_") + uuid.uuid4().hex[:8] + ".json"
    immutable_write(output / name, json_bytes(summary))
    return 1 if summary["failed"] else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", help='JSON {"files":[{"path":"absolute path","sha256":"..."}]} in processing order')
    parser.add_argument("--model", required=True, help="Existing local faster-whisper model directory")
    parser.add_argument("--output", required=True, help="Directory for derived transcripts")
    parser.add_argument("--language", default="auto",
                        help="auto detects each recording's language (default); nl/en/etc. force a language, without requesting translation")
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--check-only", action="store_true", help="Validate listed source hashes without loading ASR")
    args = parser.parse_args()
    if args.threads < 1 or args.threads > 32:
        parser.error("threads must be between 1 and 32")
    try:
        return process(args)
    except Exception as error:
        print(type(error).__name__ + ": " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
