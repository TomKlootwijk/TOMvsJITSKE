#!/usr/bin/env python3
"""Build a derived transcript PDF from explicitly listed ASR JSON and INDEX rows.

This tool never opens audio. Run the PDF skill's artifact marker before authoring,
then render and visually inspect the resulting PDF before delivery.

Manifest schema: {"schema_version":1,"title":"Recording transcripts",
"created_utc":"2026-09-27T12:00:00Z","search_terms":[],"recordings":[
{"asr_json":"absolute result.json","index_csv":"absolute INDEX.csv",
"original_archive_path":"recordings/source.aac","plot_point":true}]}
Optional per-recording asr_sha256 pins the exact result JSON. No audio paths are
opened; source identity is matched between the ASR result and its INDEX row.
Optional category and summary strings are supplied by the caller; this builder
does not infer categories, speaker identities, or conclusions from transcript text.
Optional designation text and priority boolean support a separate user-selected
key point without setting plot_point. Priority records precede chronological others.
For an unnamed marked item use user_marked:true, user_name:null, designation:"",
priority:true. It gets a descriptive heading, never an invented nickname.
"""

import argparse
from datetime import datetime, timedelta, timezone
from functools import partial
import hashlib
from io import BytesIO
import csv
import json
import math
import os
from pathlib import Path, PureWindowsPath
import re
import sys
import uuid
from xml.sax.saxutils import escape
from zoneinfo import ZoneInfo


HASH = re.compile(r"[0-9a-f]{64}")
AMSTERDAM = ZoneInfo("Europe/Amsterdam")
EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


def read_bytes(path):
    path = Path(path)
    if not path.is_absolute() or not path.is_file():
        raise ValueError("Input paths must name existing absolute local files")
    return path.resolve(), path.read_bytes()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require_hash(value, label):
    if not isinstance(value, str) or not HASH.fullmatch(value.lower()):
        raise ValueError("Invalid SHA-256: " + label)
    return value.lower()


def nonnegative_number(value, label):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
        raise ValueError("Invalid nonnegative number: " + label)
    return value


def integer_cell(value, label, required=True):
    if value == "" and not required:
        return None
    if not isinstance(value, str) or not re.fullmatch(r"\d+", value):
        raise ValueError("Missing or invalid INDEX field: " + label)
    return int(value)


def instant(epoch_ms):
    return EPOCH + timedelta(milliseconds=epoch_ms)


def utc_text(value):
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def amsterdam_text(value):
    local = value.astimezone(AMSTERDAM)
    return local.isoformat(timespec="milliseconds") + " (Europe/Amsterdam)"


def offset_text(seconds):
    millis = round(seconds * 1000)
    hours, millis = divmod(millis, 3_600_000)
    minutes, millis = divmod(millis, 60_000)
    seconds, millis = divmod(millis, 1000)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d}.{millis:03d}"


def visible_text(value):
    value = str(value)
    if any(ord(c) < 32 and c not in "\n\r\t" for c in value):
        raise ValueError("Text contains an unsupported control character")
    return value.replace("\r\n", "\n").replace("\r", "\n").replace("\t", "    ")


def markup(value):
    return escape(visible_text(value)).replace("\n", "<br/>")


def language_description(result):
    metadata = result.get("language_detection")
    if not isinstance(metadata, dict):
        metadata = result
    language = metadata.get("language", result.get("language", "unspecified"))
    selection = metadata.get("language_selection", result.get("language_selection", "unspecified"))
    requested = metadata.get("requested_language", result.get("requested_language", "unspecified"))
    note = metadata.get("language_probability_note", result.get("language_probability_note", ""))
    if not note:
        note = ("Model language estimates are not transcript-accuracy scores."
                if selection == "automatic_per_recording" else
                "This language value is not an independent verification of the spoken language.")
    return f"ASR language: {language} | Selection: {selection} | Requested mode: {requested}\n{note}"


def load_manifest(path):
    """Validate metadata references and source identity without accessing audio."""
    manifest_path, raw = read_bytes(path)
    document = json.loads(raw.decode("utf-8-sig"))
    if document.get("schema_version") != 1:
        raise ValueError("Manifest schema_version must be 1")
    created = datetime.fromisoformat(document["created_utc"].replace("Z", "+00:00"))
    if created.tzinfo is None:
        raise ValueError("created_utc must include a UTC offset")
    title = document.get("title", "Recording transcripts")
    if not isinstance(title, str) or not title.strip():
        raise ValueError("title must be nonempty text")
    requested = document.get("recordings")
    if not isinstance(requested, list) or not requested:
        raise ValueError("Manifest must explicitly list recordings")
    terms = document.get("search_terms", [])
    if not isinstance(terms, list) or any(not isinstance(term, str) or not term.strip() for term in terms):
        raise ValueError("search_terms must be a list of nonempty literal phrases")
    records, seen = [], set()
    for ordinal, entry in enumerate(requested):
        result_path, result_bytes = read_bytes(entry["asr_json"])
        result = json.loads(result_bytes.decode("utf-8-sig"))
        if result.get("status") != "complete" or result.get("human_verified") is not False:
            raise ValueError("Expected a complete, explicitly not-human-verified ASR result")
        if result.get("speaker_identification") != "not_performed":
            raise ValueError("This builder expects ASR without speaker identification")
        if entry.get("asr_sha256") and require_hash(entry["asr_sha256"], "ASR JSON") != digest(result_bytes):
            raise ValueError("ASR JSON does not match declared hash")
        index_path, index_bytes = read_bytes(entry["index_csv"])
        archive_path = entry["original_archive_path"]
        rows = [row for row in csv.DictReader(index_bytes.decode("utf-8-sig").splitlines())
                if row.get("original_archive_path") == archive_path]
        if len(rows) != 1:
            raise ValueError("Exactly one matching original_archive_path is required in INDEX.csv")
        row = rows[0]
        source = result["source"]
        source_hash = require_hash(source["sha256"], "ASR source")
        if require_hash(row["sha256"], "INDEX audio") != source_hash:
            raise ValueError("ASR source hash disagrees with INDEX.csv")
        if PureWindowsPath(source["path"]).name != row["original_filename"]:
            raise ValueError("ASR source basename disagrees with INDEX.csv")
        key = (archive_path, source_hash)
        if key in seen:
            raise ValueError("Duplicate recording in explicit manifest")
        seen.add(key)
        start = integer_cell(row["start_epoch_ms"], "start_epoch_ms")
        end = integer_cell(row["end_epoch_ms"], "end_epoch_ms", required=False)
        duration = integer_cell(row["duration_us"], "duration_us", required=False)
        instant(start)  # Reject timestamps outside datetime's supported range.
        if end is not None:
            instant(end)
        segments = result.get("segments")
        if not isinstance(segments, list):
            raise ValueError("ASR result must contain a segments list")
        for number, segment in enumerate(segments, 1):
            low = nonnegative_number(segment["start"], f"segment {number} start")
            high = nonnegative_number(segment["end"], f"segment {number} end")
            if high < low or not isinstance(segment["text"], str):
                raise ValueError("Invalid ASR segment times or text")
            flags = segment.get("review_flags", [])
            if not isinstance(flags, list) or any(not isinstance(flag, str) for flag in flags):
                raise ValueError("Segment review_flags must be a text list")
            visible_text(segment["text"])
        plot = entry.get("plot_point", False)
        if type(plot) is not bool:
            raise ValueError("plot_point must be a boolean")
        designation = entry.get("designation", "PLOT POINT" if plot else "")
        priority = entry.get("priority", False)
        if not isinstance(designation, str) or type(priority) is not bool:
            raise ValueError("designation must be text and priority must be a boolean")
        if plot and designation != "PLOT POINT":
            raise ValueError("A different designation must not set plot_point")
        user_marked = entry.get("user_marked", bool(designation))
        user_name = entry.get("user_name", designation or None)
        if type(user_marked) is not bool:
            raise ValueError("user_marked must be a boolean")
        if user_name is not None and (not isinstance(user_name, str) or not user_name.strip()):
            raise ValueError("user_name must be nonempty text or null")
        if "user_name" in entry and user_name != (designation or None):
            raise ValueError("Explicit user_name must match designation; unnamed entries must leave designation empty")
        if user_name is not None and not user_marked:
            raise ValueError("A user name requires user_marked")
        visible_text(designation)
        category, summary = entry.get("category", "Uncategorized"), entry.get("summary", "")
        comparison = entry.get("model_comparison_note", "")
        if not isinstance(category, str) or not category.strip() or not isinstance(summary, str):
            raise ValueError("category must be nonempty text; summary must be text")
        if not isinstance(comparison, str):
            raise ValueError("model_comparison_note must be text")
        visible_text(category)
        visible_text(summary)
        visible_text(comparison)
        records.append({"row": row, "asr": result, "asr_path": str(result_path),
                        "asr_sha256": digest(result_bytes), "index_path": str(index_path),
                        "index_sha256": digest(index_bytes), "start": start, "end": end,
                        "duration_us": duration, "plot_point": plot, "input_order": ordinal,
                        "category": category, "summary": summary, "model_comparison_note": comparison,
                        "designation": designation, "priority": priority,
                        "user_marked": user_marked, "user_name": user_name})
    records.sort(key=lambda r: (not (r["plot_point"] or r["priority"]), r["start"], r["input_order"]))
    return {"title": title, "created_utc": utc_text(created), "search_terms": terms,
            "manifest_path": str(manifest_path), "manifest_sha256": digest(raw), "recordings": records}


def select_fonts(regular=None, bold=None):
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    if bool(regular) != bool(bold):
        raise ValueError("Supply both --font and --font-bold")
    candidates = [(regular, bold)] if regular else [
        ("C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/arialbd.ttf"),
        ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
    ]
    for plain, strong in candidates:
        if Path(plain).is_file() and Path(strong).is_file():
            pdfmetrics.registerFont(TTFont("Transcript", plain))
            pdfmetrics.registerFont(TTFont("TranscriptBold", strong))
            pdfmetrics.registerFontFamily("Transcript", normal="Transcript", bold="TranscriptBold")
            return
    raise ValueError("No embedded Unicode font pair found; pass --font and --font-bold")


def build_bytes(document, regular=None, bold=None):
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfgen.canvas import Canvas
    from reportlab.platypus import LongTable, PageBreak, Paragraph, SimpleDocTemplate, TableStyle

    select_fonts(regular, bold)
    body = ParagraphStyle("Body", fontName="Transcript", fontSize=10, leading=14.5,
                          textColor=colors.HexColor("#172938"), spaceAfter=7,
                          splitLongWords=True, allowWidows=0, allowOrphans=0)
    small = ParagraphStyle("Small", parent=body, fontSize=8, leading=11, spaceAfter=5)
    cell = ParagraphStyle("Cell", parent=small, spaceAfter=0, allowWidows=1, allowOrphans=1)
    heading = ParagraphStyle("Heading", parent=body, fontName="TranscriptBold", fontSize=17,
                             leading=22, spaceBefore=4, spaceAfter=12, keepWithNext=True)
    subheading = ParagraphStyle("Subheading", parent=body, fontName="TranscriptBold", fontSize=11,
                                leading=15, spaceBefore=9, spaceAfter=6, keepWithNext=True)
    timing = ParagraphStyle("Timing", parent=small, fontName="TranscriptBold", keepWithNext=True,
                            textColor=colors.HexColor("#15536A"), spaceBefore=8)
    flag = ParagraphStyle("Flag", parent=small, textColor=colors.HexColor("#754009"))
    story = []
    review_codes = {"low_model_log_probability": "L", "possible_non_speech": "NS",
                    "possible_repetition": "REP", "low_probability_words": "W"}

    def paragraph(text, style=body):
        text = visible_text(text)
        supported = pdfmetrics.getFont(style.fontName).face.charToGlyph
        missing = sorted({ord(c) for c in text if c not in "\n\r\t" and ord(c) not in supported})
        if missing:
            raise ValueError("Selected font cannot render " + ", ".join(f"U+{code:04X}" for code in missing[:12])
                             + "; supply a Unicode font pair covering these characters")
        return Paragraph(markup(text), style)

    def add(text, style=body):
        story.append(paragraph(text, style))

    def duration(record):
        return "unavailable" if record["duration_us"] is None else offset_text(record["duration_us"] / 1_000_000)

    def model_key(result):
        return json.dumps({"settings_sha256": result.get("settings_sha256"),
                           "provenance": result.get("provenance", {})}, sort_keys=True, ensure_ascii=False)

    shared_models, shared_indexes = {}, {}
    for record in document["recordings"]:
        key = model_key(record["asr"])
        shared_models.setdefault(key, "ASR-" + str(len(shared_models) + 1))
        index_key = (record["index_path"], record["index_sha256"])
        shared_indexes.setdefault(index_key, "INDEX-" + str(len(shared_indexes) + 1))

    add(document["title"], heading)
    add("AUTOMATIC TRANSCRIPT - NOT HUMAN VERIFIED", subheading)
    add("All text and timing require review against the source audio. No speaker identities are assigned. "
        "Model review flags are diagnostics, not calibrated accuracy percentages. Voice activity detection may omit speech.")
    designations = sorted({record.get("designation", "PLOT POINT" if record["plot_point"] else "")
                           for record in document["recordings"]} - {""})
    unnamed_marked = sum(record.get("user_marked", False) and record.get("user_name") is None
                         for record in document["recordings"])
    if designations:
        add("User-supplied designation(s): " + ", ".join(designations) + ". These labels are not statements found in the audio "
            "or verified keyword matches.")
    if unnamed_marked:
        add(f"{unnamed_marked} recording(s) were marked by the user without a name. "
            "The descriptive heading 'User-marked recording' is not a nickname or a finding about the audio.")
    if any(record["plot_point"] or record.get("priority", False) for record in document["recordings"]):
        add("Priority recordings appear first in recorded UTC start order; others follow chronologically. "
            "ASR segment order is preserved.")
    else:
        add("Recordings appear in recorded UTC start order. ASR segment order is preserved.")
    add("Prepared: " + document["created_utc"] + " | Recordings: " + str(len(document["recordings"])), small)
    add("Read the timestamps as hours:minutes:seconds.milliseconds from the start of each recording. "
        "Each recording header gives its Amsterdam and UTC start. Recording-clock and ASR timing are estimates.", small)
    add("Categories and summaries are supplied reading aids based on unverified material; they are not verified findings. "
        "Original file identities and technical provenance are retained in the appendix.", small)
    add("Review key: L = low model log probability; NS = possible non-speech; REP = possible repetition; "
        "W = low-probability words. Any other flag retains its original name.", small)
    add("Recording catalog", subheading)
    catalog = [[paragraph(value, cell) for value in ("Recording", "Start in Amsterdam", "Duration", "Category")]]
    for number, record in enumerate(document["recordings"], 1):
        designation = record.get("designation", "PLOT POINT" if record["plot_point"] else "")
        mark = designation or ("User-marked\nNo name" if record.get("user_marked", False) else "")
        label = f"{number:03d}" + ("\n" + mark if mark else "")
        local = instant(record["start"]).astimezone(AMSTERDAM)
        start_label = local.strftime("%Y-%m-%d\n%H:%M:%S ") + local.isoformat()[-6:]
        catalog.append([paragraph(value, cell) for value in
                        (label, start_label, duration(record), record.get("category", "Uncategorized"))])
    table = LongTable(catalog, colWidths=[90, 135, 76, A4[0] - 88 - 301], repeatRows=1,
                      hAlign="LEFT", splitByRow=1, splitInRow=1)
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#E6EEF2")),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F5F8FA")]),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 7), ("RIGHTPADDING", (0, 0), (-1, -1), 7),
        ("TOPPADDING", (0, 0), (-1, -1), 7), ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
        ("LINEBELOW", (0, 0), (-1, 0), 0.6, colors.HexColor("#B5C7D0")),
    ]))
    story.append(table)

    for number, record in enumerate(document["recordings"], 1):
        story.append(PageBreak())
        row, result = record["row"], record["asr"]
        designation = record.get("designation", "PLOT POINT" if record["plot_point"] else "")
        mark = designation or ("User-marked recording" if record.get("user_marked", False) else "Recording")
        add(f"{number:03d} / " + mark, heading)
        if designation:
            add("User designation: " + designation + ". This label is independent of ASR text matches.", flag)
        elif record.get("user_marked", False):
            add("Marked as special by the user; no name supplied. This description does not imply any verified fact about the audio.", flag)
        start = instant(record["start"])
        add("Amsterdam start: " + amsterdam_text(start) + "\nUTC start: " + utc_text(start)
            + " | Duration: " + duration(record) + " | Status: " + row["status"], small)
        add("Source: " + row["original_filename"] + "\nSHA-256: " + result["source"]["sha256"], small)
        add("Category: " + record.get("category", "Uncategorized"), subheading)
        if record.get("summary"):
            add("Reading summary (unverified): " + record["summary"])
        if record.get("model_comparison_note"):
            add("Model comparison (unverified): " + record["model_comparison_note"], flag)
        if row.get("timestamp_note"):
            add("Metadata timing note: " + row["timestamp_note"], flag)
        segments = result["segments"]
        if document["search_terms"]:
            add("Literal matches in unverified ASR text", subheading)
            add("These searches examine model text only. They do not verify that a word was spoken.", small)
            for term in document["search_terms"]:
                folded = " ".join(term.split()).casefold()
                matches = [str(i) for i, segment in enumerate(segments, 1)
                           if folded in " ".join(segment["text"].split()).casefold()]
                add("User-supplied search term: " + term + " | Case-insensitive phrase substring matches: "
                    + ("segments " + ", ".join(matches) if matches else "none in returned ASR text"), small)
        add("Transcript", subheading)
        add(f"{len(segments)} returned segments; {sum(bool(s.get('review_flags')) for s in segments)} with review flags. "
            "Every returned segment is included below.", small)
        if not segments:
            add("No speech segments returned by the model. This does not establish that the recording contains no speech.", flag)
        for segment_number, segment in enumerate(segments, 1):
            caption = f"{segment_number:04d} | {offset_text(segment['start'])} - {offset_text(segment['end'])}"
            if segment.get("review_flags"):
                caption += " | Review: " + ", ".join(review_codes.get(value, value) for value in segment["review_flags"])
            add(caption, timing)
            add(segment["text"] if segment["text"] else "[Empty text returned for this segment.]")
    story.append(PageBreak())
    add("Appendix / Sources and provenance", heading)
    add("This appendix preserves the technical links behind the reading copy. SHA-256 hashes establish byte "
        "consistency, not the truth of statements, speaker identity, or exact real-world capture times.")
    add("Input manifest: " + document["manifest_path"] + "\nSHA-256: " + document["manifest_sha256"], small)
    add("Shared ASR configurations", subheading)
    for key, label in shared_models.items():
        setup = json.loads(key)
        provenance = setup["provenance"]
        add(label, subheading)
        add("Model: " + str(provenance.get("model_path", "unspecified"))
            + "\nSettings SHA-256: " + str(setup.get("settings_sha256", "unspecified")), small)
        add("Runtime: " + json.dumps({k: v for k, v in provenance.items()
                                       if k not in {"model_path", "model_files_sha256", "parameters", "packages"}},
                                      ensure_ascii=False, sort_keys=True)
            + "\nPackages: " + json.dumps(provenance.get("packages", {}), ensure_ascii=False, sort_keys=True)
            + "\nParameters: " + json.dumps(provenance.get("parameters", {}), ensure_ascii=False, sort_keys=True), small)
        for name, value in sorted(provenance.get("model_files_sha256", {}).items()):
            add("Model file " + name + " | SHA-256: " + str(value), small)
    add("Source indexes", subheading)
    for (path, value), label in shared_indexes.items():
        add(label + ": " + path + "\nSHA-256: " + value, small)
    add("Per-recording references", subheading)
    for number, record in enumerate(document["recordings"], 1):
        row, result = record["row"], record["asr"]
        add(f"{number:03d} / " + row["original_filename"], subheading)
        add("Source path: " + result["source"]["path"]
            + "\nOriginal archive member: " + row["original_archive_path"]
            + "\nSource SHA-256: " + result["source"]["sha256"], small)
        add("ASR result: " + record["asr_path"] + "\nASR JSON SHA-256: " + record["asr_sha256"]
            + "\nConfiguration: " + shared_models[model_key(result)]
            + " | Source index: " + shared_indexes[(record["index_path"], record["index_sha256"])], small)
        add("ASR created: " + str(result.get("created_utc", "unspecified")) + "\n" + language_description(result), small)
        if record["end"] is not None:
            add("Recorded end UTC: " + utc_text(instant(record["end"]))
                + " | Amsterdam: " + amsterdam_text(instant(record["end"])), small)

    output = BytesIO()
    doc = SimpleDocTemplate(output, pagesize=A4, leftMargin=44, rightMargin=44,
                            topMargin=48, bottomMargin=45, title=document["title"],
                            author="Private Recorder transcript derivative", pageCompression=1)

    def page(canvas, document_template):
        canvas.saveState()
        canvas.setFont("Transcript", 7)
        canvas.setFillColor(colors.HexColor("#596977"))
        canvas.drawString(44, 25, "AUTOMATIC TRANSCRIPT - NOT HUMAN VERIFIED")
        canvas.drawRightString(A4[0] - 44, 25, "Page " + str(document_template.page))
        canvas.restoreState()

    doc.build(story, onFirstPage=page, onLaterPages=page, canvasmaker=partial(Canvas, invariant=1))
    return output.getvalue()


def immutable_write(path, data):
    """Publish complete bytes without replacing an existing different PDF."""
    path = Path(path)
    if path.exists():
        if path.read_bytes() != data:
            raise FileExistsError("Existing output differs; choose a new output name")
        return False
    if not path.parent.is_dir():
        raise ValueError("Output directory must already exist")
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
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", help="Absolute path to the explicit JSON manifest described above")
    parser.add_argument("--output", help="New PDF path; existing different files are never replaced")
    parser.add_argument("--font", help="Embedded Unicode regular TTF")
    parser.add_argument("--font-bold", help="Matching bold TTF")
    parser.add_argument("--check-only", action="store_true", help="Validate ASR and INDEX metadata without creating a PDF or reading audio")
    args = parser.parse_args()
    try:
        document = load_manifest(args.manifest)
        summary = {"recordings": len(document["recordings"]),
                   "segments": sum(len(r["asr"]["segments"]) for r in document["recordings"]),
                   "plot_point_recordings": sum(r["plot_point"] for r in document["recordings"]),
                   "manifest_sha256": document["manifest_sha256"]}
        if args.check_only:
            print(json.dumps({**summary, "pdf_created": False, "audio_opened": False}))
            return 0
        if not args.output or Path(args.output).suffix.lower() != ".pdf":
            raise ValueError("--output must name a PDF, or use --check-only")
        data = build_bytes(document, args.font, args.font_bold)
        created = immutable_write(args.output, data)
        print(json.dumps({**summary, "pdf_created": created, "output": str(Path(args.output).absolute()),
                          "bytes": len(data), "sha256": digest(data), "visual_review_required": True}))
        return 0
    except (ValueError, KeyError, TypeError, OSError, OverflowError) as error:
        print(type(error).__name__ + ": " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
