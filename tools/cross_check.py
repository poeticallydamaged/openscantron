#!/usr/bin/env python3
"""Independent verification of Kotlin-generated artifacts with external decoders."""
import csv, hashlib, io, json, os, sys, zipfile
from pathlib import Path

OUT = Path("verification-output")
results = []

def check(name, ok, detail=""):
    results.append({"name": name, "passed": bool(ok), "detail": str(detail)})
    print(("PASS  " if ok else "FAIL  ") + name + (" -> " + str(detail) if detail else ""))
    return ok

def main():
    from pypdf import PdfReader
    # Blank sheet PDF: 3 copies, real text, XObjects absent (vector QR).
    p = OUT / "blank_answer_sheet.pdf"
    r = PdfReader(str(p))
    text = r.pages[0].extract_text() or ""
    check("pdf: blank sheet has 3 pages", len(r.pages) == 3, len(r.pages))
    check("pdf: blank sheet title extracted", "OPEN SCANTRON" in text, text[:60])
    check("pdf: blank sheet includes template code", "TPL-" in text, "")
    check("pdf: blank sheet metadata", r.metadata is not None and "/Title" in r.metadata, "")

    # Individual archive: 2 pages; page 1 must contain an image and no text/marks.
    r = PdfReader(str(OUT / "individual_archive_sample.pdf"))
    check("pdf: individual archive has 2 pages", len(r.pages) == 2, len(r.pages))
    p1 = r.pages[0]
    xo = p1.get("/Resources", {}).get("/XObject", {})
    check("pdf: archive page 1 embeds the original image", len(xo) >= 1, list(xo.keys()))
    image_stream = next(iter(xo.values())).get_object()
    check("pdf: archive page 1 uses lossless original JPEG", image_stream.get("/Filter") == "/DCTDecode",
          image_stream.get("/Filter"))
    check("pdf: archive page 1 has no rotation", "/Rotate" not in p1, p1.get("/Rotate", 0))
    check("pdf: archive page 1 image matches original JPEG dimensions", image_stream.get("/Width") == 16 and image_stream.get("/Height") == 24,
          (image_stream.get("/Width"), image_stream.get("/Height")))
    stream = p1.get_contents().get_data() if p1.get_contents() else b""
    check("pdf: archive page 1 has no text overlays", b" Tj" not in stream and b"BT" not in stream, len(stream))
    p2text = r.pages[1].extract_text() or ""
    check("pdf: archive page 2 is clearly labelled", "PERSPECTIVE-CORRECTED" in p2text, p2text[:60])

    # Batch archive: 3 retained pages with page-to-submission captions.
    r = PdfReader(str(OUT / "batch_archive_sample.pdf"))
    check("pdf: batch archive has 3 pages", len(r.pages) == 3, len(r.pages))
    caption = r.pages[0].extract_text() or ""
    check("pdf: batch page metadata", "STUDENT" in caption and "SUBMISSION" in caption, caption[:80])

    # Report PDF.
    r = PdfReader(str(OUT / "exam_report_sample.pdf"))
    t = r.pages[0].extract_text() or ""
    check("pdf: report renders KPI text", "EXAM PERFORMANCE REPORT" in t and "Submissions:" in t, t[:80])

    # QR independently decoded by OpenCV.
    import cv2
    img = cv2.imread(str(OUT / "qr_template.bmp"), cv2.IMREAD_GRAYSCALE)
    decoded, _, _ = cv2.QRCodeDetector().detectAndDecode(img)
    check("qr: OpenCV decodes Kotlin QR metadata", decoded.startswith("OMR1|"), decoded)

    # RFC 4180 CSVs via Python's csv module.
    def read_csv(name):
        raw = (OUT / name).read_bytes()
        check("csv: " + name + " has UTF-8 BOM", raw.startswith(b"\xef\xbb\xbf"), len(raw))
        text = raw.decode("utf-8-sig")
        rows = list(csv.reader(io.StringIO(text, newline="")))
        return rows
    wide = read_csv("wide_sample.csv")
    check("csv: wide header has Q001..Q007", wide[0][12] == "Q001" and wide[0][18] == "Q007", wide[0][:20])
    check("csv: wide has 1 data row", len(wide) == 2, len(wide))
    check("csv: uncertain state is explicit", "BLANK" in wide[1], wide[1][12:20])
    check("csv: quoted exam title survived", any("Midterm" in c for c in wide[1]), wide[1][2])
    audit = read_csv("recognition_audit_sample.csv")
    check("csv: audit is long format", len(audit) == 6 and audit[0][3] == "question_number", len(audit))

    # Backup zip manifest and SHA-256 integrity.
    with zipfile.ZipFile(str(OUT / "backup_sample.zip")) as z:
        names = z.namelist()
        manifest = json.loads(z.read("manifest.json"))
        check("backup: manifest lists all files", len(manifest["files"]) == len(names) - 1, len(names))
        bad = [e["path"] for e in manifest["files"]
               if hashlib.sha256(z.read(e["path"])).hexdigest() != e["sha256"]]
        check("backup: every entry checksum matches", not bad, bad)
        check("backup: database included", "database/omrgrader.db" in names, "")

    report = {"total": len(results), "passed": sum(1 for x in results if x["passed"]),
              "failed": sum(1 for x in results if not x["passed"]), "checks": results}
    (OUT / "cross-check.json").write_text(json.dumps(report, indent=2))
    print(f"\n{report['passed']}/{report['total']} independent cross-checks passed")
    return 0 if report["failed"] == 0 else 1

if __name__ == "__main__":
    sys.exit(main())
