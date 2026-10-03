"""Check full segment coverage in a PDF produced by build_transcript_pdf.

Reads only the manifest, its metadata/ASR JSON, and the PDF. This checks text
coverage and ordering; rendered-page visual review remains a separate step.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re

from pypdf import PdfReader
from build_transcript_pdf import load_manifest, offset_text


def compact(text):
    return re.sub(r"\s+", "", text)


def check(manifest_path, pdf_path):
    document = load_manifest(Path(manifest_path).resolve())
    path = Path(pdf_path).resolve()
    raw = path.read_bytes()
    reader = PdfReader(path)
    bodies = []
    for number, page in enumerate(reader.pages, 1):
        text = page.extract_text() or ""
        prefix = f"AUTOMATIC TRANSCRIPT - NOT HUMAN VERIFIED\nPage {number}\n"
        if not text.startswith(prefix):
            raise ValueError(f"Expected generated page footer at extraction start: page {number}")
        # Remove only this known footer prefix. ASR text with similar wording
        # elsewhere remains part of the comparison.
        bodies.append(text[len(prefix):])
    joined = compact("\n".join(bodies))
    appendix = joined.rfind(compact("Appendix / Sources and provenance"))
    if appendix < 0:
        raise ValueError("PDF provenance appendix not found")
    source_positions = []
    cursor = 0
    for record in document["recordings"]:
        identity = compact("Source: " + record["row"]["original_filename"]
                           + " SHA-256: " + record["asr"]["source"]["sha256"])
        position = joined.find(identity, cursor, appendix)
        if position < 0:
            raise ValueError("Source identity missing or out of order: " + record["row"]["original_filename"])
        source_positions.append(position)
        cursor = position + len(identity)
    missing, checked = [], 0
    for index, record in enumerate(document["recordings"]):
        end = source_positions[index + 1] if index + 1 < len(source_positions) else appendix
        body = joined[source_positions[index]:end]
        cursor = 0
        for number, segment in enumerate(record["asr"]["segments"], 1):
            caption = compact(f"{number:04d} | {offset_text(segment['start'])} - {offset_text(segment['end'])}")
            caption_position = body.find(caption, cursor)
            wanted = compact(segment["text"] if segment["text"] else "[Empty text returned for this segment.]")
            text_position = body.find(wanted, caption_position + len(caption)) if caption_position >= 0 else -1
            if caption_position < 0 or text_position < 0:
                missing.append({"source": record["row"]["original_filename"], "segment": number,
                                "caption_missing": caption_position < 0, "full_text_missing": text_position < 0})
            else:
                cursor = text_position + len(wanted)
                checked += 1
    blank = [i for i, text in enumerate(bodies, 1) if not text.strip()]
    expected = sum(len(r["asr"]["segments"]) for r in document["recordings"])
    return {"schema_version": 1, "verified_at_utc": datetime.now(timezone.utc).isoformat(),
            "pdf": str(path), "pdf_sha256": hashlib.sha256(raw).hexdigest(),
            "manifest_sha256": document["manifest_sha256"], "pages": len(reader.pages),
            "recordings": len(document["recordings"]), "expected_asr_segments": expected,
            "full_segments_verified_in_recording_order": checked,
            "priority_labels": [{"designation": r["designation"], "user_name": r.get("user_name"),
                                  "source": r["row"]["original_filename"]}
                                 for r in document["recordings"] if r["plot_point"] or r["priority"]],
            "plot_point_count": sum(r["plot_point"] for r in document["recordings"]),
            "comparison": "Full caption and ASR text per recording, in segment order, after whitespace-only normalization; generated footer prefix excluded",
            "missing": missing, "blank_or_footer_only_pages": blank,
            "passed": checked == expected and not missing and not blank}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("pdf", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = check(args.manifest, args.pdf)
    if args.report:
        with args.report.open("x", encoding="utf-8") as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    print(json.dumps(report, ensure_ascii=True))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
